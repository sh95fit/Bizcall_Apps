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
    val callEndTime: Long = 0,     // ★ 통화 종료 시각 (ms) — 재시도 시 메타데이터 보존
    val deleteAfterUpload: Boolean = true,  // ★ 업로드 후 삭제 여부 — Samsung 원본(보존) 구분
    val failedAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastError: String = ""
)
