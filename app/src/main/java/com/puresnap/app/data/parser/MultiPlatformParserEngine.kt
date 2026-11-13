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
import com.puresnap.app.util.ShareLinkExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * 多平台无水印原图/实况/视频解析调度引擎：
 * - 第一层：直链原文件快速直出 + 国内外 14 大平台原生 JSON/API 状态树直连解析
 * - 第二层：后台无感 Chromium WebView 动态绕过 WAF/JS 挑战并提取全网状态树与网络流
 */
object MultiPlatformParserEngine {

    private val gson = Gson()

    sealed class ParseOutcome {
        data class Success(val post: ParsedPost) : ParseOutcome()
        data class Failure(
            val errorType: ParseErrorType,
            val extractedUrl: String? = null,
            val platform: PlatformType = PlatformType.UNKNOWN
        ) : ParseOutcome()
    }

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

        val platform = extraction.platform
        val customCookie = preferences.cookieForPlatform(platform)

        // 快速通道：直接复制的图片/视频直链，直接升级原图直出，免网页解析等待
        if (ShareLinkExtractor.isDirectMediaUrl(cleanUrl)) {
            buildDirectMediaPost(
                rawInput = rawInput,
                cleanUrl = cleanUrl,
                platform = platform,
                preferences = preferences,
                previousHistory = previousHistory
            )?.let { return@withContext ParseOutcome.Success(it) }
        }

        // 第一层：客户端直连 HTTP 重定向追踪 + 多平台结构化 API / HTML 状态树解析
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

        // 第二层：后台无感 Chromium WebView 动态执行 JS 绕过挑战并提取状态树与媒体资源
        val sniffResult = HiddenWebViewExtractor.sniffUrl(
            context = context,
            targetUrl = cleanUrl,
            customCookie = customCookie,
            timeoutMs = 8500L
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

        // 第三层（抖音移动端分享页降级兜底）：当 iesdouyin.com 返回空白/错误兜底页时，转用桌面端 note 页抓取无水印原图
        if (platform == PlatformType.DOUYIN) {
            val awemeId = extractDouyinAwemeId(
                sniffResult?.finalUrl?.ifBlank { cleanUrl } ?: cleanUrl,
                sniffResult?.rawHtml ?: ""
            )
            if (!awemeId.isNullOrBlank()) {
                val desktopSniff = HiddenWebViewExtractor.sniffUrl(
                    context = context,
                    targetUrl = "https://www.douyin.com/note/$awemeId",
                    customCookie = customCookie,
                    timeoutMs = 9500L,
                    useDesktopUa = true
                )
                if (desktopSniff != null) {
                    val desktopParsed = parseFromWebSniffResult(
                        rawInput = rawInput,
                        cleanUrl = cleanUrl,
                        platform = platform,
                        sniff = desktopSniff,
                        preferences = preferences,
                        previousHistory = previousHistory
                    )
                    if (desktopParsed != null && desktopParsed.mediaItems.isNotEmpty()) {
                        return@withContext ParseOutcome.Success(desktopParsed)
                    }
                }
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
        val platform = ShareLinkExtractor.detectPlatform(url, url)
        val workId = ShareLinkExtractor.extractWorkId(url, platform)

        when (platform) {
            PlatformType.DOUYIN -> parseDouyinHtmlState(
                rawInput = url,
                cleanUrl = url,
                finalUrl = url,
                html = html,
                workId = workId,
                previousHistory = null
            )?.let { return it }

            PlatformType.XIAOHONGSHU -> parseXiaohongshuHtmlState(
                rawInput = url,
                cleanUrl = url,
                finalUrl = url,
                html = html,
                workId = workId,
                preferences = preferences,
                previousHistory = null
            )?.let { return it }

            PlatformType.KUAISHOU -> {
                parseKuaishouHtmlState(url, url, url, html, workId, null)?.let { return it }
                parseKuaishouRegexSniff(url, url, url, html, workId, null)?.let { return it }
            }

            PlatformType.WEIBO -> {
                val json = extractBalancedJsonObject(html, "\"status\":")
                    ?: extractBalancedJsonObject(html, "var \$render_data =")
                if (json != null) {
                    DomesticExtPlatformParsers.parseWeiboStatusJson(url, url, url, json, workId)?.let { return it }
                }
            }

            PlatformType.BILIBILI -> {
                val dynJson = extractBalancedJsonObject(html, "window.__INITIAL_STATE__=")
                    ?: extractBalancedJsonObject(html, "__INITIAL_STATE__ =")
                if (dynJson != null) {
                    DomesticExtPlatformParsers.parseBilibiliDynamicJson(url, url, dynJson, workId)?.let { return it }
                }
            }

            PlatformType.ZUIYOU -> {
                val zyJson = extractBalancedJsonObject(html, "__INITIAL_STATE__")
                if (zyJson != null) {
                    DomesticExtPlatformParsers.parseZuiyouJson(url, url, zyJson, workId)?.let { return it }
                }
            }

            PlatformType.INSTAGRAM -> {
                InternationalPlatformParsers.parseInstagramHtmlOrJson(url, url, html, workId)?.let { return it }
            }

            PlatformType.TIKTOK -> {
                InternationalPlatformParsers.parseTikTokHtmlState(url, url, html)?.let { return it }
            }

            PlatformType.THREADS -> {
                InternationalPlatformParsers.parseThreadsHtml(url, url, html)?.let { return it }
            }

            PlatformType.PINTEREST -> {
                val pws = extractBalancedJsonObject(html, "__PWS_DATA__")
                if (pws != null) {
                    InternationalPlatformParsers.parsePinterestPwsJson(url, url, pws, workId)?.let { return it }
                }
            }

            PlatformType.YOUTUBE -> {
                val player = extractBalancedJsonObject(html, "ytInitialPlayerResponse =")
                if (player != null) {
                    InternationalPlatformParsers.parseYouTubePlayerJson(url, url, player, workId)?.let { return it }
                }
            }

            else -> Unit
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
        val httpsUrl = ParserSupport.normalizeMediaUrl(rawMediaUrl)
        return when (platform) {
            PlatformType.XIAOHONGSHU -> {
                val key = extractXhsObjectKey(httpsUrl)
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
                httpsUrl
                    .replace(Regex("""[?&]bp=\d+"""), "")
                    .replace(Regex("""[?&]format=webp"""), "")
                    .replace(Regex("""_webp\b"""), "")
            }

            PlatformType.WEIBO -> DomesticExtPlatformParsers.rewriteWeiboOriginUrl(httpsUrl)
            PlatformType.BILIBILI -> DomesticExtPlatformParsers.rewriteBilibiliOriginUrl(httpsUrl)
            PlatformType.TWITTER -> InternationalPlatformParsers.rewriteTwitterOrigUrl(httpsUrl)
            PlatformType.PINTEREST -> InternationalPlatformParsers.rewritePinterestOriginUrl(httpsUrl)
            else -> httpsUrl.substringBefore("?x-oss-process")
        }
    }

    private fun extractXhsObjectKey(httpsUrl: String): String {
        val noQuery = httpsUrl.substringBefore("?").substringBefore("!")
        val afterDomain = noQuery.substringAfter(".com/", "")
        val strippedTimestampHash = afterDomain.replace(
            Regex("""^\d{10,14}/[0-9a-fA-F]{24,40}/"""),
            ""
        )
        return strippedTimestampHash.ifBlank { noQuery.substringAfterLast("/") }
    }

    private fun buildDirectMediaPost(
        rawInput: String,
        cleanUrl: String,
        platform: PlatformType,
        preferences: UserPreferencesState,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        val resolvedPlatform = if (platform == PlatformType.UNKNOWN) {
            ShareLinkExtractor.detectPlatform(cleanUrl, cleanUrl)
        } else platform
        val workId = ShareLinkExtractor.extractWorkId(cleanUrl, resolvedPlatform)
        val lower = cleanUrl.substringBefore('?').lowercase()
        val isVideo = lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".m4v")
        val orig = rewriteToLosslessOriginUrl(cleanUrl, resolvedPlatform, preferences.xhsCdnHostRule)

        val item = if (isVideo) {
            MediaItem(
                id = "${workId}_direct_vid",
                index = 0,
                kind = MediaKind.VIDEO,
                badge = QualityBadge.VIDEO_HD,
                originUrl = orig,
                previewUrl = cleanUrl,
                fallbackUrls = listOf(orig, cleanUrl).distinct(),
                width = 1080,
                height = 1920,
                format = "MP4",
                colorSpace = "BT.709",
                themeTitle = "直链高清视频"
            )
        } else {
            val rawXhs = if (resolvedPlatform == PlatformType.XIAOHONGSHU) {
                val base = preferences.xhsCdnHostRule.let { if (it.endsWith("/")) it else "$it/" }
                "${base}${extractXhsObjectKey(cleanUrl)}"
            } else null
            MediaItem(
                id = "${workId}_direct_img",
                index = 0,
                kind = MediaKind.IMAGE,
                badge = QualityBadge.RAW,
                originUrl = orig,
                previewUrl = cleanUrl,
                fallbackUrls = listOfNotNull(rawXhs, orig, cleanUrl).distinct(),
                width = 2000,
                height = 2000,
                format = ParserSupport.detectImageFormat(orig, "JPG"),
                colorSpace = "Display P3",
                themeTitle = "无损原图直链"
            )
        }

        val post = ParsedPost(
            id = "direct_$workId",
            sourceShareText = rawInput,
            extractedUrl = cleanUrl,
            noteId = workId,
            platform = resolvedPlatform,
            postTypeLabel = "${resolvedPlatform.displayName} · 直链原文件",
            authorName = resolvedPlatform.displayName,
            authorHandle = "@direct",
            authorAvatarUrl = "",
            coverUrl = item.previewUrl,
            title = cleanUrl.substringAfterLast('/').substringBefore('?').ifBlank { "原文件直链 #$workId" },
            content = cleanUrl,
            tags = emptyList(),
            mediaItems = listOf(item),
            parseEngineSource = "直链极速引擎 · CDN 零重压"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    private fun tryDirectHttpExtraction(
        rawInput: String,
        cleanUrl: String,
        platform: PlatformType,
        preferences: UserPreferencesState,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        // 国内拓展平台：微博、B站、最右、皮皮虾
        when (platform) {
            PlatformType.WEIBO, PlatformType.BILIBILI, PlatformType.ZUIYOU, PlatformType.PIPIXIA -> {
                DomesticExtPlatformParsers.parseDirect(
                    rawInput = rawInput,
                    cleanUrl = cleanUrl,
                    platform = platform,
                    customCookie = customCookie,
                    previousHistory = previousHistory
                )?.let { return it }
            }

            // 国外主流平台：X/Twitter、Instagram、TikTok、Threads、Pinterest、YouTube、Reddit
            PlatformType.TWITTER,
            PlatformType.INSTAGRAM,
            PlatformType.TIKTOK,
            PlatformType.THREADS,
            PlatformType.PINTEREST,
            PlatformType.YOUTUBE,
            PlatformType.REDDIT -> {
                InternationalPlatformParsers.parseDirect(
                    rawInput = rawInput,
                    cleanUrl = cleanUrl,
                    platform = platform,
                    customCookie = customCookie,
                    previousHistory = previousHistory
                )?.let { return it }
            }

            else -> Unit
        }

        val page = ParserSupport.followRedirectsAndFetch(cleanUrl, platform, customCookie) ?: return null
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
                            val sharePage = ParserSupport.followRedirectsAndFetch(shareUrl, PlatformType.DOUYIN, customCookie)
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
                        val desktopUrl = "https://www.douyin.com/note/$awemeId"
                        val desktopPage = ParserSupport.followRedirectsAndFetch(
                            startUrl = desktopUrl,
                            platform = PlatformType.DOUYIN,
                            customCookie = customCookie,
                            userAgent = ParserSupport.DESKTOP_UA
                        )
                        if (desktopPage != null && desktopPage.html.isNotBlank()) {
                            val parsed = parseDouyinHtmlState(
                                rawInput = rawInput,
                                cleanUrl = cleanUrl,
                                finalUrl = desktopPage.finalUrl,
                                html = desktopPage.html,
                                workId = awemeId,
                                previousHistory = previousHistory
                            )
                            if (parsed != null && parsed.mediaItems.isNotEmpty()) {
                                return@run parsed
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

    // ════════════════════════════════════════════════════════════════
    // 抖音解析：原图 + 精准 Live 实况识别 + 最高码率 MP4 视频
    // ════════════════════════════════════════════════════════════════

    private fun parseDouyinHtmlState(
        rawInput: String,
        cleanUrl: String,
        finalUrl: String,
        html: String,
        workId: String,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        val candidates = listOfNotNull(
            extractBalancedJsonObject(html, "_ROUTER_DATA ="),
            extractBalancedJsonObject(html, "_ROUTER_DATA="),
            extractRenderDataJson(html),
            extractDouyinPacePayloadJson(html),
            extractBalancedJsonObject(html, "\"detail\":{"),
            extractBalancedJsonObject(html, "\"aweme_detail\":")
        )
        for (jsonStr in candidates) {
            parseDouyinRouterJson(
                rawInput = rawInput,
                cleanUrl = cleanUrl.ifBlank { finalUrl },
                routerJsonStr = jsonStr,
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

            val awemeId = itemObj.optStr("aweme_id").ifBlank { itemObj.optStr("awemeId") }.ifBlank { fallbackWorkId }
            val desc = itemObj.optStr("desc").trim()

            val authorObj = itemObj.optObj("author") ?: itemObj.optObj("authorInfo")
            val authorName = authorObj?.optStr("nickname")?.takeIf { it.isNotBlank() } ?: "抖音创作者"
            val authorHandle = authorObj?.optStr("unique_id")?.takeIf { it.isNotBlank() }
                ?: authorObj?.optStr("uniqueId")?.takeIf { it.isNotBlank() }
                ?: authorObj?.optStr("short_id")?.takeIf { it.isNotBlank() }
                ?: authorObj?.optStr("uid")?.takeIf { it.isNotBlank() }
                ?: awemeId.take(8)
            val avatarUrl = extractFirstUrlFromHolder(authorObj?.optObj("avatar_larger"))
                ?: extractFirstUrlFromHolder(authorObj?.optObj("avatarLarger"))
                ?: extractFirstUrlFromHolder(authorObj?.optObj("avatar_medium"))
                ?: extractFirstUrlFromHolder(authorObj?.optObj("avatarMedium"))
                ?: extractFirstUrlFromHolder(authorObj?.optObj("avatar_thumb"))
                ?: extractFirstUrlFromHolder(authorObj?.optObj("avatarThumb"))
                ?: authorObj?.optStr("avatarUri")?.takeIf { it.startsWith("http") }
                ?: ""

            val tags = extractHashtags(desc, itemObj.optArr("text_extra") ?: itemObj.optArr("textExtra"))
            val mediaItems = mutableListOf<MediaItem>()

            val imagesArr = itemObj.optArr("images")
                ?: itemObj.optObj("image_post_info")?.optArr("images")
                ?: itemObj.optArr("image_list")
                ?: itemObj.optObj("image_album")?.optArr("images")
                ?: itemObj.optArr("imageInfos")

            val rootVideoObj = itemObj.optObj("video")
            val rootLiveVideoUrl = extractBestDouyinVideoUrl(rootVideoObj)

            if (imagesArr != null && imagesArr.size() > 0) {
                val postExplicitLive = itemObj.optBool("is_live_photo", false) ||
                    itemObj.optBool("is_live", false) ||
                    itemObj.optBool("isLivePhoto", false)

                for (i in 0 until imagesArr.size()) {
                    val imgObj = imagesArr.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    // 严格优先无水印原图字段（origin_image / display_image / url_list / urlList）；
                    // 抖音 _ROUTER_DATA / __pace_f 的 download_url_list / downloadUrlList 往往带有“抖音号：xxx”底部水印，必须跳过！
                    val cleanImageArrays = listOfNotNull(
                        imgObj.optArr("watermark_free_download_url_list"),
                        imgObj.optArr("watermarkFreeDownloadUrlList"),
                        imgObj.optObj("origin_image")?.let { it.optArr("url_list") ?: it.optArr("urlList") },
                        imgObj.optObj("originImage")?.let { it.optArr("url_list") ?: it.optArr("urlList") },
                        imgObj.optObj("original_image")?.let { it.optArr("url_list") ?: it.optArr("urlList") },
                        imgObj.optObj("originalImage")?.let { it.optArr("url_list") ?: it.optArr("urlList") },
                        imgObj.optObj("display_image")?.let { it.optArr("url_list") ?: it.optArr("urlList") },
                        imgObj.optObj("displayImage")?.let { it.optArr("url_list") ?: it.optArr("urlList") },
                        imgObj.optArr("url_list"),
                        imgObj.optArr("urlList"),
                        imgObj.optObj("live_photo")?.optObj("cover")?.let { it.optArr("url_list") ?: it.optArr("urlList") },
                        imgObj.optObj("livePhoto")?.optObj("cover")?.let { it.optArr("url_list") ?: it.optArr("urlList") }
                    )

                    var bestOriginUrl: String? = null
                    val collectedAll = linkedSetOf<String>()
                    for (arr in cleanImageArrays) {
                        selectBestDouyinImageUrl(arr)?.let { best ->
                            if (bestOriginUrl == null) bestOriginUrl = best
                            collectedAll.add(best)
                        }
                        collectCleanDouyinImageUrls(arr).forEach { collectedAll.add(it) }
                    }
                    // 仅当无水印字段完全缺失时，才兜底读取缩略图或过滤后的 download_url_list
                    if (bestOriginUrl.isNullOrBlank()) {
                        val fallbackArrays = listOfNotNull(
                            imgObj.optObj("thumbnail_image")?.let { it.optArr("url_list") ?: it.optArr("urlList") },
                            imgObj.optObj("thumbnailImage")?.let { it.optArr("url_list") ?: it.optArr("urlList") },
                            imgObj.optObj("download_image")?.let { it.optArr("url_list") ?: it.optArr("urlList") },
                            imgObj.optObj("downloadImage")?.let { it.optArr("url_list") ?: it.optArr("urlList") },
                            imgObj.optArr("download_url_list"),
                            imgObj.optArr("downloadUrlList")
                        )
                        for (arr in fallbackArrays) {
                            selectBestDouyinImageUrl(arr)?.let { best ->
                                if (bestOriginUrl == null) bestOriginUrl = best
                                collectedAll.add(best)
                            }
                        }
                    }
                    if (bestOriginUrl.isNullOrBlank()) {
                        bestOriginUrl = collectedAll.firstOrNull() ?: continue
                    }
                    val previewUrl = cleanImageArrays.firstNotNullOfOrNull { selectBestDouyinImageUrl(it) }
                        ?: collectedAll.firstOrNull()
                        ?: bestOriginUrl

                    // 精准提取单张图对应的实况视频轨（避免将普通图集的 BGM 幻灯片误判为实况）
                    val slideLiveVideoUrl = extractBestDouyinVideoUrl(imgObj.optObj("video"))
                        ?: extractBestDouyinVideoUrl(imgObj.optObj("clip_video"))
                        ?: extractBestDouyinVideoUrl(imgObj.optObj("clipVideo"))
                        ?: extractBestDouyinVideoUrl(imgObj.optObj("live_photo")?.optObj("video"))
                        ?: extractBestDouyinVideoUrl(imgObj.optObj("livePhoto")?.optObj("video"))
                        ?: extractBestDouyinVideoUrl(imgObj.optObj("live_photo"))
                        ?: if (imagesArr.size() == 1 && (postExplicitLive || imgObj.optBool("is_live", false))) {
                            rootLiveVideoUrl
                        } else null

                    val isLive = !slideLiveVideoUrl.isNullOrBlank()
                    val resLog = imgObj.optObj("resolution_log_param")
                    val w = imgObj.optInt("width", 0).takeIf { it > 0 }
                        ?: resLog?.optInt("image_source_width", 0)?.takeIf { it > 0 }
                        ?: imgObj.optObj("origin_image")?.optInt("width", 0)?.takeIf { it > 0 }
                        ?: imgObj.optObj("display_image")?.optInt("width", 0)?.takeIf { it > 0 }
                        ?: 1080
                    val h = imgObj.optInt("height", 0).takeIf { it > 0 }
                        ?: resLog?.optInt("image_source_height", 0)?.takeIf { it > 0 }
                        ?: imgObj.optObj("origin_image")?.optInt("height", 0)?.takeIf { it > 0 }
                        ?: imgObj.optObj("display_image")?.optInt("height", 0)?.takeIf { it > 0 }
                        ?: 1440

                    val is4K = w >= 2160 || h >= 2160
                    val fmt = ParserSupport.detectImageFormat(bestOriginUrl!!, "JPG")

                    mediaItems.add(
                        MediaItem(
                            id = "${awemeId}_dy_img_$i",
                            index = i,
                            kind = if (isLive) MediaKind.LIVE_PHOTO else MediaKind.IMAGE,
                            badge = when {
                                isLive -> QualityBadge.LIVE
                                is4K -> QualityBadge.FOUR_K
                                else -> QualityBadge.RAW
                            },
                            originUrl = bestOriginUrl!!,
                            previewUrl = previewUrl,
                            liveVideoUrl = slideLiveVideoUrl,
                            fallbackUrls = collectedAll.toList(),
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

            if (mediaItems.isEmpty() && rootVideoObj != null) {
                val losslessVideoUrl = rootLiveVideoUrl
                val coverUrl = extractFirstUrlFromHolder(rootVideoObj.optObj("origin_cover"))
                    ?: extractFirstUrlFromHolder(rootVideoObj.optObj("cover"))
                    ?: extractFirstUrlFromHolder(rootVideoObj.optObj("dynamic_cover"))
                    ?: ""

                val w = rootVideoObj.optInt("width", 1080).coerceAtLeast(720)
                val h = rootVideoObj.optInt("height", 1920).coerceAtLeast(1280)
                val durationMs = rootVideoObj.optLong("duration", 0L)

                if (!losslessVideoUrl.isNullOrBlank()) {
                    mediaItems.add(
                        MediaItem(
                            id = "${awemeId}_dy_vid_0",
                            index = 0,
                            kind = MediaKind.VIDEO,
                            badge = if (w >= 2160 || h >= 2160) QualityBadge.FOUR_K else QualityBadge.VIDEO_HD,
                            originUrl = losslessVideoUrl,
                            previewUrl = coverUrl.ifBlank { losslessVideoUrl },
                            liveVideoUrl = losslessVideoUrl,
                            width = w,
                            height = h,
                            format = "MP4",
                            colorSpace = "BT.709",
                            durationMs = durationMs,
                            themeTitle = desc,
                            visualSeed = 0
                        )
                    )
                }
            }

            if (mediaItems.isEmpty()) return null

            val musicObj = itemObj.optObj("music")
            val bgmUrl = extractFirstUrlFromHolder(musicObj?.optObj("play_url") ?: musicObj?.optObj("playUrl"))
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
                mediaItems.any { it.isLivePhoto } -> "抖音·实况图"
                mediaItems.any { it.isVideo } -> "抖音·视频"
                else -> "抖音·图集"
            }

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
                coverUrl = mediaItems.first().previewUrl,
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

    private fun extractBestDouyinVideoUrl(videoObj: JsonObject?): String? {
        if (videoObj == null) return null
        // 优先从 bit_rate / bitRateList 数组选出最高码率无水印视频流
        val bitRateArr = videoObj.optArr("bit_rate") ?: videoObj.optArr("bitRateList")
        var bestBitrate = -1L
        var bestBrUrl: String? = null
        bitRateArr?.forEach { el ->
            val brObj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val br = brObj.optLong("bit_rate", 0L).takeIf { it > 0L } ?: brObj.optLong("bitRate", 0L)
            val holder = brObj.optObj("play_addr") ?: brObj.optObj("playAddr")
            val u = extractFirstUrlFromHolder(holder)
            if (!u.isNullOrBlank() && br >= bestBitrate) {
                bestBitrate = br
                bestBrUrl = u
            }
        }
        if (!bestBrUrl.isNullOrBlank()) {
            return rewriteToLosslessOriginUrl(bestBrUrl!!, PlatformType.DOUYIN)
        }

        val playAddr = videoObj.optObj("play_addr")
        val uri = playAddr?.optStr("uri") ?: videoObj.optStr("uri")
        val rawUrl = extractFirstUrlFromHolder(playAddr)
            ?: extractFirstUrlFromHolder(videoObj.optObj("play_addr_h264"))
            ?: extractFirstUrlFromArray(videoObj.optArr("playAddr"))
            ?: videoObj.optStr("playApi").takeIf { it.startsWith("http") }
        if (!rawUrl.isNullOrBlank()) {
            return rewriteToLosslessOriginUrl(rawUrl, PlatformType.DOUYIN)
        }
        return uri.takeIf { it.isNotBlank() }?.let {
            "https://aweme.snssdk.com/aweme/v1/play/?video_id=$it&ratio=1080p&line=0"
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 小红书解析：原文件直链候选 + Live Photo 实况视频 + 4K 视频
    // ════════════════════════════════════════════════════════════════

    private fun parseXiaohongshuHtmlState(
        rawInput: String,
        cleanUrl: String,
        finalUrl: String,
        html: String,
        workId: String,
        preferences: UserPreferencesState,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        val stateJson = extractBalancedJsonObject(html, "window.__INITIAL_STATE__=")
            ?: extractBalancedJsonObject(html, "__INITIAL_STATE__=")
            ?: extractBalancedJsonObject(html, "window.__INITIAL_STATE__ =")
            ?: return null

        return parseXiaohongshuStateJson(
            rawInput = rawInput,
            cleanUrl = cleanUrl.ifBlank { finalUrl },
            stateJsonStr = stateJson,
            html = html,
            fallbackWorkId = workId,
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
            val normalizedJson = stateJsonStr.replace(":undefined", ":null")
            val root = JsonParser.parseString(normalizedJson)
            val noteObj = findXiaohongshuNoteObject(root) ?: return null

            val noteId = noteObj.optStr("noteId")
                .ifBlank { noteObj.optStr("id") }
                .ifBlank { fallbackWorkId }
            val title = noteObj.optStr("title")
                .ifBlank { noteObj.optStr("displayTitle") }
                .ifBlank { ParserSupport.extractMetaTags(html, "og:title").firstOrNull() ?: "" }
                .replace(" - 小红书", "")
                .trim()
            val desc = noteObj.optStr("desc")
                .ifBlank { ParserSupport.extractMetaTags(html, "description").firstOrNull() ?: "" }
                .trim()

            val userObj = noteObj.optObj("user")
            val authorName = userObj?.optStr("nickName")
                ?.ifBlank { userObj.optStr("nickname") }
                ?.takeIf { it.isNotBlank() }
                ?: "小红书创作者"
            val authorAvatar = userObj?.optStr("avatar")?.let { ParserSupport.normalizeMediaUrl(it) } ?: ""

            val tags = linkedSetOf<String>()
            noteObj.optArr("tagList")?.let { arr ->
                for (i in 0 until arr.size()) {
                    val tName = arr.get(i).takeIf { it.isJsonObject }?.asJsonObject?.optStr("name")
                    if (!tName.isNullOrBlank()) tags.add("#$tName")
                }
            }
            if (tags.isEmpty()) {
                tags.addAll(ParserSupport.extractHashtagsFromText("$title $desc"))
            }

            val mediaItems = mutableListOf<MediaItem>()
            val noteType = noteObj.optStr("type", "normal")
            val videoObj = noteObj.optObj("video")
            val videoMasterUrl = extractXiaohongshuVideoUrl(videoObj)
                ?: ParserSupport.extractMetaTags(html, "og:video").firstOrNull()
            val imageList = noteObj.optArr("imageList") ?: noteObj.optArr("imagesList")

            if (noteType == "video" && !videoMasterUrl.isNullOrBlank()) {
                val firstCover = imageList?.takeIf { it.size() > 0 }
                    ?.get(0)?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.let { extractXiaohongshuPreviewUrl(it) }
                    ?: ParserSupport.extractMetaTags(html, "og:image").firstOrNull()
                    ?: ""
                val w = videoObj?.optObj("capa")?.optInt("width", 1080) ?: 1080
                val h = videoObj?.optObj("capa")?.optInt("height", 1920) ?: 1920

                mediaItems.add(
                    MediaItem(
                        id = "${noteId}_xhs_vid_0",
                        index = 0,
                        kind = MediaKind.VIDEO,
                        badge = QualityBadge.VIDEO_HD,
                        originUrl = ParserSupport.normalizeMediaUrl(videoMasterUrl),
                        previewUrl = ParserSupport.normalizeMediaUrl(firstCover),
                        liveVideoUrl = ParserSupport.normalizeMediaUrl(videoMasterUrl),
                        width = w,
                        height = h,
                        format = "MP4",
                        colorSpace = "BT.709",
                        themeTitle = title.ifBlank { desc },
                        visualSeed = 0
                    )
                )
            } else if (imageList != null && imageList.size() > 0) {
                val base = preferences.xhsCdnHostRule.let { if (it.endsWith("/")) it else "$it/" }
                for (i in 0 until imageList.size()) {
                    val imgObj = imageList.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val previewUrl = extractXiaohongshuPreviewUrl(imgObj) ?: continue
                    val traceId = imgObj.optStr("traceId")
                        .ifBlank { imgObj.optStr("fileId") }
                        .ifBlank { imgObj.optObj("image")?.optStr("traceId") ?: "" }
                        .ifBlank { imgObj.optObj("image")?.optStr("fileId") ?: "" }

                    // rawUntouchedUrl 不附带 ?imageView2 查询参数，尽可能取回上传原始字节流(含 EXIF/Display P3)
                    val rawUntouchedUrl = if (traceId.isNotBlank()) "${base}${traceId}" else null
                    val losslessUrl = if (traceId.isNotBlank()) {
                        "${base}${traceId}?imageView2/2/w/0/format/png"
                    } else {
                        rewriteToLosslessOriginUrl(previewUrl, PlatformType.XIAOHONGSHU, preferences.xhsCdnHostRule)
                    }

                    // 实况照片动态视频轨识别：imgObj.livePhoto + stream.h264/h265/av1
                    val liveVideoUrl = extractXiaohongshuVideoUrl(imgObj)
                        ?: imgObj.optObj("liveVideo")?.optStr("url")?.takeIf { it.startsWith("http") }
                            ?.let { ParserSupport.normalizeMediaUrl(it) }
                    val isLive = imgObj.optBool("livePhoto", false) || !liveVideoUrl.isNullOrBlank()

                    val w = imgObj.optInt("width", 1440).coerceAtLeast(720)
                    val h = imgObj.optInt("height", 1920).coerceAtLeast(960)
                    val is4K = w >= 2560 || h >= 2560

                    mediaItems.add(
                        MediaItem(
                            id = "${noteId}_xhs_img_$i",
                            index = i,
                            kind = if (isLive && !liveVideoUrl.isNullOrBlank()) MediaKind.LIVE_PHOTO else MediaKind.IMAGE,
                            badge = when {
                                isLive && !liveVideoUrl.isNullOrBlank() -> QualityBadge.LIVE
                                is4K -> QualityBadge.FOUR_K
                                else -> QualityBadge.RAW
                            },
                            originUrl = losslessUrl,
                            previewUrl = previewUrl,
                            liveVideoUrl = liveVideoUrl,
                            fallbackUrls = listOfNotNull(rawUntouchedUrl, losslessUrl, previewUrl).distinct(),
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
                mediaItems.any { it.isLivePhoto } -> "小红书·实况图"
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
                tags = tags.toList(),
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

    // ════════════════════════════════════════════════════════════════
    // 快手解析：INIT_STATE / __APOLLO_STATE__ + 正则嗅探
    // ════════════════════════════════════════════════════════════════

    private fun parseKuaishouHtmlState(
        rawInput: String,
        cleanUrl: String,
        finalUrl: String,
        html: String,
        workId: String,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        val initStateStr = extractBalancedJsonObject(html, "window.INIT_STATE")
            ?: extractBalancedJsonObject(html, "__APOLLO_STATE__")
            ?: return null
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
                    if (child.isJsonObject) searchForPhoto(child.asJsonObject)
                }
            }
            searchForPhoto(root)

            val photoObj = targetPhotoObj ?: return null
            val photoId = photoObj.optStr("photoId").ifBlank { workId }
            val caption = photoObj.optStr("caption").trim()
            val authorName = photoObj.optStr("userName").takeIf { it.isNotBlank() } ?: "快手创作者"
            val authorHandle = photoObj.optStr("userId").takeIf { it.isNotBlank() } ?: photoId.take(8)
            val authorAvatar = ParserSupport.normalizeMediaUrl(photoObj.optStr("headUrl"))
            val width = photoObj.optInt("width", 1080).coerceAtLeast(720)
            val height = photoObj.optInt("height", 1920).coerceAtLeast(960)

            val mediaItems = mutableListOf<MediaItem>()
            val mainMvArr = photoObj.optArr("mainMvUrls")
            val rawVideoUrl = extractFirstUrlFromArray(mainMvArr)
                ?: photoObj.optStr("videoUrl").takeIf { it.isNotBlank() }

            if (!rawVideoUrl.isNullOrBlank()) {
                val losslessVid = ParserSupport.normalizeMediaUrl(rawVideoUrl)
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
                val atlasObj = targetAtlasObj ?: photoObj.optObj("atlas")
                val atlasList = atlasObj?.optArr("list")
                val cdnList = atlasObj?.optArr("cdnList") ?: targetSingleObj?.optArr("cdnList")
                val cdnHost = cdnList?.takeIf { it.size() > 0 }
                    ?.get(0)?.takeIf { it.isJsonObject }?.asJsonObject?.optStr("cdn")

                if (atlasList != null && atlasList.size() > 0) {
                    for (i in 0 until atlasList.size()) {
                        val el = atlasList.get(i)
                        val path = if (el.isJsonPrimitive) el.asString else continue
                        val fullUrl = when {
                            path.startsWith("http") -> ParserSupport.normalizeMediaUrl(path)
                            !cdnHost.isNullOrBlank() -> "https://$cdnHost/${path.trimStart('/')}"
                            else -> "https://p1.a.yximgs.com/${path.trimStart('/')}"
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
                                format = ParserSupport.detectImageFormat(originUrl, "JPG"),
                                colorSpace = "Display P3",
                                themeTitle = caption,
                                visualSeed = i
                            )
                        )
                    }
                } else {
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
                                format = ParserSupport.detectImageFormat(originUrl, "JPG"),
                                colorSpace = "Display P3",
                                themeTitle = caption,
                                visualSeed = 0
                            )
                        )
                    }
                }
            }

            if (mediaItems.isEmpty()) return null

            val singleObj = targetSingleObj
            val musicPath = singleObj?.optStr("music")?.takeIf { it.isNotBlank() }
                ?: photoObj.optStr("music").takeIf { it.isNotBlank() }
            val musicCdn = singleObj?.optArr("musicCdnList")?.takeIf { it.size() > 0 }
                ?.get(0)?.takeIf { it.isJsonObject }?.asJsonObject?.optStr("cdn")

            val bgmAudio = if (!musicPath.isNullOrBlank()) {
                val fullMusicUrl = when {
                    musicPath.startsWith("http") -> ParserSupport.normalizeMediaUrl(musicPath)
                    !musicCdn.isNullOrBlank() -> "https://$musicCdn/${musicPath.trimStart('/')}"
                    else -> null
                }
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

            ParsedPost(
                id = "post_$photoId",
                sourceShareText = rawInput,
                extractedUrl = cleanUrl.ifBlank { finalUrl },
                noteId = photoId,
                platform = PlatformType.KUAISHOU,
                postTypeLabel = if (mediaItems.any { it.isVideo }) "快手·视频" else "快手·图集",
                authorName = authorName,
                authorHandle = "@$authorHandle",
                authorAvatarUrl = authorAvatar,
                coverUrl = mediaItems.first().previewUrl,
                title = caption.take(40).ifBlank { "快手作品分享" },
                content = caption,
                tags = ParserSupport.extractHashtagsFromText(caption),
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
            val captionMatcher = Pattern.compile(""""caption"\s*:\s*"((?:[^"\\]|\\.)*)"""").matcher(html)
            val caption = if (captionMatcher.find()) decodeJsonString(captionMatcher.group(1) ?: "") else ""

            val userMatcher = Pattern.compile(""""userName"\s*:\s*"((?:[^"\\]|\\.)*)"""").matcher(html)
            val userName = if (userMatcher.find()) decodeJsonString(userMatcher.group(1) ?: "") else "快手创作者"

            val headMatcher = Pattern.compile(""""headUrl"\s*:\s*"((?:[^"\\]|\\.)*)"""").matcher(html)
            val headUrl = if (headMatcher.find()) ParserSupport.normalizeMediaUrl(decodeJsonString(headMatcher.group(1) ?: "")) else ""

            val wPattern = Pattern.compile(""""width"\s*:\s*(\d+)""").matcher(html)
            val width = if (wPattern.find()) wPattern.group(1)?.toIntOrNull() ?: 1080 else 1080
            val hPattern = Pattern.compile(""""height"\s*:\s*(\d+)""").matcher(html)
            val height = if (hPattern.find()) hPattern.group(1)?.toIntOrNull() ?: 1920 else 1920

            val mediaItems = mutableListOf<MediaItem>()
            val mvMatcher = Pattern.compile(""""mainMvUrls"\s*:\s*\[\s*\{.*?"url"\s*:\s*"((?:[^"\\]|\\.)*)"""").matcher(html)
            if (mvMatcher.find()) {
                val vidUrl = ParserSupport.normalizeMediaUrl(decodeJsonString(mvMatcher.group(1) ?: ""))
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
                    val rawU = ParserSupport.normalizeMediaUrl(decodeJsonString(imgMatcher.group(0) ?: ""))
                    if (!rawU.contains("avatar") && !rawU.contains("head") && !rawU.contains("icon") &&
                        !rawU.endsWith(".js") && !rawU.endsWith(".css")
                    ) {
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
            ParsedPost(
                id = "post_$workId",
                sourceShareText = rawInput,
                extractedUrl = cleanUrl.ifBlank { finalUrl },
                noteId = workId,
                platform = PlatformType.KUAISHOU,
                postTypeLabel = if (mediaItems.any { it.isVideo }) "快手·视频" else "快手·图集",
                authorName = userName,
                authorHandle = "@$workId",
                authorAvatarUrl = headUrl,
                coverUrl = mediaItems.first().previewUrl,
                title = caption.take(40).ifBlank { "快手作品分享" },
                content = caption,
                tags = ParserSupport.extractHashtagsFromText(caption),
                mediaItems = mediaItems,
                bgmAudio = null,
                parseEngineSource = "快手原画直连",
                isDuplicateDownload = previousHistory?.downloadedOnce == true,
                previousDownloadTimeMillis = previousHistory?.parsedAtMillis
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeJsonString(raw: String): String {
        return raw.replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
    }

    // ════════════════════════════════════════════════════════════════
    // 后台 WebView 嗅探结果聚合与通用 HTML 兜底解析
    // ════════════════════════════════════════════════════════════════

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
            parseDouyinRouterJson(rawInput, cleanUrl, sniff.routerDataJson, workId, previousHistory)?.let { return it }
        }
        if (platform == PlatformType.DOUYIN && !sniff.apiResponseJson.isNullOrBlank()) {
            parseDouyinRouterJson(rawInput, cleanUrl, sniff.apiResponseJson, workId, previousHistory)?.let { return it }
        }
        if (platform == PlatformType.DOUYIN && !sniff.renderDataEncoded.isNullOrBlank()) {
            val decoded = runCatching { URLDecoder.decode(sniff.renderDataEncoded.trim(), "UTF-8") }.getOrNull()
            if (!decoded.isNullOrBlank()) {
                parseDouyinRouterJson(rawInput, cleanUrl, decoded, workId, previousHistory)?.let { return it }
            }
        }

        if (!sniff.initialStateJson.isNullOrBlank()) {
            when (platform) {
                PlatformType.ZUIYOU -> DomesticExtPlatformParsers.parseZuiyouJson(
                    rawInput, sniff.finalUrl, sniff.initialStateJson, workId, previousHistory
                )?.let { return it }

                PlatformType.BILIBILI -> DomesticExtPlatformParsers.parseBilibiliDynamicJson(
                    rawInput, sniff.finalUrl, sniff.initialStateJson, workId, previousHistory
                )?.let { return it }

                PlatformType.PINTEREST -> InternationalPlatformParsers.parsePinterestPwsJson(
                    rawInput, sniff.finalUrl, sniff.initialStateJson, workId, previousHistory
                )?.let { return it }

                PlatformType.TIKTOK -> InternationalPlatformParsers.parseTikTokHtmlState(
                    rawInput, sniff.finalUrl, "<script id=\"SIGI_STATE\">${sniff.initialStateJson}</script>", previousHistory
                )?.let { return it }

                PlatformType.YOUTUBE -> InternationalPlatformParsers.parseYouTubePlayerJson(
                    rawInput, sniff.finalUrl, sniff.initialStateJson, workId, previousHistory
                )?.let { return it }

                else -> parseXiaohongshuStateJson(
                    rawInput = rawInput,
                    cleanUrl = cleanUrl,
                    stateJsonStr = sniff.initialStateJson,
                    html = sniff.rawHtml,
                    fallbackWorkId = workId,
                    preferences = preferences,
                    previousHistory = previousHistory
                )?.let { return it }
            }
        }

        if (sniff.stateScriptText.isNotBlank()) {
            parseDouyinHtmlState(rawInput, cleanUrl, sniff.finalUrl, sniff.stateScriptText, workId, previousHistory)
                ?.let { return it }
            parseXiaohongshuHtmlState(rawInput, cleanUrl, sniff.finalUrl, sniff.stateScriptText, workId, preferences, previousHistory)
                ?.let { return it }
            if (platform == PlatformType.TIKTOK) {
                InternationalPlatformParsers.parseTikTokHtmlState(rawInput, sniff.finalUrl, sniff.stateScriptText, previousHistory)
                    ?.let { return it }
            }
        }

        if (platform == PlatformType.KUAISHOU) {
            val stateCandidate = sniff.initialStateJson ?: sniff.stateScriptText
            if (stateCandidate.isNotBlank()) {
                parseKuaishouStateJson(rawInput, cleanUrl, sniff.finalUrl, stateCandidate, workId, previousHistory)
                    ?.let { return it }
            }
            parseKuaishouHtmlState(rawInput, cleanUrl, sniff.finalUrl, sniff.rawHtml, workId, previousHistory)
                ?.let { return it }
            parseKuaishouRegexSniff(rawInput, cleanUrl, sniff.finalUrl, sniff.rawHtml, workId, previousHistory)
                ?.let { return it }
        }

        if (platform == PlatformType.INSTAGRAM) {
            InternationalPlatformParsers.parseInstagramHtmlOrJson(
                rawInput, sniff.finalUrl, sniff.rawHtml, workId, previousHistory
            )?.let { return it }
        }
        if (platform == PlatformType.THREADS) {
            InternationalPlatformParsers.parseThreadsHtml(
                rawInput, sniff.finalUrl, sniff.rawHtml, previousHistory
            )?.let { return it }
        }

        // 从动态拦截的网络流、DOM 与 OG Meta 中聚合媒体项
        val mediaItems = mutableListOf<MediaItem>()

        val videoCandidates = linkedSetOf<String>().apply {
            if (sniff.ogVideo.startsWith("http")) add(sniff.ogVideo)
            sniff.domVideos.filter { it.startsWith("http") }.forEach { add(it) }
            sniff.sniffedMediaUrls.filter {
                (it.contains("aweme/v1/play") || it.contains("douyinvod") || it.contains("bytevcloud") ||
                    (it.contains("snssdk.com") && it.contains("play")) || it.contains("sns-video") ||
                    it.contains("xhscdn.com/stream") || it.contains("video.twimg.com") ||
                    it.contains("tiktokcdn") || it.contains("f.video.weibocdn") ||
                    it.endsWith(".mp4")) &&
                    !it.contains("/music/") && !it.endsWith(".mp3") && !it.endsWith(".m4a")
            }.forEach { add(it) }
        }

        val imageCandidates = linkedSetOf<String>().apply {
            sniff.ogImages.filter { it.startsWith("http") && !isDouyinWatermarkedImageUrl(it) }.forEach { add(it) }
            sniff.domImages.filter { it.startsWith("http") && !isDouyinWatermarkedImageUrl(it) }.forEach { add(it) }
            sniff.sniffedMediaUrls.filter {
                (it.contains("xhscdn.com") || it.contains("douyinpic.com") || it.contains("izuiyou.com") ||
                    it.contains("yximgs.com") || it.contains("kwimgs.com") || it.contains("kwaicdn.com") ||
                    it.contains("sinaimg.cn") || it.contains("hdslb.com") || it.contains("pbs.twimg.com") ||
                    it.contains("cdninstagram.com") || it.contains("fbcdn.net") || it.contains("pinimg.com") ||
                    it.contains("redd.it")) &&
                    !it.endsWith(".mp4") && !it.contains("avatar") && !it.contains("profile") &&
                    !isDouyinWatermarkedImageUrl(it)
            }.forEach { add(it) }
        }

        val firstCover = imageCandidates.firstOrNull()?.let { ParserSupport.normalizeMediaUrl(it) } ?: ""
        val cleanOgImages = sniff.ogImages.filter { it.startsWith("http") && !isDouyinWatermarkedImageUrl(it) }
        val targetImages = if (cleanOgImages.size > 1) cleanOgImages else imageCandidates.toList()
        val hasMultipleImages = targetImages.size > 1
        val hasVideo = videoCandidates.isNotEmpty()

        if (hasMultipleImages) {
            targetImages.distinct().take(36).forEachIndexed { idx, rawImgUrl ->
                val preview = ParserSupport.normalizeMediaUrl(rawImgUrl)
                val lossless = rewriteToLosslessOriginUrl(preview, platform, preferences.xhsCdnHostRule)
                val rawXhs = if (platform == PlatformType.XIAOHONGSHU) {
                    val base = preferences.xhsCdnHostRule.let { if (it.endsWith("/")) it else "$it/" }
                    "${base}${extractXhsObjectKey(preview)}"
                } else null
                mediaItems.add(
                    MediaItem(
                        id = "${workId}_img_$idx",
                        index = idx,
                        kind = MediaKind.IMAGE,
                        badge = QualityBadge.RAW,
                        originUrl = lossless,
                        previewUrl = preview,
                        fallbackUrls = listOfNotNull(rawXhs, lossless, preview).distinct(),
                        width = 1440,
                        height = 1920,
                        format = ParserSupport.detectImageFormat(lossless, if (platform == PlatformType.XIAOHONGSHU) "PNG" else "JPG"),
                        colorSpace = "Display P3",
                        themeTitle = sniff.ogTitle.ifBlank { sniff.title },
                        visualSeed = idx
                    )
                )
            }
        } else if (hasVideo) {
            val rawVid = ParserSupport.normalizeMediaUrl(videoCandidates.first())
            val losslessVid = rewriteToLosslessOriginUrl(rawVid, platform, preferences.xhsCdnHostRule)
            mediaItems.add(
                MediaItem(
                    id = "${workId}_vid_0",
                    index = 0,
                    kind = MediaKind.VIDEO,
                    badge = QualityBadge.VIDEO_HD,
                    originUrl = losslessVid,
                    previewUrl = firstCover.ifBlank { losslessVid },
                    liveVideoUrl = losslessVid,
                    width = 1080,
                    height = 1920,
                    format = "MP4",
                    colorSpace = "BT.709",
                    themeTitle = sniff.ogTitle.ifBlank { sniff.title },
                    visualSeed = 0
                )
            )
        } else {
            targetImages.distinct().take(36).forEachIndexed { idx, rawImgUrl ->
                val preview = ParserSupport.normalizeMediaUrl(rawImgUrl)
                val lossless = rewriteToLosslessOriginUrl(preview, platform, preferences.xhsCdnHostRule)
                val rawXhs = if (platform == PlatformType.XIAOHONGSHU) {
                    val base = preferences.xhsCdnHostRule.let { if (it.endsWith("/")) it else "$it/" }
                    "${base}${extractXhsObjectKey(preview)}"
                } else null
                mediaItems.add(
                    MediaItem(
                        id = "${workId}_img_$idx",
                        index = idx,
                        kind = MediaKind.IMAGE,
                        badge = QualityBadge.RAW,
                        originUrl = lossless,
                        previewUrl = preview,
                        fallbackUrls = listOfNotNull(rawXhs, lossless, preview).distinct(),
                        width = 1440,
                        height = 1920,
                        format = ParserSupport.detectImageFormat(lossless, if (platform == PlatformType.XIAOHONGSHU) "PNG" else "JPG"),
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
            else -> "${platform.displayName}·原图"
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
            tags = ParserSupport.extractHashtagsFromText("$cleanTitle $cleanDesc"),
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

        val ogImages = (ParserSupport.extractMetaTags(html, "og:image") +
            ParserSupport.extractMetaTags(html, "twitter:image")).distinct()
        val ogVideos = (ParserSupport.extractMetaTags(html, "og:video") +
            ParserSupport.extractMetaTags(html, "og:video:url") +
            ParserSupport.extractMetaTags(html, "og:video:secure_url")).distinct()
        val ogTitle = ParserSupport.extractMetaTags(html, "og:title").firstOrNull()
            ?: ParserSupport.extractHtmlTitle(html)
        val ogDesc = ParserSupport.extractMetaTags(html, "description").firstOrNull()
            ?: ParserSupport.extractMetaTags(html, "og:description").firstOrNull()
            ?: ogTitle

        val mediaItems = mutableListOf<MediaItem>()

        if (ogVideos.isNotEmpty()) {
            val vidUrl = ParserSupport.normalizeMediaUrl(ogVideos.first())
            val coverUrl = ogImages.firstOrNull()?.let { ParserSupport.normalizeMediaUrl(it) } ?: vidUrl
            val losslessVid = rewriteToLosslessOriginUrl(vidUrl, platform, preferences.xhsCdnHostRule)
            mediaItems.add(
                MediaItem(
                    id = "${workId}_vid_0",
                    index = 0,
                    kind = MediaKind.VIDEO,
                    badge = QualityBadge.VIDEO_HD,
                    originUrl = losslessVid,
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
        }

        // 若无 OG 视频且仅有 OG 图，额外嗅探 HTML 中高清 srcset 大图
        val extraDomImgs = mutableListOf<String>()
        if (ogImages.isEmpty()) {
            val imgPattern = Pattern.compile("""<img[^>]+src=["'](https?://[^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""", Pattern.CASE_INSENSITIVE)
            val m = imgPattern.matcher(html)
            while (m.find() && extraDomImgs.size < 18) {
                val u = ParserSupport.normalizeMediaUrl(m.group(1) ?: "")
                if (!u.contains("avatar") && !u.contains("icon") && !u.contains("logo") && !u.contains("favicon")) {
                    extraDomImgs.add(u)
                }
            }
        }

        val targetImages = if (ogImages.isNotEmpty()) ogImages else extraDomImgs
        if (mediaItems.isEmpty() || ogImages.size > 1) {
            targetImages.take(30).forEachIndexed { idx, rawUrl ->
                val cleanImgUrl = ParserSupport.normalizeMediaUrl(rawUrl)
                val lossless = rewriteToLosslessOriginUrl(cleanImgUrl, platform, preferences.xhsCdnHostRule)
                mediaItems.add(
                    MediaItem(
                        id = "${workId}_og_$idx",
                        index = mediaItems.size,
                        kind = MediaKind.IMAGE,
                        badge = QualityBadge.RAW,
                        originUrl = lossless,
                        previewUrl = cleanImgUrl,
                        fallbackUrls = listOf(lossless, cleanImgUrl).distinct(),
                        width = 1440,
                        height = 1920,
                        format = ParserSupport.detectImageFormat(lossless, "JPG"),
                        colorSpace = "Display P3",
                        themeTitle = ogTitle,
                        visualSeed = idx
                    )
                )
            }
        }

        if (mediaItems.isEmpty()) return null

        val post = ParsedPost(
            id = "post_$workId",
            sourceShareText = rawInput,
            extractedUrl = cleanUrl,
            noteId = workId,
            platform = platform,
            postTypeLabel = if (mediaItems.any { it.isVideo }) "${platform.displayName}·视频" else "${platform.displayName}·原图",
            authorName = "${platform.displayName}内容",
            authorHandle = "@${workId.take(8)}",
            authorAvatarUrl = "",
            coverUrl = mediaItems.first().previewUrl,
            title = ogTitle,
            content = ogDesc,
            tags = ParserSupport.extractHashtagsFromText("$ogTitle $ogDesc"),
            mediaItems = mediaItems,
            bgmAudio = null,
            parseEngineSource = "标准网页嗅探引擎",
            isDuplicateDownload = previousHistory?.downloadedOnce == true,
            previousDownloadTimeMillis = previousHistory?.parsedAtMillis
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    // ════════════════════════════════════════════════════════════════
    // 状态树提取辅助与 Room 历史转换
    // ════════════════════════════════════════════════════════════════

    fun extractBalancedJsonObject(html: String, marker: String): String? =
        ParserSupport.extractBalancedJsonObject(html, marker)

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

    private fun extractDouyinPacePayloadJson(html: String): String? {
        if (!html.contains("self.__pace_f.push")) return null
        var searchFrom = 0
        while (searchFrom < html.length) {
            val pushIdx = html.indexOf("self.__pace_f.push", searchFrom)
            if (pushIdx == -1) break
            val quoteStart = html.indexOf('"', pushIdx)
            if (quoteStart == -1) break

            var escaped = false
            var quoteEnd = -1
            for (i in (quoteStart + 1) until html.length) {
                val ch = html[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\') {
                    escaped = true
                    continue
                }
                if (ch == '"') {
                    quoteEnd = i
                    break
                }
            }
            if (quoteEnd == -1) break
            searchFrom = quoteEnd + 1

            val encodedLiteral = html.substring(quoteStart, quoteEnd + 1)
            if (!encodedLiteral.contains("urlList") && !encodedLiteral.contains("awemeId")) continue

            val decoded = runCatching { JsonParser.parseString(encodedLiteral).asString }.getOrNull() ?: continue
            extractBalancedJsonObject(decoded, "\"detail\":")?.let { return it }
            extractBalancedJsonObject(decoded, "\"aweme\":")?.let { return it }
        }
        return null
    }

    private fun findDouyinAwemeItemObject(node: JsonElement?): JsonObject? {
        if (node == null || node.isJsonNull) return null
        if (node.isJsonObject) {
            val obj = node.asJsonObject
            if ((obj.has("aweme_id") || obj.has("awemeId")) && (obj.has("video") || obj.has("images") || obj.has("image_post_info"))) {
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
        return candidates.firstOrNull()?.let { ParserSupport.normalizeMediaUrl(it) }
    }

    private fun extractXiaohongshuVideoUrl(videoOrImgObj: JsonObject?): String? {
        if (videoOrImgObj == null) return null
        val stream = videoOrImgObj.optObj("media")?.optObj("stream")
            ?: videoOrImgObj.optObj("stream")
        for (codec in listOf("h264", "h265", "av1")) {
            val arr = stream?.optArr(codec)
            if (arr != null && arr.size() > 0) {
                val master = arr.get(0).takeIf { it.isJsonObject }?.asJsonObject?.optStr("masterUrl")
                if (!master.isNullOrBlank()) {
                    return ParserSupport.normalizeMediaUrl(master)
                }
            }
        }
        val originKey = videoOrImgObj.optObj("consumer")?.optStr("originVideoKey")
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

    internal fun isDouyinWatermarkedImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("watermark") ||
            lower.contains("water-v") ||
            lower.contains("water_v") ||
            lower.contains("water-mark") ||
            lower.contains("water_mark") ||
            lower.contains("tplv-dy-water") ||
            lower.contains("dy-water") ||
            lower.contains("aweme-water") ||
            (lower.contains("douyinpic.com") && url.contains("5oqW6Z"))
    }

    private fun collectCleanDouyinImageUrls(arr: JsonArray?): List<String> {
        if (arr == null || arr.size() == 0) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.size()) {
            val el = arr.get(i)
            val u = if (el.isJsonPrimitive) ParserSupport.normalizeMediaUrl(el.asString) else ""
            if (u.startsWith("http") && !isDouyinWatermarkedImageUrl(u)) {
                list.add(u)
            }
        }
        return list
    }

    private fun selectBestDouyinImageUrl(arr: JsonArray?): String? {
        val clean = collectCleanDouyinImageUrls(arr)
        if (clean.isEmpty()) return null
        val isFullRes: (String) -> Boolean = { u ->
            val l = u.lowercase()
            !l.contains("shrink") && !l.contains("100x100") && !l.contains("200x200") && !l.contains("300x300")
        }
        return clean.firstOrNull { isFullRes(it) && !it.contains("tplv-", ignoreCase = true) }
            ?: clean.firstOrNull { isFullRes(it) && (it.contains(".jpeg", true) || it.contains(".jpg", true) || it.contains(".png", true)) }
            ?: clean.firstOrNull { it.contains(".jpeg", true) || it.contains(".jpg", true) || it.contains(".png", true) }
            ?: clean.firstOrNull { isFullRes(it) && it.contains(".webp", true) }
            ?: clean.firstOrNull { isFullRes(it) }
            ?: clean.firstOrNull()
    }

    private fun extractFirstUrlFromHolder(holder: JsonObject?): String? {
        return extractFirstUrlFromArray(holder?.optArr("url_list") ?: holder?.optArr("urlList"))
    }

    private fun extractFirstUrlFromArray(arr: JsonArray?): String? {
        if (arr == null || arr.size() == 0) return null
        for (i in 0 until arr.size()) {
            val el = arr.get(i)
            val u = when {
                el.isJsonPrimitive -> ParserSupport.normalizeMediaUrl(el.asString)
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    ParserSupport.normalizeMediaUrl(
                        obj.optStr("url").takeIf { it.isNotBlank() }
                            ?: obj.optStr("play_url").takeIf { it.isNotBlank() }
                            ?: obj.optStr("src").takeIf { it.isNotBlank() }
                            ?: ""
                    )
                }
                else -> ""
            }
            if (u.startsWith("http")) return u
        }
        return null
    }

    private fun extractHashtags(text: String, textExtraArr: JsonArray?): List<String> {
        val tags = linkedSetOf<String>()
        textExtraArr?.let { arr ->
            for (i in 0 until arr.size()) {
                val name = arr.get(i).takeIf { it.isJsonObject }?.asJsonObject?.optStr("hashtag_name")?.trim()
                if (!name.isNullOrBlank()) tags.add("#$name")
            }
        }
        tags.addAll(ParserSupport.extractHashtagsFromText(text))
        return tags.toList()
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

        val rawMediaItems: List<MediaItem> = runCatching {
            gson.fromJson<List<MediaItem>>(entity.mediaItemsJson, mediaType)
        }.getOrDefault(emptyList())

        val bgm: AudioTrackInfo? = entity.bgmAudioJson?.let {
            runCatching { gson.fromJson(it, AudioTrackInfo::class.java) }.getOrNull()
        }

        val platform = runCatching { PlatformType.valueOf(entity.platform) }
            .getOrDefault(PlatformType.XIAOHONGSHU)

        val mediaItems = if (platform == PlatformType.DOUYIN) {
            rawMediaItems.map { item ->
                if (item.isVideo) return@map item
                val cleanFallbacks = item.fallbackUrls.filter {
                    it.startsWith("http") && !isDouyinWatermarkedImageUrl(it)
                }
                val cleanOrigin = item.originUrl.takeIf { !isDouyinWatermarkedImageUrl(it) }
                    ?: cleanFallbacks.firstOrNull { it.contains(".jpeg", true) || it.contains(".jpg", true) || it.contains(".png", true) }
                    ?: cleanFallbacks.firstOrNull()
                    ?: item.previewUrl.takeIf { it.startsWith("http") && !isDouyinWatermarkedImageUrl(it) }
                if (cleanOrigin != null) {
                    val cleanPreview = item.previewUrl.takeIf { it.startsWith("http") && !isDouyinWatermarkedImageUrl(it) }
                        ?: cleanOrigin
                    item.copy(
                        originUrl = cleanOrigin,
                        previewUrl = cleanPreview,
                        fallbackUrls = if (cleanFallbacks.isNotEmpty()) cleanFallbacks else listOf(cleanOrigin)
                    )
                } else {
                    item
                }
            }
        } else {
            rawMediaItems
        }

        val cleanCoverUrl = if (platform == PlatformType.DOUYIN && isDouyinWatermarkedImageUrl(entity.coverUrl)) {
            mediaItems.firstOrNull()?.previewUrl?.ifBlank { entity.coverUrl } ?: entity.coverUrl
        } else {
            entity.coverUrl
        }

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
            coverUrl = cleanCoverUrl,
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
