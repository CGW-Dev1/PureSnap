package com.puresnap.app.util

import com.puresnap.app.model.PlatformType
import java.util.regex.Pattern

/**
 * 口令清洗与正则提取引擎（P0）
 * 自动从包含乱码、文案、表情符号的分享口令中精准剥离出短链与目标平台类型。
 */
object ShareLinkExtractor {

    // 匹配标准 http/https 链接或无协议头的常见短链域名
    private val URL_PATTERN: Pattern = Pattern.compile(
        """(?:https?://)?(?:[a-zA-Z0-9-]+\.)+(?:xhslink\.com|xiaohongshu\.com|xhs\.cn|douyin\.com|iesdouyin\.com|izuiyou\.com|ippzone\.com|zuiyou\.com|kuaishou\.com|chenzhongtech\.com|pipix\.com|weibo\.com|weibo\.cn|t\.cn|b23\.tv|bilibili\.com)(?:/[^\s\u4e00-\u9fa5，。！？、“”‘’【】（）《》\[\]<>\"']*)?""",
        Pattern.CASE_INSENSITIVE
    )

    private val GENERIC_HTTP_PATTERN: Pattern = Pattern.compile(
        """https?://[^\s\u4e00-\u9fa5，。！？、“”‘’【】（）《》\[\]<>\"']+""",
        Pattern.CASE_INSENSITIVE
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
     * 从混合分享口令中提取纯净短链并识别平台
     */
    fun extract(rawShareText: String): ExtractionResult {
        val trimmed = rawShareText
            .replace("\u200B", "") // 零宽字符清洗
            .replace("\uFEFF", "")
            .trim()

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

        // 优先匹配主流平台短链
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

        val normalizedUrl = foundUrl?.let {
            if (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)) {
                it
            } else {
                "https://$it"
            }
        }

        val detectedPlatform = detectPlatform(trimmed, normalizedUrl)

        // 剥离口令噪声，提取可能附带的标题文案线索
        val captionHint = trimmed
            .replace(foundUrl ?: "", "")
            .replace(Regex("""\d+\.\d+\s+[a-zA-Z]{2,}:/.*"""), "")
            .replace(Regex("""复制本条消息.*"""), "")
            .replace(Regex("""复制后打开【.*?】.*"""), "")
            .replace(Regex("""长按复制此条消息.*"""), "")
            .replace(Regex("""打开(?:小红书|抖音|最右|快手|皮皮虾).*"""), "")
            .trim()

        val lower = trimmed.lowercase()
        val isPrivateOrDeleted = lower.contains("private") ||
            lower.contains("deleted") ||
            lower.contains("404") ||
            trimmed.contains("私密") ||
            trimmed.contains("仅好友可见") ||
            trimmed.contains("已删除")

        val isAntiScrape = lower.contains("captcha") ||
            lower.contains("verify") ||
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
     * 判断剪贴板内容是否为有效社交平台分享口令
     */
    fun isValidClipboardShareText(text: String?): Boolean {
        if (text.isNullOrBlank() || text.length < 8 || text.length > 2000) return false
        val result = extract(text)
        return result.cleanUrl != null && result.platform != PlatformType.UNKNOWN
    }

    fun detectPlatform(fullText: String, extractedUrl: String?): PlatformType {
        val target = ((extractedUrl ?: "") + " " + fullText).lowercase()
        for (platform in PlatformType.entries) {
            if (platform == PlatformType.UNKNOWN) continue
            if (platform.hostKeywords.any { target.contains(it.lowercase()) }) {
                return platform
            }
        }
        return when {
            target.contains("小红书") || target.contains("xhs") -> PlatformType.XIAOHONGSHU
            target.contains("抖音") || target.contains("douyin") -> PlatformType.DOUYIN
            target.contains("最右") || target.contains("zuiyou") -> PlatformType.ZUIYOU
            target.contains("快手") || target.contains("kuaishou") -> PlatformType.KUAISHOU
            target.contains("皮皮虾") || target.contains("pipix") -> PlatformType.PIPIXIA
            target.contains("微博") || target.contains("weibo") -> PlatformType.WEIBO
            target.contains("哔哩哔哩") || target.contains("bilibili") || target.contains("b站") -> PlatformType.BILIBILI
            extractedUrl != null -> PlatformType.UNKNOWN
            else -> PlatformType.UNKNOWN
        }
    }

    /**
     * 从短链或正文中提取或生成稳定的作品 ID (noteId / aweme_id / pid)
     */
    fun extractWorkId(url: String, platform: PlatformType): String {
        val regexes = listOf(
            Regex("""/(?:explore|discovery/item|item|note)/([a-zA-Z0-9_-]{8,32})"""),
            Regex("""(?:aweme_id|modal_id|noteId|pid|id)=([a-zA-Z0-9_-]{5,32})"""),
            Regex("""https?://[^/]+/([a-zA-Z0-9_-]{5,24})""")
        )
        for (regex in regexes) {
            val match = regex.find(url)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return "${platform.name.lowercase()}_${kotlin.math.abs(url.hashCode())}"
    }

    private fun sanitizeTrailingPunctuation(rawUrl: String): String {
        return rawUrl.trimEnd('.', ',', ';', '!', '?', ')', ']', '}', '"', '\'', '，', '。', '！', '？', '】', '）')
    }
}
