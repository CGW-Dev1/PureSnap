package com.puresnap.app.model

import androidx.compose.ui.graphics.Color

/**
 * 支持的国内外多平台定义
 * - 国内平台：小红书、抖音、快手、微博、B站、最右、皮皮虾
 * - 国外平台：Instagram、TikTok、X (Twitter)、Threads、Pinterest、YouTube、Reddit
 */
enum class PlatformType(
    val displayName: String,
    val shortTag: String,
    val folderName: String,
    val badgeColorHex: Long,
    val isFirstBatch: Boolean,
    val isDomestic: Boolean,
    val capabilityHint: String,
    val hostKeywords: List<String>
) {
    // ────────── 国内主流平台 ──────────
    XIAOHONGSHU(
        displayName = "小红书",
        shortTag = "小红书",
        folderName = "Xiaohongshu",
        badgeColorHex = 0xFFFF2442,
        isFirstBatch = true,
        isDomestic = true,
        capabilityHint = "原图 · 实况 · 视频",
        hostKeywords = listOf("xhslink.com", "xiaohongshu.com", "xhs.cn", "xhscdn.com")
    ),
    DOUYIN(
        displayName = "抖音",
        shortTag = "抖音",
        folderName = "Douyin",
        badgeColorHex = 0xFF111827,
        isFirstBatch = true,
        isDomestic = true,
        capabilityHint = "原图 · 实况 · 视频 · BGM",
        hostKeywords = listOf("v.douyin.com", "douyin.com", "iesdouyin.com")
    ),
    KUAISHOU(
        displayName = "快手",
        shortTag = "快手",
        folderName = "Kuaishou",
        badgeColorHex = 0xFFFF5000,
        isFirstBatch = true,
        isDomestic = true,
        capabilityHint = "图集 · 视频 · BGM",
        hostKeywords = listOf("v.kuaishou.com", "kuaishou.com", "chenzhongtech.com", "kuaishouapp.com")
    ),
    WEIBO(
        displayName = "微博",
        shortTag = "微博",
        folderName = "Weibo",
        badgeColorHex = 0xFFF59E0B,
        isFirstBatch = false,
        isDomestic = true,
        capabilityHint = "原图 · 实况 · 视频",
        hostKeywords = listOf("weibo.com", "weibo.cn", "m.weibo.cn", "t.cn", "sinaimg.cn")
    ),
    BILIBILI(
        displayName = "Bilibili",
        shortTag = "B站",
        folderName = "Bilibili",
        badgeColorHex = 0xFF00AEEC,
        isFirstBatch = false,
        isDomestic = true,
        capabilityHint = "动态原图 · 视频 · 封面",
        hostKeywords = listOf("b23.tv", "bilibili.com", "bili2233.cn")
    ),
    ZUIYOU(
        displayName = "最右",
        shortTag = "最右",
        folderName = "Zuiyou",
        badgeColorHex = 0xFF3B82F6,
        isFirstBatch = true,
        isDomestic = true,
        capabilityHint = "无水印原图 · 视频",
        hostKeywords = listOf("izuiyou.com", "ippzone.com", "zuiyou.com")
    ),
    PIPIXIA(
        displayName = "皮皮虾",
        shortTag = "皮皮虾",
        folderName = "Pipixia",
        badgeColorHex = 0xFFEC4899,
        isFirstBatch = false,
        isDomestic = true,
        capabilityHint = "原图 · 无水印视频",
        hostKeywords = listOf("h5.pipix.com", "pipix.com")
    ),

    // ────────── 国外主流平台 ──────────
    INSTAGRAM(
        displayName = "Instagram",
        shortTag = "IG",
        folderName = "Instagram",
        badgeColorHex = 0xFFE1306C,
        isFirstBatch = false,
        isDomestic = false,
        capabilityHint = "原图轮播 · Reels 视频",
        hostKeywords = listOf("instagram.com", "instagr.am", "ddinstagram.com", "kkinstagram.com")
    ),
    TIKTOK(
        displayName = "TikTok",
        shortTag = "TikTok",
        folderName = "TikTok",
        badgeColorHex = 0xFF0F172A,
        isFirstBatch = false,
        isDomestic = false,
        capabilityHint = "图集 · 无水印视频 · 音频",
        hostKeywords = listOf("tiktok.com", "vm.tiktok.com", "vt.tiktok.com")
    ),
    TWITTER(
        displayName = "X / Twitter",
        shortTag = "X",
        folderName = "Twitter_X",
        badgeColorHex = 0xFF1D9BF0,
        isFirstBatch = false,
        isDomestic = false,
        capabilityHint = "orig 原图 · GIF · 视频",
        hostKeywords = listOf("x.com", "twitter.com", "t.co", "fxtwitter.com", "vxtwitter.com", "fixupx.com", "pbs.twimg.com")
    ),
    THREADS(
        displayName = "Threads",
        shortTag = "Threads",
        folderName = "Threads",
        badgeColorHex = 0xFF334155,
        isFirstBatch = false,
        isDomestic = false,
        capabilityHint = "原图 · 实况 · 视频",
        hostKeywords = listOf("threads.net", "threads.com")
    ),
    PINTEREST(
        displayName = "Pinterest",
        shortTag = "Pin",
        folderName = "Pinterest",
        badgeColorHex = 0xFFE60023,
        isFirstBatch = false,
        isDomestic = false,
        capabilityHint = "originals 无损原图 · 视频",
        hostKeywords = listOf("pinterest.com", "pin.it", "pinimg.com")
    ),
    YOUTUBE(
        displayName = "YouTube",
        shortTag = "YouTube",
        folderName = "YouTube",
        badgeColorHex = 0xFFFF0000,
        isFirstBatch = false,
        isDomestic = false,
        capabilityHint = "4K 封面 · Shorts/视频",
        hostKeywords = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")
    ),
    REDDIT(
        displayName = "Reddit",
        shortTag = "Reddit",
        folderName = "Reddit",
        badgeColorHex = 0xFFFF4500,
        isFirstBatch = false,
        isDomestic = false,
        capabilityHint = "画廊原图 · 视频",
        hostKeywords = listOf("reddit.com", "redd.it", "v.redd.it", "i.redd.it")
    ),

    // ────────── 通用直链与网页嗅探 ──────────
    UNKNOWN(
        displayName = "通用直链",
        shortTag = "直链",
        folderName = "General",
        badgeColorHex = 0xFF0D9488,
        isFirstBatch = false,
        isDomestic = true,
        capabilityHint = "原图直链 · 网页嗅探",
        hostKeywords = emptyList()
    );

    val brandColor: Color
        get() = Color(badgeColorHex)

    companion object {
        val domesticPlatforms: List<PlatformType>
            get() = entries.filter { it != UNKNOWN && it.isDomestic }

        val internationalPlatforms: List<PlatformType>
            get() = entries.filter { it != UNKNOWN && !it.isDomestic }
    }
}
