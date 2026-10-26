package com.puresnap.app.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.puresnap.app.data.local.DownloadedMediaEntity
import com.puresnap.app.model.AudioTrackInfo
import com.puresnap.app.model.ExportFormatMode
import com.puresnap.app.model.LivePhotoExportMode
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.MediaKind
import com.puresnap.app.model.ParsedPost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 多线程并发下载与 Android 10+ MediaStore 分区存储归档引擎（零权限直写系统相册）
 * 直接拉取真实平台 CDN 无水印媒体流，支持：
 * 1. 按平台自动建立子文件夹（如 Pictures/PureSnap/Xiaohongshu、Movies/PureSnap/Douyin）
 * 2. WebP/HEIC 原图兼容模式（100% 品质 JPEG 无损转封装）与 PNG / 原格式直出
 * 3. 小红书/抖音实况图（Live Photo）静态高清原图 + 动态 MP4 双模导出
 * 4. 可选文件末尾微调字节改变 MD5 值（适合二创防重）
 */
object MediaStoreArchiver {

    data class BatchSaveResult(
        val savedEntities: List<DownloadedMediaEntity>,
        val imageCount: Int,
        val videoCount: Int,
        val folderPathSummary: String
    )

    private enum class ArchiverTaskType {
        IMAGE,
        VIDEO,
        MOTION_PHOTO
    }

    suspend fun saveSelectedMediaBatch(
        context: Context,
        post: ParsedPost,
        itemsToSave: List<MediaItem>,
        exportFormatMode: ExportFormatMode,
        livePhotoExportMode: LivePhotoExportMode,
        modifyMd5: Boolean,
        customCookie: String? = null,
        onProgress: (completed: Int, total: Int) -> Unit
    ): BatchSaveResult = withContext(Dispatchers.IO) {
        if (itemsToSave.isEmpty()) {
            return@withContext BatchSaveResult(emptyList(), 0, 0, "")
        }

        val expandedTasks = mutableListOf<Pair<MediaItem, ArchiverTaskType>>()
        for (item in itemsToSave) {
            if (item.isLivePhoto && !item.liveVideoUrl.isNullOrBlank()) {
                when (livePhotoExportMode) {
                    LivePhotoExportMode.MOTION_PHOTO_JPEG -> {
                        expandedTasks.add(item to ArchiverTaskType.MOTION_PHOTO)
                    }
                    LivePhotoExportMode.BOTH_IMAGE_AND_MP4 -> {
                        expandedTasks.add(item to ArchiverTaskType.IMAGE)
                        expandedTasks.add(item to ArchiverTaskType.VIDEO)
                    }
                    LivePhotoExportMode.STATIC_IMAGE_ONLY -> {
                        expandedTasks.add(item to ArchiverTaskType.IMAGE)
                    }
                    LivePhotoExportMode.LIVE_VIDEO_ONLY -> {
                        expandedTasks.add(item to ArchiverTaskType.VIDEO)
                    }
                }
            } else {
                expandedTasks.add(item to if (item.isVideo) ArchiverTaskType.VIDEO else ArchiverTaskType.IMAGE)
            }
        }

        val totalTasks = expandedTasks.size
        val completedCounter = AtomicInteger(0)
        val semaphore = Semaphore(4)

        val savedEntities = coroutineScope {
            expandedTasks.mapIndexed { taskIndex, (item, taskType) ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val entity = when (taskType) {
                            ArchiverTaskType.MOTION_PHOTO -> {
                                saveMotionPhotoItemToMediaStore(
                                    context = context,
                                    post = post,
                                    item = item,
                                    modifyMd5 = modifyMd5,
                                    customCookie = customCookie
                                )
                            }
                            ArchiverTaskType.VIDEO -> {
                                saveVideoItemToMediaStore(
                                    context = context,
                                    post = post,
                                    item = item,
                                    modifyMd5 = modifyMd5,
                                    customCookie = customCookie,
                                    suffixIndex = taskIndex
                                )
                            }
                            ArchiverTaskType.IMAGE -> {
                                saveImageItemToMediaStore(
                                    context = context,
                                    post = post,
                                    item = item,
                                    exportFormatMode = exportFormatMode,
                                    modifyMd5 = modifyMd5,
                                    customCookie = customCookie
                                )
                            }
                        }
                        val done = completedCounter.incrementAndGet()
                        onProgress(done, totalTasks)
                        entity
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val imgCount = savedEntities.count {
            it.mediaKind == MediaKind.IMAGE.name || it.mediaKind == MediaKind.LIVE_PHOTO.name
        }
        val vidCount = savedEntities.count { it.mediaKind == MediaKind.VIDEO.name }
        val folderPath = if (vidCount > 0 && imgCount == 0) {
            "Movies/PureSnap/${post.platform.folderName}"
        } else {
            "Pictures/PureSnap/${post.platform.folderName}"
        }

        BatchSaveResult(
            savedEntities = savedEntities,
            imageCount = imgCount,
            videoCount = vidCount,
            folderPathSummary = folderPath
        )
    }

    suspend fun saveBgmAudioToMediaStore(
        context: Context,
        post: ParsedPost,
        bgm: AudioTrackInfo,
        modifyMd5: Boolean
    ): DownloadedMediaEntity? = withContext(Dispatchers.IO) {
        val rawAudioBytes = DynamicMediaRenderer.downloadRemoteBytes(
            urlStr = bgm.url,
            platform = post.platform,
            timeoutMs = 20_000
        ) ?: return@withContext null

        if (rawAudioBytes.size < 512) return@withContext null

        val finalBytes = if (modifyMd5) appendMd5SaltBytes(rawAudioBytes) else rawAudioBytes
        val md5Hex = computeMd5Hex(finalBytes)

        val relativePath = "${Environment.DIRECTORY_MUSIC}/PureSnap/${post.platform.folderName}"
        val fileName = "PureSnap_${post.platform.folderName}_BGM_${post.noteId}_${System.currentTimeMillis() % 100000}.mp3"

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.TITLE, bgm.title)
            put(MediaStore.Audio.Media.ARTIST, bgm.author)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return@withContext null

        try {
            resolver.openOutputStream(itemUri)?.use { out ->
                out.write(finalBytes)
                out.flush()
            }
            val completeValues = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            resolver.update(itemUri, completeValues, null, null)
            notifyMediaScanner(context, itemUri)

            DownloadedMediaEntity(
                mediaStoreUri = itemUri.toString(),
                fileName = fileName,
                relativePath = "$relativePath/$fileName",
                platform = post.platform.name,
                authorName = post.authorName,
                title = "${bgm.title} - ${bgm.author}",
                mediaKind = MediaKind.AUDIO.name,
                width = 0,
                height = 0,
                format = "MP3",
                fileSizeBytes = finalBytes.size.toLong(),
                md5Hash = md5Hex,
                md5Modified = modifyMd5,
                visualSeed = post.noteId.hashCode(),
                downloadedAtMillis = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            resolver.delete(itemUri, null, null)
            null
        }
    }

    suspend fun saveCompanionImageToMediaStore(
        context: Context,
        post: ParsedPost,
        label: String,
        imageUrl: String,
        visualSeed: Int,
        exportFormatMode: ExportFormatMode,
        modifyMd5: Boolean
    ): DownloadedMediaEntity? = withContext(Dispatchers.IO) {
        val tempItem = MediaItem(
            id = "${post.id}_$label",
            index = 0,
            kind = MediaKind.IMAGE,
            badge = com.puresnap.app.model.QualityBadge.RAW,
            originUrl = imageUrl,
            previewUrl = imageUrl,
            width = 1080,
            height = 1080,
            format = "JPG",
            themeTitle = "${post.authorName} · $label",
            visualSeed = visualSeed
        )
        saveImageItemToMediaStore(
            context = context,
            post = post,
            item = tempItem,
            exportFormatMode = exportFormatMode,
            modifyMd5 = modifyMd5,
            customCookie = null
        )
    }

    private fun saveImageItemToMediaStore(
        context: Context,
        post: ParsedPost,
        item: MediaItem,
        exportFormatMode: ExportFormatMode,
        modifyMd5: Boolean,
        customCookie: String?
    ): DownloadedMediaEntity? {
        // 优先下载无损直链 originUrl；若特定 CDN 节点需签名鉴权失败，则自动回退至真实签名的 previewUrl
        var remoteBytes = DynamicMediaRenderer.downloadRemoteBytes(
            urlStr = item.originUrl,
            platform = post.platform,
            customCookie = customCookie,
            timeoutMs = 15_000
        )
        if ((remoteBytes == null || remoteBytes.size < 1024) && item.previewUrl != item.originUrl) {
            remoteBytes = DynamicMediaRenderer.downloadRemoteBytes(
                urlStr = item.previewUrl,
                platform = post.platform,
                customCookie = customCookie,
                timeoutMs = 15_000
            )
        }

        if (remoteBytes == null || remoteBytes.size < 256) return null

        val decodedBitmap = BitmapFactory.decodeByteArray(remoteBytes, 0, remoteBytes.size)
        val realW = decodedBitmap?.width ?: item.width
        val realH = decodedBitmap?.height ?: item.height

        val sourceFormat = detectImageFormat(remoteBytes, item.format)
        val isSpecialSourceFormat = sourceFormat == "WEBP" || sourceFormat == "HEIC"

        val (targetExtension, targetMimeType, imageBytes) = when {
            exportFormatMode == ExportFormatMode.COMPATIBLE_JPEG_100 && isSpecialSourceFormat && decodedBitmap != null -> {
                val bos = ByteArrayOutputStream()
                decodedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos)
                Triple("jpg", "image/jpeg", bos.toByteArray())
            }
            exportFormatMode == ExportFormatMode.LOSSLESS_PNG && decodedBitmap != null -> {
                val bos = ByteArrayOutputStream()
                decodedBitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                Triple("png", "image/png", bos.toByteArray())
            }
            else -> {
                val ext = when (sourceFormat) {
                    "PNG" -> "png"
                    "WEBP" -> "webp"
                    "HEIC" -> "heic"
                    else -> "jpg"
                }
                val mime = when (ext) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "heic" -> "image/heic"
                    else -> "image/jpeg"
                }
                Triple(ext, mime, remoteBytes)
            }
        }

        val finalBytes = if (modifyMd5) appendMd5SaltBytes(imageBytes) else imageBytes
        val md5Hex = computeMd5Hex(finalBytes)

        val relativePath = "${Environment.DIRECTORY_PICTURES}/PureSnap/${post.platform.folderName}"
        val fileName = "PureSnap_${post.platform.folderName}_${post.noteId}_P${item.index + 1}_${System.currentTimeMillis() % 100000}.$targetExtension"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, targetMimeType)
            put(MediaStore.Images.Media.WIDTH, realW)
            put(MediaStore.Images.Media.HEIGHT, realH)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return null

        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                out.write(finalBytes)
                out.flush()
            }
            val completeValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(itemUri, completeValues, null, null)
            notifyMediaScanner(context, itemUri)

            DownloadedMediaEntity(
                mediaStoreUri = itemUri.toString(),
                fileName = fileName,
                relativePath = "$relativePath/$fileName",
                platform = post.platform.name,
                authorName = post.authorName,
                title = post.title.ifBlank { post.content }.take(40).ifBlank { "${post.platform.displayName}无损原图" },
                mediaKind = if (item.isLivePhoto) MediaKind.LIVE_PHOTO.name else MediaKind.IMAGE.name,
                width = realW,
                height = realH,
                format = targetExtension.uppercase(),
                fileSizeBytes = finalBytes.size.toLong(),
                md5Hash = md5Hex,
                md5Modified = modifyMd5,
                visualSeed = item.visualSeed,
                downloadedAtMillis = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            resolver.delete(itemUri, null, null)
            null
        }
    }

    private fun saveMotionPhotoItemToMediaStore(
        context: Context,
        post: ParsedPost,
        item: MediaItem,
        modifyMd5: Boolean,
        customCookie: String?
    ): DownloadedMediaEntity? {
        // 1. 下载静态高清原图字节流（优先无损 originUrl，回退 previewUrl）
        var imageBytes = DynamicMediaRenderer.downloadRemoteBytes(
            urlStr = item.originUrl,
            platform = post.platform,
            customCookie = customCookie,
            timeoutMs = 20_000
        )
        if ((imageBytes == null || imageBytes.size < 1024) && item.previewUrl != item.originUrl) {
            imageBytes = DynamicMediaRenderer.downloadRemoteBytes(
                urlStr = item.previewUrl,
                platform = post.platform,
                customCookie = customCookie,
                timeoutMs = 20_000
            )
        }

        if (imageBytes == null || imageBytes.size < 256) return null

        // 2. 下载实况动态短视频 MP4 流
        val videoUrl = item.liveVideoUrl
        var videoBytes: ByteArray? = null
        if (!videoUrl.isNullOrBlank() && videoUrl.startsWith("http")) {
            videoBytes = DynamicMediaRenderer.downloadRemoteBytes(
                urlStr = videoUrl,
                platform = post.platform,
                customCookie = customCookie,
                timeoutMs = 30_000
            )
        }

        // 3. 将 JPEG 与 MP4 字节流无损合成 Android 标准 Motion Photo (XMP 容器 + 尾部 MP4 挂载)
        val finalBytes: ByteArray
        val isSynthesized: Boolean
        val decodedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        val realW = decodedBitmap?.width ?: item.width
        val realH = decodedBitmap?.height ?: item.height

        if (videoBytes != null && videoBytes.size > 2048) {
            val synthesized = runCatching {
                MotionPhotoSynthesizer.synthesizeMotionPhoto(imageBytes, videoBytes)
            }.getOrNull()

            if (synthesized != null && synthesized.size > imageBytes.size) {
                finalBytes = if (modifyMd5) appendMd5SaltBytes(synthesized) else synthesized
                isSynthesized = true
            } else {
                finalBytes = if (modifyMd5) appendMd5SaltBytes(imageBytes) else imageBytes
                isSynthesized = false
            }
        } else {
            // 实况视频下载若受限，优雅降级为保存静态原图
            finalBytes = if (modifyMd5) appendMd5SaltBytes(imageBytes) else imageBytes
            isSynthesized = false
        }

        val md5Hex = computeMd5Hex(finalBytes)
        val relativePath = "${Environment.DIRECTORY_PICTURES}/PureSnap/${post.platform.folderName}"
        val ext = if (isSynthesized) "MP.jpg" else "jpg"
        val fileName = "PureSnap_${post.platform.folderName}_${post.noteId}_LIVE_P${item.index + 1}_${System.currentTimeMillis() % 100000}.$ext"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.WIDTH, realW)
            put(MediaStore.Images.Media.HEIGHT, realH)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return null

        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                out.write(finalBytes)
                out.flush()
            }
            val completeValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(itemUri, completeValues, null, null)
            notifyMediaScanner(context, itemUri)

            DownloadedMediaEntity(
                mediaStoreUri = itemUri.toString(),
                fileName = fileName,
                relativePath = "$relativePath/$fileName",
                platform = post.platform.name,
                authorName = post.authorName,
                title = post.title.ifBlank { post.content }.take(40).ifBlank { "${post.platform.displayName}实况照片" },
                mediaKind = MediaKind.LIVE_PHOTO.name,
                width = realW,
                height = realH,
                format = if (isSynthesized) "LIVE.JPG" else "JPG",
                fileSizeBytes = finalBytes.size.toLong(),
                md5Hash = md5Hex,
                md5Modified = modifyMd5,
                visualSeed = item.visualSeed,
                downloadedAtMillis = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            resolver.delete(itemUri, null, null)
            null
        }
    }

    private fun saveVideoItemToMediaStore(
        context: Context,
        post: ParsedPost,
        item: MediaItem,
        modifyMd5: Boolean,
        customCookie: String?,
        suffixIndex: Int
    ): DownloadedMediaEntity? {
        val candidateUrls = linkedSetOf<String>().apply {
            item.liveVideoUrl?.takeIf { it.startsWith("http") }?.let { add(it) }
            if (item.originUrl.startsWith("http")) add(item.originUrl)
        }

        var videoBytes: ByteArray? = null
        for (url in candidateUrls) {
            val bytes = DynamicMediaRenderer.downloadRemoteBytes(
                urlStr = url,
                platform = post.platform,
                customCookie = customCookie,
                timeoutMs = 30_000
            )
            if (bytes != null && bytes.size > 2048) {
                videoBytes = bytes
                break
            }
        }

        val rawBytes = videoBytes ?: return null
        val finalBytes = if (modifyMd5) appendMd5SaltBytes(rawBytes) else rawBytes
        val md5Hex = computeMd5Hex(finalBytes)

        val relativePath = "${Environment.DIRECTORY_MOVIES}/PureSnap/${post.platform.folderName}"
        val tag = if (item.isLivePhoto) "LIVE" else "VID"
        val fileName = "PureSnap_${post.platform.folderName}_${post.noteId}_${tag}${item.index + 1}_${suffixIndex}_${System.currentTimeMillis() % 100000}.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.WIDTH, item.width)
            put(MediaStore.Video.Media.HEIGHT, item.height)
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return null

        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                out.write(finalBytes)
                out.flush()
            }
            val completeValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(itemUri, completeValues, null, null)
            notifyMediaScanner(context, itemUri)

            DownloadedMediaEntity(
                mediaStoreUri = itemUri.toString(),
                fileName = fileName,
                relativePath = "$relativePath/$fileName",
                platform = post.platform.name,
                authorName = post.authorName,
                title = post.title.ifBlank { post.content }.take(40).ifBlank { "${post.platform.displayName}无水印视频" },
                mediaKind = MediaKind.VIDEO.name,
                width = item.width,
                height = item.height,
                format = "MP4",
                fileSizeBytes = finalBytes.size.toLong(),
                md5Hash = md5Hex,
                md5Modified = modifyMd5,
                visualSeed = item.visualSeed,
                downloadedAtMillis = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            resolver.delete(itemUri, null, null)
            null
        }
    }

    private fun detectImageFormat(bytes: ByteArray, defaultFormat: String): String {
        if (bytes.size >= 12) {
            // PNG: 89 50 4E 47
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
                return "PNG"
            }
            // JPEG: FF D8 FF
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                return "JPG"
            }
            // WEBP: RIFF .... WEBP
            if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()) {
                return "WEBP"
            }
        }
        return defaultFormat.uppercase()
    }

    fun appendMd5SaltBytes(original: ByteArray): ByteArray {
        val salt = "PS_MD5_${UUID.randomUUID().toString().take(8)}_${System.nanoTime() % 9999}".toByteArray(Charsets.UTF_8)
        return original + salt
    }

    fun computeMd5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun notifyMediaScanner(context: Context, uri: Uri) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(uri.toString()),
                null,
                null
            )
        } catch (_: Exception) {
        }
    }
}
