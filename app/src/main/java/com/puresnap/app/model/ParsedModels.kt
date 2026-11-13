package com.puresnap.app.model

/**
 * 媒体元素类型：支持静态原图、Live 实况照片、独立高清视频、背景音频
 */
enum class MediaKind(val label: String) {
    IMAGE("原图"),
    LIVE_PHOTO("实况"),
    VIDEO("视频"),
    AUDIO("音频")
}

/**
 * 缩略图左上角画质角标
 */
enum class QualityBadge(val text: String) {
    FOUR_K("4K"),
    RAW("原图"),
    LIVE("实况"),
    VIDEO_HD("1080P"),
    HEIC_RAW("HEIC")
}

/**
 * 原图导出模式：默认原图直出，零二次重编码画质损失
 */
enum class ExportFormatMode(
    val title: String,
    val subtitle: String
) {
    KEEP_ORIGINAL(
        title = "原图直出 · 零损直存（推荐·默认）",
        subtitle = "直接保存 CDN 原始字节流（JPG/PNG/HEIC/WEBP），完整保留 Display P3 色彩空间、EXIF 与原生分辨率，零二次压缩"
    ),
    COMPATIBLE_JPEG_100(
        title = "兼容转封装模式（WebP/HEIC → 100% JPG）",
        subtitle = "已是 JPG/PNG 时直接原样直出不重编码；仅当遇到 WebP/HEIC 时校正方向后以 100% 品质转封装为通用 JPG"
    ),
    LOSSLESS_PNG(
        title = "无损 PNG 模式",
        subtitle = "已是 PNG 时直接原样直出；其他格式无损编码为 PNG 保留完整像素细节"
    )
}

/**
 * 实况照片（Live Photo）导出模式
 */
enum class LivePhotoExportMode(
    val title: String,
    val subtitle: String
) {
    MOTION_PHOTO_JPEG(
        title = "Android 原生动态照片 (.MP.jpg · 推荐)",
        subtitle = "合成为标准 Motion Photo，小米/OPPO/vivo/华为/三星/Google 相册原生识别为实况并可长按播放"
    ),
    DUAL_IMAGE_AND_MP4(
        title = "原图 + 独立 MP4 双轨保存",
        subtitle = "分别保存 100% 原始无损静态图与独立无水印 MP4 动态短片"
    ),
    STATIC_IMAGE_ONLY(
        title = "仅保存静态高清原图",
        subtitle = "忽略动态视频轨，仅保留实况中的静态高画质原图"
    )
}

/**
 * 异常与边缘场景状态码
 */
enum class ParseErrorType(
    val title: String,
    val message: String
) {
    NONE("", ""),
    NO_VALID_LINK(
        title = "未识别到有效分享链接",
        message = "请复制包含小红书、抖音、快手、微博、B站、Instagram、TikTok、X、Pinterest 等平台的分享口令或 http/https 链接后重试。"
    ),
    PRIVATE_OR_DELETED(
        title = "无法提取该作品",
        message = "该作品已被作者删除或设为私密（仅好友/自己可见），无法提取无水印原片。"
    ),
    ANTI_SCRAPE_TIMEOUT(
        title = "平台风控验证或网络访问受限",
        message = "目标平台触发风控验证、登录墙或网络超时（国际平台请确认网络环境），可使用「WebView 辅助验证」或「配置 Cookie」兜底提取。"
    ),
    NETWORK_ERROR(
        title = "网络连接异常",
        message = "无法连接至目标平台服务器，请检查网络连接或开启代理后重试。"
    )
}

/**
 * 单张原图 / 实况 / 视频条目模型
 */
data class MediaItem(
    val id: String,
    val index: Int,
    val kind: MediaKind,
    val badge: QualityBadge,
    val originUrl: String,
    val previewUrl: String = originUrl,
    val liveVideoUrl: String? = null, // 实况照片对应的高清 MP4/MOV 流地址
    val fallbackUrls: List<String> = emptyList(), // 多 CDN 无损候选直链（优先尝试真原文件，自动降级）
    val width: Int = 3024,
    val height: Int = 4032,
    val format: String = "JPG",       // JPG, PNG, WEBP, HEIC, GIF, MP4
    val colorSpace: String = "Display P3",
    val estimatedSizeBytes: Long = 0L,
    val selected: Boolean = true,
    val themeTitle: String = "",
    val visualSeed: Int = index,
    val durationMs: Long = 0L
) {
    val resolutionLabel: String
        get() = "${width}×${height}"

    val formattedSize: String
        get() {
            if (estimatedSizeBytes <= 0L) return format
            val mb = estimatedSizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) String.format("%.1f MB", mb)
            else String.format("%.0f KB", (estimatedSizeBytes / 1024.0).coerceAtLeast(1.0))
        }

    val isVideo: Boolean
        get() = kind == MediaKind.VIDEO

    val isLivePhoto: Boolean
        get() = kind == MediaKind.LIVE_PHOTO || (!liveVideoUrl.isNullOrBlank() && kind != MediaKind.VIDEO)

    /**
     * 生成按优先级排序的候选下载直链列表：
     * @param preferRawUntouched 若为 true，优先尝试无查询参数/原文件直链（如 ci.xiaohongshu.com/<traceId>），失败再回退
     */
    fun orderedDownloadCandidates(preferRawUntouched: Boolean = true): List<String> {
        val list = linkedSetOf<String>()
        if (preferRawUntouched) {
            fallbackUrls.filter { it.startsWith("http") }.forEach { list.add(it) }
            if (originUrl.startsWith("http")) list.add(originUrl)
            if (previewUrl.startsWith("http")) list.add(previewUrl)
        } else {
            if (originUrl.startsWith("http")) list.add(originUrl)
            fallbackUrls.filter { it.startsWith("http") }.forEach { list.add(it) }
            if (previewUrl.startsWith("http")) list.add(previewUrl)
        }
        return list.toList()
    }
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
    val postTypeLabel: String, // 例如 "小红书·实况图"、"X·原图"、"抖音·高清视频"
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

    val livePhotoCount: Int
        get() = mediaItems.count { it.isLivePhoto }

    val videoCount: Int
        get() = mediaItems.count { it.isVideo }

    val imageCount: Int
        get() = mediaItems.count { !it.isVideo }

    val hasLivePhotos: Boolean
        get() = livePhotoCount > 0

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
