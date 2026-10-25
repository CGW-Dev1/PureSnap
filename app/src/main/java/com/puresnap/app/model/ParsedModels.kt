package com.puresnap.app.model

/**
 * 媒体元素类型：支持静态原图、小红书实况图（静态高清图 + 动态 MP4）、独立高清视频、混合贴
 */
enum class MediaKind(val label: String) {
    IMAGE("原图"),
    LIVE_PHOTO("实况"),
    VIDEO("视频"),
    AUDIO("音频")
}

/**
 * 缩略图左上角画质/实况角标
 */
enum class QualityBadge(val text: String) {
    FOUR_K("4K"),
    LIVE("LIVE"),
    RAW("原图"),
    VIDEO_HD("1080P"),
    HEIC_RAW("HEIC")
}

/**
 * 原图格式转换引擎模式（P1）
 */
enum class ExportFormatMode(
    val title: String,
    val subtitle: String
) {
    COMPATIBLE_JPEG_100(
        title = "兼容模式（推荐·默认开启）",
        subtitle = "检测到 WebP/HEIC 时以 100% 品质无损转封装为 JPG，彻底解决相册打不开或发朋友圈变糊"
    ),
    LOSSLESS_PNG(
        title = "无损 PNG 模式",
        subtitle = "转码为最高质量无损 PNG 格式，保留完整像素细节"
    ),
    KEEP_ORIGINAL(
        title = "保持原格式直出",
        subtitle = "直接保存 CDN 原始 WebP / HEIC / PNG 字节流与 Display P3 色彩空间"
    )
}

/**
 * 小红书实况图（Live Photo）导出模式
 */
enum class LivePhotoExportMode(
    val title: String,
    val subtitle: String
) {
    BOTH_IMAGE_AND_MP4(
        title = "双模同时导出（原图 + MP4）",
        subtitle = "同时保存静态高清原图与高清动态视频 (.mp4)"
    ),
    STATIC_IMAGE_ONLY(
        title = "仅保存静态高清原图",
        subtitle = "仅导出无水印最高分辨率静态帧原图"
    ),
    LIVE_VIDEO_ONLY(
        title = "仅导出动态视频 (MP4)",
        subtitle = "仅将实况动态流导出为高清 MP4 视频"
    )
}

/**
 * 异常与边缘场景状态码（第五章规范）
 */
enum class ParseErrorType(
    val title: String,
    val message: String
) {
    NONE("", ""),
    NO_VALID_LINK(
        title = "未识别到有效分享链接",
        message = "请复制包含小红书、抖音、最右、快手等平台的完整分享口令或 http/https 短链后重试。"
    ),
    PRIVATE_OR_DELETED(
        title = "无法提取该作品",
        message = "该作品已被作者删除或设为私密（仅好友/自己可见），无法提取无水印原片。"
    ),
    ANTI_SCRAPE_TIMEOUT(
        title = "平台风控验证或请求超时",
        message = "目标平台触发滑块验证或短时访问限制，可使用「内置 WebView 辅助验证」或「粘贴个人 Cookie」通道兜底。"
    ),
    NETWORK_ERROR(
        title = "网络连接异常",
        message = "无法连接至解析节点，请检查网络设置或切换至本地解析兜底模式。"
    )
}

/**
 * 单张原图 / 实况图 / 视频条目模型
 */
data class MediaItem(
    val id: String,
    val index: Int,
    val kind: MediaKind,
    val badge: QualityBadge,
    val originUrl: String,
    val previewUrl: String = originUrl,
    val liveVideoUrl: String? = null, // 小红书 Live Photo 对应的高清 MP4 流地址
    val width: Int = 3024,
    val height: Int = 4032,
    val format: String = "WEBP",      // WEBP, HEIC, PNG, JPG, MP4
    val colorSpace: String = "Display P3",
    val estimatedSizeBytes: Long = 6_850_000L,
    val selected: Boolean = true,
    val themeTitle: String = "",
    val visualSeed: Int = index
) {
    val resolutionLabel: String
        get() = "${width}×${height}"

    val formattedSize: String
        get() {
            val mb = estimatedSizeBytes / (1024.0 * 1024.0)
            return String.format("%.1f MB", mb)
        }

    val isLivePhoto: Boolean
        get() = kind == MediaKind.LIVE_PHOTO || badge == QualityBadge.LIVE

    val isVideo: Boolean
        get() = kind == MediaKind.VIDEO
}

/**
 * 背景音乐（BGM）独立提取信息
 */
data class AudioTrackInfo(
    val id: String,
    val title: String,
    val author: String,
    val url: String,
    val format: String = "MP3",
    val durationSec: Int = 28,
    val bitrateKbps: Int = 320
)

/**
 * 标准化多平台解析结果模型
 */
data class ParsedPost(
    val id: String,
    val sourceShareText: String,
    val extractedUrl: String,
    val noteId: String,
    val platform: PlatformType,
    val postTypeLabel: String, // 例如 "小红书·图集"、"小红书·实况混合"、"抖音·高清视频"
    val authorName: String,
    val authorHandle: String,
    val authorAvatarUrl: String,
    val coverUrl: String,
    val title: String,
    val content: String,
    val tags: List<String>,
    val mediaItems: List<MediaItem>,
    val bgmAudio: AudioTrackInfo? = null,
    val parseEngineSource: String = "混合引擎 · CDN 原画直连",
    val parsedAtMillis: Long = System.currentTimeMillis(),
    val isDuplicateDownload: Boolean = false,
    val previousDownloadTimeMillis: Long? = null
) {
    val selectedItems: List<MediaItem>
        get() = mediaItems.filter { it.selected }

    val selectedCount: Int
        get() = mediaItems.count { it.selected }

    val totalCount: Int
        get() = mediaItems.size

    val allSelected: Boolean
        get() = mediaItems.isNotEmpty() && mediaItems.all { it.selected }

    val fullCopywritingText: String
        get() = buildString {
            if (title.isNotBlank()) {
                appendLine(title)
                appendLine()
            }
            if (content.isNotBlank()) {
                appendLine(content)
            }
            if (tags.isNotEmpty()) {
                appendLine()
                append(tags.joinToString(" ") { if (it.startsWith("#")) it else "#$it" })
            }
        }.trim()
}
