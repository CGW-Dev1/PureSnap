package com.puresnap.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.MediaKind
import com.puresnap.app.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 真实网络媒体加载、流式传输与内存/磁盘两级缓存引擎
 * - 内存 LruCache 按实际字节占用计算上限（彻底杜绝 2K 大图导致 OOM）
 * - 磁盘预览缓存用于实况/视频本地平滑播放与二次快速预览
 * - 本地媒体库采用 Android 10+ 系统硬件级缩略图加载，杜绝瀑布流全尺寸解码卡顿
 */
object DynamicMediaRenderer {

    const val MOBILE_SAFARI_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    private const val PREVIEW_CACHE_DIR = "media_preview_cache"
    private const val MAX_DISK_CACHE_BYTES = 60L * 1024 * 1024 // 60 MB 上限
    private const val MAX_DISK_CACHE_AGE_MS = 3L * 24 * 60 * 60 * 1000 // 3 天

    private val cacheMemoryKb = ((Runtime.getRuntime().maxMemory() / 1024L) / 8L)
        .toInt()
        .coerceIn(16 * 1024, 64 * 1024)

    private val thumbnailCache = object : LruCache<String, Bitmap>(cacheMemoryKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    /**
     * 加载真实图片或视频封面缩略图（带字节限制内存 LruCache）
     */
    suspend fun loadPreviewBitmap(
        item: MediaItem,
        platform: PlatformType,
        customCookie: String? = null,
        maxDimension: Int = 960
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${item.id}_${item.previewUrl.hashCode()}_$maxDimension"
        thumbnailCache.get(cacheKey)?.let { if (!it.isRecycled) return@withContext it }

        // 缩略图加载优先使用轻量签名 previewUrl（快且稳），失败时再回退无损直链
        val candidateUrls = linkedSetOf<String>().apply {
            if (item.previewUrl.startsWith("http")) add(item.previewUrl)
            if (!item.isVideo && item.originUrl.startsWith("http")) add(item.originUrl)
            item.fallbackUrls.filter { !item.isVideo && it.startsWith("http") }.forEach { add(it) }
        }

        for (candidate in candidateUrls) {
            val bytes = downloadRemoteBytes(
                urlStr = candidate,
                platform = platform,
                customCookie = customCookie,
                timeoutMs = 10_000
            )
            if (bytes != null && bytes.size > 256) {
                decodeSampledBitmap(bytes, maxDimension)?.let { bmp ->
                    thumbnailCache.put(cacheKey, bmp)
                    return@withContext bmp
                }
            }
        }

        // 若是视频或实况且无封面图，从视频流截取首帧
        val videoUrl = if (item.isVideo) item.originUrl else item.liveVideoUrl
        if (!videoUrl.isNullOrBlank() && videoUrl.startsWith("http")) {
            extractVideoFrame(videoUrl, platform, customCookie)?.let { frameBmp ->
                thumbnailCache.put(cacheKey, frameBmp)
                return@withContext frameBmp
            }
        }

        null
    }

    /**
     * 媒体库本地缩略图高效加载（优先使用 Android 10+ ContentResolver.loadThumbnail，零 OOM）
     */
    suspend fun loadLocalGalleryThumbnail(
        context: Context,
        uriStr: String,
        mediaKind: String,
        maxDimension: Int = 520
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "local_${uriStr.hashCode()}_$maxDimension"
        thumbnailCache.get(cacheKey)?.let { if (!it.isRecycled) return@withContext it }

        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return@withContext null

        val bmp: Bitmap? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(maxDimension, maxDimension), null)
            } else {
                null
            }
        }.getOrNull() ?: runCatching {
            if (mediaKind == MediaKind.VIDEO.name) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.getFrameAtTime(0)
                } finally {
                    runCatching { retriever.release() }
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    decodeSampledBitmap(bytes, maxDimension)
                }
            }
        }.getOrNull()

        bmp?.also { thumbnailCache.put(cacheKey, it) }
    }

    suspend fun loadAvatarBitmap(
        avatarUrl: String,
        platform: PlatformType
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!avatarUrl.startsWith("http")) return@withContext null
        val cacheKey = "avatar_${avatarUrl.hashCode()}"
        thumbnailCache.get(cacheKey)?.let { if (!it.isRecycled) return@withContext it }

        val bytes = downloadRemoteBytes(
            urlStr = avatarUrl,
            platform = platform,
            timeoutMs = 6000
        ) ?: return@withContext null

        decodeSampledBitmap(bytes, 256)?.also { bmp ->
            thumbnailCache.put(cacheKey, bmp)
        }
    }

    suspend fun loadThumbnailBitmap(
        urlStr: String,
        platform: PlatformType,
        maxDimension: Int = 360
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!urlStr.startsWith("http")) return@withContext null
        val cacheKey = "thumb_${urlStr.hashCode()}_$maxDimension"
        thumbnailCache.get(cacheKey)?.let { if (!it.isRecycled) return@withContext it }

        val bytes = downloadRemoteBytes(
            urlStr = urlStr,
            platform = platform,
            timeoutMs = 8000
        ) ?: return@withContext null

        decodeSampledBitmap(bytes, maxDimension)?.also { bmp ->
            thumbnailCache.put(cacheKey, bmp)
        }
    }

    /**
     * 下载并缓存媒体文件至应用缓存目录，用于实况/视频本地稳定播放（彻底规避 VideoView 跨协议重定向与 Header 兼容性错误）
     */
    suspend fun downloadToCacheFile(
        context: Context,
        urlStr: String,
        platform: PlatformType,
        customCookie: String? = null,
        extension: String = "mp4",
        timeoutMs: Int = 25_000
    ): File? = withContext(Dispatchers.IO) {
        if (!urlStr.startsWith("http")) return@withContext null
        val cacheDir = File(context.cacheDir, PREVIEW_CACHE_DIR).apply { mkdirs() }
        val fileName = "ps_${platform.name.lowercase()}_${urlStr.hashCode().toUInt()}.$extension"
        val targetFile = File(cacheDir, fileName)
        if (targetFile.exists() && targetFile.length() > 2048) {
            targetFile.setLastModified(System.currentTimeMillis())
            return@withContext targetFile
        }

        val tempFile = File(cacheDir, "$fileName.part")
        val ok = downloadToFile(
            urlStr = urlStr,
            platform = platform,
            destFile = tempFile,
            customCookie = customCookie,
            timeoutMs = timeoutMs
        )
        if (ok && tempFile.length() > 1024) {
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)
            targetFile
        } else {
            tempFile.delete()
            null
        }
    }

    fun downloadToFile(
        urlStr: String,
        platform: PlatformType,
        destFile: File,
        customCookie: String? = null,
        timeoutMs: Int = 30_000,
        onBytesRead: ((Long) -> Unit)? = null
    ): Boolean {
        return openStreamingConnection(
            urlStr = urlStr,
            platform = platform,
            customCookie = customCookie,
            timeoutMs = timeoutMs
        ) { inputStream, _ ->
            destFile.parentFile?.mkdirs()
            FileOutputStream(destFile).use { out ->
                val buf = ByteArray(32 * 1024)
                var total = 0L
                var nRead: Int
                while (inputStream.read(buf).also { nRead = it } != -1) {
                    out.write(buf, 0, nRead)
                    total += nRead
                    onBytesRead?.invoke(total)
                }
                out.flush()
            }
            true
        } ?: false
    }

    fun <T> openStreamingConnection(
        urlStr: String,
        platform: PlatformType,
        customCookie: String? = null,
        timeoutMs: Int = 25_000,
        block: (InputStream, Long) -> T
    ): T? {
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) return null
        var currentUrl = urlStr
        var redirects = 0

        while (redirects < 8) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", MOBILE_SAFARI_UA)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Referer", refererForPlatform(platform, currentUrl))
                    if (!customCookie.isNullOrBlank()) {
                        setRequestProperty("Cookie", customCookie)
                    }
                }

                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: return null
                    currentUrl = URL(url, location).toString()
                    redirects++
                    continue
                }

                if (code in 200..299) {
                    val contentLength = connection.contentLengthLong
                    return connection.inputStream.use { input ->
                        block(input, contentLength)
                    }
                }
                return null
            } catch (_: Exception) {
                return null
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }

    /**
     * 从远程 CDN 下载字节流（适用于图片、小片段或元信息加载）
     */
    fun downloadRemoteBytes(
        urlStr: String,
        platform: PlatformType,
        customCookie: String? = null,
        timeoutMs: Int = 15_000
    ): ByteArray? {
        return openStreamingConnection(
            urlStr = urlStr,
            platform = platform,
            customCookie = customCookie,
            timeoutMs = timeoutMs
        ) { input, contentLength ->
            val initialSize = if (contentLength > 0 && contentLength < 32 * 1024 * 1024) {
                contentLength.toInt()
            } else {
                16 * 1024
            }
            val buffer = ByteArrayOutputStream(initialSize)
            val data = ByteArray(32 * 1024)
            var nRead: Int
            while (input.read(data, 0, data.size).also { nRead = it } != -1) {
                buffer.write(data, 0, nRead)
            }
            buffer.toByteArray()
        }
    }

    fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

            var sampleSize = 1
            val w = boundsOptions.outWidth
            val h = boundsOptions.outHeight
            if (w > maxDimension || h > maxDimension) {
                val halfW = w / 2
                val halfH = h / 2
                while ((halfW / sampleSize) >= maxDimension && (halfH / sampleSize) >= maxDimension) {
                    sampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractVideoFrame(
        videoUrl: String,
        platform: PlatformType,
        customCookie: String? = null
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val headers = buildMap {
                put("User-Agent", MOBILE_SAFARI_UA)
                put("Referer", refererForPlatform(platform, videoUrl))
                if (!customCookie.isNullOrBlank()) put("Cookie", customCookie)
            }
            retriever.setDataSource(videoUrl, headers)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun pruneDiskCache(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, PREVIEW_CACHE_DIR)
            if (!dir.exists()) return
            val now = System.currentTimeMillis()
            val files = dir.listFiles()?.toMutableList() ?: return

            files.removeAll { file ->
                val expired = (now - file.lastModified()) > MAX_DISK_CACHE_AGE_MS || file.name.endsWith(".part")
                if (expired) {
                    file.delete()
                    true
                } else false
            }

            var totalBytes = files.sumOf { it.length() }
            if (totalBytes > MAX_DISK_CACHE_BYTES) {
                files.sortBy { it.lastModified() }
                for (file in files) {
                    if (totalBytes <= MAX_DISK_CACHE_BYTES * 0.75) break
                    val len = file.length()
                    if (file.delete()) {
                        totalBytes -= len
                    }
                }
            }
        }
    }

    fun getDiskCacheSizeBytes(context: Context): Long {
        val dir = File(context.cacheDir, PREVIEW_CACHE_DIR)
        if (!dir.exists()) return 0L
        return dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun clearDiskCache(context: Context) {
        thumbnailCache.evictAll()
        runCatching {
            File(context.cacheDir, PREVIEW_CACHE_DIR).deleteRecursively()
        }
    }

    fun refererForPlatform(platform: PlatformType, url: String): String {
        return when (platform) {
            PlatformType.XIAOHONGSHU -> "https://www.xiaohongshu.com/"
            PlatformType.DOUYIN -> "https://www.iesdouyin.com/"
            PlatformType.ZUIYOU -> "https://share.izuiyou.com/"
            PlatformType.KUAISHOU -> "https://www.kuaishou.com/"
            PlatformType.WEIBO -> "https://m.weibo.cn/"
            PlatformType.BILIBILI -> "https://www.bilibili.com/"
            PlatformType.PIPIXIA -> "https://h5.pipix.com/"
            PlatformType.INSTAGRAM -> "https://www.instagram.com/"
            PlatformType.TIKTOK -> "https://www.tiktok.com/"
            PlatformType.TWITTER -> "https://x.com/"
            PlatformType.THREADS -> "https://www.threads.net/"
            PlatformType.PINTEREST -> "https://www.pinterest.com/"
            PlatformType.YOUTUBE -> "https://www.youtube.com/"
            PlatformType.REDDIT -> "https://www.reddit.com/"
            else -> url
        }
    }
}
