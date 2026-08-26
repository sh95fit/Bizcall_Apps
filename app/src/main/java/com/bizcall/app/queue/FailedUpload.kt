package com.bizcall.app.queue

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "failed_uploads")
data class FailedUpload(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val localFilePath: String,    // 로컬 파일 경로
    val direction: String,         // incoming / outgoing / unknown
    val callerNumber: String,      // 전화번호
    val callStartTime: Long,       // 통화 시작 시각 (ms)
    val failedAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastError: String = ""
)
