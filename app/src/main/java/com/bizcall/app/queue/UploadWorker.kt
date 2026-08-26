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
    }

    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: run {
            Log.e(TAG, "파일 경로 없음 — 작업 취소")
            return Result.failure()
        }
        val direction = inputData.getString(KEY_DIRECTION) ?: ""
        val callerNumber = inputData.getString(KEY_CALLER_NUMBER) ?: ""
        val callStartTime = inputData.getLong(KEY_CALL_START_TIME, 0L)

        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "파일 없음 (이미 삭제됐거나 경로 오류): $filePath")
            // 파일 자체가 없으면 재시도해도 의미 없으므로 failure 반환
            return Result.failure()
        }

        Log.d(TAG, "업로드 시도: $filePath (시도 횟수: ${runAttemptCount + 1})")

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
                Log.w(TAG, "업로드 실패 — 재시도 예약: $filePath")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "업로드 예외 발생: ${e.message}")
            Result.retry()
        }
    }
}
