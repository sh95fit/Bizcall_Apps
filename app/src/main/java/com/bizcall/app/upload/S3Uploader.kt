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

    /**
     * BizCall 표준 S3 키 생성
     * 형식: recordings/{phone_id}_{direction}_{callerNumber}_{yyyyMMddHHmmss}.m4a
     *
     * - CallRecordingService(자체 녹음): 이미 파일명이 표준 형식 → file.name 그대로 사용
     * - SamsungRecordingDetector(삼성 자동 녹음): 삼성 자체 파일명 → 표준 형식으로 변환
     */
    private fun buildS3Key(
        context: Context,
        file: File,
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
        callStartTime: Long
    ) {
        val inputData = Data.Builder()
            .putString(UploadWorker.KEY_FILE_PATH, filePath)
            .putString(UploadWorker.KEY_DIRECTION, direction)
            .putString(UploadWorker.KEY_CALLER_NUMBER, callerNumber)
            .putLong(UploadWorker.KEY_CALL_START_TIME, callStartTime)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueue(uploadRequest)
        Log.d(TAG, "WorkManager 업로드 큐 등록: $filePath")
    }

    suspend fun uploadDirect(
        context: Context,
        filePath: String,
        direction: String,
        callerNumber: String,
        callStartTime: Long
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

            val sessionCredentials = BasicSessionCredentials(
                creds.access_key_id,
                creds.secret_access_key,
                creds.session_token
            )

            val s3Client = AmazonS3Client(
                sessionCredentials,
                Region.getRegion(Regions.AP_NORTHEAST_2)
            )

            // 표준 파일명으로 S3 키 생성
            // CallRecordingService 파일: phone_id가 이미 포함된 표준 파일명
            // Samsung 자동 녹음 파일: 삼성 자체 파일명 → 표준 파일명으로 변환
            val s3Key = buildS3Key(
                context = context,
                file = file,
                direction = direction,
                callerNumber = callerNumber,
                callStartTime = callStartTime
            )

            Log.d(TAG, "S3 업로드 키: $s3Key")

            val metadata = ObjectMetadata().apply {
                contentType = "audio/mp4"
                contentLength = file.length()
            }

            val request = PutObjectRequest(creds.bucket, s3Key, file.inputStream(), metadata)
            s3Client.putObject(request)

            Log.d(TAG, "S3 업로드 성공: $s3Key")

            file.delete()
            Log.d(TAG, "로컬 파일 삭제: $filePath")

            true

        } catch (e: Exception) {
            Log.e(TAG, "S3 업로드 실패: ${e.message}")
            false
        }
    }
}
