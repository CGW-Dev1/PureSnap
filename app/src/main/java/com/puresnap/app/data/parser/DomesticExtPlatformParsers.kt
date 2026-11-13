package com.puresnap.app.data.parser

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.puresnap.app.data.local.ParseHistoryEntity
import com.puresnap.app.model.MediaItem
import com.puresnap.app.model.MediaKind
import com.puresnap.app.model.ParsedPost
import com.puresnap.app.model.PlatformType
import com.puresnap.app.model.QualityBadge
import com.puresnap.app.util.ShareLinkExtractor

/**
 * 国内拓展平台解析器：
 * - 最右 (ZUIYOU)：share.izuiyou.com JSON API + __INITIAL_STATE__，原图 id 直拼去水印
 * - 皮皮虾 (PIPIXIA)：bds/cell/detail 无水印视频与多图状态树
 * - 微博 (WEIBO)：statuses/show + AJAX + $render_data，支持原图(/large/)、Live Photo 实况与 MP4 视频
 * - B站 (BILIBILI)：web-interface/view + playurl progressive MP4 + 动态/专栏 @ 后缀清理无损原图
 */
internal object DomesticExtPlatformParsers {

    fun parseDirect(
        rawInput: String,
        cleanUrl: String,
        platform: PlatformType,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        return when (platform) {
            PlatformType.WEIBO -> parseWeiboDirect(rawInput, cleanUrl, customCookie, previousHistory)
            PlatformType.BILIBILI -> parseBilibiliDirect(rawInput, cleanUrl, customCookie, previousHistory)
            PlatformType.ZUIYOU -> parseZuiyouDirect(rawInput, cleanUrl, customCookie, previousHistory)
            PlatformType.PIPIXIA -> parsePipixiaDirect(rawInput, cleanUrl, customCookie, previousHistory)
            else -> null
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 1. 微博 (Weibo)：原图 / Live Photo 实况 / 高清视频
    // ════════════════════════════════════════════════════════════════

    private fun parseWeiboDirect(
        rawInput: String,
        cleanUrl: String,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        var resolvedUrl = cleanUrl
        if (cleanUrl.contains("t.cn/", ignoreCase = true) || cleanUrl.contains("sinaurl.cn", ignoreCase = true)) {
            ParserSupport.followRedirectsAndFetch(cleanUrl, PlatformType.WEIBO, customCookie)?.let {
                resolvedUrl = it.finalUrl
            }
        }

        val statusId = extractWeiboId(resolvedUrl) ?: extractWeiboId(cleanUrl)
        if (!statusId.isNullOrBlank()) {
            // 优先移动端 API：免登录且稳定暴露 pics + livephoto videoSrc
            ParserSupport.followRedirectsAndFetch(
                startUrl = "https://m.weibo.cn/statuses/show?id=$statusId",
                platform = PlatformType.WEIBO,
                customCookie = customCookie,
                extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest")
            )?.let { resp ->
                if (resp.statusCode in 200..299 && resp.html.trimStart().startsWith("{")) {
                    parseWeiboStatusJson(
                        rawInput = rawInput,
                        cleanUrl = cleanUrl,
                        finalUrl = resolvedUrl,
                        jsonStr = resp.html,
                        workId = statusId,
                        previousHistory = previousHistory
                    )?.let { return it }
                }
            }

            // 次选 PC AJAX 接口：支持长文 pic_infos 与 1080P MP4
            ParserSupport.followRedirectsAndFetch(
                startUrl = "https://weibo.com/ajax/statuses/show?id=$statusId",
                platform = PlatformType.WEIBO,
                customCookie = customCookie,
                userAgent = ParserSupport.DESKTOP_UA,
                extraHeaders = mapOf(
                    "Referer" to "https://weibo.com/",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )?.let { resp ->
                if (resp.statusCode in 200..299 && resp.html.trimStart().startsWith("{")) {
                    parseWeiboStatusJson(
                        rawInput = rawInput,
                        cleanUrl = cleanUrl,
                        finalUrl = resolvedUrl,
                        jsonStr = resp.html,
                        workId = statusId,
                        previousHistory = previousHistory
                    )?.let { return it }
                }
            }
        }

        // 页面 HTML $render_data 兜底
        val pageResp = ParserSupport.followRedirectsAndFetch(resolvedUrl, PlatformType.WEIBO, customCookie)
            ?: return null
        val renderJson = ParserSupport.extractBalancedJsonObject(pageResp.html, "\"status\":")
            ?: ParserSupport.extractBalancedJsonObject(pageResp.html, "var \$render_data =")
            ?: return null
        return parseWeiboStatusJson(
            rawInput = rawInput,
            cleanUrl = cleanUrl,
            finalUrl = pageResp.finalUrl,
            jsonStr = renderJson,
            workId = statusId ?: ShareLinkExtractor.extractWorkId(pageResp.finalUrl),
            previousHistory = previousHistory
        )
    }

    fun parseWeiboStatusJson(
        rawInput: String,
        cleanUrl: String,
        finalUrl: String,
        jsonStr: String,
        workId: String,
        previousHistory: ParseHistoryEntity? = null
    ): ParsedPost? {
        val rootEl = runCatching { JsonParser.parseString(jsonStr) }.getOrNull() ?: return null
        val rootObj = when {
            rootEl.isJsonObject -> {
                val o = rootEl.asJsonObject
                o.optObj("data") ?: o.optObj("status") ?: o
            }
            rootEl.isJsonArray && rootEl.asJsonArray.size() > 0 -> {
                val first = rootEl.asJsonArray.get(0)
                if (first.isJsonObject) {
                    first.asJsonObject.optObj("status") ?: first.asJsonObject
                } else null
            }
            else -> null
        } ?: return null

        // 若转发且原博无媒体，尝试读取 retweeted_status
        val statusObj = if (
            rootObj.optArr("pics") == null &&
            rootObj.optObj("pic_infos") == null &&
            rootObj.optObj("page_info") == null &&
            rootObj.optObj("retweeted_status") != null
        ) {
            rootObj.optObj("retweeted_status") ?: rootObj
        } else {
            rootObj
        }

        val resolvedWorkId = statusObj.optStr("idstr")
            .ifBlank { statusObj.optStr("mid") }
            .ifBlank { workId }
        val userObj = statusObj.optObj("user")
        val authorName = userObj?.optStr("screen_name")?.ifBlank { "微博用户" } ?: "微博用户"
        val avatarUrl = userObj?.optStr("avatar_hd")
            ?.ifBlank { userObj.optStr("profile_image_url") }
            ?.let { ParserSupport.normalizeMediaUrl(it) }
            ?: ""

        val rawText = statusObj.optStr("text_raw").ifBlank { statusObj.optStr("text") }
        val cleanText = ParserSupport.stripHtmlTags(rawText)
        val mediaItems = mutableListOf<MediaItem>()

        // 格式 A：pic_infos (PC / 新版 AJAX，含 pic_ids 排序)
        val picInfos = statusObj.optObj("pic_infos")
        if (picInfos != null && picInfos.size() > 0) {
            val orderedIds = mutableListOf<String>()
            statusObj.optArr("pic_ids")?.forEach { el ->
                if (el.isJsonPrimitive) orderedIds.add(el.asString)
            }
            if (orderedIds.isEmpty()) {
                picInfos.entrySet().forEach { orderedIds.add(it.key) }
            }
            orderedIds.forEachIndexed { idx, pid ->
                val info = picInfos.optObj(pid) ?: return@forEachIndexed
                val largest = info.optObj("largest")
                    ?: info.optObj("original")
                    ?: info.optObj("mw2000")
                    ?: info.optObj("large")
                    ?: info.optObj("bmiddle")
                val rawUrl = largest?.optStr("url").orEmpty()
                if (rawUrl.isBlank()) return@forEachIndexed
                val normalized = ParserSupport.normalizeMediaUrl(rawUrl)
                val origUrl = rewriteWeiboOriginUrl(normalized)
                val liveVid = info.optStr("video").takeIf { it.startsWith("http") }
                    ?.let { ParserSupport.normalizeMediaUrl(it) }
                val isLive = info.optStr("type").equals("livephoto", ignoreCase = true) || !liveVid.isNullOrBlank()
                val w = largest?.optInt("width", 1440)?.takeIf { it > 0 } ?: 1440
                val h = largest?.optInt("height", 1920)?.takeIf { it > 0 } ?: 1920
                val fmt = ParserSupport.detectImageFormat(origUrl, "JPG")

                mediaItems.add(
                    MediaItem(
                        id = "${resolvedWorkId}_wb_$idx",
                        index = idx,
                        kind = if (isLive) MediaKind.LIVE_PHOTO else MediaKind.IMAGE,
                        badge = when {
                            isLive -> QualityBadge.LIVE
                            maxOf(w, h) >= 3840 -> QualityBadge.FOUR_K
                            else -> QualityBadge.RAW
                        },
                        originUrl = origUrl,
                        previewUrl = normalized,
                        liveVideoUrl = liveVid,
                        fallbackUrls = listOf(origUrl, normalized).distinct(),
                        width = w,
                        height = h,
                        format = fmt,
                        colorSpace = "Display P3",
                        themeTitle = if (isLive) "实况 ${idx + 1}" else "原图 ${idx + 1}"
                    )
                )
            }
        }

        // 格式 B：pics 数组 (m.weibo.cn)
        if (mediaItems.isEmpty()) {
            statusObj.optArr("pics")?.let { picsArr ->
                for (i in 0 until picsArr.size()) {
                    val pic = picsArr.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val large = pic.optObj("large")
                    val preview = ParserSupport.normalizeMediaUrl(
                        large?.optStr("url")?.ifBlank { pic.optStr("url") } ?: pic.optStr("url")
                    )
                    if (!preview.startsWith("http")) continue
                    val orig = rewriteWeiboOriginUrl(preview)
                    val geo = large?.optObj("geo") ?: pic.optObj("geo")
                    val w = geo?.optInt("width", 1440)?.takeIf { it > 0 } ?: 1440
                    val h = geo?.optInt("height", 1920)?.takeIf { it > 0 } ?: 1920
                    val liveVid = pic.optStr("videoSrc")
                        .ifBlank { pic.optStr("video") }
                        .takeIf { it.startsWith("http") }
                        ?.let { ParserSupport.normalizeMediaUrl(it) }
                    val isLive = pic.optStr("type").equals("livephoto", ignoreCase = true) || !liveVid.isNullOrBlank()
                    val fmt = ParserSupport.detectImageFormat(orig, "JPG")

                    mediaItems.add(
                        MediaItem(
                            id = "${resolvedWorkId}_wb_$i",
                            index = i,
                            kind = if (isLive) MediaKind.LIVE_PHOTO else MediaKind.IMAGE,
                            badge = when {
                                isLive -> QualityBadge.LIVE
                                maxOf(w, h) >= 3840 -> QualityBadge.FOUR_K
                                else -> QualityBadge.RAW
                            },
                            originUrl = orig,
                            previewUrl = preview,
                            liveVideoUrl = liveVid,
                            fallbackUrls = listOf(orig, preview).distinct(),
                            width = w,
                            height = h,
                            format = fmt,
                            colorSpace = "Display P3",
                            themeTitle = if (isLive) "实况 ${i + 1}" else "原图 ${i + 1}"
                        )
                    )
                }
            }
        }

        // 视频流（page_info.media_info）
        val pageInfo = statusObj.optObj("page_info")
        if (pageInfo != null && (pageInfo.optStr("type") == "video" || pageInfo.optObj("media_info") != null)) {
            val mediaInfo = pageInfo.optObj("media_info") ?: pageInfo
            val candidates = listOf(
                mediaInfo.optStr("mp4_720p_mp4"),
                mediaInfo.optStr("mp4_hd_url"),
                mediaInfo.optStr("stream_url_hd"),
                mediaInfo.optStr("mp4_sd_url"),
                mediaInfo.optStr("stream_url")
            ).filter { it.startsWith("http") }.map { ParserSupport.normalizeMediaUrl(it) }

            val playbackList = mediaInfo.optArr("playback_list")
            var bestPlaybackUrl = ""
            if (playbackList != null && playbackList.size() > 0) {
                var maxBitrate = -1L
                for (i in 0 until playbackList.size()) {
                    val item = playbackList.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val playInfo = item.optObj("play_info") ?: continue
                    val url = playInfo.optStr("url")
                    val br = playInfo.optLong("bitrate", 0L)
                    if (url.startsWith("http") && br >= maxBitrate) {
                        maxBitrate = br
                        bestPlaybackUrl = ParserSupport.normalizeMediaUrl(url)
                    }
                }
            }

            val finalVideo = bestPlaybackUrl.ifBlank { candidates.firstOrNull().orEmpty() }
            if (finalVideo.startsWith("http")) {
                val poster = pageInfo.optObj("page_pic")?.optStr("url")
                    ?.let { rewriteWeiboOriginUrl(ParserSupport.normalizeMediaUrl(it)) }
                    ?.ifBlank { finalVideo }
                    ?: finalVideo
                val idx = mediaItems.size
                mediaItems.add(
                    MediaItem(
                        id = "${resolvedWorkId}_wb_vid",
                        index = idx,
                        kind = MediaKind.VIDEO,
                        badge = QualityBadge.VIDEO_HD,
                        originUrl = finalVideo,
                        previewUrl = poster,
                        fallbackUrls = (listOfNotNull(bestPlaybackUrl.takeIf { it.isNotBlank() }) + candidates).distinct(),
                        width = mediaInfo.optInt("width", 1080).takeIf { it > 0 } ?: 1080,
                        height = mediaInfo.optInt("height", 1920).takeIf { it > 0 } ?: 1920,
                        format = "MP4",
                        colorSpace = "BT.709",
                        durationMs = mediaInfo.optLong("duration", 0L) * 1000L,
                        themeTitle = "高清视频"
                    )
                )
            }
        }

        if (mediaItems.isEmpty()) return null

        val liveCount = mediaItems.count { it.isLivePhoto }
        val videoCount = mediaItems.count { it.isVideo }
        val typeLabel = when {
            liveCount > 0 -> "微博 · 实况图"
            videoCount > 0 && mediaItems.size == videoCount -> "微博 · 视频"
            mediaItems.size > 1 -> "微博 · 原图"
            else -> "微博 · 图片"
        }

        val post = ParsedPost(
            id = "wb_$resolvedWorkId",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = resolvedWorkId,
            platform = PlatformType.WEIBO,
            postTypeLabel = typeLabel,
            authorName = authorName,
            authorHandle = "@$authorName",
            authorAvatarUrl = avatarUrl,
            coverUrl = mediaItems.first().previewUrl,
            title = cleanText.lineSequence().firstOrNull()?.take(48)?.ifBlank { "微博内容 #$resolvedWorkId" }
                ?: "微博内容 #$resolvedWorkId",
            content = cleanText,
            tags = ParserSupport.extractHashtagsFromText(cleanText),
            mediaItems = mediaItems,
            parseEngineSource = "微博引擎 · 原图/实况直连"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    fun rewriteWeiboOriginUrl(rawUrl: String): String {
        val norm = ParserSupport.normalizeMediaUrl(rawUrl)
        if (!norm.contains("sinaimg.cn", ignoreCase = true)) return norm
        return norm.replace(
            Regex("""/(orj360|orj480|mw690|mw1024|mw2000|thumb180|thumb300|wap180|wap360|wap720|bmiddle|sq612)/"""),
            "/large/"
        )
    }

    private fun extractWeiboId(url: String): String? {
        val patterns = listOf(
            Regex("""m\.weibo\.cn/(?:detail|status)/([A-Za-z0-9]+)"""),
            Regex("""weibo\.(?:com|cn)/(?:\d+|u/\d+)/([A-Za-z0-9]+)"""),
            Regex("""weibo\.com/status/([A-Za-z0-9]+)""")
        )
        for (p in patterns) {
            p.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    // ════════════════════════════════════════════════════════════════
    // 2. Bilibili (B站)：视频 MP4 + 无损封面 / 动态 Opus 原图
    // ════════════════════════════════════════════════════════════════

    private fun parseBilibiliDirect(
        rawInput: String,
        cleanUrl: String,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        var resolvedUrl = cleanUrl
        if (cleanUrl.contains("b23.tv", ignoreCase = true) || cleanUrl.contains("bili2233.cn", ignoreCase = true)) {
            ParserSupport.followRedirectsAndFetch(cleanUrl, PlatformType.BILIBILI, customCookie)?.let {
                resolvedUrl = it.finalUrl
            }
        }

        // 先检查动态 / Opus 图文
        val dynId = Regex("""(?:t\.bilibili\.com/|bilibili\.com/opus/)(\d+)""")
            .find(resolvedUrl)?.groupValues?.getOrNull(1)
        if (!dynId.isNullOrBlank()) {
            ParserSupport.followRedirectsAndFetch(
                startUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=$dynId",
                platform = PlatformType.BILIBILI,
                customCookie = customCookie,
                userAgent = ParserSupport.DESKTOP_UA,
                extraHeaders = mapOf("Referer" to "https://t.bilibili.com/$dynId")
            )?.let { resp ->
                if (resp.statusCode in 200..299) {
                    parseBilibiliDynamicJson(
                        rawInput = rawInput,
                        finalUrl = resolvedUrl,
                        jsonStr = resp.html,
                        dynId = dynId,
                        previousHistory = previousHistory
                    )?.let { return it }
                }
            }
        }

        val bvid = Regex("""(BV[0-9A-Za-z]{10})""").find(resolvedUrl)?.groupValues?.getOrNull(1)
        val aid = if (bvid == null) Regex("""/av(\d+)""").find(resolvedUrl)?.groupValues?.getOrNull(1) else null

        if (bvid != null || aid != null) {
            val viewQuery = if (bvid != null) "bvid=$bvid" else "aid=$aid"
            val viewResp = ParserSupport.followRedirectsAndFetch(
                startUrl = "https://api.bilibili.com/x/web-interface/view?$viewQuery",
                platform = PlatformType.BILIBILI,
                customCookie = customCookie,
                userAgent = ParserSupport.DESKTOP_UA
            )
            if (viewResp != null && viewResp.statusCode in 200..299) {
                val viewRoot = runCatching { JsonParser.parseString(viewResp.html).asJsonObject }.getOrNull()
                val viewData = viewRoot?.optObj("data")
                val resolvedBvid = viewData?.optStr("bvid")?.ifBlank { bvid.orEmpty() } ?: bvid.orEmpty()
                val cid = viewData?.optLong("cid", 0L) ?: 0L
                val playJson = if (resolvedBvid.isNotBlank() && cid > 0L) {
                    ParserSupport.followRedirectsAndFetch(
                        startUrl = "https://api.bilibili.com/x/player/playurl?bvid=$resolvedBvid&cid=$cid&qn=80&fnval=1&platform=html5&high_quality=1",
                        platform = PlatformType.BILIBILI,
                        customCookie = customCookie,
                        userAgent = ParserSupport.DESKTOP_UA,
                        extraHeaders = mapOf("Referer" to "https://www.bilibili.com/video/$resolvedBvid")
                    )?.html
                } else null

                parseBilibiliVideoJson(
                    rawInput = rawInput,
                    finalUrl = resolvedUrl,
                    viewJsonStr = viewResp.html,
                    playJsonStr = playJson,
                    previousHistory = previousHistory
                )?.let { return it }
            }
        }
        return null
    }

    fun parseBilibiliVideoJson(
        rawInput: String,
        finalUrl: String,
        viewJsonStr: String,
        playJsonStr: String? = null,
        previousHistory: ParseHistoryEntity? = null
    ): ParsedPost? {
        val viewRoot = runCatching { JsonParser.parseString(viewJsonStr).asJsonObject }.getOrNull() ?: return null
        if (viewRoot.optInt("code", -1) != 0) return null
        val data = viewRoot.optObj("data") ?: return null

        val bvid = data.optStr("bvid").ifBlank { data.optStr("aid") }
        val title = data.optStr("title").ifBlank { "Bilibili 视频 $bvid" }
        val desc = data.optStr("desc")
        val pic = rewriteBilibiliOriginUrl(data.optStr("pic"))
        val owner = data.optObj("owner")
        val authorName = owner?.optStr("name")?.ifBlank { "UP主" } ?: "UP主"
        val avatarUrl = owner?.optStr("face")?.let { ParserSupport.normalizeMediaUrl(it) } ?: ""
        val dim = data.optObj("dimension")
        val width = dim?.optInt("width", 1920)?.takeIf { it > 0 } ?: 1920
        val height = dim?.optInt("height", 1080)?.takeIf { it > 0 } ?: 1080
        val durationSec = data.optLong("duration", 0L)

        var videoUrl = ""
        if (!playJsonStr.isNullOrBlank()) {
            val playRoot = runCatching { JsonParser.parseString(playJsonStr).asJsonObject }.getOrNull()
            val playData = playRoot?.optObj("data")
            val durl = playData?.optArr("durl")
            if (durl != null && durl.size() > 0) {
                val first = durl.get(0).takeIf { it.isJsonObject }?.asJsonObject
                videoUrl = first?.optStr("url").orEmpty().let { ParserSupport.normalizeMediaUrl(it) }
            }
        }

        val mediaItems = mutableListOf<MediaItem>()
        if (videoUrl.startsWith("http")) {
            mediaItems.add(
                MediaItem(
                    id = "${bvid}_video",
                    index = 0,
                    kind = MediaKind.VIDEO,
                    badge = QualityBadge.VIDEO_HD,
                    originUrl = videoUrl,
                    previewUrl = pic.ifBlank { videoUrl },
                    width = width,
                    height = height,
                    format = "MP4",
                    colorSpace = "BT.709",
                    durationMs = durationSec * 1000L,
                    themeTitle = "高清视频"
                )
            )
        }
        if (pic.startsWith("http")) {
            val idx = mediaItems.size
            mediaItems.add(
                MediaItem(
                    id = "${bvid}_cover",
                    index = idx,
                    kind = MediaKind.IMAGE,
                    badge = QualityBadge.RAW,
                    originUrl = pic,
                    previewUrl = pic,
                    width = width,
                    height = height,
                    format = ParserSupport.detectImageFormat(pic, "JPG"),
                    colorSpace = "Display P3",
                    themeTitle = "无损封面"
                )
            )
        }
        if (mediaItems.isEmpty()) return null

        val post = ParsedPost(
            id = "bili_$bvid",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = bvid,
            platform = PlatformType.BILIBILI,
            postTypeLabel = if (videoUrl.isNotBlank()) "B站 · 视频 + 封面" else "B站 · 封面原图",
            authorName = authorName,
            authorHandle = authorName,
            authorAvatarUrl = avatarUrl,
            coverUrl = pic.ifBlank { mediaItems.first().previewUrl },
            title = title,
            content = desc,
            tags = ParserSupport.extractHashtagsFromText("$title $desc"),
            mediaItems = mediaItems,
            parseEngineSource = "B站引擎 · 视频与无损封面直出"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    fun parseBilibiliDynamicJson(
        rawInput: String,
        finalUrl: String,
        jsonStr: String,
        dynId: String,
        previousHistory: ParseHistoryEntity? = null
    ): ParsedPost? {
        val root = runCatching { JsonParser.parseString(jsonStr).asJsonObject }.getOrNull() ?: return null
        val item = root.optObj("data")?.optObj("item") ?: return null
        val modules = item.optObj("modules") ?: return null
        val author = modules.optObj("module_author")
        val dynamic = modules.optObj("module_dynamic")
        val major = dynamic?.optObj("major")

        val opusPics = major?.optObj("opus")?.optArr("pics")
        val drawItems = major?.optObj("draw")?.optArr("items")
        val rawPicsArr = opusPics ?: drawItems ?: return null

        val mediaItems = mutableListOf<MediaItem>()
        for (i in 0 until rawPicsArr.size()) {
            val pObj = rawPicsArr.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val rawUrl = pObj.optStr("url").ifBlank { pObj.optStr("src") }
            if (!rawUrl.startsWith("http")) continue
            val orig = rewriteBilibiliOriginUrl(rawUrl)
            val w = pObj.optInt("width", 1440).takeIf { it > 0 } ?: 1440
            val h = pObj.optInt("height", 1920).takeIf { it > 0 } ?: 1920
            mediaItems.add(
                MediaItem(
                    id = "${dynId}_dyn_$i",
                    index = i,
                    kind = MediaKind.IMAGE,
                    badge = if (maxOf(w, h) >= 3840) QualityBadge.FOUR_K else QualityBadge.RAW,
                    originUrl = orig,
                    previewUrl = ParserSupport.normalizeMediaUrl(rawUrl),
                    width = w,
                    height = h,
                    format = ParserSupport.detectImageFormat(orig, "JPG"),
                    colorSpace = "Display P3",
                    themeTitle = "动态原图 ${i + 1}"
                )
            )
        }
        if (mediaItems.isEmpty()) return null

        val title = major?.optObj("opus")?.optStr("title").orEmpty()
        val text = dynamic?.optObj("desc")?.optStr("text").orEmpty()
            .ifBlank { major?.optObj("opus")?.optObj("summary")?.optStr("text").orEmpty() }

        val post = ParsedPost(
            id = "bili_dyn_$dynId",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = dynId,
            platform = PlatformType.BILIBILI,
            postTypeLabel = "B站 · 动态原图",
            authorName = author?.optStr("name")?.ifBlank { "B站 UP 主" } ?: "B站 UP 主",
            authorHandle = author?.optStr("name") ?: "",
            authorAvatarUrl = author?.optStr("face")?.let { ParserSupport.normalizeMediaUrl(it) } ?: "",
            coverUrl = mediaItems.first().previewUrl,
            title = title.ifBlank { text.lineSequence().firstOrNull()?.take(48).orEmpty() }
                .ifBlank { "B站动态 #$dynId" },
            content = text,
            tags = ParserSupport.extractHashtagsFromText(text),
            mediaItems = mediaItems,
            parseEngineSource = "B站引擎 · 动态无损原图"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    fun rewriteBilibiliOriginUrl(rawUrl: String): String {
        val norm = ParserSupport.normalizeMediaUrl(rawUrl)
        if (!norm.contains("hdslb.com", ignoreCase = true)) return norm
        // 移除 @100w_100h_1c.webp 等裁切缩放参数，恢复上传原图字节
        return norm.replace(Regex("""@\d+w.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""@\d+h.*$""", RegexOption.IGNORE_CASE), "")
    }

    // ════════════════════════════════════════════════════════════════
    // 3. 最右 (Zuiyou)：原图 ID 直拼去水印 + 视频
    // ════════════════════════════════════════════════════════════════

    private fun parseZuiyouDirect(
        rawInput: String,
        cleanUrl: String,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        var resolvedUrl = cleanUrl
        if (!resolvedUrl.contains("pid=") && !Regex("""/(?:post|detail)/\d+""").containsMatchIn(resolvedUrl)) {
            ParserSupport.followRedirectsAndFetch(cleanUrl, PlatformType.ZUIYOU, customCookie)?.let {
                resolvedUrl = it.finalUrl
            }
        }
        val pidStr = Regex("""(?:pid=|/detail/|/post/)(\d+)""").find(resolvedUrl)?.groupValues?.getOrNull(1)
            ?: Regex("""(?:pid=|/detail/|/post/)(\d+)""").find(cleanUrl)?.groupValues?.getOrNull(1)
        val pidLong = pidStr?.toLongOrNull()

        if (pidLong != null) {
            val apiResp = ParserSupport.httpPostJson(
                targetUrl = "https://share.izuiyou.com/api/post/detail",
                jsonBody = """{"pid":$pidLong,"h_av":"5.2.13","type":"post"}""",
                platform = PlatformType.ZUIYOU,
                customCookie = customCookie
            )
            if (apiResp != null && apiResp.statusCode in 200..299) {
                parseZuiyouJson(
                    rawInput = rawInput,
                    finalUrl = resolvedUrl,
                    jsonStr = apiResp.html,
                    workId = pidLong.toString(),
                    previousHistory = previousHistory
                )?.let { return it }
            }
        }

        // H5 页面内嵌状态树兜底
        val pageResp = ParserSupport.followRedirectsAndFetch(resolvedUrl, PlatformType.ZUIYOU, customCookie) ?: return null
        val stateJson = ParserSupport.extractBalancedJsonObject(pageResp.html, "window.__INITIAL_STATE__ =")
            ?: ParserSupport.extractBalancedJsonObject(pageResp.html, "__INITIAL_STATE__=")
            ?: return null
        return parseZuiyouJson(
            rawInput = rawInput,
            finalUrl = pageResp.finalUrl,
            jsonStr = stateJson,
            workId = pidStr ?: ShareLinkExtractor.extractWorkId(pageResp.finalUrl),
            previousHistory = previousHistory
        )
    }

    fun parseZuiyouJson(
        rawInput: String,
        finalUrl: String,
        jsonStr: String,
        workId: String,
        previousHistory: ParseHistoryEntity? = null
    ): ParsedPost? {
        val root = runCatching { JsonParser.parseString(jsonStr).asJsonObject }.getOrNull() ?: return null
        val postObj = root.optObj("data")?.optObj("post")
            ?: root.optObj("postDetail")?.optObj("post")
            ?: root.optObj("post")
            ?: root

        val pid = postObj.optStr("id").ifBlank { workId }
        val imgsArr = postObj.optArr("imgs") ?: return null
        val videosMap = postObj.optObj("videos")
        val member = postObj.optObj("member")
        val authorName = member?.optStr("name")?.ifBlank { "右友" } ?: "右友"
        val avatarId = member?.optStr("avatar")
        val avatarUrl = if (!avatarId.isNullOrBlank() && avatarId.all { it.isDigit() }) {
            "https://file.izuiyou.com/img/view/id/$avatarId"
        } else {
            member?.optObj("avatar_urls")?.optObj("origin")?.optArr("urls")?.get(0)?.asString.orEmpty()
        }

        val mediaItems = mutableListOf<MediaItem>()
        for (i in 0 until imgsArr.size()) {
            val imgObj = imgsArr.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val imgId = imgObj.optStr("id")
            val w = imgObj.optInt("w", 1440).takeIf { it > 0 } ?: 1440
            val h = imgObj.optInt("h", 1920).takeIf { it > 0 } ?: 1920
            val isVideoFlag = imgObj.optInt("video", 0) == 1
            val urlsOrigin = imgObj.optObj("urls")?.optObj("origin")?.optArr("urls")?.get(0)?.asString
            val viewOrigUrl = if (imgId.isNotBlank()) "https://file.izuiyou.com/img/view/id/$imgId" else ""
            val fallbackImg = urlsOrigin?.let { ParserSupport.normalizeMediaUrl(it) } ?: viewOrigUrl
            val chosenImgOrig = viewOrigUrl.ifBlank { fallbackImg }

            val videoObj = videosMap?.optObj(imgId)
            val videoUrl = videoObj?.let { v ->
                v.optStr("url").ifBlank { v.optStr("urlsrc") }.ifBlank { v.optStr("urlhd") }
            }?.let { ParserSupport.normalizeMediaUrl(it) }.orEmpty()

            if (isVideoFlag && videoUrl.startsWith("http")) {
                mediaItems.add(
                    MediaItem(
                        id = "${pid}_zy_$i",
                        index = i,
                        kind = MediaKind.VIDEO,
                        badge = QualityBadge.VIDEO_HD,
                        originUrl = videoUrl,
                        previewUrl = chosenImgOrig.ifBlank { videoUrl },
                        fallbackUrls = listOfNotNull(
                            videoObj?.optStr("urlsrc")?.takeIf { it.startsWith("http") },
                            videoObj?.optStr("urlhd")?.takeIf { it.startsWith("http") },
                            videoUrl
                        ).distinct(),
                        width = w,
                        height = h,
                        format = "MP4",
                        colorSpace = "BT.709",
                        themeTitle = "视频 ${i + 1}"
                    )
                )
            } else if (chosenImgOrig.startsWith("http")) {
                mediaItems.add(
                    MediaItem(
                        id = "${pid}_zy_$i",
                        index = i,
                        kind = MediaKind.IMAGE,
                        badge = QualityBadge.RAW,
                        originUrl = chosenImgOrig,
                        previewUrl = fallbackImg.ifBlank { chosenImgOrig },
                        fallbackUrls = listOf(chosenImgOrig, fallbackImg).filter { it.startsWith("http") }.distinct(),
                        width = w,
                        height = h,
                        format = ParserSupport.detectImageFormat(fallbackImg.ifBlank { chosenImgOrig }, "JPG"),
                        colorSpace = "Display P3",
                        themeTitle = "原图 ${i + 1}"
                    )
                )
            }
        }

        if (mediaItems.isEmpty()) return null
        val content = postObj.optStr("content")
        val topicName = postObj.optObj("topic")?.optStr("topic").orEmpty()

        val post = ParsedPost(
            id = "zy_$pid",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = pid,
            platform = PlatformType.ZUIYOU,
            postTypeLabel = if (mediaItems.any { it.isVideo }) "最右 · 视频" else "最右 · 无水印原图",
            authorName = authorName,
            authorHandle = "@$authorName",
            authorAvatarUrl = avatarUrl,
            coverUrl = mediaItems.first().previewUrl,
            title = content.lineSequence().firstOrNull()?.take(48)?.ifBlank { topicName }?.ifBlank { "最右帖子 #$pid" }
                ?: "最右帖子 #$pid",
            content = content,
            tags = if (topicName.isNotBlank()) listOf("#$topicName") else emptyList(),
            mediaItems = mediaItems,
            parseEngineSource = "最右引擎 · 去水印原图直出"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    // ════════════════════════════════════════════════════════════════
    // 4. 皮皮虾 (Pipixia)：bds/cell/detail 无水印视频与图集
    // ════════════════════════════════════════════════════════════════

    private fun parsePipixiaDirect(
        rawInput: String,
        cleanUrl: String,
        customCookie: String?,
        previousHistory: ParseHistoryEntity?
    ): ParsedPost? {
        var resolvedUrl = cleanUrl
        if (!Regex("""(?:item/|cell_id=)(\d+)""").containsMatchIn(resolvedUrl)) {
            ParserSupport.followRedirectsAndFetch(cleanUrl, PlatformType.PIPIXIA, customCookie)?.let {
                resolvedUrl = it.finalUrl
            }
        }
        val itemId = Regex("""(?:item/|cell_id=|item_id=)(\d+)""").find(resolvedUrl)?.groupValues?.getOrNull(1)
            ?: return null

        val apiUrl = "https://is.snssdk.com/bds/cell/detail/?cell_type=1&aid=1319&app_name=super&cell_id=$itemId"
        val apiResp = ParserSupport.followRedirectsAndFetch(apiUrl, PlatformType.PIPIXIA, customCookie)
        if (apiResp != null && apiResp.statusCode in 200..299 && apiResp.html.trimStart().startsWith("{")) {
            parsePipixiaJson(
                rawInput = rawInput,
                finalUrl = resolvedUrl,
                jsonStr = apiResp.html,
                workId = itemId,
                previousHistory = previousHistory
            )?.let { return it }
        }
        return null
    }

    fun parsePipixiaJson(
        rawInput: String,
        finalUrl: String,
        jsonStr: String,
        workId: String,
        previousHistory: ParseHistoryEntity? = null
    ): ParsedPost? {
        val root = runCatching { JsonParser.parseString(jsonStr).asJsonObject }.getOrNull() ?: return null
        val itemObj = root.optObj("data")?.optObj("data")?.optObj("item")
            ?: root.optObj("data")?.optObj("item")
            ?: root.optObj("item")
            ?: return null

        val itemId = itemObj.optStr("item_id_str").ifBlank { workId }
        val content = itemObj.optStr("content")
        val author = itemObj.optObj("author")
        val authorName = author?.optStr("name")?.ifBlank { "皮友" } ?: "皮友"
        val avatarUrl = extractUrlFromUrlListObj(author?.optObj("avatar")).orEmpty()

        val mediaItems = mutableListOf<MediaItem>()

        // 无水印视频优先：origin_video_download -> video.video_high -> video.video_download
        val videoUrl = extractUrlFromUrlListObj(itemObj.optObj("origin_video_download"))
            ?: extractUrlFromUrlListObj(itemObj.optObj("video")?.optObj("video_high"))
            ?: extractUrlFromUrlListObj(itemObj.optObj("video")?.optObj("video_download"))

        if (!videoUrl.isNullOrBlank()) {
            val coverUrl = extractUrlFromUrlListObj(itemObj.optObj("cover"))
                ?: extractUrlFromUrlListObj(itemObj.optObj("video")?.optObj("cover_image"))
                ?: videoUrl
            val vidObj = itemObj.optObj("video")
            mediaItems.add(
                MediaItem(
                    id = "${itemId}_ppx_vid",
                    index = 0,
                    kind = MediaKind.VIDEO,
                    badge = QualityBadge.VIDEO_HD,
                    originUrl = videoUrl,
                    previewUrl = coverUrl,
                    width = vidObj?.optInt("width", 1080)?.takeIf { it > 0 } ?: 1080,
                    height = vidObj?.optInt("height", 1920)?.takeIf { it > 0 } ?: 1920,
                    format = "MP4",
                    colorSpace = "BT.709",
                    themeTitle = "无水印视频"
                )
            )
        }

        // 多图作品（note.multi_image）
        itemObj.optObj("note")?.optArr("multi_image")?.let { multi ->
            for (i in 0 until multi.size()) {
                val img = multi.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val url = extractUrlFromUrlListObj(img)
                    ?: img.optStr("uri").takeIf { it.isNotBlank() }?.let { "https://sf1-cdn-tos.pstatp.com/obj/$it" }
                if (url.isNullOrBlank()) continue
                val w = img.optInt("width", 1440).takeIf { it > 0 } ?: 1440
                val h = img.optInt("height", 1920).takeIf { it > 0 } ?: 1920
                mediaItems.add(
                    MediaItem(
                        id = "${itemId}_ppx_img_$i",
                        index = mediaItems.size,
                        kind = MediaKind.IMAGE,
                        badge = QualityBadge.RAW,
                        originUrl = url,
                        previewUrl = url,
                        width = w,
                        height = h,
                        format = ParserSupport.detectImageFormat(url, "JPG"),
                        colorSpace = "Display P3",
                        themeTitle = "原图 ${i + 1}"
                    )
                )
            }
        }

        if (mediaItems.isEmpty()) return null

        val post = ParsedPost(
            id = "ppx_$itemId",
            sourceShareText = rawInput,
            extractedUrl = finalUrl,
            noteId = itemId,
            platform = PlatformType.PIPIXIA,
            postTypeLabel = if (mediaItems.any { it.isVideo }) "皮皮虾 · 无水印视频" else "皮皮虾 · 原图",
            authorName = authorName,
            authorHandle = "@$authorName",
            authorAvatarUrl = avatarUrl,
            coverUrl = mediaItems.first().previewUrl,
            title = content.lineSequence().firstOrNull()?.take(48)?.ifBlank { "皮皮虾作品 #$itemId" }
                ?: "皮皮虾作品 #$itemId",
            content = content,
            tags = ParserSupport.extractHashtagsFromText(content),
            mediaItems = mediaItems,
            parseEngineSource = "皮皮虾引擎 · 原片无水印直连"
        )
        return ParserSupport.applyDuplicateInfo(post, previousHistory)
    }

    private fun extractUrlFromUrlListObj(obj: JsonObject?): String? {
        if (obj == null) return null
        val arr = obj.optArr("url_list") ?: return null
        for (i in 0 until arr.size()) {
            val el = arr.get(i)
            val candidate = when {
                el.isJsonPrimitive -> el.asString
                el.isJsonObject -> el.asJsonObject.optStr("url")
                else -> ""
            }
            if (candidate.startsWith("http")) {
                return ParserSupport.normalizeMediaUrl(candidate)
            }
        }
        return null
    }
}
