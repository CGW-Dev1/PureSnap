package com.puresnap.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EXIF 摄影参数与隐私信息查看 / 无损擦除工具
 * - 完整读取相片设备型号、镜头参数、ISO、光圈、快门及 GPS 地理位置
 * - 擦除时直接操作图像头部元数据段，不解码/重编码像素，保持 100% 原始画质
 */
object ExifPrivacyHelper {

    data class ExifReport(
        val cameraMakeModel: String,
        val lensModel: String,
        val exposureInfo: String,
        val dimensions: String,
        val dateTaken: String,
        val hasGpsLocation: Boolean,
        val gpsSummary: String
    )

    suspend fun inspectExif(context: Context, uri: Uri): ExifReport? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
                val makeModel = listOf(make, model).filter { it.isNotBlank() }.joinToString(" ")
                    .ifBlank { "未记录拍摄设备" }

                val lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()
                    ?.ifBlank { null } ?: "未知镜头"

                val fNum = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" }
                val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" }
                val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
                    .takeIf { it > 0 }?.let { "${it.toInt()}mm" }
                val exposure = listOfNotNull(focal, fNum, iso).joinToString(" · ")
                    .ifBlank { "无曝光参数" }

                val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                val dims = if (w > 0 && h > 0) "${w}×${h}" else "以文件头为准"

                val dateTaken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    ?: "未记录拍摄时间"

                val latLong = exif.latLong
                val hasGps = latLong != null
                val gpsText = if (latLong != null) {
                    String.format("%.5f, %.5f (含定位隐私!)", latLong[0], latLong[1])
                } else {
                    "无 GPS 定位隐私"
                }

                ExifReport(
                    cameraMakeModel = makeModel,
                    lensModel = lens,
                    exposureInfo = exposure,
                    dimensions = dims,
                    dateTaken = dateTaken,
                    hasGpsLocation = hasGps,
                    gpsSummary = gpsText
                )
            }
        }.getOrNull()
    }

    /**
     * 无损擦除 GPS、相机机身序列号、拍摄时间与作者信息，生成纯净隐私副本至 Pictures/PureSnap/Cleaned
     */
    suspend fun createPrivacyCleanedCopy(context: Context, sourceUri: Uri): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val tempFile = File.createTempFile("ps_exif_clean_", ".jpg", context.cacheDir)
        try {
            val mimeType = resolver.getType(sourceUri) ?: "image/jpeg"
            val ext = when {
                mimeType.contains("png", true) -> "png"
                mimeType.contains("webp", true) -> "webp"
                else -> "jpg"
            }

            resolver.openInputStream(sourceUri)?.use { input ->
                tempFile.outputStream().use { out ->
                    input.copyTo(out)
                }
            } ?: return@withContext null

            val exif = ExifInterface(tempFile)
            val sensitiveTags = listOf(
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_LENS_MAKE,
                ExifInterface.TAG_LENS_MODEL,
                ExifInterface.TAG_CAMERA_OWNER_NAME,
                ExifInterface.TAG_BODY_SERIAL_NUMBER,
                ExifInterface.TAG_LENS_SERIAL_NUMBER,
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_SOFTWARE,
                ExifInterface.TAG_USER_COMMENT,
                ExifInterface.TAG_ARTIST
            )
            sensitiveTags.forEach { tag -> exif.setAttribute(tag, null) }
            exif.saveAttributes()

            val relativePath = "${Environment.DIRECTORY_PICTURES}/PureSnap/Cleaned"
            val fileName = "PureSnap_Clean_${System.currentTimeMillis() % 1000000}.$ext"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val targetUri = resolver.insert(collection, values) ?: return@withContext null

            return@withContext try {
                resolver.openOutputStream(targetUri)?.use { out ->
                    tempFile.inputStream().use { it.copyTo(out) }
                    out.flush()
                }
                val doneValues = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(targetUri, doneValues, null, null)
                targetUri
            } catch (_: Exception) {
                resolver.delete(targetUri, null, null)
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            tempFile.delete()
        }
    }
}
