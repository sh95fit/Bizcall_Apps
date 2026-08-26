package com.bizcall.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.bizcall.app.service.CallRecordingService
import com.bizcall.app.util.PreferenceManager

class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"

        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var callStartTime: Long = 0
        private var incomingNumber = ""
        private var isRecording = false
        private var currentDirection = ""
        private var currentCallerNumber = ""
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.PHONE_STATE") return
        if (!PreferenceManager.isRegistered(context)) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

        Log.d(TAG, "PHONE_STATE: $state / number: $number / lastState: $lastState")

        when (state) {

            // 수신 전화 울리는 중
            TelephonyManager.EXTRA_STATE_RINGING -> {
                lastState = TelephonyManager.CALL_STATE_RINGING
                incomingNumber = number
            }

            // 통화 연결됨 (발신: IDLE→OFFHOOK / 수신: RINGING→OFFHOOK)
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (isRecording) {
                    // 이미 녹음 중인데 번호가 새로 들어오면 업데이트
                    if (number.isNotEmpty() && currentCallerNumber.isEmpty()) {
                        currentCallerNumber = number
                        val updateIntent = Intent(context, CallRecordingService::class.java).apply {
                            action = CallRecordingService.ACTION_UPDATE_NUMBER
                            putExtra(CallRecordingService.EXTRA_CALLER_NUMBER, number)
                        }
                        context.startForegroundService(updateIntent)
                    }
                    return
                }

                currentDirection = if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    "incoming"
                } else {
                    "outgoing"
                }
                currentCallerNumber = if (currentDirection == "incoming") incomingNumber else number

                lastState = TelephonyManager.CALL_STATE_OFFHOOK
                callStartTime = System.currentTimeMillis()
                isRecording = true

                Log.d(TAG, "녹음 시작 요청 — direction: $currentDirection / number: $currentCallerNumber")

                val serviceIntent = Intent(context, CallRecordingService::class.java).apply {
                    action = CallRecordingService.ACTION_START
                    putExtra(CallRecordingService.EXTRA_DIRECTION, currentDirection)
                    putExtra(CallRecordingService.EXTRA_CALLER_NUMBER, currentCallerNumber)
                    putExtra(CallRecordingService.EXTRA_CALL_START_TIME, callStartTime)
                }
                context.startForegroundService(serviceIntent)
            }

            // 통화 종료
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (lastState != TelephonyManager.CALL_STATE_IDLE) {

                    // 실제 통화 연결 시간 계산 (OFFHOOK 기준)
                    val callDurationMs = if (callStartTime > 0) {
                        System.currentTimeMillis() - callStartTime
                    } else {
                        0L
                    }

                    Log.d(TAG, "통화 종료 — 통화 시간: ${callDurationMs / 1000}초 / isRecording: $isRecording")

                    lastState = TelephonyManager.CALL_STATE_IDLE
                    isRecording = false
                    incomingNumber = ""
                    currentCallerNumber = ""
                    currentDirection = ""
                    callStartTime = 0L

                    val serviceIntent = Intent(context, CallRecordingService::class.java).apply {
                        action = CallRecordingService.ACTION_STOP
                        // 통화 시간 전달 → CallRecordingService에서 최소 시간 미만 시 파일 삭제
                        putExtra(CallRecordingService.EXTRA_CALL_DURATION_MS, callDurationMs)
                    }
                    context.startForegroundService(serviceIntent)
                }
            }
        }
    }
}
