package com.bizcall.app.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import com.bizcall.app.api.ApiClient
import com.bizcall.app.api.CredentialsRequest
import com.bizcall.app.queue.UploadWorker
import com.bizcall.app.util.PreferenceManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object S3Uploader {

    private const val TAG = "S3Uploader"
    private const val S3_PREFIX = "recordings/"

    private fun buildS3Key(
        context: Context,
        direction: String,
        callerNumber: String,
        callStartTime: Long
    ): String {
        val phoneId = PreferenceManager.getPhoneId(context) ?: "unknown"
        val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
            .format(Date(callStartTime))
        val standardName = "${phoneId}_${direction}_${callerNumber}_${timestamp}.m4a"
        return "$S3_PREFIX$standardName"
    }

    fun enqueue(
        context: Context,
        filePath: String,
        direction: String,
        callerNumber: String,
        callStartTime: Long,
        callEndTime: Long,
        deleteAfterUpload: Boolean
    ) {
        val inputData = Data.Builder()
            .putString(UploadWorker.KEY_FILE_PATH, filePath)
            .putString(UploadWorker.KEY_DIRECTION, direction)
            .putString(UploadWorker.KEY_CALLER_NUMBER, callerNumber)
            .putLong(UploadWorker.KEY_CALL_START_TIME, callStartTime)
            .putLong(UploadWorker.KEY_CALL_END_TIME, callEndTime)
            .putBoolean(UploadWorker.KEY_DELETE_AFTER_UPLOAD, deleteAfterUpload)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueue(uploadRequest)
        Log.d(TAG, "WorkManager 업로드 큐 등록: $filePath / deleteAfterUpload=$deleteAfterUpload")
    }

    suspend fun uploadDirect(
        context: Context,
        filePath: String,
        direction: String,
        callerNumber: String,
        callStartTime: Long,
        callEndTime: Long,
        deleteAfterUpload: Boolean
    ): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "파일 없음: $filePath")
                return false
            }

            val phoneId = PreferenceManager.getPhoneId(context)
            val deviceId = PreferenceManager.getDeviceId(context)

            if (phoneId.isNullOrBlank() || deviceId.isNullOrBlank()) {
                Log.e(TAG, "phone_id 또는 device_id 없음 — 업로드 불가")
                return false
            }

            val credResponse = ApiClient.phoneApi.getCredentials(
                CredentialsRequest(phone_id = phoneId, device_id = deviceId)
            )

            if (!credResponse.isSuccessful || credResponse.body() == null) {
                Log.e(TAG, "임시 자격증명 발급 실패: ${credResponse.code()}")
                return false
            }

            val creds = credResponse.body()!!

            val s3Client = AmazonS3Client(
                BasicSessionCredentials(
                    creds.access_key_id,
                    creds.secret_access_key,
                    creds.session_token
                ),
                Region.getRegion(Regions.AP_NORTHEAST_2)
            )

            val s3Key = buildS3Key(
                context = context,
                direction = direction,
                callerNumber = callerNumber,
                callStartTime = callStartTime
            )

            Log.d(TAG, "S3 업로드 키: $s3Key")

            // ★ call_ended_at / duration_sec → S3 Object Metadata로 파이프라인에 전달
            val callEndIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                .format(Date(callEndTime))
            val durationSec = if (callEndTime > callStartTime) {
                ((callEndTime - callStartTime) / 1000).toString()
            } else {
                "0"
            }

            val metadata = ObjectMetadata().apply {
                contentType = "audio/mp4"
                contentLength = file.length()
                addUserMetadata("call-end-time", callEndIso)
                addUserMetadata("call-duration-sec", durationSec)
            }

            s3Client.putObject(PutObjectRequest(creds.bucket, s3Key, file.inputStream(), metadata))
            Log.d(TAG, "S3 업로드 성공: $s3Key / end=$callEndIso / duration=${durationSec}s")

            // 모드별 파일 삭제 분기
            // DIRECT_MIC: 앱이 직접 생성한 파일 → 삭제
            // SAMSUNG: 삼성 원본 녹음 파일 → 보존
            if (deleteAfterUpload) {
                file.delete()
                Log.d(TAG, "로컬 파일 삭제: $filePath")
            } else {
                Log.d(TAG, "로컬 파일 보존 (Samsung 원본): $filePath")
            }

            true

        } catch (e: Exception) {
            Log.e(TAG, "S3 업로드 실패: ${e.message}")
            false
        }
    }
}
