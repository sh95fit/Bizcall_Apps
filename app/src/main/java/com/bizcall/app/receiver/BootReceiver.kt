package com.bizcall.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bizcall.app.service.PhoneStateService
import com.bizcall.app.upload.S3Uploader
import com.bizcall.app.util.PreferenceManager
import java.io.File

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!PreferenceManager.isRegistered(context)) return

        Log.d(TAG, "부팅 완료 — PhoneStateService 재시작")

        val serviceIntent = Intent(context, PhoneStateService::class.java)
        context.startForegroundService(serviceIntent)

        val recordingDir = File(context.filesDir, "recordings")
        if (!recordingDir.exists()) return

        val pendingFiles = recordingDir.listFiles { file ->
            file.extension == "m4a" && file.length() > 0
        } ?: return

        if (pendingFiles.isEmpty()) return

        Log.d(TAG, "미업로드 파일 ${pendingFiles.size}개 재시도 큐 등록")

        pendingFiles.forEach { file ->
            val parsed = parseFileName(file.name)
            S3Uploader.enqueue(
                context = context,
                filePath = file.absolutePath,
                direction = parsed.first,
                callerNumber = parsed.second,
                callStartTime = parsed.third,
                callEndTime = 0L,           // ★ 재시도 파일은 종료 시각 알 수 없음 → null 폴백
                deleteAfterUpload = true    // ★ DIRECT_MIC 파일 → 업로드 후 삭제
            )
            Log.d(TAG, "큐 등록: ${file.name}")
        }
    }

    private fun parseFileName(fileName: String): Triple<String, String, Long> {
        return try {
            val nameWithoutExt = fileName.removeSuffix(".m4a")
            val parts = nameWithoutExt.split("_")
            val direction = if (parts.size > 1) parts[1] else ""
            val callerNumber = if (parts.size > 2) parts[2] else ""
            val dateStr = if (parts.size > 3) parts[3] else ""
            val callStartTime = try {
                java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.getDefault())
                    .parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
            Triple(direction, callerNumber, callStartTime)
        } catch (e: Exception) {
            Triple("", "", 0L)
        }
    }
}
