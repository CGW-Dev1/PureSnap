package com.puresnap.app.model

import androidx.compose.ui.graphics.Color

/**
 * 支持的多平台定义（首批 P0：小红书、抖音、最右；二期扩展：快手、皮皮虾、微博、Bilibili）
 */
enum class PlatformType(
    val displayName: String,
    val shortTag: String,
    val folderName: String,
    val badgeColorHex: Long,
    val isFirstBatch: Boolean,
    val hostKeywords: List<String>
) {
    XIAOHONGSHU(
        displayName = "小红书",
        shortTag = "小红书",
        folderName = "Xiaohongshu",
        badgeColorHex = 0xFFFF2442,
        isFirstBatch = true,
        hostKeywords = listOf("xhslink.com", "xiaohongshu.com", "xhs.cn")
    ),
    DOUYIN(
        displayName = "抖音",
        shortTag = "抖音",
        folderName = "Douyin",
        badgeColorHex = 0xFF111827,
        isFirstBatch = true,
        hostKeywords = listOf("v.douyin.com", "douyin.com", "iesdouyin.com")
    ),
    ZUIYOU(
        displayName = "最右",
        shortTag = "最右",
        folderName = "Zuiyou",
        badgeColorHex = 0xFF3B82F6,
        isFirstBatch = true,
        hostKeywords = listOf("izuiyou.com", "ippzone.com", "zuiyou.com")
    ),
    KUAISHOU(
        displayName = "快手",
        shortTag = "快手",
        folderName = "Kuaishou",
        badgeColorHex = 0xFFFF5000,
        isFirstBatch = false,
        hostKeywords = listOf("v.kuaishou.com", "kuaishou.com", "chenzhongtech.com")
    ),
    PIPIXIA(
        displayName = "皮皮虾",
        shortTag = "皮皮虾",
        folderName = "Pipixia",
        badgeColorHex = 0xFFEC4899,
        isFirstBatch = false,
        hostKeywords = listOf("h5.pipix.com", "pipix.com")
    ),
    WEIBO(
        displayName = "微博",
        shortTag = "微博",
        folderName = "Weibo",
        badgeColorHex = 0xFFF59E0B,
        isFirstBatch = false,
        hostKeywords = listOf("weibo.com", "weibo.cn", "t.cn")
    ),
    BILIBILI(
        displayName = "Bilibili",
        shortTag = "B站",
        folderName = "Bilibili",
        badgeColorHex = 0xFF00AEEC,
        isFirstBatch = false,
        hostKeywords = listOf("b23.tv", "bilibili.com")
    ),
    UNKNOWN(
        displayName = "通用平台",
        shortTag = "直链",
        folderName = "General",
        badgeColorHex = 0xFF0D9488,
        isFirstBatch = false,
        hostKeywords = emptyList()
    );

    val brandColor: Color
        get() = Color(badgeColorHex)
}
