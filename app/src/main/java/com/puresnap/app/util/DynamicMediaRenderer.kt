package com.puresnap.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 真实网络媒体加载与解码引擎
 * - 手动处理跨协议 (http <-> https) 301/302/307/308 重定向，每次跳转保持移动端 User-Agent 与 Referer
 * - 缩略图优先加载 previewUrl（封面/直链），若失败自动回退至 originUrl；视频支持 MediaMetadataRetriever 抽帧
 */
object DynamicMediaRenderer {

    private const val MOBILE_SAFARI_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    private val thumbnailCache = object : LruCache<String, Bitmap>(80) {}

    /**
     * 加载真实图片或视频封面缩略图（带内存 LruCache）
     */
    suspend fun loadPreviewBitmap(
        item: MediaItem,
        platform: PlatformType,
        customCookie: String? = null,
        maxDimension: Int = 960
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${item.id}_${item.previewUrl.hashCode()}_$maxDimension"
        thumbnailCache.get(cacheKey)?.let { if (!it.isRecycled) return@withContext it }

        // 1. 对于图片优先拉取无水印直链 originUrl，若特定节点受限则自动回退至 previewUrl；对于视频优先拉取封面 previewUrl
        val candidateUrls = linkedSetOf<String>().apply {
            if (!item.isVideo && item.originUrl.startsWith("http")) add(item.originUrl)
            if (item.previewUrl.startsWith("http")) add(item.previewUrl)
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

        // 2. 若是视频且无独立封面图，使用 MediaMetadataRetriever 从真实视频流抽取首帧
        val videoUrl = if (item.isVideo) item.originUrl else item.liveVideoUrl
        if (!videoUrl.isNullOrBlank() && videoUrl.startsWith("http")) {
            extractVideoFrame(videoUrl, platform)?.let { frameBmp ->
                thumbnailCache.put(cacheKey, frameBmp)
                return@withContext frameBmp
            }
        }

        null
    }

    /**
     * 加载作者真实高清头像
     */
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

    /**
     * 加载任意远程缩略图或封面图（带内存 LruCache）
     */
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
     * 从远程 CDN 下载原始媒体字节流（支持跨 http/https 重定向追踪与备用 URL 自动降级）
     */
    fun downloadRemoteBytes(
        urlStr: String,
        platform: PlatformType,
        customCookie: String? = null,
        timeoutMs: Int = 15_000
    ): ByteArray? {
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
                    instanceFollowRedirects = false // 手动处理跨协议重定向
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
                    return connection.inputStream.use { input ->
                        val buffer = ByteArrayOutputStream()
                        val data = ByteArray(32 * 1024)
                        var nRead: Int
                        while (input.read(data, 0, data.size).also { nRead = it } != -1) {
                            buffer.write(data, 0, nRead)
                        }
                        buffer.toByteArray()
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

    private fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? {
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

    private fun extractVideoFrame(videoUrl: String, platform: PlatformType): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val headers = mapOf(
                "User-Agent" to MOBILE_SAFARI_UA,
                "Referer" to refererForPlatform(platform, videoUrl)
            )
            retriever.setDataSource(videoUrl, headers)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    fun refererForPlatform(platform: PlatformType, url: String): String {
        return when (platform) {
            PlatformType.XIAOHONGSHU -> "https://www.xiaohongshu.com/"
            PlatformType.DOUYIN -> "https://www.iesdouyin.com/"
            PlatformType.ZUIYOU -> "https://share.izuiyou.com/"
            PlatformType.KUAISHOU -> "https://www.kuaishou.com/"
            PlatformType.WEIBO -> "https://m.weibo.cn/"
            PlatformType.BILIBILI -> "https://m.bilibili.com/"
            else -> url
        }
    }
}
