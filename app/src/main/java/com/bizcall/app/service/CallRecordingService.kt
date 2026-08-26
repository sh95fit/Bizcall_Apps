package com.bizcall.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.bizcall.app.upload.S3Uploader
import com.bizcall.app.util.PreferenceManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallRecordingService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START_RECORDING"
        const val ACTION_STOP = "ACTION_STOP_RECORDING"
        const val ACTION_UPDATE_NUMBER = "ACTION_UPDATE_NUMBER"
        const val EXTRA_DIRECTION = "extra_direction"
        const val EXTRA_CALLER_NUMBER = "extra_caller_number"
        const val EXTRA_CALL_START_TIME = "extra_call_start_time"
        const val EXTRA_CALL_DURATION_MS = "extra_call_duration_ms"
        const val CHANNEL_ID = "bizcall_recording_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "CallRecordingService"

        // 최소 저장 통화 시간 (5초 미만이면 부재중/즉시 끊김으로 판단해 파일 삭제)
        private const val MIN_CALL_DURATION_MS = 5_000L

        // AudioSource 시도 순서
        // VOICE_CALL(4): 양쪽 녹음, 일부 기기에서 비루팅으로도 동작
        // VOICE_COMMUNICATION(7): Android 10+ 무음 가능성 있으나 일부 기기 동작
        // MIC(1): 스피커폰 모드와 함께 사용 시 양쪽 캡처
        private val AUDIO_SOURCE_FALLBACK_ORDER = listOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
    }

    private var mediaRecorder: MediaRecorder? = null
    private var outputFilePath: String = ""
    private var direction: String = ""
    private var callerNumber: String = ""
    private var callStartTime: Long = 0

    private var audioManager: AudioManager? = null
    private var originalSpeakerState: Boolean = false
    private var originalMode: Int = AudioManager.MODE_NORMAL
    private var speakerActivated: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("통화 녹음 준비 중..."),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("통화 녹음 준비 중..."))
        }

        when (intent?.action) {
            ACTION_START -> {
                if (mediaRecorder != null) {
                    Log.d(TAG, "이미 녹음 중 — 중복 시작 무시")
                    return START_STICKY
                }
                direction = intent.getStringExtra(EXTRA_DIRECTION) ?: "unknown"
                callerNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: ""
                callStartTime = intent.getLongExtra(EXTRA_CALL_START_TIME, System.currentTimeMillis())
                startRecording()
            }
            ACTION_UPDATE_NUMBER -> {
                val newNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: return START_STICKY
                if (newNumber.isNotEmpty() && callerNumber.isEmpty()) {
                    val oldPath = outputFilePath
                    val newFileName = oldPath.replace("__", "_${newNumber}_")
                    val oldFile = File(oldPath)
                    val newFile = File(newFileName)
                    if (oldFile.exists()) {
                        oldFile.renameTo(newFile)
                        outputFilePath = newFileName
                        callerNumber = newNumber
                        Log.d(TAG, "파일명 업데이트: $newFileName")
                    }
                }
            }
            ACTION_STOP -> {
                val callDurationMs = intent?.getLongExtra(EXTRA_CALL_DURATION_MS, 0L) ?: 0L
                stopRecording(callDurationMs)
            }
        }
        return START_STICKY
    }

    private fun startRecording() {
        val phoneId = PreferenceManager.getPhoneId(this) ?: "unknown"
        val dateStr = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
            .format(Date(callStartTime))
        val fileName = "${phoneId}_${direction}_${callerNumber}_${dateStr}.m4a"
        val recordingDir = File(filesDir, "recordings").also { it.mkdirs() }
        outputFilePath = File(recordingDir, fileName).absolutePath

        var recordingStarted = false
        for (audioSource in AUDIO_SOURCE_FALLBACK_ORDER) {
            if (tryStartRecording(audioSource)) {
                Log.d(TAG, "녹음 시작 성공 — AudioSource: $audioSource / 경로: $outputFilePath")
                recordingStarted = true
                break
            } else {
                Log.w(TAG, "AudioSource $audioSource 실패 — 다음 소스 시도")
            }
        }

        if (!recordingStarted) {
            Log.e(TAG, "모든 AudioSource 시도 실패 — 녹음 중단")
            stopSelf()
        }
    }

    private fun tryStartRecording(audioSource: Int): Boolean {
        return try {
            if (audioSource == MediaRecorder.AudioSource.MIC) {
                activateSpeaker()
            }

            mediaRecorder?.release()
            mediaRecorder = null

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(audioSource)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(outputFilePath)
                prepare()
            }

            Thread.sleep(300)
            recorder.start()

            mediaRecorder = recorder
            true

        } catch (e: Exception) {
            Log.e(TAG, "tryStartRecording 실패 (AudioSource: $audioSource): ${e.message}")
            if (audioSource == MediaRecorder.AudioSource.MIC) {
                restoreSpeaker()
            }
            mediaRecorder?.release()
            mediaRecorder = null
            false
        }
    }

    private fun activateSpeaker() {
        try {
            audioManager?.let { am ->
                originalMode = am.mode
                originalSpeakerState = am.isSpeakerphoneOn
                am.mode = AudioManager.MODE_IN_CALL
                am.isSpeakerphoneOn = true
                speakerActivated = true
                Log.d(TAG, "스피커폰 활성화 (MIC 녹음용)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "스피커폰 활성화 실패: ${e.message}")
        }
    }

    private fun restoreSpeaker() {
        if (!speakerActivated) return
        try {
            audioManager?.let { am ->
                am.isSpeakerphoneOn = originalSpeakerState
                am.mode = originalMode
                speakerActivated = false
                Log.d(TAG, "스피커폰 원상복구")
            }
        } catch (e: Exception) {
            Log.e(TAG, "스피커폰 복구 실패: ${e.message}")
        }
    }

    private fun stopRecording(callDurationMs: Long) {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            Log.d(TAG, "녹음 중지 — 통화 시간: ${callDurationMs / 1000}초")

            restoreSpeaker()

            if (outputFilePath.isEmpty()) return

            val file = File(outputFilePath)

            // 최소 통화 시간 미만이면 파일 삭제 (부재중/즉시 끊김 처리)
            if (callDurationMs < MIN_CALL_DURATION_MS) {
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "통화 시간 ${callDurationMs / 1000}초 미만 — 파일 삭제: $outputFilePath")
                }
                return
            }

            // 최소 시간 이상이고 파일이 존재하면 업로드 큐 등록
            if (file.exists() && file.length() > 0) {
                S3Uploader.enqueue(
                    context = this,
                    filePath = outputFilePath,
                    direction = direction,
                    callerNumber = callerNumber,
                    callStartTime = callStartTime
                )
            } else {
                Log.w(TAG, "파일 없거나 빈 파일 — 업로드 생략: $outputFilePath")
            }

        } catch (e: Exception) {
            Log.e(TAG, "녹음 중지 실패: ${e.message}")
            restoreSpeaker()
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BizCall 녹음",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "통화 녹음 진행 중"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BizCall")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
