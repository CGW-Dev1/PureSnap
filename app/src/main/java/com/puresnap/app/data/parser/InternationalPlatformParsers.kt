package com.puresnap.app.data.parser

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.puresnap.app.data.local.ParseHistoryEntity
import com.puresnap.app.model.AudioTrackInfo
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.MediaKind
import com.puresnap.app.model.ParsedPost
import com.puresnap.app.model.PlatformType
import com.puresnap.app.model.QualityBadge
import com.puresnap.app.util.ShareLinkExtractor
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * 国外主流平台无水印原图/视频解析器：
 * - X / Twitter：FxTwitter / VxTwitter / Syndication + pbs.twimg.com?name=orig 无损原图
 * - Instagram：Embed 状态树 / GraphQL / carousel 原图 & Reels 视频
 * - TikTok：__UNIVERSAL_DATA_FOR_REHYDRATION__ / SIGI_STATE / TikWM 去水印视频与图文模式
 * - Threads：Relay 嵌入式状态树 & Meta 高清原图/视频
 * - Pinterest：__PWS_DATA__ + i.pinimg.com/originals/ 原始文件直连
 * - YouTube：ytInitialPlayerResponse 渐进式 MP4 + maxresdefault 高清封面
 * - Reddit：.json?raw_json=1 官方结构化数据（Gallery 多图画廊 / i.redd.it / v.redd.it）
 */
internal object InternationalPlatformParsers {

    fun parseDirect(
        rawInput: String,
        cleanUrl: String,
        platform: PlatformType,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        return when (platform) {
            PlatformType.TWITTER -> parseTwitterDirect(rawInput, cleanUrl, customCookie, previousHistory)
            PlatformType.INSTAGRAM -> parseInstagramDirect(rawInput, cleanUrl, customCookie, previousHistory)
            PlatformType.TIKTOK -> parseTikTokDirect(rawInput, cleanUrl, customCookie, previousHistory)
            PlatformType.THREADS -> parseThreadsDirect(rawInput, cleanUrl, customCookie, previousHistory)
            PlatformType.PINTEREST -> parsePinterestDirect(rawInput, cleanUrl, customCookie, previousHistory)
            PlatformType.YOUTUBE -> parseYouTubeDirect(rawInput, cleanUrl, customCookie, previousHistory)
            PlatformType.REDDIT -> parseRedditDirect(rawInput, cleanUrl, customCookie, previousHistory)
            else -> null
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 1. X / Twitter：FxTwitter / Syndication / pbs.twimg.com orig
    // ════════════════════════════════════════════════════════════════

    private fun parseTwitterDirect(
        rawInput: String,
        cleanUrl: String,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        var resolvedUrl = cleanUrl
        if (cleanUrl.contains("pbs.twimg.com/media/", ignoreCase = true)) {
            val orig = rewriteTwitterOrigUrl(cleanUrl)
            val id = ShareLinkExtractor.extractWorkId(orig)
            return ParserSupport.applyDuplicateInfo(
                ParsedPost(
                    id = "tw_direct_$id",
                    sourceShareText = rawInput,
                    extractedUrl = cleanUrl,
                    noteId = id,
                    platform = PlatformType.TWITTER,
                    postTypeLabel = "X · orig 原图",
                    authorName = "X / Twitter",
                    authorHandle = "@twitter",
                    authorAvatarUrl = "",
                    coverUrl = orig,
                    title = "X 原图直链 #$id",
                    content = cleanUrl,
                    tags = emptyList(),
                    mediaItems = listOf(
                        MediaItem(
                            id = "${id}_tw_0",
                            index = 0,
                            kind = MediaKind.IMAGE,
                            badge = QualityBadge.RAW,
                            originUrl = orig,
                            previewUrl = orig,
                            fallbackUrls = listOf(orig, cleanUrl).distinct(),
                            width = 2048,
                            height = 2048,
                            format = ParserSupport.detectImageFormat(orig, "JPG"),
                            colorSpace = "Display P3",
                            themeTitle = "X orig 原图"
                        )
                    ),
                    parseEngineSource = "X 引擎 · pbs.twimg.com?name=orig"
                ),
                previousHistory
            )
        }

        if (cleanUrl.contains("t.co/", ignoreCase = true)) {
            ParserSupport.followRedirectsAndFetch(cleanUrl, PlatformType.TWITTER, customCookie)?.let {
                resolvedUrl = it.finalUrl
            }
        }

        val statusId = Regex("""/(?:status|statuses)/(\d+)""").find(resolvedUrl)?.groupValues?.getOrNull(1)
            ?: Regex("""/(?:status|statuses)/(\d+)""").find(cleanUrl)?.groupValues?.getOrNull(1)
            ?: return null

        // Layer A: FxTwitter JSON（最完整支持多图 orig + GIF + 高清 MP4）
        ParserSupport.followRedirectsAndFetch(
            startUrl = "https://api.fxtwitter.com/status/$statusId",
            platform = PlatformType.TWITTER,
            userAgent = ParserSupport.DESKTOP_UA
        )?.let { resp ->
            if (resp.statusCode in 200..299 && resp.html.trimStart().startsWith("{")) {
                parseFxTwitterJson(rawInput, resolvedUrl, resp.html, statusId, previousHistory)?.let { return it }
            }
        }

        // Layer B: VxTwitter JSON
        ParserSupport.followRedirectsAndFetch(
            startUrl = "https://api.vxtwitter.com/status/$statusId",
            platform = PlatformType.TWITTER,
            userAgent = ParserSupport.DESKTOP_UA
        )?.let { resp ->
            if (resp.statusCode in 200..299 && resp.html.trimStart().startsWith("{")) {
                parseVxTwitterJson(rawInput, resolvedUrl, resp.html, statusId, previousHistory)?.let { return it }
            }
        }

        // Layer C: Twitter Syndication API
        ParserSupport.followRedirectsAndFetch(
            startUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$statusId&token=4c2v8p0z1y",
            platform = PlatformType.TWITTER,
            userAgent = ParserSupport.DESKTOP_UA
        )?.let { resp ->
            if (resp.statusCode in 200..299 && resp.html.trimStart().startsWith("{")) {
                parseTwitterSyndicationJson(rawInput, resolvedUrl, resp.html, statusId, previousHistory)?.let { return it }
            }
        }

        return null
    }

    fun parseFxTwitterJson(
        rawInput: String,
        finalUrl: String,
        jsonStr: String,
        statusId: String,
        previousHistory: ParseHistoryEntity? = null
    ): ParsedPost? {
        val root = runCatching { JsonParser.parseString(jsonStr).asJsonObject }.getOrNull() ?: return null
        val tweet = root.optObj("tweet") ?: return null
        val author = tweet.optObj("author")
        val mediaObj = tweet.optObj("media") ?: return null

        val mediaItems = mutableListOf<MediaItem>()
        val allArr = mediaObj.optArr("all")
        if (allArr != null && allArr.size() > 0) {
            for (i in 0 until allArr.size()) {
                val m = allArr.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                addFxMediaItem(m, statusId, mediaItems)
            }
        } else {
            mediaObj.optArr("photos")?.forEach { el ->
                el.takeIf { it.isJsonObject }?.asJsonObject?.let { addFxMediaItem(it, statusId, mediaItems) }
            }
            mediaObj.optArr("videos")?.forEach { el ->
                el.takeIf { it.isJsonObject }?.asJsonObject?.let { addFxMediaItem(it, statusId, mediaItems) }
            }
        }

        if (mediaItems.isEmpty()) return null

        val text = tweet.optStr("text")
        val authorName = author?.optStr("name")?.ifBlank { "X User" } ?: "X User"
        val handle = author?.optStr("screen_name")?.let { "@$it" } ?: "@twitter"

        val post = ParsedPost(
            id = "tw_$statusId",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = statusId,
            platform = PlatformType.TWITTER,
            postTypeLabel = if (mediaItems.any { it.isVideo }) "X · 视频/动图" else "X · orig 原图",
            authorName = authorName,
            authorHandle = handle,
            authorAvatarUrl = author?.optStr("avatar_url")?.let { ParserSupport.normalizeMediaUrl(it) } ?: "",
            coverUrl = mediaItems.first().previewUrl,
            title = text.lineSequence().firstOrNull()?.take(48)?.ifBlank { "$authorName 的推文" }
                ?: "$authorName 的推文",
            content = text,
            tags = ParserSupport.extractHashtagsFromText(text),
            mediaItems = mediaItems,
            parseEngineSource = "X 引擎 · FxTwitter + name=orig"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    private fun addFxMediaItem(m: JsonObject, statusId: String, out: MutableList<MediaItem>) {
        val type = m.optStr("type").lowercase()
        val rawUrl = ParserSupport.normalizeMediaUrl(m.optStr("url"))
        if (!rawUrl.startsWith("http")) return
        val w = m.optInt("width", 1440).takeIf { it > 0 } ?: 1440
        val h = m.optInt("height", 1920).takeIf { it > 0 } ?: 1920
        val idx = out.size

        if (type == "video" || type == "gif") {
            val thumb = m.optStr("thumbnail_url").let { ParserSupport.normalizeMediaUrl(it) }.ifBlank { rawUrl }
            out.add(
                MediaItem(
                    id = "${statusId}_tw_$idx",
                    index = idx,
                    kind = MediaKind.VIDEO,
                    badge = QualityBadge.VIDEO_HD,
                    originUrl = rawUrl,
                    previewUrl = thumb,
                    width = w,
                    height = h,
                    format = "MP4",
                    colorSpace = "BT.709",
                    durationMs = (m.optLong("duration", 0L) * 1000L),
                    themeTitle = if (type == "gif") "动图 MP4" else "高清视频 ${idx + 1}"
                )
            )
        } else {
            val origUrl = rewriteTwitterOrigUrl(rawUrl)
            out.add(
                MediaItem(
                    id = "${statusId}_tw_$idx",
                    index = idx,
                    kind = MediaKind.IMAGE,
                    badge = if (maxOf(w, h) >= 3840) QualityBadge.FOUR_K else QualityBadge.RAW,
                    originUrl = origUrl,
                    previewUrl = rawUrl,
                    fallbackUrls = listOf(origUrl, rawUrl).distinct(),
                    width = w,
                    height = h,
                    format = ParserSupport.detectImageFormat(origUrl, "JPG"),
                    colorSpace = "Display P3",
                    themeTitle = "orig 原图 ${idx + 1}"
                )
            )
        }
    }

    private fun parseVxTwitterJson(
        rawInput: String,
        finalUrl: String,
        jsonStr: String,
        statusId: String,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        val root = runCatching { JsonParser.parseString(jsonStr).asJsonObject }.getOrNull() ?: return null
        val extended = root.optArr("media_extended") ?: return null
        val mediaItems = mutableListOf<MediaItem>()

        for (i in 0 until extended.size()) {
            val m = extended.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val url = ParserSupport.normalizeMediaUrl(m.optStr("url"))
            if (!url.startsWith("http")) continue
            val type = m.optStr("type").lowercase()
            val size = m.optObj("size")
            val w = size?.optInt("width", 1440)?.takeIf { it > 0 } ?: 1440
            val h = size?.optInt("height", 1920)?.takeIf { it > 0 } ?: 1920

            if (type == "video" || type == "gif") {
                val thumb = ParserSupport.normalizeMediaUrl(m.optStr("thumbnail_url")).ifBlank { url }
                mediaItems.add(
                    MediaItem(
                        id = "${statusId}_vx_$i",
                        index = mediaItems.size,
                        kind = MediaKind.VIDEO,
                        badge = QualityBadge.VIDEO_HD,
                        originUrl = url,
                        previewUrl = thumb,
                        width = w,
                        height = h,
                        format = "MP4",
                        colorSpace = "BT.709",
                        themeTitle = "视频 ${mediaItems.size + 1}"
                    )
                )
            } else {
                val orig = rewriteTwitterOrigUrl(url)
                mediaItems.add(
                    MediaItem(
                        id = "${statusId}_vx_$i",
                        index = mediaItems.size,
                        kind = MediaKind.IMAGE,
                        badge = QualityBadge.RAW,
                        originUrl = orig,
                        previewUrl = url,
                        fallbackUrls = listOf(orig, url).distinct(),
                        width = w,
                        height = h,
                        format = ParserSupport.detectImageFormat(orig, "JPG"),
                        colorSpace = "Display P3",
                        themeTitle = "orig 原图 ${mediaItems.size + 1}"
                    )
                )
            }
        }
        if (mediaItems.isEmpty()) return null

        val userName = root.optStr("user_name").ifBlank { "X User" }
        val handle = root.optStr("user_screen_name").let { if (it.isNotBlank()) "@$it" else "@twitter" }
        val text = root.optStr("text")

        val post = ParsedPost(
            id = "tw_$statusId",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = statusId,
            platform = PlatformType.TWITTER,
            postTypeLabel = if (mediaItems.any { it.isVideo }) "X · 视频" else "X · orig 原图",
            authorName = userName,
            authorHandle = handle,
            authorAvatarUrl = ParserSupport.normalizeMediaUrl(root.optStr("user_profile_image_url")),
            coverUrl = mediaItems.first().previewUrl,
            title = text.lineSequence().firstOrNull()?.take(48)?.ifBlank { "$userName 的推文" } ?: "$userName 的推文",
            content = text,
            tags = ParserSupport.extractHashtagsFromText(text),
            mediaItems = mediaItems,
            parseEngineSource = "X 引擎 · VxTwitter + name=orig"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    fun parseTwitterSyndicationJson(
        rawInput: String,
        finalUrl: String,
        jsonStr: String,
        statusId: String,
        previousHistory: ParseHistoryEntity? = null
    ): ParsedPost? {
        val root = runCatching { JsonParser.parseString(jsonStr).asJsonObject }.getOrNull() ?: return null
        val details = root.optArr("mediaDetails") ?: return null
        val mediaItems = mutableListOf<MediaItem>()

        for (i in 0 until details.size()) {
            val d = details.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val type = d.optStr("type").lowercase()
            val rawPhoto = ParserSupport.normalizeMediaUrl(d.optStr("media_url_https"))
            val origInfo = d.optObj("original_info")
            val w = origInfo?.optInt("width", 1440)?.takeIf { it > 0 } ?: 1440
            val h = origInfo?.optInt("height", 1920)?.takeIf { it > 0 } ?: 1920

            if (type == "video" || type == "animated_gif") {
                val variants = d.optObj("video_info")?.optArr("variants")
                var bestMp4 = ""
                var bestBitrate = -1L
                variants?.forEach { vEl ->
                    val v = vEl.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    val ct = v.optStr("content_type")
                    val br = v.optLong("bitrate", 0L)
                    val url = v.optStr("url")
                    if (ct.contains("mp4", ignoreCase = true) && url.startsWith("http") && br >= bestBitrate) {
                        bestBitrate = br
                        bestMp4 = ParserSupport.normalizeMediaUrl(url)
                    }
                }
                if (bestMp4.isNotBlank()) {
                    mediaItems.add(
                        MediaItem(
                            id = "${statusId}_syn_$i",
                            index = mediaItems.size,
                            kind = MediaKind.VIDEO,
                            badge = QualityBadge.VIDEO_HD,
                            originUrl = bestMp4,
                            previewUrl = rawPhoto.ifBlank { bestMp4 },
                            width = w,
                            height = h,
                            format = "MP4",
                            colorSpace = "BT.709",
                            themeTitle = "高清视频 ${mediaItems.size + 1}"
                        )
                    )
                }
            } else if (rawPhoto.startsWith("http")) {
                val orig = rewriteTwitterOrigUrl(rawPhoto)
                mediaItems.add(
                    MediaItem(
                        id = "${statusId}_syn_$i",
                        index = mediaItems.size,
                        kind = MediaKind.IMAGE,
                        badge = if (maxOf(w, h) >= 3840) QualityBadge.FOUR_K else QualityBadge.RAW,
                        originUrl = orig,
                        previewUrl = rawPhoto,
                        fallbackUrls = listOf(orig, rawPhoto).distinct(),
                        width = w,
                        height = h,
                        format = ParserSupport.detectImageFormat(orig, "JPG"),
                        colorSpace = "Display P3",
                        themeTitle = "orig 原图 ${mediaItems.size + 1}"
                    )
                )
            }
        }
        if (mediaItems.isEmpty()) return null

        val user = root.optObj("user")
        val name = user?.optStr("name")?.ifBlank { "X User" } ?: "X User"
        val handle = user?.optStr("screen_name")?.let { "@$it" } ?: "@twitter"
        val text = root.optStr("text")

        val post = ParsedPost(
            id = "tw_$statusId",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = statusId,
            platform = PlatformType.TWITTER,
            postTypeLabel = if (mediaItems.any { it.isVideo }) "X · 视频" else "X · orig 原图",
            authorName = name,
            authorHandle = handle,
            authorAvatarUrl = user?.optStr("profile_image_url_https")?.let { ParserSupport.normalizeMediaUrl(it) } ?: "",
            coverUrl = mediaItems.first().previewUrl,
            title = text.lineSequence().firstOrNull()?.take(48)?.ifBlank { "$name 的推文" } ?: "$name 的推文",
            content = text,
            tags = ParserSupport.extractHashtagsFromText(text),
            mediaItems = mediaItems,
            parseEngineSource = "X 引擎 · Syndication + name=orig"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    fun rewriteTwitterOrigUrl(rawUrl: String): String {
        val norm = ParserSupport.normalizeMediaUrl(rawUrl)
        if (!norm.contains("pbs.twimg.com/", ignoreCase = true)) return norm
        val withoutSizeColon = norm.replace(Regex(":(small|medium|large|thumb|4096x4096|orig)$"), "")
        return if (withoutSizeColon.contains("name=")) {
            withoutSizeColon.replace(Regex("""([?&])name=[^&]+"""), "$1name=orig")
        } else if (withoutSizeColon.contains("?")) {
            "$withoutSizeColon&name=orig"
        } else {
            "$withoutSizeColon?name=orig"
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 2. Instagram：Embed 状态树 / GraphQL / Reels 视频与多图轮播
    // ════════════════════════════════════════════════════════════════

    private fun parseInstagramDirect(
        rawInput: String,
        cleanUrl: String,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        val normalizedUrl = cleanUrl
            .replace("ddinstagram.com", "www.instagram.com", ignoreCase = true)
            .replace("kkinstagram.com", "www.instagram.com", ignoreCase = true)

        val codeMatch = Regex("""instagram\.com/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""").find(normalizedUrl)
        val shortcode = codeMatch?.groupValues?.getOrNull(1)

        if (!shortcode.isNullOrBlank()) {
            // 优先访问 captioned embed 页：免登录经常暴露完整 GraphQL sidecar_to_children 与 video_url
            ParserSupport.followRedirectsAndFetch(
                startUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/",
                platform = PlatformType.INSTAGRAM,
                customCookie = customCookie,
                userAgent = ParserSupport.DESKTOP_UA
            )?.let { resp ->
                if (resp.statusCode in 200..299 && resp.html.isNotBlank()) {
                    parseInstagramHtmlOrJson(
                        rawInput = rawInput,
                        finalUrl = normalizedUrl,
                        body = resp.html,
                        workId = shortcode,
                        previousHistory = previousHistory
                    )?.let { return it }
                }
            }
        }

        val pageResp = ParserSupport.followRedirectsAndFetch(
            startUrl = normalizedUrl,
            platform = PlatformType.INSTAGRAM,
            customCookie = customCookie,
            userAgent = ParserSupport.DESKTOP_UA
        ) ?: return null

        return parseInstagramHtmlOrJson(
            rawInput = rawInput,
            finalUrl = pageResp.finalUrl,
            body = pageResp.html,
            workId = shortcode ?: ShareLinkExtractor.extractWorkId(pageResp.finalUrl),
            previousHistory = previousHistory
        )
    }

    fun parseInstagramHtmlOrJson(
        rawInput: String,
        finalUrl: String,
        body: String,
        workId: String,
        previousHistory: ParseHistoryEntity? = null
    ): ParsedPost? {
        val mediaItems = mutableListOf<MediaItem>()

        // 1) 尝试提取嵌在 JS 里的 JSON（gql_data / shortcode_media / items）
        val jsonCandidate = ParserSupport.extractBalancedJsonObject(body, "\"shortcode_media\":")
            ?: ParserSupport.extractBalancedJsonObject(body, "\"gql_data\":")
            ?: body.trimStart().takeIf { it.startsWith("{") }

        var authorName = "Instagram"
        var captionText = ""
        if (jsonCandidate != null) {
            val parsedObj = runCatching { JsonParser.parseString(jsonCandidate).asJsonObject }.getOrNull()
            val sc = parsedObj?.optObj("shortcode_media") ?: parsedObj?.optObj("gql_data")?.optObj("shortcode_media") ?: parsedObj
            if (sc != null) {
                sc.optObj("owner")?.optStr("username")?.takeIf { it.isNotBlank() }?.let { authorName = it }
                captionText = sc.optObj("edge_media_to_caption")
                    ?.optArr("edges")
                    ?.takeIf { it.size() > 0 }
                    ?.get(0)?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.optObj("node")?.optStr("text").orEmpty()

                val sidecar = sc.optObj("edge_sidecar_to_children")?.optArr("edges")
                if (sidecar != null && sidecar.size() > 0) {
                    for (i in 0 until sidecar.size()) {
                        val node = sidecar.get(i).takeIf { it.isJsonObject }?.asJsonObject?.optObj("node") ?: continue
                        extractIgShortcodeNode(node, workId, mediaItems)
                    }
                } else {
                    extractIgShortcodeNode(sc, workId, mediaItems)
                }
            }
        }

        // 2) Embed HTML EmbeddedMediaImage & video 正则兜底
        if (mediaItems.isEmpty()) {
            val videoMatcher = Pattern.compile("""["'](https?:[^"']+?\.mp4[^"']*)["']""").matcher(body)
            while (videoMatcher.find()) {
                val u = ParserSupport.normalizeMediaUrl(videoMatcher.group(1) ?: "")
                if (u.startsWith("http") && !mediaItems.any { it.originUrl == u }) {
                    mediaItems.add(
                        MediaItem(
                            id = "${workId}_ig_vid_${mediaItems.size}",
                            index = mediaItems.size,
                            kind = MediaKind.VIDEO,
                            badge = QualityBadge.VIDEO_HD,
                            originUrl = u,
                            previewUrl = u,
                            width = 1080,
                            height = 1920,
                            format = "MP4",
                            colorSpace = "BT.709",
                            themeTitle = "Reels 视频"
                        )
                    )
                }
            }
            val imgMatcher = Pattern.compile("""<img[^>]+class=["'][^"']*EmbeddedMediaImage[^"']*["'][^>]+src=["']([^"']+)["']""")
                .matcher(body)
            while (imgMatcher.find()) {
                val u = ParserSupport.normalizeMediaUrl(imgMatcher.group(1) ?: "")
                if (u.startsWith("http") && !mediaItems.any { it.originUrl == u }) {
                    mediaItems.add(
                        MediaItem(
                            id = "${workId}_ig_img_${mediaItems.size}",
                            index = mediaItems.size,
                            kind = MediaKind.IMAGE,
                            badge = QualityBadge.RAW,
                            originUrl = u,
                            previewUrl = u,
                            width = 1440,
                            height = 1800,
                            format = ParserSupport.detectImageFormat(u, "JPG"),
                            colorSpace = "Display P3",
                            themeTitle = "原图 ${mediaItems.size + 1}"
                        )
                    )
                }
            }
        }

        if (mediaItems.isEmpty()) return null
        val ogTitle = ParserSupport.extractMetaTags(body, "og:title").firstOrNull().orEmpty()
        val finalTitle = captionText.lineSequence().firstOrNull()?.take(48)
            ?: ogTitle.ifBlank { "Instagram 作品 #$workId" }

        val post = ParsedPost(
            id = "ig_$workId",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = workId,
            platform = PlatformType.INSTAGRAM,
            postTypeLabel = if (mediaItems.any { it.isVideo }) "IG · 视频/Reels" else "IG · 原图",
            authorName = authorName,
            authorHandle = "@$authorName",
            authorAvatarUrl = "",
            coverUrl = mediaItems.first().previewUrl,
            title = finalTitle,
            content = captionText.ifBlank { ogTitle },
            tags = ParserSupport.extractHashtagsFromText(captionText),
            mediaItems = mediaItems,
            parseEngineSource = "Instagram 引擎 · 原图/Reels 直连"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    private fun extractIgShortcodeNode(node: JsonObject, workId: String, out: MutableList<MediaItem>) {
        val isVideo = node.optBool("is_video", false)
        val videoUrl = ParserSupport.normalizeMediaUrl(node.optStr("video_url"))
        val displayResources = node.optArr("display_resources")
        var bestImg = ""
        var bestW = 1440
        var bestH = 1800
        if (displayResources != null && displayResources.size() > 0) {
            var maxArea = -1L
            for (i in 0 until displayResources.size()) {
                val r = displayResources.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val w = r.optInt("config_width", 0)
                val h = r.optInt("config_height", 0)
                val src = r.optStr("src")
                val area = w.toLong() * h.toLong()
                if (src.startsWith("http") && area >= maxArea) {
                    maxArea = area
                    bestImg = ParserSupport.normalizeMediaUrl(src)
                    bestW = w.takeIf { it > 0 } ?: 1440
                    bestH = h.takeIf { it > 0 } ?: 1800
                }
            }
        }
        if (bestImg.isBlank()) {
            bestImg = ParserSupport.normalizeMediaUrl(node.optStr("display_url"))
        }

        val idx = out.size
        if (isVideo && videoUrl.startsWith("http")) {
            out.add(
                MediaItem(
                    id = "${workId}_ig_$idx",
                    index = idx,
                    kind = MediaKind.VIDEO,
                    badge = QualityBadge.VIDEO_HD,
                    originUrl = videoUrl,
                    previewUrl = bestImg.ifBlank { videoUrl },
                    width = bestW,
                    height = bestH,
                    format = "MP4",
                    colorSpace = "BT.709",
                    themeTitle = "Reels 视频 ${idx + 1}"
                )
            )
        } else if (bestImg.startsWith("http")) {
            out.add(
                MediaItem(
                    id = "${workId}_ig_$idx",
                    index = idx,
                    kind = MediaKind.IMAGE,
                    badge = QualityBadge.RAW,
                    originUrl = bestImg,
                    previewUrl = bestImg,
                    width = bestW,
                    height = bestH,
                    format = ParserSupport.detectImageFormat(bestImg, "JPG"),
                    colorSpace = "Display P3",
                    themeTitle = "原图 ${idx + 1}"
                )
            )
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 3. TikTok：__UNIVERSAL_DATA_FOR_REHYDRATION__ / TikWM 无水印与图文模式
    // ════════════════════════════════════════════════════════════════

    private fun parseTikTokDirect(
        rawInput: String,
        cleanUrl: String,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        var resolvedUrl = cleanUrl
        val pageResp = ParserSupport.followRedirectsAndFetch(cleanUrl, PlatformType.TIKTOK, customCookie, userAgent = ParserSupport.DESKTOP_UA)
        if (pageResp != null) {
            resolvedUrl = pageResp.finalUrl
            parseTikTokHtmlState(rawInput, resolvedUrl, pageResp.html, previousHistory)?.let { return it }
        }

        // 回退至 TikWM 公开去水印接口（支持无水印 hdplay + Photo Mode 图片数组）
        val encoded = URLEncoder.encode(resolvedUrl, "UTF-8")
        ParserSupport.followRedirectsAndFetch(
            startUrl = "https://www.tikwm.com/api/?url=$encoded&hd=1",
            platform = PlatformType.TIKTOK,
            userAgent = ParserSupport.DESKTOP_UA
        )?.let { apiResp ->
            if (apiResp.statusCode in 200..299 && apiResp.html.trimStart().startsWith("{")) {
                parseTikWmJson(rawInput, resolvedUrl, apiResp.html, previousHistory)?.let { return it }
            }
        }
        return null
    }

    fun parseTikTokHtmlState(
        rawInput: String,
        finalUrl: String,
        html: String,
        previousHistory: ParseHistoryEntity? = null
    ): ParsedPost? {
        val rehydrationJson = ParserSupport.extractBalancedJsonObject(html, "id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\" type=\"application/json\">")
            ?: ParserSupport.extractBalancedJsonObject(html, "__UNIVERSAL_DATA_FOR_REHYDRATION__\">")
            ?: ParserSupport.extractBalancedJsonObject(html, "SIGI_STATE\">")
            ?: return null

        val root = runCatching { JsonParser.parseString(rehydrationJson).asJsonObject }.getOrNull() ?: return null
        val itemStruct = root.optObj("__DEFAULT_SCOPE__")
            ?.optObj("webapp.video-detail")
            ?.optObj("itemInfo")
            ?.optObj("itemStruct")
            ?: root.optObj("ItemModule")?.entrySet()?.firstOrNull()?.value?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return null

        val workId = itemStruct.optStr("id").ifBlank { ShareLinkExtractor.extractWorkId(finalUrl) }
        val desc = itemStruct.optStr("desc")
        val author = itemStruct.optObj("author")
        val authorName = author?.optStr("nickname")?.ifBlank { "TikTok Creator" } ?: "TikTok Creator"
        val handle = author?.optStr("uniqueId")?.let { "@$it" } ?: "@tiktok"
        val avatarUrl = author?.optStr("avatarLarger")?.let { ParserSupport.normalizeMediaUrl(it) } ?: ""

        val music = itemStruct.optObj("music")
        val bgmAudio = music?.optStr("playUrl")?.takeIf { it.startsWith("http") }?.let { url ->
            AudioTrackInfo(
                id = "${workId}_tt_bgm",
                title = music.optStr("title").ifBlank { "TikTok BGM" },
                author = music.optStr("authorName").ifBlank { authorName },
                url = ParserSupport.normalizeMediaUrl(url),
                format = "MP3",
                durationSec = music.optInt("duration", 30)
            )
        }

        val mediaItems = mutableListOf<MediaItem>()

        // TikTok 图文模式（Photo Mode）
        itemStruct.optObj("imagePost")?.optArr("images")?.let { imgs ->
            for (i in 0 until imgs.size()) {
                val imgObj = imgs.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val urlList = imgObj.optObj("imageURL")?.optArr("urlList")
                val url = urlList?.takeIf { it.size() > 0 }?.get(0)?.asString
                    ?.let { ParserSupport.normalizeMediaUrl(it) }.orEmpty()
                if (!url.startsWith("http")) continue
                val w = imgObj.optInt("imageWidth", 1080).takeIf { it > 0 } ?: 1080
                val h = imgObj.optInt("imageHeight", 1920).takeIf { it > 0 } ?: 1920
                mediaItems.add(
                    MediaItem(
                        id = "${workId}_tt_img_$i",
                        index = i,
                        kind = MediaKind.IMAGE,
                        badge = QualityBadge.RAW,
                        originUrl = url,
                        previewUrl = url,
                        width = w,
                        height = h,
                        format = ParserSupport.detectImageFormat(url, "JPG"),
                        colorSpace = "Display P3",
                        themeTitle = "图文原图 ${i + 1}"
                    )
                )
            }
        }

        // TikTok 视频模式
        if (mediaItems.isEmpty()) {
            val vid = itemStruct.optObj("video")
            val candidates = mutableListOf<String>()
            vid?.optStr("downloadAddr")?.takeIf { it.startsWith("http") }?.let { candidates.add(ParserSupport.normalizeMediaUrl(it)) }
            vid?.optStr("playAddr")?.takeIf { it.startsWith("http") }?.let { candidates.add(ParserSupport.normalizeMediaUrl(it)) }
            vid?.optArr("bitrateInfo")?.forEach { bEl ->
                bEl.takeIf { it.isJsonObject }?.asJsonObject
                    ?.optObj("PlayAddr")?.optArr("UrlList")?.takeIf { it.size() > 0 }
                    ?.get(0)?.asString?.takeIf { it.startsWith("http") }
                    ?.let { candidates.add(ParserSupport.normalizeMediaUrl(it)) }
            }
            val bestVideo = candidates.firstOrNull().orEmpty()
            if (bestVideo.startsWith("http")) {
                val cover = vid?.optStr("originCover")?.ifBlank { vid.optStr("cover") }
                    ?.let { ParserSupport.normalizeMediaUrl(it) } ?: bestVideo
                mediaItems.add(
                    MediaItem(
                        id = "${workId}_tt_vid",
                        index = 0,
                        kind = MediaKind.VIDEO,
                        badge = QualityBadge.VIDEO_HD,
                        originUrl = bestVideo,
                        previewUrl = cover,
                        fallbackUrls = candidates.distinct(),
                        width = vid?.optInt("width", 1080)?.takeIf { it > 0 } ?: 1080,
                        height = vid?.optInt("height", 1920)?.takeIf { it > 0 } ?: 1920,
                        format = "MP4",
                        colorSpace = "BT.709",
                        durationMs = (vid?.optLong("duration", 0L) ?: 0L) * 1000L,
                        themeTitle = "高清视频"
                    )
                )
            }
        }

        if (mediaItems.isEmpty()) return null
        val post = ParsedPost(
            id = "tt_$workId",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = workId,
            platform = PlatformType.TIKTOK,
            postTypeLabel = if (mediaItems.any { it.isVideo }) "TikTok · 视频" else "TikTok · 图集原图",
            authorName = authorName,
            authorHandle = handle,
            authorAvatarUrl = avatarUrl,
            coverUrl = mediaItems.first().previewUrl,
            title = desc.lineSequence().firstOrNull()?.take(48)?.ifBlank { "$authorName 的 TikTok" }
                ?: "$authorName 的 TikTok",
            content = desc,
            tags = ParserSupport.extractHashtagsFromText(desc),
            mediaItems = mediaItems,
            bgmAudio = bgmAudio,
            parseEngineSource = "TikTok 引擎 · Rehydration 状态树"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    fun parseTikWmJson(
        rawInput: String,
        finalUrl: String,
        jsonStr: String,
        previousHistory: ParseHistoryEntity? = null
    ): ParsedPost? {
        val root = runCatching { JsonParser.parseString(jsonStr).asJsonObject }.getOrNull() ?: return null
        if (root.optInt("code", -1) != 0) return null
        val data = root.optObj("data") ?: return null
        val workId = data.optStr("id").ifBlank { ShareLinkExtractor.extractWorkId(finalUrl) }
        val title = data.optStr("title")
        val author = data.optObj("author")
        val authorName = author?.optStr("nickname")?.ifBlank { "TikTok Creator" } ?: "TikTok Creator"
        val handle = author?.optStr("unique_id")?.let { "@$it" } ?: "@tiktok"
        val avatarUrl = author?.optStr("avatar")?.let { ParserSupport.normalizeMediaUrl(it) } ?: ""

        val bgmUrl = data.optStr("music").let { ParserSupport.normalizeMediaUrl(it) }
        val bgmAudio = if (bgmUrl.startsWith("http")) {
            val musicInfo = data.optObj("music_info")
            AudioTrackInfo(
                id = "${workId}_tt_bgm",
                title = musicInfo?.optStr("title")?.ifBlank { "TikTok BGM" } ?: "TikTok BGM",
                author = musicInfo?.optStr("author")?.ifBlank { authorName } ?: authorName,
                url = bgmUrl,
                format = "MP3",
                durationSec = data.optInt("duration", 30)
            )
        } else null

        val mediaItems = mutableListOf<MediaItem>()
        data.optArr("images")?.let { imgs ->
            for (i in 0 until imgs.size()) {
                val u = ParserSupport.normalizeMediaUrl(imgs.get(i).asString)
                if (!u.startsWith("http")) continue
                mediaItems.add(
                    MediaItem(
                        id = "${workId}_tikwm_img_$i",
                        index = i,
                        kind = MediaKind.IMAGE,
                        badge = QualityBadge.RAW,
                        originUrl = u,
                        previewUrl = u,
                        width = 1080,
                        height = 1920,
                        format = ParserSupport.detectImageFormat(u, "JPG"),
                        colorSpace = "Display P3",
                        themeTitle = "图文原图 ${i + 1}"
                    )
                )
            }
        }

        if (mediaItems.isEmpty()) {
            val hdPlay = ParserSupport.normalizeMediaUrl(data.optStr("hdplay"))
            val play = ParserSupport.normalizeMediaUrl(data.optStr("play"))
            val chosen = hdPlay.ifBlank { play }
            if (chosen.startsWith("http")) {
                val cover = ParserSupport.normalizeMediaUrl(data.optStr("origin_cover"))
                    .ifBlank { ParserSupport.normalizeMediaUrl(data.optStr("cover")) }
                    .ifBlank { chosen }
                mediaItems.add(
                    MediaItem(
                        id = "${workId}_tikwm_vid",
                        index = 0,
                        kind = MediaKind.VIDEO,
                        badge = QualityBadge.VIDEO_HD,
                        originUrl = chosen,
                        previewUrl = cover,
                        fallbackUrls = listOf(hdPlay, play).filter { it.startsWith("http") }.distinct(),
                        width = 1080,
                        height = 1920,
                        format = "MP4",
                        colorSpace = "BT.709",
                        durationMs = data.optLong("duration", 0L) * 1000L,
                        themeTitle = "无水印 HD 视频"
                    )
                )
            }
        }

        if (mediaItems.isEmpty()) return null
        val post = ParsedPost(
            id = "tt_$workId",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = workId,
            platform = PlatformType.TIKTOK,
            postTypeLabel = if (mediaItems.any { it.isVideo }) "TikTok · 无水印视频" else "TikTok · 图集原图",
            authorName = authorName,
            authorHandle = handle,
            authorAvatarUrl = avatarUrl,
            coverUrl = mediaItems.first().previewUrl,
            title = title.lineSequence().firstOrNull()?.take(48)?.ifBlank { "$authorName 的 TikTok" }
                ?: "$authorName 的 TikTok",
            content = title,
            tags = ParserSupport.extractHashtagsFromText(title),
            mediaItems = mediaItems,
            bgmAudio = bgmAudio,
            parseEngineSource = "TikTok 引擎 · 无水印原片直出"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    // ════════════════════════════════════════════════════════════════
    // 4. Threads：Relay JSON / Meta 原图与视频提取
    // ════════════════════════════════════════════════════════════════

    private fun parseThreadsDirect(
        rawInput: String,
        cleanUrl: String,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        val resp = ParserSupport.followRedirectsAndFetch(
            startUrl = cleanUrl,
            platform = PlatformType.THREADS,
            customCookie = customCookie,
            userAgent = ParserSupport.DESKTOP_UA
        ) ?: return null
        return parseThreadsHtml(rawInput, resp.finalUrl, resp.html, previousHistory)
    }

    fun parseThreadsHtml(
        rawInput: String,
        finalUrl: String,
        html: String,
        previousHistory: ParseHistoryEntity? = null
    ): ParsedPost? {
        val workId = ShareLinkExtractor.extractWorkId(finalUrl)
        val mediaItems = mutableListOf<MediaItem>()

        // 尝试从嵌入式 JSON 中提取 carousel_media / image_versions2 / video_versions
        val postJson = ParserSupport.extractBalancedJsonObject(html, "\"image_versions2\":")
        if (postJson != null) {
            runCatching { JsonParser.parseString("{\"image_versions2\":$postJson}").asJsonObject }.getOrNull()?.let { obj ->
                val candidates = obj.optObj("image_versions2")?.optArr("candidates")
                val bestImg = selectLargestIgCandidate(candidates)
                if (!bestImg.isNullOrBlank()) {
                    mediaItems.add(
                        MediaItem(
                            id = "${workId}_th_0",
                            index = 0,
                            kind = MediaKind.IMAGE,
                            badge = QualityBadge.RAW,
                            originUrl = bestImg,
                            previewUrl = bestImg,
                            width = 1440,
                            height = 1800,
                            format = ParserSupport.detectImageFormat(bestImg, "JPG"),
                            colorSpace = "Display P3",
                            themeTitle = "Threads 原图"
                        )
                    )
                }
            }
        }

        if (mediaItems.isEmpty()) {
            val ogVideos = ParserSupport.extractMetaTags(html, "og:video")
            val ogImages = ParserSupport.extractMetaTags(html, "og:image")
            ogVideos.firstOrNull { it.startsWith("http") }?.let { v ->
                val poster = ogImages.firstOrNull { it.startsWith("http") } ?: v
                mediaItems.add(
                    MediaItem(
                        id = "${workId}_th_vid",
                        index = 0,
                        kind = MediaKind.VIDEO,
                        badge = QualityBadge.VIDEO_HD,
                        originUrl = ParserSupport.normalizeMediaUrl(v),
                        previewUrl = ParserSupport.normalizeMediaUrl(poster),
                        width = 1080,
                        height = 1920,
                        format = "MP4",
                        colorSpace = "BT.709",
                        themeTitle = "Threads 视频"
                    )
                )
            }
            if (mediaItems.isEmpty()) {
                ogImages.filter { it.startsWith("http") && !it.contains("s150x150") }.forEachIndexed { idx, img ->
                    val norm = ParserSupport.normalizeMediaUrl(img)
                    mediaItems.add(
                        MediaItem(
                            id = "${workId}_th_img_$idx",
                            index = idx,
                            kind = MediaKind.IMAGE,
                            badge = QualityBadge.RAW,
                            originUrl = norm,
                            previewUrl = norm,
                            width = 1440,
                            height = 1800,
                            format = ParserSupport.detectImageFormat(norm, "JPG"),
                            colorSpace = "Display P3",
                            themeTitle = "Threads 原图 ${idx + 1}"
                        )
                    )
                }
            }
        }

        if (mediaItems.isEmpty()) return null
        val ogTitle = ParserSupport.extractMetaTags(html, "og:title").firstOrNull().orEmpty()
        val ogDesc = ParserSupport.extractMetaTags(html, "og:description").firstOrNull().orEmpty()

        val post = ParsedPost(
            id = "th_$workId",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = workId,
            platform = PlatformType.THREADS,
            postTypeLabel = if (mediaItems.any { it.isVideo }) "Threads · 视频" else "Threads · 原图",
            authorName = ogTitle.substringBefore(" (").ifBlank { "Threads User" },
            authorHandle = "@threads",
            authorAvatarUrl = "",
            coverUrl = mediaItems.first().previewUrl,
            title = ogTitle.ifBlank { "Threads 内容 #$workId" },
            content = ogDesc,
            tags = ParserSupport.extractHashtagsFromText(ogDesc),
            mediaItems = mediaItems,
            parseEngineSource = "Threads 引擎 · 原图/视频直连"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    private fun selectLargestIgCandidate(arr: com.google.gson.JsonArray?): String? {
        if (arr == null || arr.size() == 0) return null
        var best = ""
        var maxArea = -1L
        for (i in 0 until arr.size()) {
            val c = arr.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val u = c.optStr("url")
            val area = c.optLong("width", 0L) * c.optLong("height", 0L)
            if (u.startsWith("http") && area >= maxArea) {
                maxArea = area
                best = ParserSupport.normalizeMediaUrl(u)
            }
        }
        return best.takeIf { it.isNotBlank() }
    }

    // ════════════════════════════════════════════════════════════════
    // 5. Pinterest：__PWS_DATA__ + i.pinimg.com/originals/ 无损原始图
    // ════════════════════════════════════════════════════════════════

    private fun parsePinterestDirect(
        rawInput: String,
        cleanUrl: String,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        if (cleanUrl.contains("pinimg.com", ignoreCase = true)) {
            val orig = rewritePinterestOriginUrl(cleanUrl)
            val id = ShareLinkExtractor.extractWorkId(orig)
            return ParserSupport.applyDuplicateInfo(
                ParsedPost(
                    id = "pin_direct_$id",
                    sourceShareText = rawInput,
                    extractedUrl = cleanUrl,
                    noteId = id,
                    platform = PlatformType.PINTEREST,
                    postTypeLabel = "Pinterest · originals 原图",
                    authorName = "Pinterest",
                    authorHandle = "@pinterest",
                    authorAvatarUrl = "",
                    coverUrl = orig,
                    title = "Pinterest 原图 #$id",
                    content = cleanUrl,
                    tags = emptyList(),
                    mediaItems = listOf(
                        MediaItem(
                            id = "${id}_pin_0",
                            index = 0,
                            kind = MediaKind.IMAGE,
                            badge = QualityBadge.RAW,
                            originUrl = orig,
                            previewUrl = cleanUrl,
                            fallbackUrls = listOf(orig, cleanUrl).distinct(),
                            width = 2000,
                            height = 2800,
                            format = ParserSupport.detectImageFormat(orig, "JPG"),
                            colorSpace = "Display P3",
                            themeTitle = "originals 无损原图"
                        )
                    ),
                    parseEngineSource = "Pinterest 引擎 · originals/ 直出"
                ),
                previousHistory
            )
        }

        val pageResp = ParserSupport.followRedirectsAndFetch(
            startUrl = cleanUrl,
            platform = PlatformType.PINTEREST,
            customCookie = customCookie,
            userAgent = ParserSupport.DESKTOP_UA
        ) ?: return null

        val pinId = Regex("""/pin/(\d+)""").find(pageResp.finalUrl)?.groupValues?.getOrNull(1)
            ?: ShareLinkExtractor.extractWorkId(pageResp.finalUrl)

        val pwsJson = ParserSupport.extractBalancedJsonObject(pageResp.html, "id=\"__PWS_DATA__\" type=\"application/json\">")
            ?: ParserSupport.extractBalancedJsonObject(pageResp.html, "__PWS_DATA__\">")
            ?: ParserSupport.extractBalancedJsonObject(pageResp.html, "__PWS_INITIAL_PROPS__")

        if (pwsJson != null) {
            parsePinterestPwsJson(rawInput, pageResp.finalUrl, pwsJson, pinId, previousHistory)?.let { return it }
        }

        // OG image 升级 originals/ 兜底
        val ogImg = ParserSupport.extractMetaTags(pageResp.html, "og:image")
            .firstOrNull { it.contains("pinimg.com") } ?: return null
        val origUrl = rewritePinterestOriginUrl(ogImg)
        val ogTitle = ParserSupport.extractMetaTags(pageResp.html, "og:title").firstOrNull()
            ?: "Pinterest Pin #$pinId"

        return ParserSupport.applyDuplicateInfo(
            ParsedPost(
                id = "pin_$pinId",
                sourceShareText = rawInput,
                extractedUrl = pageResp.finalUrl,
                noteId = pinId,
                platform = PlatformType.PINTEREST,
                postTypeLabel = "Pinterest · originals 原图",
                authorName = "Pinterest",
                authorHandle = "@pinterest",
                authorAvatarUrl = "",
                coverUrl = ogImg,
                title = ogTitle,
                content = ParserSupport.extractMetaTags(pageResp.html, "og:description").firstOrNull().orEmpty(),
                tags = emptyList(),
                mediaItems = listOf(
                    MediaItem(
                        id = "${pinId}_pin_og",
                        index = 0,
                        kind = MediaKind.IMAGE,
                        badge = QualityBadge.RAW,
                        originUrl = origUrl,
                        previewUrl = ogImg,
                        fallbackUrls = listOf(origUrl, ogImg).distinct(),
                        width = 1600,
                        height = 2400,
                        format = ParserSupport.detectImageFormat(origUrl, "JPG"),
                        colorSpace = "Display P3",
                        themeTitle = "originals 无损原图"
                    )
                ),
                parseEngineSource = "Pinterest 引擎 · originals/ 映射"
            ),
            previousHistory
        )
    }

    fun parsePinterestPwsJson(
        rawInput: String,
        finalUrl: String,
        jsonStr: String,
        pinId: String,
        previousHistory: ParseHistoryEntity? = null
    ): ParsedPost? {
        val root = runCatching { JsonParser.parseString(jsonStr).asJsonObject }.getOrNull() ?: return null
        val pinObj = findPinterestPinObject(root, pinId) ?: return null
        val resolvedId = pinObj.optStr("id").ifBlank { pinId }
        val mediaItems = mutableListOf<MediaItem>()

        // 1) 视频 Pin
        pinObj.optObj("videos")?.optObj("video_list")?.let { vList ->
            val preferredKeys = listOf("V_720P", "V_1080P", "V_EXP7", "V_EXP6", "V_EXP5", "V_EXP4", "V_EXP3")
            var chosenVid: JsonObject? = null
            for (k in preferredKeys) {
                val cand = vList.optObj(k)
                if (cand != null && cand.optStr("url").endsWith(".mp4", ignoreCase = true)) {
                    chosenVid = cand
                    break
                }
            }
            if (chosenVid == null) {
                for ((_, vEl) in vList.entrySet()) {
                    val cand = vEl.takeIf { it.isJsonObject }?.asJsonObject
                    if (cand != null && cand.optStr("url").endsWith(".mp4", ignoreCase = true)) {
                        chosenVid = cand
                        break
                    }
                }
            }
            if (chosenVid != null) {
                val vidUrl = ParserSupport.normalizeMediaUrl(chosenVid.optStr("url"))
                val thumb = ParserSupport.normalizeMediaUrl(chosenVid.optStr("thumbnail")).ifBlank { vidUrl }
                mediaItems.add(
                    MediaItem(
                        id = "${resolvedId}_pin_vid",
                        index = 0,
                        kind = MediaKind.VIDEO,
                        badge = QualityBadge.VIDEO_HD,
                        originUrl = vidUrl,
                        previewUrl = thumb,
                        width = chosenVid.optInt("width", 1080).takeIf { it > 0 } ?: 1080,
                        height = chosenVid.optInt("height", 1920).takeIf { it > 0 } ?: 1920,
                        format = "MP4",
                        colorSpace = "BT.709",
                        durationMs = chosenVid.optLong("duration", 0L),
                        themeTitle = "Pinterest 视频"
                    )
                )
            }
        }

        // 2) 原图 (images.orig)
        val imagesObj = pinObj.optObj("images")
        val origNode = imagesObj?.optObj("orig") ?: imagesObj?.optObj("736x") ?: imagesObj?.optObj("564x")
        val rawImgUrl = origNode?.optStr("url")?.let { ParserSupport.normalizeMediaUrl(it) }.orEmpty()
        if (rawImgUrl.startsWith("http")) {
            val origUrl = rewritePinterestOriginUrl(rawImgUrl)
            val w = origNode?.optInt("width", 1440)?.takeIf { it > 0 } ?: 1440
            val h = origNode?.optInt("height", 2160)?.takeIf { it > 0 } ?: 2160
            mediaItems.add(
                MediaItem(
                    id = "${resolvedId}_pin_orig",
                    index = mediaItems.size,
                    kind = MediaKind.IMAGE,
                    badge = if (maxOf(w, h) >= 3840) QualityBadge.FOUR_K else QualityBadge.RAW,
                    originUrl = origUrl,
                    previewUrl = rawImgUrl,
                    fallbackUrls = listOf(origUrl, rawImgUrl).distinct(),
                    width = w,
                    height = h,
                    format = ParserSupport.detectImageFormat(origUrl, "JPG"),
                    colorSpace = "Display P3",
                    themeTitle = if (mediaItems.isEmpty()) "originals 无损原图" else "高清封面原图"
                )
            )
        }

        // 3) Idea Pin 多页图片 (story_pin_data)
        pinObj.optObj("story_pin_data")?.optArr("pages")?.let { pages ->
            for (pIdx in 0 until pages.size()) {
                val page = pages.get(pIdx).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                page.optArr("blocks")?.forEach { bEl ->
                    val imgNode = bEl.takeIf { it.isJsonObject }?.asJsonObject?.optObj("image")
                    val orig = imgNode?.optObj("images")?.optObj("originals")?.optStr("url")
                        ?.let { ParserSupport.normalizeMediaUrl(it) }
                        ?.let { rewritePinterestOriginUrl(it) }
                    if (!orig.isNullOrBlank() && !mediaItems.any { it.originUrl == orig }) {
                        mediaItems.add(
                            MediaItem(
                                id = "${resolvedId}_pin_story_${mediaItems.size}",
                                index = mediaItems.size,
                                kind = MediaKind.IMAGE,
                                badge = QualityBadge.RAW,
                                originUrl = orig,
                                previewUrl = orig,
                                width = 1440,
                                height = 1920,
                                format = ParserSupport.detectImageFormat(orig, "JPG"),
                                colorSpace = "Display P3",
                                themeTitle = "图集页 ${mediaItems.size + 1}"
                            )
                        )
                    }
                }
            }
        }

        if (mediaItems.isEmpty()) return null
        val pinner = pinObj.optObj("pinner") ?: pinObj.optObj("native_creator")
        val authorName = pinner?.optStr("full_name")?.ifBlank { pinner.optStr("username") }?.ifBlank { "Pinterest Creator" }
            ?: "Pinterest Creator"
        val title = pinObj.optStr("title").ifBlank { pinObj.optStr("grid_title") }
            .ifBlank { pinObj.optStr("description").take(48) }
            .ifBlank { "Pinterest Pin #$resolvedId" }
        val desc = pinObj.optStr("description")

        val post = ParsedPost(
            id = "pin_$resolvedId",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = resolvedId,
            platform = PlatformType.PINTEREST,
            postTypeLabel = if (mediaItems.any { it.isVideo }) "Pinterest · 视频/原图" else "Pinterest · originals 原图",
            authorName = authorName,
            authorHandle = pinner?.optStr("username")?.let { "@$it" } ?: "@pinterest",
            authorAvatarUrl = pinner?.optStr("image_medium_url")?.let { ParserSupport.normalizeMediaUrl(it) } ?: "",
            coverUrl = mediaItems.first().previewUrl,
            title = title,
            content = desc,
            tags = ParserSupport.extractHashtagsFromText(desc),
            mediaItems = mediaItems,
            parseEngineSource = "Pinterest 引擎 · __PWS_DATA__ + originals"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    private fun findPinterestPinObject(node: JsonElement?, targetPinId: String): JsonObject? {
        if (node == null || node.isJsonNull) return null
        if (node.isJsonObject) {
            val obj = node.asJsonObject
            obj.optObj("initialReduxState")?.optObj("pins")?.let { pins ->
                if (targetPinId.isNotBlank() && pins.optObj(targetPinId) != null) {
                    return pins.optObj(targetPinId)
                }
                pins.entrySet().firstOrNull()?.value?.takeIf { it.isJsonObject }?.asJsonObject?.let { return it }
            }
            if (obj.has("images") && (obj.optObj("images")?.has("orig") == true || obj.optObj("images")?.has("736x") == true)) {
                return obj
            }
            for ((_, v) in obj.entrySet()) {
                findPinterestPinObject(v, targetPinId)?.let { return it }
            }
        } else if (node.isJsonArray) {
            val arr = node.asJsonArray
            for (i in 0 until arr.size()) {
                findPinterestPinObject(arr.get(i), targetPinId)?.let { return it }
            }
        }
        return null
    }

    fun rewritePinterestOriginUrl(rawUrl: String): String {
        val norm = ParserSupport.normalizeMediaUrl(rawUrl)
        if (!norm.contains("pinimg.com", ignoreCase = true)) return norm
        return norm.replace(Regex("""/(?:\d+x|originals|236x|474x|564x|736x|1200x)/"""), "/originals/")
    }

    // ════════════════════════════════════════════════════════════════
    // 6. YouTube：ytInitialPlayerResponse MP4 + 4K 封面
    // ════════════════════════════════════════════════════════════════

    private fun parseYouTubeDirect(
        rawInput: String,
        cleanUrl: String,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        val videoId = extractYouTubeVideoId(cleanUrl) ?: return null
        val pageResp = ParserSupport.followRedirectsAndFetch(
            startUrl = "https://www.youtube.com/watch?v=$videoId",
            platform = PlatformType.YOUTUBE,
            customCookie = customCookie,
            userAgent = ParserSupport.DESKTOP_UA
        )

        if (pageResp != null && pageResp.statusCode in 200..299) {
            val playerJson = ParserSupport.extractBalancedJsonObject(pageResp.html, "ytInitialPlayerResponse =")
            if (playerJson != null) {
                parseYouTubePlayerJson(rawInput, pageResp.finalUrl, playerJson, videoId, previousHistory)?.let { return it }
            }
        }

        // 兜底导出无水印 4K/HD 封面原图
        val maxRes = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        val hqFallback = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        return ParserSupport.applyDuplicateInfo(
            ParsedPost(
                id = "yt_$videoId",
                sourceShareText = rawInput,
                extractedUrl = cleanUrl,
                noteId = videoId,
                platform = PlatformType.YOUTUBE,
                postTypeLabel = "YouTube · HD 封面",
                authorName = "YouTube",
                authorHandle = "@youtube",
                authorAvatarUrl = "",
                coverUrl = maxRes,
                title = pageResp?.let { ParserSupport.extractHtmlTitle(it.html) }?.ifBlank { "YouTube 视频 #$videoId" }
                    ?: "YouTube 视频 #$videoId",
                content = "",
                tags = emptyList(),
                mediaItems = listOf(
                    MediaItem(
                        id = "${videoId}_yt_cover",
                        index = 0,
                        kind = MediaKind.IMAGE,
                        badge = QualityBadge.FOUR_K,
                        originUrl = maxRes,
                        previewUrl = hqFallback,
                        fallbackUrls = listOf(maxRes, hqFallback),
                        width = 1280,
                        height = 720,
                        format = "JPG",
                        colorSpace = "Display P3",
                        themeTitle = "高清封面"
                    )
                ),
                parseEngineSource = "YouTube 引擎 · 高清封面直出"
            ),
            previousHistory
        )
    }

    fun parseYouTubePlayerJson(
        rawInput: String,
        finalUrl: String,
        jsonStr: String,
        fallbackVideoId: String,
        previousHistory: ParseHistoryEntity? = null
    ): ParsedPost? {
        val root = runCatching { JsonParser.parseString(jsonStr).asJsonObject }.getOrNull() ?: return null
        val details = root.optObj("videoDetails") ?: return null
        val videoId = details.optStr("videoId").ifBlank { fallbackVideoId }
        val title = details.optStr("title").ifBlank { "YouTube $videoId" }
        val author = details.optStr("author").ifBlank { "YouTube Creator" }
        val desc = details.optStr("shortDescription")
        val lengthSec = details.optStr("lengthSeconds").toLongOrNull() ?: 0L

        val coverUrl = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        val coverFallback = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        val mediaItems = mutableListOf<MediaItem>()

        // 从 streamingData.formats 读取带音轨渐进式直链 MP4（无需二次混流即可直接播放与保存）
        val formats = root.optObj("streamingData")?.optArr("formats")
        var bestMp4Url = ""
        var bestWidth = 1280
        var bestHeight = 720
        var bestQualityLabel = 0
        formats?.forEach { fEl ->
            val f = fEl.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val mime = f.optStr("mimeType")
            val directUrl = f.optStr("url")
            val h = f.optInt("height", 360)
            val w = f.optInt("width", 640)
            if (mime.contains("video/mp4", ignoreCase = true) && directUrl.startsWith("http") && h >= bestQualityLabel) {
                bestQualityLabel = h
                bestMp4Url = ParserSupport.normalizeMediaUrl(directUrl)
                bestWidth = w.takeIf { it > 0 } ?: 1280
                bestHeight = h.takeIf { it > 0 } ?: 720
            }
        }

        if (bestMp4Url.startsWith("http")) {
            mediaItems.add(
                MediaItem(
                    id = "${videoId}_yt_vid",
                    index = 0,
                    kind = MediaKind.VIDEO,
                    badge = QualityBadge.VIDEO_HD,
                    originUrl = bestMp4Url,
                    previewUrl = coverUrl,
                    width = bestWidth,
                    height = bestHeight,
                    format = "MP4",
                    colorSpace = "BT.709",
                    durationMs = lengthSec * 1000L,
                    themeTitle = "${bestHeight}P 视频"
                )
            )
        }

        // 添加 4K/HD 原始封面
        mediaItems.add(
            MediaItem(
                id = "${videoId}_yt_cover",
                index = mediaItems.size,
                kind = MediaKind.IMAGE,
                badge = QualityBadge.FOUR_K,
                originUrl = coverUrl,
                previewUrl = coverFallback,
                fallbackUrls = listOf(coverUrl, coverFallback),
                width = 1280,
                height = 720,
                format = "JPG",
                colorSpace = "Display P3",
                themeTitle = "视频原画封面"
            )
        )

        val post = ParsedPost(
            id = "yt_$videoId",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = videoId,
            platform = PlatformType.YOUTUBE,
            postTypeLabel = if (bestMp4Url.isNotBlank()) "YouTube · 视频 + 封面" else "YouTube · 高清封面",
            authorName = author,
            authorHandle = "@$author",
            authorAvatarUrl = "",
            coverUrl = coverUrl,
            title = title,
            content = desc,
            tags = ParserSupport.extractHashtagsFromText(desc),
            mediaItems = mediaItems,
            parseEngineSource = "YouTube 引擎 · ytInitialPlayerResponse"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    private fun extractYouTubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:v=|/)([0-9A-Za-z_-]{11})(?:[?&/#]|$)"""),
            Regex("""youtu\.be/([0-9A-Za-z_-]{11})"""),
            Regex("""youtube\.com/shorts/([0-9A-Za-z_-]{11})""")
        )
        for (p in patterns) {
            p.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    // ════════════════════════════════════════════════════════════════
    // 7. Reddit：.json?raw_json=1 Gallery 多图 / i.redd.it / v.redd.it
    // ════════════════════════════════════════════════════════════════

    private fun parseRedditDirect(
        rawInput: String,
        cleanUrl: String,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        var resolvedUrl = cleanUrl
        if (cleanUrl.contains("redd.it/", ignoreCase = true) && !cleanUrl.contains("reddit.com/")) {
            ParserSupport.followRedirectsAndFetch(
                startUrl = cleanUrl,
                platform = PlatformType.REDDIT,
                customCookie = customCookie,
                userAgent = "PureSnap/1.3 (Android Media Extractor)"
            )?.let { resolvedUrl = it.finalUrl }
        }

        val cleanBase = resolvedUrl.substringBefore('?').trimEnd('/')
        val jsonUrl = if (cleanBase.endsWith(".json")) {
            "$cleanBase?raw_json=1"
        } else {
            "$cleanBase.json?raw_json=1"
        }

        val resp = ParserSupport.followRedirectsAndFetch(
            startUrl = jsonUrl,
            platform = PlatformType.REDDIT,
            customCookie = customCookie,
            userAgent = "PureSnap/1.3 (Android Media Extractor)"
        ) ?: return null

        if (resp.statusCode !in 200..299 || resp.html.isBlank()) return null
        return parseRedditPostJson(rawInput, resolvedUrl, resp.html, previousHistory)
    }

    fun parseRedditPostJson(
        rawInput: String,
        finalUrl: String,
        jsonStr: String,
        previousHistory: ParseHistoryEntity? = null
    ): ParsedPost? {
        val rootEl = runCatching { JsonParser.parseString(jsonStr) }.getOrNull() ?: return null
        val postData: JsonObject? = when {
            rootEl.isJsonArray && rootEl.asJsonArray.size() > 0 -> {
                rootEl.asJsonArray.get(0).takeIf { it.isJsonObject }?.asJsonObject
                    ?.optObj("data")?.optArr("children")
                    ?.takeIf { it.size() > 0 }?.get(0)?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.optObj("data")
            }
            rootEl.isJsonObject -> {
                val o = rootEl.asJsonObject
                o.optObj("data")?.optArr("children")?.takeIf { it.size() > 0 }
                    ?.get(0)?.takeIf { it.isJsonObject }?.asJsonObject?.optObj("data")
                    ?: o.optObj("data")
                    ?: o
            }
            else -> null
        }
        if (postData == null) return null

        val postId = postData.optStr("id").ifBlank { ShareLinkExtractor.extractWorkId(finalUrl) }
        val title = postData.optStr("title").ifBlank { "Reddit 帖子 #$postId" }
        val author = postData.optStr("author").ifBlank { "redditor" }
        val subreddit = postData.optStr("subreddit_name_prefixed").ifBlank { "r/${postData.optStr("subreddit")}" }
        val selfText = postData.optStr("selftext")
        val mediaItems = mutableListOf<MediaItem>()

        // 1) 多图画廊（is_gallery == true）
        val mediaMetadata = postData.optObj("media_metadata")
        val galleryItems = postData.optObj("gallery_data")?.optArr("items")
        if (mediaMetadata != null) {
            val orderedIds = mutableListOf<String>()
            galleryItems?.forEach { gEl ->
                gEl.takeIf { it.isJsonObject }?.asJsonObject?.optStr("media_id")
                    ?.takeIf { it.isNotBlank() }?.let { orderedIds.add(it) }
            }
            if (orderedIds.isEmpty()) {
                mediaMetadata.entrySet().forEach { orderedIds.add(it.key) }
            }
            orderedIds.forEachIndexed { idx, mediaId ->
                val meta = mediaMetadata.optObj(mediaId) ?: return@forEachIndexed
                val source = meta.optObj("s") ?: return@forEachIndexed
                val mp4 = source.optStr("mp4").takeIf { it.startsWith("http") }?.let { ParserSupport.normalizeMediaUrl(it) }
                val gif = source.optStr("gif").takeIf { it.startsWith("http") }?.let { ParserSupport.normalizeMediaUrl(it) }
                val origImg = source.optStr("u").takeIf { it.startsWith("http") }?.let { ParserSupport.normalizeMediaUrl(it) }
                val w = source.optInt("x", 1440).takeIf { it > 0 } ?: 1440
                val h = source.optInt("y", 1920).takeIf { it > 0 } ?: 1920

                val chosenImg = gif ?: origImg
                if (!chosenImg.isNullOrBlank()) {
                    mediaItems.add(
                        MediaItem(
                            id = "${postId}_rd_$idx",
                            index = idx,
                            kind = MediaKind.IMAGE,
                            badge = if (maxOf(w, h) >= 3840) QualityBadge.FOUR_K else QualityBadge.RAW,
                            originUrl = chosenImg,
                            previewUrl = origImg ?: chosenImg,
                            width = w,
                            height = h,
                            format = ParserSupport.detectImageFormat(chosenImg, "JPG"),
                            colorSpace = "Display P3",
                            themeTitle = "画廊原图 ${idx + 1}"
                        )
                    )
                } else if (!mp4.isNullOrBlank()) {
                    mediaItems.add(
                        MediaItem(
                            id = "${postId}_rd_$idx",
                            index = idx,
                            kind = MediaKind.VIDEO,
                            badge = QualityBadge.VIDEO_HD,
                            originUrl = mp4,
                            previewUrl = mp4,
                            width = w,
                            height = h,
                            format = "MP4",
                            colorSpace = "BT.709",
                            themeTitle = "动图视频 ${idx + 1}"
                        )
                    )
                }
            }
        }

        // 2) Reddit 原生视频 (v.redd.it)
        if (mediaItems.isEmpty()) {
            val redditVideo = postData.optObj("secure_media")?.optObj("reddit_video")
                ?: postData.optObj("media")?.optObj("reddit_video")
            val vidUrl = redditVideo?.optStr("fallback_url")?.takeIf { it.startsWith("http") }
                ?.let { ParserSupport.normalizeMediaUrl(it) }
            if (!vidUrl.isNullOrBlank()) {
                val thumb = postData.optStr("thumbnail").takeIf { it.startsWith("http") }
                    ?.let { ParserSupport.normalizeMediaUrl(it) } ?: vidUrl
                mediaItems.add(
                    MediaItem(
                        id = "${postId}_rd_vid",
                        index = 0,
                        kind = MediaKind.VIDEO,
                        badge = QualityBadge.VIDEO_HD,
                        originUrl = vidUrl,
                        previewUrl = thumb,
                        width = redditVideo.optInt("width", 1080).takeIf { it > 0 } ?: 1080,
                        height = redditVideo.optInt("height", 1920).takeIf { it > 0 } ?: 1920,
                        format = "MP4",
                        colorSpace = "BT.709",
                        durationMs = redditVideo.optLong("duration", 0L) * 1000L,
                        themeTitle = "Reddit 视频"
                    )
                )
            }
        }

        // 3) 单图 i.redd.it
        if (mediaItems.isEmpty()) {
            val destUrl = ParserSupport.normalizeMediaUrl(postData.optStr("url_overridden_by_dest").ifBlank { postData.optStr("url") })
            if (destUrl.startsWith("http") &&
                (destUrl.contains("i.redd.it") || destUrl.contains("i.imgur.com") ||
                    ParserSupport.detectImageFormat(destUrl, "").isNotBlank())
            ) {
                mediaItems.add(
                    MediaItem(
                        id = "${postId}_rd_img_0",
                        index = 0,
                        kind = MediaKind.IMAGE,
                        badge = QualityBadge.RAW,
                        originUrl = destUrl,
                        previewUrl = destUrl,
                        width = 1600,
                        height = 2000,
                        format = ParserSupport.detectImageFormat(destUrl, "JPG"),
                        colorSpace = "Display P3",
                        themeTitle = "Reddit 原图"
                    )
                )
            }
        }

        if (mediaItems.isEmpty()) return null
        val post = ParsedPost(
            id = "rd_$postId",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = postId,
            platform = PlatformType.REDDIT,
            postTypeLabel = if (mediaItems.any { it.isVideo }) "Reddit · 视频" else "Reddit · 画廊原图",
            authorName = "u/$author",
            authorHandle = subreddit,
            authorAvatarUrl = "",
            coverUrl = mediaItems.first().previewUrl,
            title = title,
            content = selfText,
            tags = if (subreddit.isNotBlank()) listOf("#$subreddit") else emptyList(),
            mediaItems = mediaItems,
            parseEngineSource = "Reddit 引擎 · raw_json=1 原图直出"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }
}
