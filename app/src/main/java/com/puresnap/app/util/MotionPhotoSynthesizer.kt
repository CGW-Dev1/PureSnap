package com.puresnap.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Android 原生实况照片（Motion Photo / 动态照片）合成引擎
 *
 * 遵循 Google Camera / Android Motion Photo 1.0 标准（ISO Container 规范）：
 * 1. 静态主图：标准高画质 JPEG，头部位于文件起始处（0 字节至 EOI 0xFF 0xD9）。
 * 2. XMP 元数据：注入包含 Camera:MotionPhoto="1"、GCamera:MicroVideo="1"、
 *    GCamera:MicroVideoOffset 与 Container:Directory (Primary Image + MotionPhoto Video) 的 APP1 段。
 * 3. 动态流：尾部追加完整的 MP4 二进制流。
 *
 * 经由 MediaStore.Images.Media 保存为 image/jpeg 后，小米 HyperOS/MIUI、OPPO ColorOS、
 * vivo OriginOS、华为/荣耀、三星 One UI、Google Pixel 等手机系统相册直接原生识别为“实况/动态照片”，
 * 并支持相册内长按动效播放与动态声音。
 */
object MotionPhotoSynthesizer {

    private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val JPEG_EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val XMP_NAMESPACE_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8)

    /**
     * 将静态原图字节流与实况视频 MP4 字节流无损合成为符合安卓原生相册规范的单个 Motion Photo 文件字节流
     */
    fun synthesizeMotionPhoto(imageBytes: ByteArray, videoBytes: ByteArray): ByteArray {
        require(imageBytes.isNotEmpty()) { "Image bytes must not be empty" }
        require(videoBytes.isNotEmpty()) { "Video bytes must not be empty" }

        // 1. 确保主图为纯净合规的 JPEG 字节流（若为 WebP/PNG/HEIC 则以 100% 画质无损转码）
        val jpegBaseBytes = ensureJpegBytes(imageBytes)

        // 2. 剥离可能存在的冲突或旧版 XMP APP1 段
        val sanitizedJpeg = removeExistingXmpApp1(jpegBaseBytes)

        // 3. 截断至标准 JPEG 结尾标记 EOI (0xFF, 0xD9)，确保文件体紧凑合规
        val eoiEndIndex = findLastEoiIndex(sanitizedJpeg)
        val trimmedJpeg = if (eoiEndIndex > 0) sanitizedJpeg.copyOfRange(0, eoiEndIndex) else sanitizedJpeg

        // 4. 构建包含 Android Motion Photo 规范的 XMP APP1 段
        val videoLength = videoBytes.size
        val xmpApp1Segment = buildMotionPhotoApp1Segment(videoLength)

        // 5. 将 XMP APP1 段插入到 JPEG 头部（若有 APP0 则紧跟 APP0 之后，否则紧随 SOI 之后）
        val insertPos = findApp1InsertPosition(trimmedJpeg)
        val jpegWithXmp = ByteArrayOutputStream(trimmedJpeg.size + xmpApp1Segment.size + videoLength).apply {
            write(trimmedJpeg, 0, insertPos)
            write(xmpApp1Segment)
            write(trimmedJpeg, insertPos, trimmedJpeg.size - insertPos)
            // 6. 在 EOI (0xFF, 0xD9) 之后追加完整的 MP4 二进制视频流
            write(videoBytes)
        }.toByteArray()

        return jpegWithXmp
    }

    /**
     * 校验文件是否包含 Android Motion Photo XMP 标识
     */
    fun isMotionPhoto(fileBytes: ByteArray): Boolean {
        if (fileBytes.size < 64) return false
        val content = String(fileBytes.copyOfRange(0, minOf(fileBytes.size, 16384)), Charsets.ISO_8859_1)
        return (content.contains("Camera:MotionPhoto=\"1\"") || content.contains("GCamera:MotionPhoto=\"1\"") || content.contains("GCamera:MicroVideo=\"1\""))
    }

    /**
     * 校验并确保输入图像为 JPEG 格式
     */
    private fun ensureJpegBytes(bytes: ByteArray): ByteArray {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return bytes
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("Failed to decode image bitmap for Motion Photo synthesis")
        return ByteArrayOutputStream().use { bos ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, bos)
            bos.toByteArray()
        }
    }

    /**
     * 查找最后一个 JPEG EOI (0xFF, 0xD9) 的结束位置（包含 0xD9 之后的下一字节下标）
     */
    private fun findLastEoiIndex(jpegBytes: ByteArray): Int {
        for (i in jpegBytes.size - 2 downTo 0) {
            if (jpegBytes[i] == 0xFF.toByte() && jpegBytes[i + 1] == 0xD9.toByte()) {
                return i + 2
            }
        }
        return -1
    }

    /**
     * 确定 XMP APP1 段的最佳插入下标
     * 优先插入在 APP0 (JFIF) 之后；若无 APP0 则插入在 SOI (0xFF, 0xD8) 之后
     */
    private fun findApp1InsertPosition(jpegBytes: ByteArray): Int {
        if (jpegBytes.size >= 4 && jpegBytes[0] == 0xFF.toByte() && jpegBytes[1] == 0xD8.toByte()) {
            if (jpegBytes[2] == 0xFF.toByte() && jpegBytes[3] == 0xE0.toByte()) {
                val app0Len = ((jpegBytes[4].toInt() and 0xFF) shl 8) or (jpegBytes[5].toInt() and 0xFF)
                val pos = 4 + app0Len
                if (pos <= jpegBytes.size) {
                    return pos
                }
            }
        }
        return 2
    }

    /**
     * 剥离原始 JPEG 中已有的 XMP APP1 段，避免重复和元数据冲突
     */
    private fun removeExistingXmpApp1(jpegBytes: ByteArray): ByteArray {
        if (jpegBytes.size < 4 || jpegBytes[0] != 0xFF.toByte() || jpegBytes[1] != 0xD8.toByte()) {
            return jpegBytes
        }
        val bos = ByteArrayOutputStream(jpegBytes.size)
        bos.write(jpegBytes, 0, 2) // SOI
        var idx = 2
        while (idx + 3 < jpegBytes.size) {
            if (jpegBytes[idx] != 0xFF.toByte()) {
                bos.write(jpegBytes, idx, jpegBytes.size - idx)
                break
            }
            val markerType = jpegBytes[idx + 1].toInt() and 0xFF
            // SOS (0xDA) 之后是压缩图像数据流，或 EOI (0xD9)
            if (markerType == 0xDA || markerType == 0xD9) {
                bos.write(jpegBytes, idx, jpegBytes.size - idx)
                break
            }
            // 无长度参数的独立 marker: RST0..RST7 (0xD0..0xD7)
            if (markerType in 0xD0..0xD7) {
                bos.write(jpegBytes, idx, 2)
                idx += 2
                continue
            }
            val segLen = ((jpegBytes[idx + 2].toInt() and 0xFF) shl 8) or (jpegBytes[idx + 3].toInt() and 0xFF)
            val totalSegBytes = 2 + segLen
            if (idx + totalSegBytes > jpegBytes.size) {
                bos.write(jpegBytes, idx, jpegBytes.size - idx)
                break
            }
            var isXmp = false
            if (markerType == 0xE1 && segLen >= 2 + XMP_NAMESPACE_HEADER.size) {
                val startPayload = idx + 4
                var match = true
                for (k in XMP_NAMESPACE_HEADER.indices) {
                    if (jpegBytes[startPayload + k] != XMP_NAMESPACE_HEADER[k]) {
                        match = false
                        break
                    }
                }
                if (match) isXmp = true
            }
            if (!isXmp) {
                bos.write(jpegBytes, idx, totalSegBytes)
            }
            idx += totalSegBytes
        }
        return bos.toByteArray()
    }

    /**
     * 构造完整的 APP1 XMP 数据段
     */
    private fun buildMotionPhotoApp1Segment(videoLength: Int): ByteArray {
        val xmpXml = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                xmlns:Container="http://ns.google.com/photos/1.0/container/"
                xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                Camera:MotionPhoto="1"
                Camera:MotionPhotoVersion="1"
                Camera:MotionPhotoPresentationTimestampUs="0"
                GCamera:MotionPhoto="1"
                GCamera:MotionPhotoVersion="1"
                GCamera:MotionPhotoPresentationTimestampUs="0"
                GCamera:MicroVideo="1"
                GCamera:MicroVideoVersion="1"
                GCamera:MicroVideoOffset="$videoLength"
                GCamera:MicroVideoPresentationTimestampUs="0">
               <Container:Directory>
                <rdf:Seq>
                 <rdf:li rdf:parseType="Resource">
                  <Container:Item
                   Item:Mime="image/jpeg"
                   Item:Semantic="Primary"
                   Item:Length="0"
                   Item:Padding="0"/>
                 </rdf:li>
                 <rdf:li rdf:parseType="Resource">
                  <Container:Item
                   Item:Mime="video/mp4"
                   Item:Semantic="MotionPhoto"
                   Item:Length="$videoLength"
                   Item:Padding="0"/>
                 </rdf:li>
                </rdf:Seq>
               </Container:Directory>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent().replace("\r\n", "\n")

        val xmpPayload = xmpXml.toByteArray(Charsets.UTF_8)
        val segLength = 2 + XMP_NAMESPACE_HEADER.size + xmpPayload.size
        require(segLength <= 0xFFFF) { "XMP metadata segment length exceeds 65535 bytes" }

        val bos = ByteArrayOutputStream(2 + segLength)
        bos.write(0xFF)
        bos.write(0xE1)
        bos.write((segLength shr 8) and 0xFF)
        bos.write(segLength and 0xFF)
        bos.write(XMP_NAMESPACE_HEADER)
        bos.write(xmpPayload)
        return bos.toByteArray()
    }
}
