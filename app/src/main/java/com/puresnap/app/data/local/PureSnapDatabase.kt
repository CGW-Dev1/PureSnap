package com.puresnap.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ParseHistoryEntity::class, DownloadedMediaEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PureSnapDatabase : RoomDatabase() {

    abstract fun pureSnapDao(): PureSnapDao

    companion object {
        @Volatile
        private var INSTANCE: PureSnapDatabase? = null

        fun getInstance(context: Context): PureSnapDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PureSnapDatabase::class.java,
                    "puresnap_storage.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
