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
import java.util.concurrent.TimeUnit

object S3Uploader {

    private const val TAG = "S3Uploader"
    private const val S3_PREFIX = "recordings/"

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

            // PreferenceManager에서 phone_id, token 가져오기
            val phoneId = PreferenceManager.getPhoneId(context)
            val token = PreferenceManager.getToken(context)

            if (phoneId.isNullOrBlank() || token.isNullOrBlank()) {
                Log.e(TAG, "phone_id 또는 token 없음 — 업로드 불가")
                return false
            }

            // API Gateway에서 임시 자격증명 발급
            val credResponse = ApiClient.phoneApi.getCredentials(
                CredentialsRequest(phone_id = phoneId, token = token)
            )

            if (!credResponse.isSuccessful || credResponse.body() == null) {
                Log.e(TAG, "임시 자격증명 발급 실패: ${credResponse.code()}")
                return false
            }

            val creds = credResponse.body()!!

            // 발급받은 임시 자격증명으로 S3 클라이언트 생성
            val sessionCredentials = BasicSessionCredentials(
                creds.access_key_id,
                creds.secret_access_key,
                creds.session_token
            )

            val s3Client = AmazonS3Client(
                sessionCredentials,
                Region.getRegion(Regions.AP_NORTHEAST_2)
            )

            val s3Key = "$S3_PREFIX${file.name}"

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
