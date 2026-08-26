package com.bizcall.app.queue

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bizcall.app.upload.S3Uploader
import java.io.File

class UploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_FILE_PATH = "key_file_path"
        const val KEY_DIRECTION = "key_direction"
        const val KEY_CALLER_NUMBER = "key_caller_number"
        const val KEY_CALL_START_TIME = "key_call_start_time"
        private const val TAG = "UploadWorker"
        private const val MAX_RETRY_COUNT = 3  // 최대 재시도 횟수
    }

    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: run {
            Log.e(TAG, "파일 경로 없음 — 작업 취소")
            return Result.failure()
        }
        val direction = inputData.getString(KEY_DIRECTION) ?: "unknown"
        val callerNumber = inputData.getString(KEY_CALLER_NUMBER) ?: "unknown"
        val callStartTime = inputData.getLong(KEY_CALL_START_TIME, 0L)

        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "파일 없음 (이미 삭제됐거나 경로 오류): $filePath")
            return Result.failure()
        }

        Log.d(TAG, "업로드 시도: $filePath (시도 횟수: ${runAttemptCount + 1}/$MAX_RETRY_COUNT)")

        return try {
            val success = S3Uploader.uploadDirect(
                context = context,
                filePath = filePath,
                direction = direction,
                callerNumber = callerNumber,
                callStartTime = callStartTime
            )
            if (success) {
                Log.d(TAG, "업로드 성공: $filePath")
                Result.success()
            } else {
                handleFailure(filePath, direction, callerNumber, callStartTime, "업로드 실패")
            }
        } catch (e: Exception) {
            Log.e(TAG, "업로드 예외: ${e.message}")
            handleFailure(filePath, direction, callerNumber, callStartTime, e.message ?: "알 수 없는 오류")
        }
    }

    private suspend fun handleFailure(
        filePath: String,
        direction: String,
        callerNumber: String,
        callStartTime: Long,
        error: String
    ): Result {
        return if (runAttemptCount < MAX_RETRY_COUNT - 1) {
            // 아직 재시도 횟수 남음 → WorkManager 재시도
            Log.w(TAG, "재시도 예약 (${runAttemptCount + 1}/$MAX_RETRY_COUNT): $filePath")
            Result.retry()
        } else {
            // 3회 모두 실패 → FailedUploadQueue에 저장
            Log.e(TAG, "최대 재시도 초과 → FailedUploadQueue 저장: $filePath")
            saveToFailedQueue(filePath, direction, callerNumber, callStartTime, error)
            Result.failure()
        }
    }

    private suspend fun saveToFailedQueue(
        filePath: String,
        direction: String,
        callerNumber: String,
        callStartTime: Long,
        error: String
    ) {
        try {
            val db = FailedUploadDatabase.getInstance(context)
            db.dao().insert(
                FailedUpload(
                    localFilePath = filePath,
                    direction = direction,
                    callerNumber = callerNumber,
                    callStartTime = callStartTime,
                    retryCount = runAttemptCount + 1,
                    lastError = error
                )
            )
            Log.d(TAG, "FailedUploadQueue 저장 완료: $filePath")
        } catch (e: Exception) {
            Log.e(TAG, "FailedUploadQueue 저장 실패: ${e.message}")
        }
    }
}
