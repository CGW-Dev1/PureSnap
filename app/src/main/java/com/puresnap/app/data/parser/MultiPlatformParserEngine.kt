package com.puresnap.app.data.parser

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.puresnap.app.data.local.ParseHistoryEntity
import com.puresnap.app.data.preferences.UserPreferencesState
import com.puresnap.app.model.AudioTrackInfo
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.MediaKind
import com.puresnap.app.model.ParseErrorType
import com.puresnap.app.model.ParsedPost
import com.puresnap.app.model.PlatformType
import com.puresnap.app.model.QualityBadge
import com.puresnap.app.util.DynamicMediaRenderer
import com.puresnap.app.util.ShareLinkExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * 真实多平台无水印原图/视频解析引擎（基于 Gson JsonParser 深度状态树解析 + 后台 WebView 动态嗅探）
 */
object MultiPlatformParserEngine {

    private val gson = Gson()

    private const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    sealed class ParseOutcome {
        data class Success(val post: ParsedPost) : ParseOutcome()
        data class Failure(
            val errorType: ParseErrorType,
            val extractedUrl: String? = null,
            val platform: PlatformType = PlatformType.UNKNOWN
        ) : ParseOutcome()
    }

    private data class HttpPageResponse(
        val finalUrl: String,
        val html: String,
        val statusCode: Int
    )

    suspend fun parseShareInput(
        context: Context,
        rawInput: String,
        preferences: UserPreferencesState,
        previousHistory: ParseHistoryEntity? = null
    ): ParseOutcome = withContext(Dispatchers.IO) {
        val extraction = ShareLinkExtractor.extract(rawInput)
        val cleanUrl = extraction.cleanUrl
            ?: return@withContext ParseOutcome.Failure(ParseErrorType.NO_VALID_LINK)

        if (extraction.isPrivateOrDeletedHint) {
            return@withContext ParseOutcome.Failure(
                errorType = ParseErrorType.PRIVATE_OR_DELETED,
                extractedUrl = cleanUrl,
                platform = extraction.platform
            )
        }

        val platform = if (extraction.platform == PlatformType.UNKNOWN) {
            PlatformType.XIAOHONGSHU
        } else {
            extraction.platform
        }

        val customCookie = preferences.cookieForPlatform(platform)

        // 第一层：客户端直连 HTTP 重定向追踪 + 页面状态树解析
        val directPost = tryDirectHttpExtraction(
            rawInput = rawInput,
            cleanUrl = cleanUrl,
            platform = platform,
            preferences = preferences,
            customCookie = customCookie,
            previousHistory = previousHistory
        )
        if (directPost != null && directPost.mediaItems.isNotEmpty()) {
            return@withContext ParseOutcome.Success(directPost)
        }

        // 第二层：后台无感 Chromium WebView 动态执行 JS 绕过 WAF 签名挑战并提取 _ROUTER_DATA / __INITIAL_STATE__
        val sniffResult = HiddenWebViewExtractor.sniffUrl(
            context = context,
            targetUrl = cleanUrl,
            customCookie = customCookie,
            timeoutMs = 9000L
        )
        if (sniffResult != null) {
            val webParsed = parseFromWebSniffResult(
                rawInput = rawInput,
                cleanUrl = cleanUrl,
                platform = platform,
                sniff = sniffResult,
                preferences = preferences,
                previousHistory = previousHistory
            )
            if (webParsed != null && webParsed.mediaItems.isNotEmpty()) {
                return@withContext ParseOutcome.Success(webParsed)
            }
        }

        ParseOutcome.Failure(
            errorType = ParseErrorType.ANTI_SCRAPE_TIMEOUT,
            extractedUrl = cleanUrl,
            platform = platform
        )
    }

    fun parseFromWebViewHtml(
        url: String,
        html: String,
        preferences: UserPreferencesState
    ): ParsedPost? {
        val platform = ShareLinkExtractor.detectPlatform(url, url).let {
            if (it == PlatformType.UNKNOWN) PlatformType.XIAOHONGSHU else it
        }
        val workId = ShareLinkExtractor.extractWorkId(url, platform)

        if (platform == PlatformType.DOUYIN) {
            parseDouyinHtmlState(
                rawInput = url,
                cleanUrl = url,
                finalUrl = url,
                html = html,
                workId = workId,
                previousHistory = null
            )?.let { return it }
        }

        if (platform == PlatformType.XIAOHONGSHU) {
            parseXiaohongshuHtmlState(
                rawInput = url,
                cleanUrl = url,
                finalUrl = url,
                html = html,
                workId = workId,
                preferences = preferences,
                previousHistory = null
            )?.let { return it }
        }

        if (platform == PlatformType.KUAISHOU) {
            parseKuaishouHtmlState(
                rawInput = url,
                cleanUrl = url,
                finalUrl = url,
                html = html,
                workId = workId,
                previousHistory = null
            )?.let { return it }

            parseKuaishouRegexSniff(
                rawInput = url,
                cleanUrl = url,
                finalUrl = url,
                html = html,
                workId = workId,
                previousHistory = null
            )?.let { return it }
        }

        return parseGenericHtmlMetaAndDom(
            rawInput = url,
            cleanUrl = url,
            html = html,
            platform = platform,
            workId = workId,
            preferences = preferences,
            previousHistory = null
        )
    }

    fun rewriteToLosslessOriginUrl(
        rawMediaUrl: String,
        platform: PlatformType,
        xhsCdnBase: String = "https://ci.xiaohongshu.com/"
    ): String {
        val httpsUrl = rawMediaUrl.replace("\\u002F", "/").replace("http://", "https://").trim()
        return when (platform) {
            PlatformType.XIAOHONGSHU -> {
                val noQuery = httpsUrl.substringBefore("?").substringBefore("!")
                val afterDomain = noQuery.substringAfter(".com/", "")
                val strippedTimestampHash = afterDomain.replace(
                    Regex("""^\d{10,14}/[0-9a-fA-F]{24,40}/"""),
                    ""
                )
                val key = strippedTimestampHash.ifBlank { noQuery.substringAfterLast("/") }
                val normalizedBase = if (xhsCdnBase.endsWith("/")) xhsCdnBase else "$xhsCdnBase/"
                "${normalizedBase}${key}?imageView2/2/w/0/format/png"
            }
            PlatformType.DOUYIN -> {
                val replaced = httpsUrl
                    .replace("/playwm/", "/play/")
                    .replace("playwm", "play")
                if (replaced.contains("aweme/v1/play")) {
                    if (replaced.contains("ratio=")) {
                        replaced.replace(Regex("ratio=[0-9a-zA-Z]+"), "ratio=1080p")
                    } else {
                        val sep = if (replaced.contains("?")) "&" else "?"
                        "${replaced}${sep}ratio=1080p"
                    }
                } else {
                    replaced
                }
            }
            PlatformType.ZUIYOU -> {
                httpsUrl
                    .replace("/watermarked", "/origin")
                    .replace("watermarked", "origin")
                    .substringBefore("?x-oss-process")
            }
            PlatformType.KUAISHOU -> {
                // 快手无损原图直出：剥离客户端截断参数与压缩格式转换，保留原生 4K/超清原图
                httpsUrl
                    .replace(Regex("""[?&]bp=\d+"""), "")
                    .replace(Regex("""[?&]format=webp"""), "")
                    .replace(Regex("""_webp\b"""), "")
            }
            else -> httpsUrl.substringBefore("?x-oss-process")
        }
    }

    private fun tryDirectHttpExtraction(
        rawInput: String,
        cleanUrl: String,
        platform: PlatformType,
        preferences: UserPreferencesState,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        val page = fetchPageFollowingRedirects(cleanUrl, platform, customCookie) ?: return null
        val workId = ShareLinkExtractor.extractWorkId(page.finalUrl.ifBlank { cleanUrl }, platform)

        return when (platform) {
            PlatformType.DOUYIN -> {
                parseDouyinHtmlState(
                    rawInput = rawInput,
                    cleanUrl = cleanUrl,
                    finalUrl = page.finalUrl,
                    html = page.html,
                    workId = workId,
                    previousHistory = previousHistory
                ) ?: run {
                    val awemeId = extractDouyinAwemeId(page.finalUrl, page.html)
                    if (!awemeId.isNullOrBlank()) {
                        for (pathType in listOf("video", "note", "slides")) {
                            val shareUrl = "https://www.iesdouyin.com/share/$pathType/$awemeId/"
                            val sharePage = fetchPageFollowingRedirects(shareUrl, PlatformType.DOUYIN, customCookie)
                            if (sharePage != null && sharePage.html.isNotBlank()) {
                                val parsed = parseDouyinHtmlState(
                                    rawInput = rawInput,
                                    cleanUrl = cleanUrl,
                                    finalUrl = sharePage.finalUrl,
                                    html = sharePage.html,
                                    workId = awemeId,
                                    previousHistory = previousHistory
                                )
                                if (parsed != null && parsed.mediaItems.isNotEmpty()) {
                                    return@run parsed
                                }
                            }
                        }
                    }
                    null
                }
            }

            PlatformType.XIAOHONGSHU -> {
                parseXiaohongshuHtmlState(
                    rawInput = rawInput,
                    cleanUrl = cleanUrl,
                    finalUrl = page.finalUrl,
                    html = page.html,
                    workId = workId,
                    preferences = preferences,
                    previousHistory = previousHistory
                )
            }

            PlatformType.KUAISHOU -> {
                // 快手多级直连解析：状态树 INIT_STATE -> 正则嗅探兜底
                parseKuaishouHtmlState(
                    rawInput = rawInput,
                    cleanUrl = cleanUrl,
                    finalUrl = page.finalUrl,
                    html = page.html,
                    workId = workId,
                    previousHistory = previousHistory
                ) ?: parseKuaishouRegexSniff(
                    rawInput = rawInput,
                    cleanUrl = cleanUrl,
                    finalUrl = page.finalUrl,
                    html = page.html,
                    workId = workId,
                    previousHistory = previousHistory
                )
            }

            else -> {
                parseGenericHtmlMetaAndDom(
                    rawInput = rawInput,
                    cleanUrl = cleanUrl,
                    html = page.html,
                    platform = platform,
                    workId = workId,
                    preferences = preferences,
                    previousHistory = previousHistory
                )
            }
        }
    }

    private fun parseDouyinHtmlState(
        rawInput: String,
        cleanUrl: String,
        finalUrl: String,
        html: String,
        workId: String,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        val routerJsonStr = extractBalancedJsonObject(html, "window._ROUTER_DATA")
            ?: extractBalancedJsonObject(html, "window._SSR_DATA")
            ?: extractRenderDataJson(html)

        if (!routerJsonStr.isNullOrBlank()) {
            parseDouyinRouterJson(
                rawInput = rawInput,
                cleanUrl = cleanUrl,
                routerJsonStr = routerJsonStr,
                fallbackWorkId = workId,
                previousHistory = previousHistory
            )?.let { return it }
        }

        return null
    }

    private fun parseDouyinRouterJson(
        rawInput: String,
        cleanUrl: String,
        routerJsonStr: String,
        fallbackWorkId: String,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        return try {
            val root = JsonParser.parseString(routerJsonStr)
            val itemObj = findDouyinAwemeItemObject(root) ?: return null

            val awemeId = itemObj.optStr("aweme_id").ifBlank { fallbackWorkId }
            val desc = itemObj.optStr("desc").trim()

            val authorObj = itemObj.optObj("author")
            val authorName = authorObj?.optStr("nickname")?.takeIf { it.isNotBlank() } ?: "抖音创作者"
            val authorHandle = authorObj?.optStr("unique_id")?.takeIf { it.isNotBlank() }
                ?: authorObj?.optStr("short_id")?.takeIf { it.isNotBlank() }
                ?: awemeId.take(8)
            val avatarUrl = extractFirstUrlFromHolder(authorObj?.optObj("avatar_larger"))
                ?: extractFirstUrlFromHolder(authorObj?.optObj("avatar_medium"))
                ?: extractFirstUrlFromHolder(authorObj?.optObj("avatar_thumb"))
                ?: ""

            val tags = extractHashtags(desc, itemObj.optArr("text_extra"))

            val mediaItems = mutableListOf<MediaItem>()
            val imagesArr = itemObj.optArr("images")
                ?: itemObj.optObj("image_post_info")?.optArr("images")
                ?: itemObj.optArr("image_list")
                ?: itemObj.optObj("image_album")?.optArr("images")

            val rootVideoObj = itemObj.optObj("video")
            val rootLiveVideoUrl = rootVideoObj?.optObj("play_addr")?.let { playAddr ->
                extractFirstUrlFromArray(playAddr.optArr("url_list"))
                    ?: extractFirstUrlFromArray(rootVideoObj.optArr("play_addr_h264"))
            }?.let { rewriteToLosslessOriginUrl(it, PlatformType.DOUYIN) }
                ?: rootVideoObj?.optStr("uri")?.takeIf { it.isNotBlank() }?.let {
                    "https://aweme.snssdk.com/aweme/v1/play/?video_id=$it&ratio=1080p&line=0"
                }

            if (imagesArr != null && imagesArr.size() > 0) {
                for (i in 0 until imagesArr.size()) {
                    val imgObj = imagesArr.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val candidateImageArrays = listOfNotNull(
                        imgObj.optObj("display_image")?.optArr("url_list"),
                        imgObj.optObj("origin_image")?.optArr("url_list"),
                        imgObj.optObj("original_image")?.optArr("url_list"),
                        imgObj.optObj("download_image")?.optArr("url_list"),
                        imgObj.optArr("url_list"),
                        imgObj.optArr("download_url_list"),
                        imgObj.optObj("thumbnail_image")?.optArr("url_list"),
                        imgObj.optObj("live_photo")?.optObj("cover")?.optArr("url_list")
                    )

                    var bestOriginUrl: String? = null
                    for (arr in candidateImageArrays) {
                        val best = selectBestDouyinImageUrl(arr)
                        if (!best.isNullOrBlank()) {
                            bestOriginUrl = best
                            break
                        }
                    }
                    if (bestOriginUrl.isNullOrBlank()) {
                        bestOriginUrl = candidateImageArrays.firstNotNullOfOrNull { extractFirstUrlFromArray(it) }
                            ?: continue
                    }
                    val previewUrl = candidateImageArrays.firstNotNullOfOrNull { extractFirstUrlFromArray(it) } ?: bestOriginUrl

                    val w = imgObj.optInt("width", 0).takeIf { it > 0 }
                        ?: imgObj.optObj("display_image")?.optInt("width", 0)?.takeIf { it > 0 }
                        ?: imgObj.optObj("origin_image")?.optInt("width", 0)?.takeIf { it > 0 }
                        ?: 1080
                    val h = imgObj.optInt("height", 0).takeIf { it > 0 }
                        ?: imgObj.optObj("display_image")?.optInt("height", 0)?.takeIf { it > 0 }
                        ?: imgObj.optObj("origin_image")?.optInt("height", 0)?.takeIf { it > 0 }
                        ?: 1440

                    val is4K = w >= 2160 || h >= 2160
                    val fmt = if (bestOriginUrl.lowercase().contains(".webp")) "WEBP" else "JPG"

                    mediaItems.add(
                        MediaItem(
                            id = "${awemeId}_dy_img_$i",
                            index = i,
                            kind = MediaKind.IMAGE,
                            badge = if (is4K) QualityBadge.FOUR_K else QualityBadge.RAW,
                            originUrl = bestOriginUrl,
                            previewUrl = previewUrl,
                            liveVideoUrl = null,
                            width = w,
                            height = h,
                            format = fmt,
                            colorSpace = "Display P3",
                            themeTitle = desc,
                            visualSeed = i
                        )
                    )
                }
            }

            if (mediaItems.isEmpty()) {
                val videoObj = itemObj.optObj("video")
                if (videoObj != null) {
                    val playAddr = videoObj.optObj("play_addr")
                    val uri = playAddr?.optStr("uri") ?: ""
                    val rawPlayUrl = extractFirstUrlFromArray(playAddr?.optArr("url_list"))

                    val losslessVideoUrl = when {
                        !rawPlayUrl.isNullOrBlank() -> rewriteToLosslessOriginUrl(rawPlayUrl, PlatformType.DOUYIN)
                        uri.isNotBlank() -> "https://aweme.snssdk.com/aweme/v1/play/?video_id=$uri&ratio=1080p&line=0"
                        else -> null
                    }

                    val coverUrl = extractFirstUrlFromHolder(videoObj.optObj("origin_cover"))
                        ?: extractFirstUrlFromHolder(videoObj.optObj("cover"))
                        ?: extractFirstUrlFromHolder(videoObj.optObj("dynamic_cover"))
                        ?: ""

                    val w = videoObj.optInt("width", 1080).coerceAtLeast(720)
                    val h = videoObj.optInt("height", 1920).coerceAtLeast(1280)

                    if (!losslessVideoUrl.isNullOrBlank()) {
                        mediaItems.add(
                            MediaItem(
                                id = "${awemeId}_dy_vid_0",
                                index = 0,
                                kind = MediaKind.VIDEO,
                                badge = if (w >= 2160 || h >= 2160) QualityBadge.FOUR_K else QualityBadge.VIDEO_HD,
                                originUrl = losslessVideoUrl,
                                previewUrl = coverUrl.ifBlank { losslessVideoUrl },
                                liveVideoUrl = if (uri.isNotBlank()) {
                                    "https://aweme.snssdk.com/aweme/v1/play/?video_id=$uri&ratio=1080p&line=0"
                                } else losslessVideoUrl,
                                width = w,
                                height = h,
                                format = "MP4",
                                colorSpace = "BT.709",
                                themeTitle = desc,
                                visualSeed = 0
                            )
                        )
                    }
                }
            }

            if (mediaItems.isEmpty()) return null

            val musicObj = itemObj.optObj("music")
            val bgmUrl = extractFirstUrlFromHolder(musicObj?.optObj("play_url"))
            val bgmAudio = if (!bgmUrl.isNullOrBlank()) {
                AudioTrackInfo(
                    id = "bgm_$awemeId",
                    title = musicObj?.optStr("title")?.ifBlank { "抖音原声 BGM" } ?: "抖音原声 BGM",
                    author = musicObj?.optStr("author")?.ifBlank { authorName } ?: authorName,
                    url = bgmUrl,
                    format = "MP3",
                    durationSec = musicObj?.optInt("duration", 30) ?: 30
                )
            } else null

            val postTypeLabel = when {
                mediaItems.any { it.isVideo } -> "抖音·视频"
                else -> "抖音·图集"
            }
            val coverUrl = mediaItems.first().previewUrl

            ParsedPost(
                id = "post_$awemeId",
                sourceShareText = rawInput,
                extractedUrl = cleanUrl,
                noteId = awemeId,
                platform = PlatformType.DOUYIN,
                postTypeLabel = postTypeLabel,
                authorName = authorName,
                authorHandle = "@$authorHandle",
                authorAvatarUrl = avatarUrl,
                coverUrl = coverUrl,
                title = desc.lineSequence().firstOrNull()?.take(80) ?: "",
                content = desc,
                tags = tags,
                mediaItems = mediaItems,
                bgmAudio = bgmAudio,
                parseEngineSource = "抖音原画直连",
                isDuplicateDownload = previousHistory?.downloadedOnce == true,
                previousDownloadTimeMillis = previousHistory?.parsedAtMillis
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseXiaohongshuHtmlState(
        rawInput: String,
        cleanUrl: String,
        finalUrl: String,
        html: String,
        workId: String,
        preferences: UserPreferencesState,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        val markers = listOf(
            "window.__INITIAL_STATE__",
            "__INITIAL_STATE__",
            "window.__INITIAL_SSR_STATE__",
            "__INITIAL_SSR_STATE__"
        )
        for (marker in markers) {
            val initialStateRaw = extractBalancedJsonObject(html, marker)
            if (!initialStateRaw.isNullOrBlank()) {
                val sanitizedJson = initialStateRaw.replace(Regex("""\bundefined\b"""), "null")

                parseXiaohongshuStateJson(
                    rawInput = rawInput,
                    cleanUrl = cleanUrl,
                    stateJsonStr = sanitizedJson,
                    html = html,
                    fallbackWorkId = workId,
                    preferences = preferences,
                    previousHistory = previousHistory
                )?.let { return it }
            }
        }

        return parseGenericHtmlMetaAndDom(
            rawInput = rawInput,
            cleanUrl = cleanUrl,
            html = html,
            platform = PlatformType.XIAOHONGSHU,
            workId = workId,
            preferences = preferences,
            previousHistory = previousHistory
        )
    }

    private fun parseXiaohongshuStateJson(
        rawInput: String,
        cleanUrl: String,
        stateJsonStr: String,
        html: String,
        fallbackWorkId: String,
        preferences: UserPreferencesState,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        return try {
            val root = JsonParser.parseString(stateJsonStr)
            val noteObj = findXiaohongshuNoteObject(root) ?: return null

            val noteId = noteObj.optStr("noteId").ifBlank { fallbackWorkId }
            val title = noteObj.optStr("title").replace("[话题]#", "").trim()
            val desc = noteObj.optStr("desc").replace("[话题]#", "").trim()

            val userObj = noteObj.optObj("user")
            val authorName = userObj?.optStr("nickname")?.takeIf { it.isNotBlank() }
                ?: userObj?.optStr("nickName")?.takeIf { it.isNotBlank() }
                ?: "小红书创作者"
            val authorAvatar = (userObj?.optStr("avatar")?.takeIf { it.isNotBlank() }
                ?: userObj?.optStr("images")?.takeIf { it.isNotBlank() }
                ?: "").replace("http://", "https://")

            val tags = mutableListOf<String>()
            noteObj.optArr("tagList")?.let { tagArr ->
                for (i in 0 until tagArr.size()) {
                    val name = tagArr.get(i).takeIf { it.isJsonObject }?.asJsonObject?.optStr("name")?.trim()
                    if (!name.isNullOrBlank()) {
                        tags.add("#$name")
                    }
                }
            }
            if (tags.isEmpty()) {
                tags.addAll(extractHashtags("$title $desc", null))
            }

            val mediaItems = mutableListOf<MediaItem>()
            val noteType = noteObj.optStr("type", "normal")

            val videoObj = noteObj.optObj("video")
            val videoMasterUrl = extractXiaohongshuVideoUrl(videoObj)
                ?: extractMetaTags(html, "og:video").firstOrNull()

            val imageList = noteObj.optArr("imageList") ?: noteObj.optArr("imagesList")

            if (noteType == "video" && !videoMasterUrl.isNullOrBlank()) {
                val firstCover = imageList?.takeIf { it.size() > 0 }
                    ?.get(0)?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.let { extractXiaohongshuPreviewUrl(it) }
                    ?: extractMetaTags(html, "og:image").firstOrNull()
                    ?: ""
                val w = videoObj?.optObj("capa")?.optInt("width", 1080) ?: 1080
                val h = videoObj?.optObj("capa")?.optInt("height", 1920) ?: 1920

                mediaItems.add(
                    MediaItem(
                        id = "${noteId}_xhs_vid_0",
                        index = 0,
                        kind = MediaKind.VIDEO,
                        badge = QualityBadge.VIDEO_HD,
                        originUrl = videoMasterUrl.replace("http://", "https://"),
                        previewUrl = firstCover.replace("http://", "https://"),
                        liveVideoUrl = videoMasterUrl.replace("http://", "https://"),
                        width = w,
                        height = h,
                        format = "MP4",
                        colorSpace = "BT.709",
                        themeTitle = title.ifBlank { desc },
                        visualSeed = 0
                    )
                )
            } else if (imageList != null && imageList.size() > 0) {
                for (i in 0 until imageList.size()) {
                    val imgObj = imageList.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val previewUrl = extractXiaohongshuPreviewUrl(imgObj) ?: continue
                    val traceId = imgObj.optStr("traceId")
                        .ifBlank { imgObj.optStr("fileId") }
                        .ifBlank { imgObj.optObj("image")?.optStr("traceId") ?: "" }
                        .ifBlank { imgObj.optObj("image")?.optStr("fileId") ?: "" }

                    val losslessUrl = if (traceId.isNotBlank()) {
                        val base = preferences.xhsCdnHostRule.let { if (it.endsWith("/")) it else "$it/" }
                        "${base}${traceId}?imageView2/2/w/0/format/png"
                    } else {
                        rewriteToLosslessOriginUrl(previewUrl, PlatformType.XIAOHONGSHU, preferences.xhsCdnHostRule)
                    }

                    val w = imgObj.optInt("width", 1440).coerceAtLeast(720)
                    val h = imgObj.optInt("height", 1920).coerceAtLeast(960)
                    val is4K = w >= 2560 || h >= 2560

                    mediaItems.add(
                        MediaItem(
                            id = "${noteId}_xhs_img_$i",
                            index = i,
                            kind = MediaKind.IMAGE,
                            badge = if (is4K) QualityBadge.FOUR_K else QualityBadge.RAW,
                            originUrl = losslessUrl,
                            previewUrl = previewUrl,
                            liveVideoUrl = null,
                            width = w,
                            height = h,
                            format = "PNG",
                            colorSpace = "Display P3",
                            themeTitle = title.ifBlank { desc },
                            visualSeed = i
                        )
                    )
                }
            }

            if (mediaItems.isEmpty()) return null

            val xhsPostTypeLabel = when {
                mediaItems.any { it.isVideo } -> "小红书·视频"
                else -> "小红书·图集"
            }

            ParsedPost(
                id = "post_$noteId",
                sourceShareText = rawInput,
                extractedUrl = cleanUrl,
                noteId = noteId,
                platform = PlatformType.XIAOHONGSHU,
                postTypeLabel = xhsPostTypeLabel,
                authorName = authorName,
                authorHandle = "@${noteId.take(8)}",
                authorAvatarUrl = authorAvatar,
                coverUrl = mediaItems.first().previewUrl,
                title = title,
                content = desc,
                tags = tags,
                mediaItems = mediaItems,
                bgmAudio = null,
                parseEngineSource = "小红书原画直连",
                isDuplicateDownload = previousHistory?.downloadedOnce == true,
                previousDownloadTimeMillis = previousHistory?.parsedAtMillis
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseKuaishouHtmlState(
        rawInput: String,
        cleanUrl: String,
        finalUrl: String,
        html: String,
        workId: String,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        val initStateStr = extractBalancedJsonObject(html, "window.INIT_STATE") ?: return null
        return parseKuaishouStateJson(
            rawInput = rawInput,
            cleanUrl = cleanUrl,
            finalUrl = finalUrl,
            stateJsonStr = initStateStr,
            workId = workId,
            previousHistory = previousHistory
        )
    }

    private fun parseKuaishouStateJson(
        rawInput: String,
        cleanUrl: String,
        finalUrl: String,
        stateJsonStr: String,
        workId: String,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        return try {
            val root = JsonParser.parseString(stateJsonStr).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            var targetPhotoObj: JsonObject? = null
            var targetSingleObj: JsonObject? = null
            var targetAtlasObj: JsonObject? = null

            fun searchForPhoto(obj: JsonObject) {
                if (targetPhotoObj != null) return
                if (obj.has("photo") && obj.get("photo").isJsonObject) {
                    targetPhotoObj = obj.getAsJsonObject("photo")
                    targetSingleObj = obj.optObj("single")
                    targetAtlasObj = obj.optObj("atlas")
                    return
                }
                for (entry in obj.entrySet()) {
                    val child = entry.value
                    if (child.isJsonObject) {
                        searchForPhoto(child.asJsonObject)
                    }
                }
            }
            searchForPhoto(root)

            val photoObj = targetPhotoObj ?: return null
            val photoId = photoObj.optStr("photoId").ifBlank { workId }
            val caption = photoObj.optStr("caption").trim()
            val authorName = photoObj.optStr("userName").takeIf { it.isNotBlank() } ?: "快手创作者"
            val authorHandle = photoObj.optStr("userId").takeIf { it.isNotBlank() } ?: photoId.take(8)
            val authorAvatar = photoObj.optStr("headUrl").replace("http://", "https://")
            val width = photoObj.optInt("width", 1080).coerceAtLeast(720)
            val height = photoObj.optInt("height", 1920).coerceAtLeast(960)

            val mediaItems = mutableListOf<MediaItem>()

            // 1. 检查是否为视频（mainMvUrls 或 videoUrl）
            val mainMvArr = photoObj.optArr("mainMvUrls")
            val rawVideoUrl = extractFirstUrlFromArray(mainMvArr) ?: photoObj.optStr("videoUrl").takeIf { it.isNotBlank() }
            if (!rawVideoUrl.isNullOrBlank()) {
                val losslessVid = rawVideoUrl.replace("http://", "https://")
                val coverUrl = extractFirstUrlFromArray(photoObj.optArr("coverUrls"))
                    ?: extractFirstUrlFromArray(photoObj.optArr("webpCoverUrls"))
                    ?: losslessVid
                mediaItems.add(
                    MediaItem(
                        id = "${photoId}_ks_vid_0",
                        index = 0,
                        kind = MediaKind.VIDEO,
                        badge = QualityBadge.VIDEO_HD,
                        originUrl = losslessVid,
                        previewUrl = coverUrl,
                        liveVideoUrl = losslessVid,
                        width = width,
                        height = height,
                        format = "MP4",
                        colorSpace = "BT.709",
                        themeTitle = caption,
                        visualSeed = 0
                    )
                )
            } else {
                // 2. 检查多图图集 (atlas)
                val atlasObj = targetAtlasObj ?: photoObj.optObj("atlas")
                val atlasList = atlasObj?.optArr("list")
                val cdnList = atlasObj?.optArr("cdnList") ?: targetSingleObj?.optArr("cdnList")
                val cdnHost = cdnList?.takeIf { it.size() > 0 }
                    ?.get(0)?.takeIf { it.isJsonObject }?.asJsonObject?.optStr("cdn")

                if (atlasList != null && atlasList.size() > 0) {
                    for (i in 0 until atlasList.size()) {
                        val el = atlasList.get(i)
                        val path = if (el.isJsonPrimitive) el.asString else continue
                        val fullUrl = if (path.startsWith("http")) {
                            path.replace("http://", "https://")
                        } else if (!cdnHost.isNullOrBlank()) {
                            "https://$cdnHost/${path.trimStart('/')}"
                        } else {
                            "https://p1.a.yximgs.com/${path.trimStart('/')}"
                        }
                        val originUrl = rewriteToLosslessOriginUrl(fullUrl, PlatformType.KUAISHOU)
                        val is4K = width >= 2160 || height >= 2160
                        mediaItems.add(
                            MediaItem(
                                id = "${photoId}_ks_img_$i",
                                index = i,
                                kind = MediaKind.IMAGE,
                                badge = if (is4K) QualityBadge.FOUR_K else QualityBadge.RAW,
                                originUrl = originUrl,
                                previewUrl = fullUrl,
                                width = width,
                                height = height,
                                format = "JPG",
                                colorSpace = "Display P3",
                                themeTitle = caption,
                                visualSeed = i
                            )
                        )
                    }
                } else {
                    // 3. 单图作品 (singlePicture 原图直出)
                    val covers = photoObj.optArr("coverUrls") ?: photoObj.optArr("webpCoverUrls")
                    val coverCandidate = extractFirstUrlFromArray(covers)
                    if (!coverCandidate.isNullOrBlank()) {
                        val originUrl = rewriteToLosslessOriginUrl(coverCandidate, PlatformType.KUAISHOU)
                        val is4K = width >= 2160 || height >= 2160
                        mediaItems.add(
                            MediaItem(
                                id = "${photoId}_ks_img_0",
                                index = 0,
                                kind = MediaKind.IMAGE,
                                badge = if (is4K) QualityBadge.FOUR_K else QualityBadge.RAW,
                                originUrl = originUrl,
                                previewUrl = coverCandidate,
                                width = width,
                                height = height,
                                format = "JPG",
                                colorSpace = "Display P3",
                                themeTitle = caption,
                                visualSeed = 0
                            )
                        )
                    }
                }
            }

            if (mediaItems.isEmpty()) return null

            // 提取背景原声音乐 BGM
            val singleObj = targetSingleObj
            val musicPath = singleObj?.optStr("music")?.takeIf { it.isNotBlank() }
                ?: photoObj.optStr("music").takeIf { it.isNotBlank() }
            val musicCdn = singleObj?.optArr("musicCdnList")?.takeIf { it.size() > 0 }
                ?.get(0)?.takeIf { it.isJsonObject }?.asJsonObject?.optStr("cdn")

            val bgmAudio = if (!musicPath.isNullOrBlank()) {
                val fullMusicUrl = if (musicPath.startsWith("http")) {
                    musicPath.replace("http://", "https://")
                } else if (!musicCdn.isNullOrBlank()) {
                    "https://$musicCdn/${musicPath.trimStart('/')}"
                } else null

                fullMusicUrl?.let {
                    AudioTrackInfo(
                        id = "${photoId}_ks_bgm",
                        title = "快手原声音乐",
                        author = authorName,
                        url = it,
                        format = if (it.contains(".m4a")) "M4A" else "MP3"
                    )
                }
            } else null

            val tags = extractHashtags(caption, null)

            ParsedPost(
                id = "post_$photoId",
                sourceShareText = rawInput,
                extractedUrl = cleanUrl,
                noteId = photoId,
                platform = PlatformType.KUAISHOU,
                postTypeLabel = if (mediaItems.any { it.isVideo }) "快手·视频" else "快手·图集",
                authorName = authorName,
                authorHandle = "@$authorHandle",
                authorAvatarUrl = authorAvatar,
                coverUrl = mediaItems.first().previewUrl,
                title = caption.take(40).ifBlank { "快手作品分享" },
                content = caption,
                tags = tags,
                mediaItems = mediaItems,
                bgmAudio = bgmAudio,
                parseEngineSource = "快手原画直连",
                isDuplicateDownload = previousHistory?.downloadedOnce == true,
                previousDownloadTimeMillis = previousHistory?.parsedAtMillis
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseKuaishouRegexSniff(
        rawInput: String,
        cleanUrl: String,
        finalUrl: String,
        html: String,
        workId: String,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        if (html.isBlank()) return null
        return try {
            val captionPattern = Pattern.compile(""""caption"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            val captionMatcher = captionPattern.matcher(html)
            val caption = if (captionMatcher.find()) decodeJsonString(captionMatcher.group(1) ?: "") else ""

            val userPattern = Pattern.compile(""""userName"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            val userMatcher = userPattern.matcher(html)
            val userName = if (userMatcher.find()) decodeJsonString(userMatcher.group(1) ?: "") else "快手创作者"

            val headPattern = Pattern.compile(""""headUrl"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            val headMatcher = headPattern.matcher(html)
            val headUrl = if (headMatcher.find()) decodeJsonString(headMatcher.group(1) ?: "").replace("http://", "https://") else ""

            val wPattern = Pattern.compile(""""width"\s*:\s*(\d+)""").matcher(html)
            val width = if (wPattern.find()) wPattern.group(1)?.toIntOrNull() ?: 1080 else 1080
            val hPattern = Pattern.compile(""""height"\s*:\s*(\d+)""").matcher(html)
            val height = if (hPattern.find()) hPattern.group(1)?.toIntOrNull() ?: 1920 else 1920

            val mediaItems = mutableListOf<MediaItem>()

            val mvPattern = Pattern.compile(""""mainMvUrls"\s*:\s*\[\s*\{.*?"url"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            val mvMatcher = mvPattern.matcher(html)
            if (mvMatcher.find()) {
                val vidUrl = decodeJsonString(mvMatcher.group(1) ?: "").replace("http://", "https://")
                mediaItems.add(
                    MediaItem(
                        id = "${workId}_ks_vid_0",
                        index = 0,
                        kind = MediaKind.VIDEO,
                        badge = QualityBadge.VIDEO_HD,
                        originUrl = vidUrl,
                        previewUrl = vidUrl,
                        liveVideoUrl = vidUrl,
                        width = width,
                        height = height,
                        format = "MP4",
                        colorSpace = "BT.709",
                        themeTitle = caption,
                        visualSeed = 0
                    )
                )
            } else {
                val imgPattern = Pattern.compile("""https?:\\/\\/[^"'\s]+\.(?:yximgs|kwimgs|kwaicdn)\.com\\/[^"'\s]+""")
                val imgMatcher = imgPattern.matcher(html)
                val foundImages = linkedSetOf<String>()
                while (imgMatcher.find()) {
                    val rawU = decodeJsonString(imgMatcher.group(0) ?: "").replace("http://", "https://")
                    if (!rawU.contains("avatar") && !rawU.contains("head") && !rawU.contains("icon") && !rawU.endsWith(".js") && !rawU.endsWith(".css")) {
                        foundImages.add(rawU)
                    }
                }
                foundImages.take(20).forEachIndexed { idx, u ->
                    val lossless = rewriteToLosslessOriginUrl(u, PlatformType.KUAISHOU)
                    val is4K = width >= 2160 || height >= 2160
                    mediaItems.add(
                        MediaItem(
                            id = "${workId}_ks_img_$idx",
                            index = idx,
                            kind = MediaKind.IMAGE,
                            badge = if (is4K) QualityBadge.FOUR_K else QualityBadge.RAW,
                            originUrl = lossless,
                            previewUrl = u,
                            width = width,
                            height = height,
                            format = "JPG",
                            colorSpace = "Display P3",
                            themeTitle = caption,
                            visualSeed = idx
                        )
                    )
                }
            }

            if (mediaItems.isEmpty()) return null

            return ParsedPost(
                id = "post_$workId",
                sourceShareText = rawInput,
                extractedUrl = cleanUrl,
                noteId = workId,
                platform = PlatformType.KUAISHOU,
                postTypeLabel = if (mediaItems.any { it.isVideo }) "快手·视频" else "快手·图集",
                authorName = userName,
                authorHandle = "@${workId.take(8)}",
                authorAvatarUrl = headUrl,
                coverUrl = mediaItems.first().previewUrl,
                title = caption.take(40).ifBlank { "快手作品分享" },
                content = caption,
                tags = extractHashtags(caption, null),
                mediaItems = mediaItems,
                bgmAudio = null,
                parseEngineSource = "快手正则兜底",
                isDuplicateDownload = previousHistory?.downloadedOnce == true,
                previousDownloadTimeMillis = previousHistory?.parsedAtMillis
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeJsonString(input: String): String {
        return input
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
    }

    private fun parseFromWebSniffResult(
        rawInput: String,
        cleanUrl: String,
        platform: PlatformType,
        sniff: HiddenWebViewExtractor.WebSniffResult,
        preferences: UserPreferencesState,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        val workId = ShareLinkExtractor.extractWorkId(sniff.finalUrl.ifBlank { cleanUrl }, platform)

        if (!sniff.routerDataJson.isNullOrBlank()) {
            parseDouyinRouterJson(
                rawInput = rawInput,
                cleanUrl = cleanUrl,
                routerJsonStr = sniff.routerDataJson,
                fallbackWorkId = workId,
                previousHistory = previousHistory
            )?.let { return it }
        }
        if (!sniff.apiResponseJson.isNullOrBlank()) {
            parseDouyinRouterJson(
                rawInput = rawInput,
                cleanUrl = cleanUrl,
                routerJsonStr = sniff.apiResponseJson,
                fallbackWorkId = workId,
                previousHistory = previousHistory
            )?.let { return it }
        }
        if (!sniff.renderDataEncoded.isNullOrBlank()) {
            val decoded = runCatching { URLDecoder.decode(sniff.renderDataEncoded, "UTF-8") }.getOrNull()
            if (!decoded.isNullOrBlank()) {
                parseDouyinRouterJson(
                    rawInput = rawInput,
                    cleanUrl = cleanUrl,
                    routerJsonStr = decoded,
                    fallbackWorkId = workId,
                    previousHistory = previousHistory
                )?.let { return it }
            }
        }

        if (!sniff.initialStateJson.isNullOrBlank()) {
            parseXiaohongshuStateJson(
                rawInput = rawInput,
                cleanUrl = cleanUrl,
                stateJsonStr = sniff.initialStateJson,
                html = sniff.rawHtml,
                fallbackWorkId = workId,
                preferences = preferences,
                previousHistory = previousHistory
            )?.let { return it }
        }

        if (sniff.stateScriptText.isNotBlank()) {
            parseDouyinHtmlState(
                rawInput = rawInput,
                cleanUrl = cleanUrl,
                finalUrl = sniff.finalUrl,
                html = sniff.stateScriptText,
                workId = workId,
                previousHistory = previousHistory
            )?.let { return it }

            parseXiaohongshuHtmlState(
                rawInput = rawInput,
                cleanUrl = cleanUrl,
                finalUrl = sniff.finalUrl,
                html = sniff.stateScriptText,
                workId = workId,
                preferences = preferences,
                previousHistory = previousHistory
            )?.let { return it }
        }

        if (platform == PlatformType.KUAISHOU) {
            val stateCandidate = sniff.initialStateJson ?: sniff.stateScriptText
            if (stateCandidate.isNotBlank()) {
                parseKuaishouStateJson(
                    rawInput = rawInput,
                    cleanUrl = cleanUrl,
                    finalUrl = sniff.finalUrl,
                    stateJsonStr = stateCandidate,
                    workId = workId,
                    previousHistory = previousHistory
                )?.let { return it }
            }
            parseKuaishouHtmlState(
                rawInput = rawInput,
                cleanUrl = cleanUrl,
                finalUrl = sniff.finalUrl,
                html = sniff.rawHtml,
                workId = workId,
                previousHistory = previousHistory
            )?.let { return it }
            parseKuaishouRegexSniff(
                rawInput = rawInput,
                cleanUrl = cleanUrl,
                finalUrl = sniff.finalUrl,
                html = sniff.rawHtml,
                workId = workId,
                previousHistory = previousHistory
            )?.let { return it }
        }

        val mediaItems = mutableListOf<MediaItem>()

        val videoCandidates = linkedSetOf<String>().apply {
            if (sniff.ogVideo.startsWith("http")) add(sniff.ogVideo)
            sniff.domVideos.filter { it.startsWith("http") }.forEach { add(it) }
            sniff.sniffedMediaUrls.filter {
                (it.contains("aweme/v1/play") || it.contains("douyinvod") || it.contains("bytevcloud") ||
                    (it.contains("snssdk.com") && it.contains("play")) || it.contains("sns-video") ||
                    it.contains("xhscdn.com/stream") || it.endsWith(".mp4")) &&
                    !it.contains("/music/") && !it.endsWith(".mp3") && !it.endsWith(".m4a")
            }.forEach { add(it) }
        }

        val imageCandidates = linkedSetOf<String>().apply {
            sniff.ogImages.filter { it.startsWith("http") }.forEach { add(it) }
            sniff.domImages.filter { it.startsWith("http") }.forEach { add(it) }
            sniff.sniffedMediaUrls.filter {
                (it.contains("xhscdn.com") || it.contains("douyinpic.com") || it.contains("izuiyou.com") ||
                    it.contains("yximgs.com") || it.contains("kwimgs.com") || it.contains("kwaicdn.com")) &&
                    !it.endsWith(".mp4")
            }.forEach { add(it) }
        }

        val firstCover = imageCandidates.firstOrNull()?.replace("http://", "https://") ?: ""
        val targetImages = if (sniff.ogImages.isNotEmpty()) sniff.ogImages else imageCandidates.toList()
        val hasMultipleImages = targetImages.size > 1
        val hasVideo = videoCandidates.isNotEmpty()

        if (hasMultipleImages) {
            targetImages.distinct().take(35).forEachIndexed { idx, rawImgUrl ->
                val preview = rawImgUrl.replace("http://", "https://")
                val lossless = rewriteToLosslessOriginUrl(preview, platform, preferences.xhsCdnHostRule)
                mediaItems.add(
                    MediaItem(
                        id = "${workId}_img_$idx",
                        index = idx,
                        kind = MediaKind.IMAGE,
                        badge = QualityBadge.RAW,
                        originUrl = lossless,
                        previewUrl = preview,
                        width = 1440,
                        height = 1920,
                        format = if (platform == PlatformType.XIAOHONGSHU) "PNG" else "JPG",
                        colorSpace = "Display P3",
                        themeTitle = sniff.ogTitle.ifBlank { sniff.title },
                        visualSeed = idx
                    )
                )
            }
        } else if (hasVideo) {
            val rawVid = videoCandidates.first()
            val losslessVid = rewriteToLosslessOriginUrl(rawVid, platform, preferences.xhsCdnHostRule)
            mediaItems.add(
                MediaItem(
                    id = "${workId}_vid_0",
                    index = 0,
                    kind = MediaKind.VIDEO,
                    badge = QualityBadge.VIDEO_HD,
                    originUrl = losslessVid,
                    previewUrl = firstCover.ifBlank { losslessVid },
                    liveVideoUrl = null,
                    width = 1080,
                    height = 1920,
                    format = "MP4",
                    colorSpace = "BT.709",
                    themeTitle = sniff.ogTitle.ifBlank { sniff.title },
                    visualSeed = 0
                )
            )
        } else {
            targetImages.distinct().take(35).forEachIndexed { idx, rawImgUrl ->
                val preview = rawImgUrl.replace("http://", "https://")
                val lossless = rewriteToLosslessOriginUrl(preview, platform, preferences.xhsCdnHostRule)
                mediaItems.add(
                    MediaItem(
                        id = "${workId}_img_$idx",
                        index = idx,
                        kind = MediaKind.IMAGE,
                        badge = QualityBadge.RAW,
                        originUrl = lossless,
                        previewUrl = preview,
                        width = 1440,
                        height = 1920,
                        format = if (platform == PlatformType.XIAOHONGSHU) "PNG" else "JPG",
                        colorSpace = "Display P3",
                        themeTitle = sniff.ogTitle.ifBlank { sniff.title },
                        visualSeed = idx
                    )
                )
            }
        }

        if (mediaItems.isEmpty()) return null

        val cleanTitle = sniff.ogTitle.ifBlank { sniff.title }
            .replace(" - 小红书", "")
            .replace(" - 抖音", "")
            .trim()
        val cleanDesc = sniff.ogDesc.ifBlank { cleanTitle }

        val postTypeLabel = when {
            mediaItems.any { it.isVideo } -> "${platform.displayName}·视频"
            else -> "${platform.displayName}·图集"
        }

        return ParsedPost(
            id = "post_$workId",
            sourceShareText = rawInput,
            extractedUrl = cleanUrl,
            noteId = workId,
            platform = platform,
            postTypeLabel = postTypeLabel,
            authorName = "${platform.displayName}创作者",
            authorHandle = "@${workId.take(8)}",
            authorAvatarUrl = "",
            coverUrl = mediaItems.first().previewUrl,
            title = cleanTitle,
            content = cleanDesc,
            tags = extractHashtags("$cleanTitle $cleanDesc", null),
            mediaItems = mediaItems,
            bgmAudio = null,
            parseEngineSource = "动态引擎直出",
            isDuplicateDownload = previousHistory?.downloadedOnce == true,
            previousDownloadTimeMillis = previousHistory?.parsedAtMillis
        )
    }

    private fun parseGenericHtmlMetaAndDom(
        rawInput: String,
        cleanUrl: String,
        html: String,
        platform: PlatformType,
        workId: String,
        preferences: UserPreferencesState,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        if (html.isBlank()) return null

        val ogImages = extractMetaTags(html, "og:image")
        val ogVideos = extractMetaTags(html, "og:video") + extractMetaTags(html, "og:video:url")
        val ogTitle = extractMetaTags(html, "og:title").firstOrNull()
            ?: extractHtmlTitle(html)
        val ogDesc = extractMetaTags(html, "description").firstOrNull()
            ?: extractMetaTags(html, "og:description").firstOrNull()
            ?: ogTitle

        val mediaItems = mutableListOf<MediaItem>()

        if (ogVideos.isNotEmpty()) {
            val vidUrl = ogVideos.first().replace("http://", "https://")
            val coverUrl = ogImages.firstOrNull()?.replace("http://", "https://") ?: vidUrl
            mediaItems.add(
                MediaItem(
                    id = "${workId}_vid_0",
                    index = 0,
                    kind = MediaKind.VIDEO,
                    badge = QualityBadge.VIDEO_HD,
                    originUrl = rewriteToLosslessOriginUrl(vidUrl, platform, preferences.xhsCdnHostRule),
                    previewUrl = coverUrl,
                    liveVideoUrl = vidUrl,
                    width = 1080,
                    height = 1920,
                    format = "MP4",
                    colorSpace = "BT.709",
                    themeTitle = ogTitle,
                    visualSeed = 0
                )
            )
        } else if (ogImages.isNotEmpty()) {
            ogImages.distinct().take(35).forEachIndexed { index, rawImg ->
                val preview = rawImg.replace("http://", "https://")
                val lossless = rewriteToLosslessOriginUrl(preview, platform, preferences.xhsCdnHostRule)
                mediaItems.add(
                    MediaItem(
                        id = "${workId}_img_$index",
                        index = index,
                        kind = MediaKind.IMAGE,
                        badge = QualityBadge.RAW,
                        originUrl = lossless,
                        previewUrl = preview,
                        width = 1440,
                        height = 1920,
                        format = if (platform == PlatformType.XIAOHONGSHU) "PNG" else "JPG",
                        colorSpace = "Display P3",
                        themeTitle = ogTitle,
                        visualSeed = index
                    )
                )
            }
        }

        if (mediaItems.isEmpty()) return null

        val cleanTitle = ogTitle
            .replace(" - 小红书", "")
            .replace(" - 抖音", "")
            .trim()

        return ParsedPost(
            id = "post_$workId",
            sourceShareText = rawInput,
            extractedUrl = cleanUrl,
            noteId = workId,
            platform = platform,
            postTypeLabel = if (mediaItems.any { it.isVideo }) "${platform.displayName}·视频" else "${platform.displayName}·图集",
            authorName = "${platform.displayName}创作者",
            authorHandle = "@${workId.take(8)}",
            authorAvatarUrl = "",
            coverUrl = mediaItems.first().previewUrl,
            title = cleanTitle,
            content = ogDesc,
            tags = extractHashtags("$cleanTitle $ogDesc", null),
            mediaItems = mediaItems,
            bgmAudio = null,
            parseEngineSource = "原画直连",
            isDuplicateDownload = previousHistory?.downloadedOnce == true,
            previousDownloadTimeMillis = previousHistory?.parsedAtMillis
        )
    }

    private fun fetchPageFollowingRedirects(
        startUrl: String,
        platform: PlatformType,
        customCookie: String?
    ): HttpPageResponse? {
        var currentUrl = startUrl
        var redirects = 0

        while (redirects < 8) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(currentUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", MOBILE_UA)
                    setRequestProperty(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    setRequestProperty("Referer", DynamicMediaRenderer.refererForPlatform(platform, currentUrl))
                    if (!customCookie.isNullOrBlank()) {
                        setRequestProperty("Cookie", customCookie)
                    }
                }

                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val loc = conn.getHeaderField("Location") ?: break
                    currentUrl = URL(url, loc).toString()
                    redirects++
                    continue
                }

                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val html = stream?.let { s ->
                    BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
                } ?: ""

                return HttpPageResponse(
                    finalUrl = currentUrl,
                    html = html,
                    statusCode = code
                )
            } catch (_: Exception) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    fun extractBalancedJsonObject(html: String, marker: String): String? {
        val markerIdx = html.indexOf(marker)
        if (markerIdx == -1) return null

        val startBraceIdx = html.indexOf('{', markerIdx + marker.length)
        if (startBraceIdx == -1) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in startBraceIdx until html.length) {
            val ch = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                if (inString) escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '{') {
                    depth++
                } else if (ch == '}') {
                    depth--
                    if (depth == 0) {
                        return html.substring(startBraceIdx, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun extractRenderDataJson(html: String): String? {
        val matcher = Pattern.compile(
            """<script[^>]*id=["']RENDER_DATA["'][^>]*>(.*?)</script>""",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (matcher.find()) {
            val encoded = matcher.group(1)?.trim() ?: return null
            return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
        }
        return null
    }

    private fun findDouyinAwemeItemObject(node: JsonElement?): JsonObject? {
        if (node == null || node.isJsonNull) return null
        if (node.isJsonObject) {
            val obj = node.asJsonObject
            if (obj.has("item_list")) {
                val arr = obj.optArr("item_list")
                if (arr != null && arr.size() > 0 && arr.get(0).isJsonObject) {
                    return arr.get(0).asJsonObject
                }
            }
            if (obj.has("aweme_detail") && obj.optObj("aweme_detail") != null) {
                return obj.optObj("aweme_detail")
            }
            if (obj.has("aweme_id") && (obj.has("video") || obj.has("images") || obj.has("image_post_info") || obj.has("image_list"))) {
                return obj
            }
            for ((_, v) in obj.entrySet()) {
                findDouyinAwemeItemObject(v)?.let { return it }
            }
        } else if (node.isJsonArray) {
            val arr = node.asJsonArray
            for (i in 0 until arr.size()) {
                findDouyinAwemeItemObject(arr.get(i))?.let { return it }
            }
        }
        return null
    }

    private fun findXiaohongshuNoteObject(node: JsonElement?): JsonObject? {
        if (node == null || node.isJsonNull) return null
        if (node.isJsonObject) {
            val obj = node.asJsonObject
            obj.optObj("noteData")
                ?.optObj("data")
                ?.optObj("noteData")
                ?.let { if (it.has("imageList") || it.has("imagesList") || it.has("images") || it.has("video")) return it }

            obj.optObj("noteDetailMap")?.let { mapObj ->
                for ((_, detailEl) in mapObj.entrySet()) {
                    val detail = detailEl.takeIf { it.isJsonObject }?.asJsonObject?.optObj("note")
                        ?: detailEl.takeIf { it.isJsonObject }?.asJsonObject?.optObj("noteDetail")
                        ?: detailEl.takeIf { it.isJsonObject }?.asJsonObject
                    if (detail != null && (detail.has("imageList") || detail.has("imagesList") || detail.has("images") || detail.has("video"))) {
                        return detail
                    }
                }
            }

            if ((obj.has("imageList") || obj.has("imagesList") || obj.has("images") || obj.has("video")) &&
                (obj.has("noteId") || obj.has("id") || obj.has("title") || obj.has("desc") || obj.has("displayTitle"))
            ) {
                return obj
            }

            for ((_, v) in obj.entrySet()) {
                findXiaohongshuNoteObject(v)?.let { return it }
            }
        } else if (node.isJsonArray) {
            val arr = node.asJsonArray
            for (i in 0 until arr.size()) {
                findXiaohongshuNoteObject(arr.get(i))?.let { return it }
            }
        }
        return null
    }

    private fun extractXiaohongshuPreviewUrl(imgObj: JsonObject): String? {
        val candidates = mutableListOf<String>()
        imgObj.optStr("urlDefault").takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        imgObj.optStr("url").takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        imgObj.optStr("urlPre").takeIf { it.isNotBlank() }?.let { candidates.add(it) }

        imgObj.optArr("infoList")?.let { arr ->
            for (i in arr.size() - 1 downTo 0) {
                arr.get(i).takeIf { it.isJsonObject }?.asJsonObject
                    ?.optStr("url")?.takeIf { it.isNotBlank() }
                    ?.let { candidates.add(it) }
            }
        }

        val chosen = candidates.firstOrNull() ?: return null
        return chosen.replace("\\u002F", "/").replace("http://", "https://")
    }

    private fun extractXiaohongshuVideoUrl(videoObj: JsonObject?): String? {
        if (videoObj == null) return null
        val stream = videoObj.optObj("media")?.optObj("stream")
        for (codec in listOf("h264", "h265", "av1")) {
            val arr = stream?.optArr(codec)
            if (arr != null && arr.size() > 0) {
                val master = arr.get(0).takeIf { it.isJsonObject }?.asJsonObject?.optStr("masterUrl")
                if (!master.isNullOrBlank()) {
                    return master.replace("\\u002F", "/").replace("http://", "https://")
                }
            }
        }
        val originKey = videoObj.optObj("consumer")?.optStr("originVideoKey")
        if (!originKey.isNullOrBlank()) {
            return "https://sns-video-bd.xhscdn.com/$originKey"
        }
        return null
    }

    private fun extractDouyinAwemeId(url: String, html: String): String? {
        val patterns = listOf(
            Regex("""/(?:video|note|slides)/(\d{15,22})"""),
            Regex("""(?:modal_id|aweme_id|item_ids)=(\d{15,22})"""),
            Regex(""""aweme_id"\s*:\s*"(\d{15,22})"""")
        )
        for (regex in patterns) {
            regex.find(url)?.groupValues?.getOrNull(1)?.let { return it }
            regex.find(html)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun selectBestDouyinImageUrl(arr: JsonArray?): String? {
        if (arr == null || arr.size() == 0) return null
        val list = mutableListOf<String>()
        for (i in 0 until arr.size()) {
            val el = arr.get(i)
            val u = if (el.isJsonPrimitive) {
                el.asString.replace("\\u002F", "/").replace("http://", "https://")
            } else ""
            if (u.startsWith("http")) {
                list.add(u)
            }
        }
        return list.firstOrNull { !it.contains("watermark") && (it.contains(".jpeg") || it.contains(".jpg") || it.contains(".png")) }
            ?: list.lastOrNull()
    }

    private fun extractFirstUrlFromHolder(holder: JsonObject?): String? {
        return extractFirstUrlFromArray(holder?.optArr("url_list"))
    }

    private fun extractFirstUrlFromArray(arr: JsonArray?): String? {
        if (arr == null || arr.size() == 0) return null
        for (i in 0 until arr.size()) {
            val el = arr.get(i)
            val u = when {
                el.isJsonPrimitive -> el.asString.replace("\\u002F", "/").replace("http://", "https://")
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    (obj.optStr("url").takeIf { it.isNotBlank() }
                        ?: obj.optStr("play_url").takeIf { it.isNotBlank() }
                        ?: obj.optStr("src").takeIf { it.isNotBlank() }
                        ?: "")
                        .replace("\\u002F", "/")
                        .replace("http://", "https://")
                }
                else -> ""
            }
            if (u.startsWith("http")) {
                return u
            }
        }
        return null
    }

    private fun extractMetaTags(html: String, propertyName: String): List<String> {
        val results = mutableListOf<String>()
        val p1 = Pattern.compile(
            """<meta[^>]+(?:property|name)=["']${Pattern.quote(propertyName)}["'][^>]+content=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        while (p1.find()) {
            p1.group(1)?.trim()?.takeIf { it.isNotBlank() }?.let { results.add(decodeHtmlEntities(it)) }
        }

        val p2 = Pattern.compile(
            """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']${Pattern.quote(propertyName)}["']""",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        while (p2.find()) {
            p2.group(1)?.trim()?.takeIf { it.isNotBlank() }?.let { results.add(decodeHtmlEntities(it)) }
        }
        return results.distinct()
    }

    private fun extractHtmlTitle(html: String): String {
        val m = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE).matcher(html)
        return if (m.find()) decodeHtmlEntities(m.group(1)?.trim() ?: "") else ""
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun extractHashtags(text: String, textExtraArr: JsonArray?): List<String> {
        val tags = linkedSetOf<String>()
        textExtraArr?.let { arr ->
            for (i in 0 until arr.size()) {
                val name = arr.get(i).takeIf { it.isJsonObject }?.asJsonObject?.optStr("hashtag_name")?.trim()
                if (!name.isNullOrBlank()) {
                    tags.add("#$name")
                }
            }
        }
        val matcher = Pattern.compile("""#([^\s#，。！？、“”‘’【】（）]{1,24})""").matcher(text)
        while (matcher.find()) {
            matcher.group(1)?.let { tags.add("#$it") }
        }
        return tags.toList()
    }

    private fun JsonObject.optStr(key: String, default: String = ""): String {
        val el = get(key) ?: return default
        return if (el.isJsonPrimitive) el.asString else default
    }

    private fun JsonObject.optInt(key: String, default: Int = 0): Int {
        val el = get(key) ?: return default
        return if (el.isJsonPrimitive) runCatching { el.asInt }.getOrDefault(default) else default
    }

    private fun JsonObject.optBool(key: String, default: Boolean = false): Boolean {
        val el = get(key) ?: return default
        return if (el.isJsonPrimitive) runCatching { el.asBoolean }.getOrDefault(default) else default
    }

    private fun JsonObject.optObj(key: String): JsonObject? {
        val el = get(key) ?: return null
        return if (el.isJsonObject) el.asJsonObject else null
    }

    private fun JsonObject.optArr(key: String): JsonArray? {
        val el = get(key) ?: return null
        return if (el.isJsonArray) el.asJsonArray else null
    }

    fun toHistoryEntity(post: ParsedPost, downloadedOnce: Boolean = false): ParseHistoryEntity {
        return ParseHistoryEntity(
            postId = post.id,
            sourceShareText = post.sourceShareText,
            extractedUrl = post.extractedUrl,
            noteId = post.noteId,
            platform = post.platform.name,
            postTypeLabel = post.postTypeLabel,
            authorName = post.authorName,
            authorHandle = post.authorHandle,
            authorAvatarUrl = post.authorAvatarUrl,
            coverUrl = post.coverUrl,
            title = post.title,
            content = post.content,
            tagsJson = gson.toJson(post.tags),
            mediaItemsJson = gson.toJson(post.mediaItems),
            bgmAudioJson = post.bgmAudio?.let { gson.toJson(it) },
            mediaCount = post.mediaItems.size,
            downloadedOnce = downloadedOnce,
            parsedAtMillis = post.parsedAtMillis
        )
    }

    fun fromHistoryEntity(entity: ParseHistoryEntity): ParsedPost {
        val tagsType = object : TypeToken<List<String>>() {}.type
        val mediaType = object : TypeToken<List<MediaItem>>() {}.type

        val tags: List<String> = runCatching {
            gson.fromJson<List<String>>(entity.tagsJson, tagsType)
        }.getOrDefault(emptyList())

        val mediaItems: List<MediaItem> = runCatching {
            gson.fromJson<List<MediaItem>>(entity.mediaItemsJson, mediaType)
        }.getOrDefault(emptyList())

        val bgm: AudioTrackInfo? = entity.bgmAudioJson?.let {
            runCatching { gson.fromJson(it, AudioTrackInfo::class.java) }.getOrNull()
        }

        val platform = runCatching { PlatformType.valueOf(entity.platform) }
            .getOrDefault(PlatformType.XIAOHONGSHU)

        return ParsedPost(
            id = entity.postId,
            sourceShareText = entity.sourceShareText,
            extractedUrl = entity.extractedUrl,
            noteId = entity.noteId,
            platform = platform,
            postTypeLabel = entity.postTypeLabel,
            authorName = entity.authorName,
            authorHandle = entity.authorHandle,
            authorAvatarUrl = entity.authorAvatarUrl,
            coverUrl = entity.coverUrl,
            title = entity.title,
            content = entity.content,
            tags = tags,
            mediaItems = mediaItems,
            bgmAudio = bgm,
            parseEngineSource = "本地历史恢复",
            parsedAtMillis = entity.parsedAtMillis,
            isDuplicateDownload = entity.downloadedOnce,
            previousDownloadTimeMillis = entity.parsedAtMillis
        )
    }
}
