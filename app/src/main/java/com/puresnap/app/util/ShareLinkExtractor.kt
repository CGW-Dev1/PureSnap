package com.puresnap.app.util

import com.puresnap.app.model.PlatformType
import java.net.URI
import java.util.regex.Pattern

/**
 * 口令清洗与正则提取引擎（国内外多平台 + 多链接批量提取）
 */
object ShareLinkExtractor {

    private const val KNOWN_DOMAINS_REGEX =
        """xhslink\.com|xiaohongshu\.com|xhs\.cn|douyin\.com|iesdouyin\.com|izuiyou\.com|ippzone\.com|zuiyou\.com|kuaishou\.com|chenzhongtech\.com|kuaishouapp\.com|pipix\.com|weibo\.com|weibo\.cn|t\.cn|b23\.tv|bilibili\.com|bili2233\.cn|instagram\.com|instagr\.am|ddinstagram\.com|kkinstagram\.com|tiktok\.com|vm\.tiktok\.com|vt\.tiktok\.com|x\.com|twitter\.com|t\.co|fxtwitter\.com|vxtwitter\.com|fixupx\.com|threads\.net|threads\.com|pinterest\.com|pin\.it|youtube\.com|youtu\.be|reddit\.com|redd\.it|v\.redd\.it|i\.redd\.it"""

    private val URL_PATTERN: Pattern = Pattern.compile(
        """(?:https?://)?(?:[a-zA-Z0-9-]+\.)*(?:$KNOWN_DOMAINS_REGEX)(?:/[^\s\u4e00-\u9fa5，。！？、“”‘’【】（）《》\[\]<>\"']*)?""",
        Pattern.CASE_INSENSITIVE
    )

    private val GENERIC_HTTP_PATTERN: Pattern = Pattern.compile(
        """https?://[^\s\u4e00-\u9fa5，。！？、“”‘’【】（）《》\[\]<>\"']+""",
        Pattern.CASE_INSENSITIVE
    )

    private val DIRECT_MEDIA_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "mp4", "mov", "m4v"
    )

    data class ExtractionResult(
        val rawInput: String,
        val cleanUrl: String?,
        val platform: PlatformType,
        val cleanedCaptionHint: String,
        val isPrivateOrDeletedHint: Boolean,
        val isAntiScrapeHint: Boolean
    )

    /**
     * 从混合分享口令中提取主链接并识别平台
     */
    fun extract(rawShareText: String): ExtractionResult {
        val trimmed = sanitizeInputText(rawShareText)
        if (trimmed.isEmpty()) {
            return ExtractionResult(
                rawInput = rawShareText,
                cleanUrl = null,
                platform = PlatformType.UNKNOWN,
                cleanedCaptionHint = "",
                isPrivateOrDeletedHint = false,
                isAntiScrapeHint = false
            )
        }

        val platformMatcher = URL_PATTERN.matcher(trimmed)
        var foundUrl: String? = null
        if (platformMatcher.find()) {
            foundUrl = sanitizeTrailingPunctuation(platformMatcher.group(0) ?: "")
        } else {
            val genericMatcher = GENERIC_HTTP_PATTERN.matcher(trimmed)
            if (genericMatcher.find()) {
                foundUrl = sanitizeTrailingPunctuation(genericMatcher.group(0) ?: "")
            }
        }

        val normalizedUrl = foundUrl?.let(::normalizeProtocol)
        val detectedPlatform = detectPlatform(trimmed, normalizedUrl)
        val captionHint = cleanCaptionHint(trimmed, foundUrl)

        val lower = trimmed.lowercase()
        val isPrivateOrDeleted = lower.contains("private") ||
            lower.contains("deleted") ||
            trimmed.contains("私密") ||
            trimmed.contains("仅好友可见") ||
            trimmed.contains("已删除")

        val isAntiScrape = lower.contains("captcha") ||
            trimmed.contains("风控验证")

        return ExtractionResult(
            rawInput = rawShareText,
            cleanUrl = normalizedUrl,
            platform = detectedPlatform,
            cleanedCaptionHint = captionHint,
            isPrivateOrDeletedHint = isPrivateOrDeleted,
            isAntiScrapeHint = isAntiScrape
        )
    }

    /**
     * 批量提取输入文本中的所有去重有效链接（支持用户一次粘贴多条分享口令/链接）
     */
    fun extractAllUrls(rawShareText: String): List<ExtractionResult> {
        val trimmed = sanitizeInputText(rawShareText)
        if (trimmed.isEmpty()) return emptyList()

        val foundUrls = linkedSetOf<String>()
        val platformMatcher = URL_PATTERN.matcher(trimmed)
        while (platformMatcher.find()) {
            val raw = sanitizeTrailingPunctuation(platformMatcher.group(0) ?: "")
            if (raw.isNotBlank()) foundUrls.add(normalizeProtocol(raw))
        }
        val genericMatcher = GENERIC_HTTP_PATTERN.matcher(trimmed)
        while (genericMatcher.find()) {
            val raw = sanitizeTrailingPunctuation(genericMatcher.group(0) ?: "")
            if (raw.isNotBlank()) foundUrls.add(normalizeProtocol(raw))
        }

        return foundUrls.map { url ->
            val platform = detectPlatform(url, url)
            ExtractionResult(
                rawInput = url,
                cleanUrl = url,
                platform = platform,
                cleanedCaptionHint = "",
                isPrivateOrDeletedHint = false,
                isAntiScrapeHint = false
            )
        }
    }

    /**
     * 判断剪贴板内容是否为有效社交平台分享口令或直接媒体 URL
     */
    fun isValidClipboardShareText(text: String?): Boolean {
        if (text.isNullOrBlank() || text.length < 8 || text.length > 4000) return false
        val result = extract(text)
        val url = result.cleanUrl ?: return false
        return result.platform != PlatformType.UNKNOWN || isDirectMediaUrl(url)
    }

    fun isDirectMediaUrl(url: String): Boolean {
        val path = url.substringBefore("?").substringBefore("#").lowercase()
        val ext = path.substringAfterLast('.', "")
        return ext in DIRECT_MEDIA_EXTENSIONS
    }

    /**
     * 优先基于 URL Host 精确识别平台（防止混合文案中出现其他平台关键词导致误判），
     * 若无明确 Host 再回退到关键词匹配。
     */
    fun detectPlatform(fullText: String, extractedUrl: String?): PlatformType {
        val host = extractedUrl?.let {
            runCatching { URI(it).host?.lowercase() }.getOrNull()
        }?.removePrefix("www.")?.removePrefix("m.") ?: ""

        if (host.isNotBlank()) {
            for (platform in PlatformType.entries) {
                if (platform == PlatformType.UNKNOWN) continue
                if (platform.hostKeywords.any { kw ->
                        val k = kw.lowercase()
                        host == k || host.endsWith(".$k")
                    }
                ) {
                    return platform
                }
            }
            if (host.startsWith("pinterest.")) return PlatformType.PINTEREST
        }

        val fallbackText = if (host.isBlank()) ((extractedUrl ?: "") + " " + fullText).lowercase() else fullText.lowercase()
        for (platform in PlatformType.entries) {
            if (platform == PlatformType.UNKNOWN) continue
            if (platform.hostKeywords.any { kw ->
                    val k = kw.lowercase()
                    Regex("""(?:^|[^a-z0-9.])${Regex.escape(k)}(?:$|[^a-z0-9.])""").containsMatchIn(fallbackText)
                }
            ) {
                return platform
            }
        }
        return when {
            fallbackText.contains("小红书") || fallbackText.contains("xhs") -> PlatformType.XIAOHONGSHU
            fallbackText.contains("抖音") || fallbackText.contains("douyin") -> PlatformType.DOUYIN
            fallbackText.contains("快手") || fallbackText.contains("kuaishou") -> PlatformType.KUAISHOU
            fallbackText.contains("最右") || fallbackText.contains("zuiyou") -> PlatformType.ZUIYOU
            fallbackText.contains("皮皮虾") || fallbackText.contains("pipix") -> PlatformType.PIPIXIA
            fallbackText.contains("微博") || fallbackText.contains("weibo") -> PlatformType.WEIBO
            fallbackText.contains("哔哩哔哩") || fallbackText.contains("bilibili") || fallbackText.contains("b站") -> PlatformType.BILIBILI
            fallbackText.contains("instagram") -> PlatformType.INSTAGRAM
            fallbackText.contains("tiktok") -> PlatformType.TIKTOK
            fallbackText.contains("twitter") -> PlatformType.TWITTER
            fallbackText.contains("threads.net") || fallbackText.contains("threads.com") -> PlatformType.THREADS
            fallbackText.contains("pinterest") -> PlatformType.PINTEREST
            fallbackText.contains("youtube") || fallbackText.contains("youtu.be") -> PlatformType.YOUTUBE
            fallbackText.contains("reddit") -> PlatformType.REDDIT
            else -> PlatformType.UNKNOWN
        }
    }

    /**
     * 从链接中提取稳定的作品 ID
     */
    fun extractWorkId(url: String, platform: PlatformType = PlatformType.UNKNOWN): String {
        val platformSpecificRegexes = when (platform) {
            PlatformType.BILIBILI -> listOf(
                Regex("""/(BV[0-9A-Za-z]{8,14})"""),
                Regex("""/(av\d+)""", RegexOption.IGNORE_CASE),
                Regex("""/opus/(\d+)""")
            )
            PlatformType.TWITTER -> listOf(
                Regex("""/status/(\d+)""")
            )
            PlatformType.INSTAGRAM, PlatformType.THREADS -> listOf(
                Regex("""/(?:p|reel|reels|tv|post)/([A-Za-z0-9_-]{5,32})""")
            )
            PlatformType.YOUTUBE -> listOf(
                Regex("""[?&]v=([A-Za-z0-9_-]{8,16})"""),
                Regex("""youtu\.be/([A-Za-z0-9_-]{8,16})"""),
                Regex("""/shorts/([A-Za-z0-9_-]{8,16})""")
            )
            PlatformType.PINTEREST -> listOf(
                Regex("""/pin/(\d+)""")
            )
            PlatformType.REDDIT -> listOf(
                Regex("""/comments/([A-Za-z0-9]+)""")
            )
            else -> emptyList()
        }

        for (regex in platformSpecificRegexes) {
            val match = regex.find(url)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }

        val genericRegexes = listOf(
            Regex("""/(?:explore|discovery/item|item|note|photo|video|slides|status|detail)/([a-zA-Z0-9_-]{6,36})"""),
            Regex("""(?:aweme_id|modal_id|noteId|photoId|pid|item_id|id)=([a-zA-Z0-9_-]{5,36})"""),
            Regex("""https?://[^/]+/([a-zA-Z0-9_-]{5,24})""")
        )
        for (regex in genericRegexes) {
            val match = regex.find(url)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return "${platform.name.lowercase()}_${kotlin.math.abs(url.hashCode())}"
    }

    private fun sanitizeInputText(raw: String): String {
        return raw
            .replace("\u200B", "")
            .replace("\uFEFF", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .trim()
    }

    private fun normalizeProtocol(url: String): String {
        return if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            url
        } else {
            "https://$url"
        }
    }

    private fun cleanCaptionHint(trimmed: String, foundUrl: String?): String {
        return trimmed
            .replace(foundUrl ?: "", "")
            .replace(Regex("""\d+\.\d+\s+[a-zA-Z]{2,}:/.*"""), "")
            .replace(Regex("""复制本条消息.*"""), "")
            .replace(Regex("""复制后打开【.*?】.*"""), "")
            .replace(Regex("""长按复制此条消息.*"""), "")
            .replace(Regex("""打开(?:小红书|抖音|最右|快手|皮皮虾|微博|B站).*"""), "")
            .trim()
    }

    private fun sanitizeTrailingPunctuation(rawUrl: String): String {
        return rawUrl.trimEnd('.', ',', ';', '!', '?', ')', ']', '}', '"', '\'', '，', '。', '！', '？', '】', '）')
    }
}
