package com.puresnap.app.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.puresnap.app.data.local.DownloadedMediaEntity
import com.puresnap.app.data.parser.MultiPlatformParserEngine
import com.puresnap.app.model.AudioTrackInfo
import com.puresnap.app.model.ExportFormatMode
import com.puresnap.app.model.LivePhotoExportMode
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.MediaKind
import com.puresnap.app.model.ParsedPost
import com.puresnap.app.model.PlatformType
import com.puresnap.app.model.QualityBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 多线程并发流式下载与 Android 10+ MediaStore 无损归档引擎
 * 1. 真·原图直出：默认直接保存平台 CDN 原始文件字节流，零二次重编码、完整保留 Display P3 与 EXIF
 * 2. 流式写盘：视频与音频直接流式写入 MediaStore，杜绝大体积视频加载进内存导致 OOM
 * 3. 原生实况照片：支持合成为 Android 标准 Motion Photo (.MP.jpg)，或原图+MP4 双轨分离导出
 * 4. 合法结构 MD5 去重：JPEG 注入 COM 段，MP4 追加 ISO free box，Motion Photo 于 EOI 前注入 COM 段
 */
object MediaStoreArchiver {

    data class BatchSaveResult(
        val savedEntities: List<DownloadedMediaEntity>,
        val imageCount: Int,
        val livePhotoCount: Int,
        val videoCount: Int,
        val folderPathSummary: String
    )

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
            return@withContext BatchSaveResult(emptyList(), 0, 0, 0, "")
        }

        val totalTasks = itemsToSave.size
        val completedCounter = AtomicInteger(0)
        val semaphore = Semaphore(4)

        val savedEntities = coroutineScope {
            itemsToSave.mapIndexed { taskIndex, item ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val list = when {
                            item.isVideo -> listOfNotNull(
                                saveVideoItemToMediaStore(
                                    context = context,
                                    post = post,
                                    item = item,
                                    modifyMd5 = modifyMd5,
                                    customCookie = customCookie,
                                    suffixIndex = taskIndex
                                )
                            )
                            item.isLivePhoto -> saveLivePhotoItemToMediaStore(
                                context = context,
                                post = post,
                                item = item,
                                exportFormatMode = exportFormatMode,
                                livePhotoExportMode = livePhotoExportMode,
                                modifyMd5 = modifyMd5,
                                customCookie = customCookie,
                                suffixIndex = taskIndex
                            )
                            else -> listOfNotNull(
                                saveImageItemToMediaStore(
                                    context = context,
                                    post = post,
                                    item = item,
                                    exportFormatMode = exportFormatMode,
                                    modifyMd5 = modifyMd5,
                                    customCookie = customCookie
                                )
                            )
                        }
                        val done = completedCounter.incrementAndGet()
                        onProgress(done, totalTasks)
                        list
                    }
                }
            }.awaitAll().flatten()
        }

        val imgCount = savedEntities.count { it.mediaKind == MediaKind.IMAGE.name }
        val liveCount = savedEntities.count { it.mediaKind == MediaKind.LIVE_PHOTO.name }
        val vidCount = savedEntities.count { it.mediaKind == MediaKind.VIDEO.name }
        val folderPath = if (vidCount > 0 && imgCount == 0 && liveCount == 0) {
            "Movies/PureSnap/${post.platform.folderName}"
        } else {
            "Pictures/PureSnap/${post.platform.folderName}"
        }

        BatchSaveResult(
            savedEntities = savedEntities,
            imageCount = imgCount,
            livePhotoCount = liveCount,
            videoCount = vidCount,
            folderPathSummary = folderPath
        )
    }

    private fun saveLivePhotoItemToMediaStore(
        context: Context,
        post: ParsedPost,
        item: MediaItem,
        exportFormatMode: ExportFormatMode,
        livePhotoExportMode: LivePhotoExportMode,
        modifyMd5: Boolean,
        customCookie: String?,
        suffixIndex: Int
    ): List<DownloadedMediaEntity> {
        if (livePhotoExportMode == LivePhotoExportMode.STATIC_IMAGE_ONLY) {
            return listOfNotNull(
                saveImageItemToMediaStore(
                    context = context,
                    post = post,
                    item = item,
                    exportFormatMode = exportFormatMode,
                    modifyMd5 = modifyMd5,
                    customCookie = customCookie
                )
            )
        }

        if (livePhotoExportMode == LivePhotoExportMode.DUAL_IMAGE_AND_MP4) {
            val saved = mutableListOf<DownloadedMediaEntity>()
            saveImageItemToMediaStore(
                context = context,
                post = post,
                item = item,
                exportFormatMode = exportFormatMode,
                modifyMd5 = modifyMd5,
                customCookie = customCookie
            )?.let { saved.add(it) }

            val liveVid = item.liveVideoUrl
            if (!liveVid.isNullOrBlank() && liveVid.startsWith("http")) {
                val videoOnlyItem = item.copy(
                    id = "${item.id}_live_mp4",
                    kind = MediaKind.VIDEO,
                    originUrl = liveVid,
                    previewUrl = item.previewUrl,
                    liveVideoUrl = liveVid,
                    format = "MP4"
                )
                saveVideoItemToMediaStore(
                    context = context,
                    post = post,
                    item = videoOnlyItem,
                    modifyMd5 = modifyMd5,
                    customCookie = customCookie,
                    suffixIndex = suffixIndex
                )?.let { saved.add(it) }
            }
            return saved
        }

        // 默认模式：合成 Android 原生 Motion Photo (.MP.jpg)
        val preferRaw = exportFormatMode == ExportFormatMode.KEEP_ORIGINAL
        val imageBytes = downloadFirstValidImageBytes(
            candidates = item.orderedDownloadCandidates(preferRawUntouched = preferRaw),
            platform = post.platform,
            customCookie = customCookie
        ) ?: return emptyList()

        val videoBytes = item.liveVideoUrl?.takeIf { it.startsWith("http") }?.let { vidUrl ->
            DynamicMediaRenderer.downloadRemoteBytes(
                urlStr = vidUrl,
                platform = post.platform,
                customCookie = customCookie,
                timeoutMs = 30_000
            )?.takeIf { it.size > 2048 }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        val realW = bounds.outWidth.takeIf { it > 0 } ?: item.width
        val realH = bounds.outHeight.takeIf { it > 0 } ?: item.height

        val synthesizedBytes = if (videoBytes != null) {
            runCatching {
                MotionPhotoSynthesizer.synthesizeMotionPhoto(
                    imageBytes = imageBytes,
                    videoBytes = videoBytes,
                    modifyMd5 = modifyMd5
                )
            }.getOrNull()
        } else null

        if (synthesizedBytes == null || synthesizedBytes.size <= imageBytes.size) {
            // 若动态视频流暂时无法获取，优雅保存静态原图
            return listOfNotNull(
                saveRawImageBytesToMediaStore(
                    context = context,
                    post = post,
                    item = item,
                    imageBytes = imageBytes,
                    exportFormatMode = exportFormatMode,
                    modifyMd5 = modifyMd5,
                    realW = realW,
                    realH = realH
                )
            )
        }

        val md5Hex = computeMd5Hex(synthesizedBytes)
        val relativePath = "${Environment.DIRECTORY_PICTURES}/PureSnap/${post.platform.folderName}"
        val fileName = "PureSnap_${post.platform.folderName}_${post.noteId}_LIVE_P${item.index + 1}_${System.currentTimeMillis() % 100000}.MP.jpg"

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
        val itemUri = resolver.insert(collection, values) ?: return emptyList()

        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                out.write(synthesizedBytes)
                out.flush()
            }
            val completeValues = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(itemUri, completeValues, null, null)
            notifyMediaScanner(context, itemUri)

            listOf(
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
                    format = "MP.JPG",
                    fileSizeBytes = synthesizedBytes.size.toLong(),
                    md5Hash = md5Hex,
                    md5Modified = modifyMd5,
                    visualSeed = item.visualSeed,
                    downloadedAtMillis = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {
            resolver.delete(itemUri, null, null)
            emptyList()
        }
    }

    suspend fun saveBgmAudioToMediaStore(
        context: Context,
        post: ParsedPost,
        bgm: AudioTrackInfo,
        modifyMd5: Boolean
    ): DownloadedMediaEntity? = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("ps_bgm_", ".tmp", context.cacheDir)
        try {
            val ok = DynamicMediaRenderer.downloadToFile(
                urlStr = bgm.url,
                platform = post.platform,
                destFile = tempFile,
                timeoutMs = 25_000
            )
            if (!ok || tempFile.length() < 512) return@withContext null

            val ext = if (bgm.format.equals("M4A", true)) "m4a" else "mp3"
            val mime = if (ext == "m4a") "audio/mp4" else "audio/mpeg"
            val relativePath = "${Environment.DIRECTORY_MUSIC}/PureSnap/${post.platform.folderName}"
            val fileName = "PureSnap_${post.platform.folderName}_BGM_${post.noteId}_${System.currentTimeMillis() % 100000}.$ext"

            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.TITLE, bgm.title)
                put(MediaStore.Audio.Media.ARTIST, bgm.author)
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values) ?: return@withContext null

            return@withContext try {
                val digest = MessageDigest.getInstance("MD5")
                var totalWritten = 0L
                resolver.openOutputStream(itemUri)?.use { out ->
                    tempFile.inputStream().use { input ->
                        val buf = ByteArray(32 * 1024)
                        var nRead: Int
                        while (input.read(buf).also { nRead = it } != -1) {
                            out.write(buf, 0, nRead)
                            digest.update(buf, 0, nRead)
                            totalWritten += nRead
                        }
                    }
                    if (modifyMd5) {
                        val salt = createSaltBytes()
                        out.write(salt)
                        digest.update(salt)
                        totalWritten += salt.size
                    }
                    out.flush()
                }
                val completeValues = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                resolver.update(itemUri, completeValues, null, null)
                notifyMediaScanner(context, itemUri)

                val md5Hex = digest.digest().joinToString("") { "%02x".format(it) }
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
                    format = ext.uppercase(),
                    fileSizeBytes = totalWritten,
                    md5Hash = md5Hex,
                    md5Modified = modifyMd5,
                    visualSeed = post.noteId.hashCode(),
                    downloadedAtMillis = System.currentTimeMillis()
                )
            } catch (_: Exception) {
                resolver.delete(itemUri, null, null)
                null
            }
        } finally {
            tempFile.delete()
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
            badge = QualityBadge.RAW,
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
        val preferRaw = exportFormatMode == ExportFormatMode.KEEP_ORIGINAL
        val remoteBytes = downloadFirstValidImageBytes(
            candidates = item.orderedDownloadCandidates(preferRawUntouched = preferRaw),
            platform = post.platform,
            customCookie = customCookie
        ) ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(remoteBytes, 0, remoteBytes.size, bounds)
        val realW = bounds.outWidth.takeIf { it > 0 } ?: item.width
        val realH = bounds.outHeight.takeIf { it > 0 } ?: item.height

        return saveRawImageBytesToMediaStore(
            context = context,
            post = post,
            item = item,
            imageBytes = remoteBytes,
            exportFormatMode = exportFormatMode,
            modifyMd5 = modifyMd5,
            realW = realW,
            realH = realH
        )
    }

    private fun saveRawImageBytesToMediaStore(
        context: Context,
        post: ParsedPost,
        item: MediaItem,
        imageBytes: ByteArray,
        exportFormatMode: ExportFormatMode,
        modifyMd5: Boolean,
        realW: Int,
        realH: Int
    ): DownloadedMediaEntity? {
        val sourceFormat = detectImageFormat(imageBytes, item.format)
        val isSpecialSourceFormat = sourceFormat == "WEBP" || sourceFormat == "HEIC"

        // 只有在明确需要转码时才解码像素；原图直出、或已是 JPG/PNG 目标格式时保持原始码流零二次压缩
        val (targetExtension, targetMimeType, processedBytes) = when {
            exportFormatMode == ExportFormatMode.COMPATIBLE_JPEG_100 && isSpecialSourceFormat -> {
                decodeAndReencodeLosslessSafe(imageBytes, Bitmap.CompressFormat.JPEG, "jpg", "image/jpeg")
                    ?: Triple(sourceFormat.lowercase(), mimeForFormat(sourceFormat), imageBytes)
            }
            exportFormatMode == ExportFormatMode.LOSSLESS_PNG && sourceFormat != "PNG" && sourceFormat != "GIF" -> {
                decodeAndReencodeLosslessSafe(imageBytes, Bitmap.CompressFormat.PNG, "png", "image/png")
                    ?: Triple(sourceFormat.lowercase(), mimeForFormat(sourceFormat), imageBytes)
            }
            else -> {
                Triple(extensionForFormat(sourceFormat), mimeForFormat(sourceFormat), imageBytes)
            }
        }

        val finalBytes = if (modifyMd5) {
            if (targetExtension == "jpg") {
                MotionPhotoSynthesizer.injectJpegCommentSalt(processedBytes)
            } else {
                appendMd5SaltBytes(processedBytes)
            }
        } else {
            processedBytes
        }

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
            val completeValues = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(itemUri, completeValues, null, null)
            notifyMediaScanner(context, itemUri)

            DownloadedMediaEntity(
                mediaStoreUri = itemUri.toString(),
                fileName = fileName,
                relativePath = "$relativePath/$fileName",
                platform = post.platform.name,
                authorName = post.authorName,
                title = post.title.ifBlank { post.content }.take(40).ifBlank { "${post.platform.displayName}无损原图" },
                mediaKind = MediaKind.IMAGE.name,
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
            item.fallbackUrls.filter { it.startsWith("http") }.forEach { add(it) }
        }

        val tempFile = File.createTempFile("ps_vid_${suffixIndex}_", ".mp4", context.cacheDir)
        try {
            var downloaded = false
            for (url in candidateUrls) {
                val ok = DynamicMediaRenderer.downloadToFile(
                    urlStr = url,
                    platform = post.platform,
                    destFile = tempFile,
                    customCookie = customCookie,
                    timeoutMs = 35_000
                )
                if (ok && tempFile.length() > 2048) {
                    downloaded = true
                    break
                }
            }
            if (!downloaded) return null

            var realW = item.width
            var realH = item.height
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(tempFile.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.let {
                        if (it > 0) realW = it
                    }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.let {
                        if (it > 0) realH = it
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }

            val relativePath = "${Environment.DIRECTORY_MOVIES}/PureSnap/${post.platform.folderName}"
            val fileName = "PureSnap_${post.platform.folderName}_${post.noteId}_VID${item.index + 1}_${suffixIndex}_${System.currentTimeMillis() % 100000}.mp4"

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.WIDTH, realW)
                put(MediaStore.Video.Media.HEIGHT, realH)
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values) ?: return null

            return try {
                val digest = MessageDigest.getInstance("MD5")
                var totalBytes = 0L
                resolver.openOutputStream(itemUri)?.use { out ->
                    tempFile.inputStream().use { input ->
                        val buf = ByteArray(32 * 1024)
                        var nRead: Int
                        while (input.read(buf).also { nRead = it } != -1) {
                            out.write(buf, 0, nRead)
                            digest.update(buf, 0, nRead)
                            totalBytes += nRead
                        }
                    }
                    if (modifyMd5) {
                        val freeBox = createMp4FreeBoxSalt()
                        out.write(freeBox)
                        digest.update(freeBox)
                        totalBytes += freeBox.size
                    }
                    out.flush()
                }
                val completeValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(itemUri, completeValues, null, null)
                notifyMediaScanner(context, itemUri)

                val md5Hex = digest.digest().joinToString("") { "%02x".format(it) }
                DownloadedMediaEntity(
                    mediaStoreUri = itemUri.toString(),
                    fileName = fileName,
                    relativePath = "$relativePath/$fileName",
                    platform = post.platform.name,
                    authorName = post.authorName,
                    title = post.title.ifBlank { post.content }.take(40).ifBlank { "${post.platform.displayName}无水印视频" },
                    mediaKind = MediaKind.VIDEO.name,
                    width = realW,
                    height = realH,
                    format = "MP4",
                    fileSizeBytes = totalBytes,
                    md5Hash = md5Hex,
                    md5Modified = modifyMd5,
                    visualSeed = item.visualSeed,
                    downloadedAtMillis = System.currentTimeMillis()
                )
            } catch (_: Exception) {
                resolver.delete(itemUri, null, null)
                null
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 实用工具：对手机本地任意图片或视频进行无损 MD5 改写并导出新副本
     */
    suspend fun duplicateLocalMediaWithNewMd5(
        context: Context,
        sourceUri: Uri
    ): DownloadedMediaEntity? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(sourceUri) ?: "image/jpeg"
        val isVideo = mime.startsWith("video/")

        val tempFile = File.createTempFile("ps_md5_", if (isVideo) ".mp4" else ".tmp", context.cacheDir)
        try {
            resolver.openInputStream(sourceUri)?.use { input ->
                tempFile.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext null

            if (tempFile.length() < 64) return@withContext null

            val ext: String
            val mediaKind: String
            val collection: Uri
            val relPath: String
            var width = 0
            var height = 0

            val bytesToWrite: ByteArray?
            if (isVideo) {
                ext = "mp4"
                mediaKind = MediaKind.VIDEO.name
                collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                relPath = "${Environment.DIRECTORY_MOVIES}/PureSnap/Repack"
                bytesToWrite = null
            } else {
                val raw = tempFile.readBytes()
                val fmt = detectImageFormat(raw, "JPG")
                ext = extensionForFormat(fmt)
                mediaKind = if (MotionPhotoSynthesizer.isMotionPhoto(raw)) MediaKind.LIVE_PHOTO.name else MediaKind.IMAGE.name
                collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                relPath = "${Environment.DIRECTORY_PICTURES}/PureSnap/Repack"
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                width = bounds.outWidth.coerceAtLeast(0)
                height = bounds.outHeight.coerceAtLeast(0)
                bytesToWrite = if (mediaKind == MediaKind.LIVE_PHOTO.name || fmt == "JPG") {
                    MotionPhotoSynthesizer.injectJpegCommentSalt(raw)
                } else {
                    appendMd5SaltBytes(raw)
                }
            }

            val fileName = "PureSnap_MD5_${System.currentTimeMillis() % 1000000}.$ext"
            val values = ContentValues().apply {
                if (isVideo) {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, mime)
                    put(MediaStore.Video.Media.RELATIVE_PATH, relPath)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeForFormat(ext.uppercase()))
                    if (width > 0) put(MediaStore.Images.Media.WIDTH, width)
                    if (height > 0) put(MediaStore.Images.Media.HEIGHT, height)
                    put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val targetUri = resolver.insert(collection, values) ?: return@withContext null
            return@withContext try {
                val digest = MessageDigest.getInstance("MD5")
                var totalSize = 0L
                resolver.openOutputStream(targetUri)?.use { out ->
                    if (bytesToWrite != null) {
                        out.write(bytesToWrite)
                        digest.update(bytesToWrite)
                        totalSize = bytesToWrite.size.toLong()
                    } else {
                        tempFile.inputStream().use { input ->
                            val buf = ByteArray(32 * 1024)
                            var nRead: Int
                            while (input.read(buf).also { nRead = it } != -1) {
                                out.write(buf, 0, nRead)
                                digest.update(buf, 0, nRead)
                                totalSize += nRead
                            }
                        }
                        val freeBox = createMp4FreeBoxSalt()
                        out.write(freeBox)
                        digest.update(freeBox)
                        totalSize += freeBox.size
                    }
                    out.flush()
                }
                val doneValues = ContentValues().apply {
                    put(
                        if (isVideo) MediaStore.Video.Media.IS_PENDING else MediaStore.Images.Media.IS_PENDING,
                        0
                    )
                }
                resolver.update(targetUri, doneValues, null, null)
                notifyMediaScanner(context, targetUri)

                DownloadedMediaEntity(
                    mediaStoreUri = targetUri.toString(),
                    fileName = fileName,
                    relativePath = "$relPath/$fileName",
                    platform = PlatformType.UNKNOWN.name,
                    authorName = "本地工具箱",
                    title = "MD5 改写副本 · $fileName",
                    mediaKind = mediaKind,
                    width = width,
                    height = height,
                    format = ext.uppercase(),
                    fileSizeBytes = totalSize,
                    md5Hash = digest.digest().joinToString("") { "%02x".format(it) },
                    md5Modified = true,
                    visualSeed = fileName.hashCode(),
                    downloadedAtMillis = System.currentTimeMillis()
                )
            } catch (_: Exception) {
                resolver.delete(targetUri, null, null)
                null
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 实用工具：删除本地 MediaStore 归档文件（仅删除本应用有权限移除的条目）
     */
    suspend fun deleteMediaStoreFiles(context: Context, uris: List<String>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (u in uris) {
            runCatching {
                val parsed = Uri.parse(u)
                count += context.contentResolver.delete(parsed, null, null)
            }
        }
        count
    }

    private fun downloadFirstValidImageBytes(
        candidates: List<String>,
        platform: PlatformType,
        customCookie: String?
    ): ByteArray? {
        val cleanCandidates = if (platform == PlatformType.DOUYIN) {
            candidates.filterNot { MultiPlatformParserEngine.isDouyinWatermarkedImageUrl(it) }
                .ifEmpty { candidates }
        } else {
            candidates
        }
        for (candidate in cleanCandidates) {
            val bytes = DynamicMediaRenderer.downloadRemoteBytes(
                urlStr = candidate,
                platform = platform,
                customCookie = customCookie,
                timeoutMs = 15_000
            )
            if (bytes != null && bytes.size > 256 && isLikelyImagePayload(bytes)) {
                return bytes
            }
        }
        return null
    }

    private fun isLikelyImagePayload(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        val prefix = String(bytes.copyOfRange(0, minOf(bytes.size, 64)), Charsets.ISO_8859_1).trimStart().lowercase()
        if (prefix.startsWith("<!doctype") || prefix.startsWith("<html") || prefix.startsWith("{")) {
            return false
        }
        return true
    }

    private fun decodeAndReencodeLosslessSafe(
        bytes: ByteArray,
        format: Bitmap.CompressFormat,
        ext: String,
        mime: String
    ): Triple<String, String, ByteArray>? {
        return runCatching {
            val opts = BitmapFactory.Options().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
                }
            }
            val rawBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val oriented = MotionPhotoSynthesizer.applyExifOrientationIfNeeded(bytes, rawBmp)
            val outBytes = ByteArrayOutputStream().use { bos ->
                oriented.compress(format, 100, bos)
                if (oriented !== rawBmp) oriented.recycle()
                rawBmp.recycle()
                bos.toByteArray()
            }
            Triple(ext, mime, outBytes)
        }.getOrNull()
    }

    fun detectImageFormat(bytes: ByteArray, defaultFormat: String): String {
        if (bytes.size >= 12) {
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
                return "PNG"
            }
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                return "JPG"
            }
            if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()) {
                return "WEBP"
            }
            if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) {
                return "GIF"
            }
            val ftyp = String(bytes.copyOfRange(4, 12), Charsets.ISO_8859_1).lowercase()
            if (ftyp.startsWith("ftyp") && (ftyp.contains("heic") || ftyp.contains("heix") || ftyp.contains("mif1") || ftyp.contains("avif"))) {
                return "HEIC"
            }
        }
        return defaultFormat.uppercase()
    }

    private fun extensionForFormat(fmt: String): String {
        return when (fmt.uppercase()) {
            "PNG" -> "png"
            "WEBP" -> "webp"
            "HEIC" -> "heic"
            "GIF" -> "gif"
            else -> "jpg"
        }
    }

    private fun mimeForFormat(fmt: String): String {
        return when (fmt.uppercase()) {
            "PNG" -> "image/png"
            "WEBP" -> "image/webp"
            "HEIC" -> "image/heic"
            "GIF" -> "image/gif"
            else -> "image/jpeg"
        }
    }

    fun appendMd5SaltBytes(original: ByteArray): ByteArray {
        return original + createSaltBytes()
    }

    private fun createSaltBytes(): ByteArray {
        return "PS_MD5_${UUID.randomUUID().toString().take(8)}_${System.nanoTime() % 9999}"
            .toByteArray(Charsets.UTF_8)
    }

    /**
     * 构造标准的 ISO BMFF 'free' box（8字节头 + 随机负载），追加至 MP4 末尾可在不破坏封装兼容性的前提下改写文件 MD5
     */
    fun createMp4FreeBoxSalt(): ByteArray {
        val payload = createSaltBytes()
        val boxSize = 8 + payload.size
        return ByteArrayOutputStream(boxSize).apply {
            write((boxSize ushr 24) and 0xFF)
            write((boxSize ushr 16) and 0xFF)
            write((boxSize ushr 8) and 0xFF)
            write(boxSize and 0xFF)
            write("free".toByteArray(Charsets.US_ASCII))
            write(payload)
        }.toByteArray()
    }

    fun computeMd5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun notifyMediaScanner(context: Context, uri: Uri) {
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(uri.toString()), null, null)
        }
    }
}
