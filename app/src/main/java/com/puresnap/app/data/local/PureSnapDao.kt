package com.puresnap.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PureSnapDao {

    @Query("SELECT * FROM parse_history ORDER BY parsedAtMillis DESC")
    fun observeAllHistory(): Flow<List<ParseHistoryEntity>>

    @Query("SELECT * FROM parse_history WHERE extractedUrl = :url ORDER BY parsedAtMillis DESC LIMIT 1")
    suspend fun findLatestHistoryByUrl(url: String): ParseHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entity: ParseHistoryEntity): Long

    @Query("UPDATE parse_history SET downloadedOnce = 1 WHERE extractedUrl = :url")
    suspend fun markHistoryDownloaded(url: String)

    @Query("DELETE FROM parse_history WHERE id = :id")
    suspend fun deleteHistoryById(id: Long)

    @Query("DELETE FROM parse_history")
    suspend fun clearAllHistory()

    @Query("SELECT * FROM downloaded_media ORDER BY downloadedAtMillis DESC")
    fun observeAllDownloadedMedia(): Flow<List<DownloadedMediaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadedMedia(entities: List<DownloadedMediaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSingleDownloadedMedia(entity: DownloadedMediaEntity): Long

    @Query("DELETE FROM downloaded_media WHERE id IN (:ids)")
    suspend fun deleteDownloadedMediaByIds(ids: List<Long>)

    @Query("DELETE FROM downloaded_media")
    suspend fun clearAllDownloadedMedia()
}
