package com.puresnap.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Android 原生实况照片（Motion Photo / 动态照片）无损合成与拆包引擎
 *
 * 严格遵循 Android Motion Photo 1.0 / Google Camera 容器标准：
 * 1. 若输入静态图已是 JPEG：完全保留原始 JPEG 压缩码流、EXIF 与 Display P3 ICC 配置，实现 0% 画质损失。
 * 2. 若输入为 WebP / PNG / HEIC：按 EXIF 方向校正并在支持 Display P3 时以 100% 品质无损转封装为 JPEG。
 * 3. 若需修改 MD5：在 EOI (0xFF 0xD9) 之前插入合规 JPEG COM (0xFF 0xFE) 注释段，
 *    绝不向 MP4 末尾追加字节，确保 GCamera:MicroVideoOffset 与 Container:Item Length 始终 100% 精准。
 * 4. 在 EOI 之后追加完整 MP4 流，兼容小米 HyperOS/MIUI、OPPO ColorOS、vivo OriginOS、华为/荣耀、三星、Pixel 原生相册。
 */
object MotionPhotoSynthesizer {

    private val XMP_NAMESPACE_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8)

    data class SplitMotionPhotoResult(
        val jpegBytes: ByteArray,
        val videoBytes: ByteArray
    )

    /**
     * 合成 Android 原生 Motion Photo (.MP.jpg)
     */
    fun synthesizeMotionPhoto(
        imageBytes: ByteArray,
        videoBytes: ByteArray,
        modifyMd5: Boolean = false
    ): ByteArray {
        require(imageBytes.isNotEmpty()) { "Image bytes must not be empty" }
        require(videoBytes.isNotEmpty()) { "Video bytes must not be empty" }

        // 1. 保持 JPEG 原流零重编码；仅对非 JPEG 格式执行 100% 高品质转封装
        val jpegBaseBytes = ensureJpegBytesPreservingQuality(imageBytes)

        // 2. 剥离旧版冲突的 XMP APP1 段
        val sanitizedJpeg = removeExistingXmpApp1(jpegBaseBytes)

        // 3. 截取到标准 JPEG 结束标记 EOI (0xFF 0xD9)
        val eoiEndIndex = findLastEoiIndex(sanitizedJpeg)
        val trimmedJpeg = if (eoiEndIndex > 0) sanitizedJpeg.copyOfRange(0, eoiEndIndex) else sanitizedJpeg

        // 4. 若需去重修改 MD5，在 EOI 之前（头部段位置）注入 COM 注释段，不破坏末尾 MP4 Offset
        val jpegBeforeXmp = if (modifyMd5) {
            injectJpegCommentSalt(trimmedJpeg)
        } else {
            trimmedJpeg
        }

        // 5. 构建严格对齐 videoBytes.size 的 XMP APP1 段
        val videoLength = videoBytes.size
        val xmpApp1Segment = buildMotionPhotoApp1Segment(videoLength)

        val insertPos = findApp1InsertPosition(jpegBeforeXmp)
        return ByteArrayOutputStream(jpegBeforeXmp.size + xmpApp1Segment.size + videoLength).apply {
            write(jpegBeforeXmp, 0, insertPos)
            write(xmpApp1Segment)
            write(jpegBeforeXmp, insertPos, jpegBeforeXmp.size - insertPos)
            write(videoBytes)
        }.toByteArray()
    }

    /**
     * 将原生 Motion Photo 拆分为独立的静态 JPEG 原图与 MP4 动态视频
     */
    fun splitMotionPhoto(fileBytes: ByteArray): SplitMotionPhotoResult? {
        if (fileBytes.size < 128) return null
        val headerStr = String(fileBytes.copyOfRange(0, minOf(fileBytes.size, 32768)), Charsets.ISO_8859_1)

        // 策略 1：优先根据标准 XMP 的 MicroVideoOffset / Item:Length 精准切割
        val offsetMatch = Regex("""(?:GCamera:MicroVideoOffset|Item:Length)=["'](\d+)["']""")
            .findAll(headerStr)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it > 1024 && it < fileBytes.size }

        if (offsetMatch != null) {
            val splitIndex = fileBytes.size - offsetMatch
            if (splitIndex > 64 && isMp4HeaderAt(fileBytes, splitIndex)) {
                return SplitMotionPhotoResult(
                    jpegBytes = fileBytes.copyOfRange(0, splitIndex),
                    videoBytes = fileBytes.copyOfRange(splitIndex, fileBytes.size)
                )
            }
        }

        // 策略 2：扫描 EOI (0xFF 0xD9) 后紧跟的 MP4 'ftyp' Box
        for (i in 12 until fileBytes.size - 12) {
            if (fileBytes[i - 2] == 0xFF.toByte() && fileBytes[i - 1] == 0xD9.toByte() && isMp4HeaderAt(fileBytes, i)) {
                return SplitMotionPhotoResult(
                    jpegBytes = fileBytes.copyOfRange(0, i),
                    videoBytes = fileBytes.copyOfRange(i, fileBytes.size)
                )
            }
        }
        return null
    }

    fun isMotionPhoto(fileBytes: ByteArray): Boolean {
        if (fileBytes.size < 64) return false
        val content = String(fileBytes.copyOfRange(0, minOf(fileBytes.size, 16384)), Charsets.ISO_8859_1)
        return content.contains("Camera:MotionPhoto=\"1\"") ||
            content.contains("GCamera:MotionPhoto=\"1\"") ||
            content.contains("GCamera:MicroVideo=\"1\"")
    }

    /**
     * 在 JPEG 文件安全段位置注入 COM (0xFF 0xFE) 注释字节以改变文件 MD5（零画质损失）
     */
    fun injectJpegCommentSalt(jpegBytes: ByteArray): ByteArray {
        if (jpegBytes.size < 4 || jpegBytes[0] != 0xFF.toByte() || jpegBytes[1] != 0xD8.toByte()) {
            return jpegBytes
        }
        val saltText = "PureSnap_${UUID.randomUUID().toString().take(8)}_${System.nanoTime() % 99999}"
        val saltPayload = saltText.toByteArray(Charsets.UTF_8)
        val segLen = 2 + saltPayload.size
        val insertPos = findApp1InsertPosition(jpegBytes)
        return ByteArrayOutputStream(jpegBytes.size + 2 + segLen).apply {
            write(jpegBytes, 0, insertPos)
            write(0xFF)
            write(0xFE)
            write((segLen shr 8) and 0xFF)
            write(segLen and 0xFF)
            write(saltPayload)
            write(jpegBytes, insertPos, jpegBytes.size - insertPos)
        }.toByteArray()
    }

    private fun isMp4HeaderAt(bytes: ByteArray, index: Int): Boolean {
        if (index + 8 > bytes.size) return false
        return bytes[index + 4] == 'f'.code.toByte() &&
            bytes[index + 5] == 't'.code.toByte() &&
            bytes[index + 6] == 'y'.code.toByte() &&
            bytes[index + 7] == 'p'.code.toByte()
    }

    private fun ensureJpegBytesPreservingQuality(bytes: ByteArray): ByteArray {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return bytes
        }
        val opts = BitmapFactory.Options().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            }
        }
        val rawBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalArgumentException("Failed to decode image bitmap for Motion Photo synthesis")
        val orientedBmp = applyExifOrientationIfNeeded(bytes, rawBmp)
        return ByteArrayOutputStream().use { bos ->
            orientedBmp.compress(Bitmap.CompressFormat.JPEG, 100, bos)
            if (orientedBmp !== rawBmp) orientedBmp.recycle()
            rawBmp.recycle()
            bos.toByteArray()
        }
    }

    fun applyExifOrientationIfNeeded(bytes: ByteArray, source: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return source
        }
        return runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrDefault(source)
    }

    private fun findLastEoiIndex(jpegBytes: ByteArray): Int {
        for (i in jpegBytes.size - 2 downTo 2) {
            if (jpegBytes[i] == 0xFF.toByte() && jpegBytes[i + 1] == 0xD9.toByte()) {
                return i + 2
            }
        }
        return -1
    }

    private fun findApp1InsertPosition(jpegBytes: ByteArray): Int {
        if (jpegBytes.size >= 6 &&
            jpegBytes[0] == 0xFF.toByte() && jpegBytes[1] == 0xD8.toByte() &&
            jpegBytes[2] == 0xFF.toByte() && jpegBytes[3] == 0xE0.toByte()
        ) {
            val app0Len = ((jpegBytes[4].toInt() and 0xFF) shl 8) or (jpegBytes[5].toInt() and 0xFF)
            val pos = 4 + app0Len
            if (pos <= jpegBytes.size) return pos
        }
        return 2
    }

    private fun removeExistingXmpApp1(jpegBytes: ByteArray): ByteArray {
        if (jpegBytes.size < 4 || jpegBytes[0] != 0xFF.toByte() || jpegBytes[1] != 0xD8.toByte()) {
            return jpegBytes
        }
        val bos = ByteArrayOutputStream(jpegBytes.size)
        bos.write(jpegBytes, 0, 2)
        var idx = 2
        while (idx + 3 < jpegBytes.size) {
            if (jpegBytes[idx] != 0xFF.toByte()) {
                bos.write(jpegBytes, idx, jpegBytes.size - idx)
                break
            }
            val markerType = jpegBytes[idx + 1].toInt() and 0xFF
            if (markerType == 0xDA || markerType == 0xD9) {
                bos.write(jpegBytes, idx, jpegBytes.size - idx)
                break
            }
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

        return ByteArrayOutputStream(2 + segLength).apply {
            write(0xFF)
            write(0xE1)
            write((segLength shr 8) and 0xFF)
            write(segLength and 0xFF)
            write(XMP_NAMESPACE_HEADER)
            write(xmpPayload)
        }.toByteArray()
    }
}
