package com.bizcall.app.queue

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FailedUpload::class], version = 1, exportSchema = false)
abstract class FailedUploadDatabase : RoomDatabase() {

    abstract fun dao(): FailedUploadDao

    companion object {
        @Volatile
        private var INSTANCE: FailedUploadDatabase? = null

        fun getInstance(context: Context): FailedUploadDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    FailedUploadDatabase::class.java,
                    "bizcall_failed_uploads.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
