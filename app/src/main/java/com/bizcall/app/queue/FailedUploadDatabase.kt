package com.bizcall.app.queue

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [FailedUpload::class], version = 2, exportSchema = false)
abstract class FailedUploadDatabase : RoomDatabase() {

    abstract fun dao(): FailedUploadDao

    companion object {
        @Volatile
        private var INSTANCE: FailedUploadDatabase? = null

        /**
         * v1 → v2: FailedUpload.callEndTime + deleteAfterUpload 컬럼 추가
         * (재시도 시 통화 종료 시각 보존, Samsung 원본 보존 정책 복원)
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE failed_uploads ADD COLUMN call_end_time INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE failed_uploads ADD COLUMN delete_after_upload INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        fun getInstance(context: Context): FailedUploadDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    FailedUploadDatabase::class.java,
                    "bizcall_failed_uploads.db"
                ).addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
