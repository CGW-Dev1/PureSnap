package com.puresnap.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地解析历史记录表（P1）
 * 保存历史解析记录（含原链接、文案、作者、图集元数据 JSON），防止原帖被删后无法找回。
 */
@Entity(
    tableName = "parse_history",
    indices = [Index(value = ["extractedUrl"])]
)
data class ParseHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val postId: String,
    val sourceShareText: String,
    val extractedUrl: String,
    val noteId: String,
    val platform: String,
    val postTypeLabel: String,
    val authorName: String,
    val authorHandle: String,
    val authorAvatarUrl: String,
    val coverUrl: String,
    val title: String,
    val content: String,
    val tagsJson: String,
    val mediaItemsJson: String,
    val bgmAudioJson: String?,
    val mediaCount: Int,
    val downloadedOnce: Boolean = false,
    val parsedAtMillis: Long = System.currentTimeMillis()
)

/**
 * 本地已下载归档媒体表（用于「媒体库 Gallery」分类浏览与批量管理）
 */
@Entity(tableName = "downloaded_media")
data class DownloadedMediaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val mediaStoreUri: String,
    val fileName: String,
    val relativePath: String,
    val platform: String,
    val authorName: String,
    val title: String,
    val mediaKind: String,
    val width: Int,
    val height: Int,
    val format: String,
    val fileSizeBytes: Long,
    val md5Hash: String,
    val md5Modified: Boolean,
    val visualSeed: Int = 0,
    val downloadedAtMillis: Long = System.currentTimeMillis()
) {
    val formattedFileSize: String
        get() {
            val mb = fileSizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) {
                String.format("%.2f MB", mb)
            } else {
                String.format("%.0f KB", (fileSizeBytes / 1024.0).coerceAtLeast(1.0))
            }
        }
}
