package com.bizcall.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.bizcall.app.service.CallRecordingService
import com.bizcall.app.util.PendingCallMeta
import com.bizcall.app.util.PreferenceManager
import com.bizcall.app.util.RecordingMode

class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"

        // BroadcastReceiver는 매번 새 인스턴스 생성 → 통화 상태는 companion object에 유지
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var callStartTime: Long = 0L
        private var incomingNumber = ""
        private var isOffhook = false
        private var currentDirection = ""
        private var currentCallerNumber = ""
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.PHONE_STATE") return
        if (!PreferenceManager.isRegistered(context)) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
        val mode = PreferenceManager.getRecordingMode(context)

        Log.d(TAG, "[$mode] PHONE_STATE=$state / number=$number / lastState=$lastState")

        when (state) {

            TelephonyManager.EXTRA_STATE_RINGING -> {
                lastState = TelephonyManager.CALL_STATE_RINGING
                incomingNumber = number
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (isOffhook) return // 이미 통화 중 — 중복 이벤트 무시

                currentDirection = if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    "incoming"
                } else {
                    "outgoing"
                }
                currentCallerNumber = if (currentDirection == "incoming") incomingNumber else number
                callStartTime = System.currentTimeMillis()
                isOffhook = true
                lastState = TelephonyManager.CALL_STATE_OFFHOOK

                Log.d(TAG, "통화 연결: direction=$currentDirection, number=$currentCallerNumber")

                // DIRECT_MIC 모드만 CallRecordingService 실행
                // SAMSUNG 모드는 SamsungRecordingDetector가 단독 처리
                if (mode == RecordingMode.DIRECT_MIC) {
                    context.startForegroundService(
                        Intent(context, CallRecordingService::class.java).apply {
                            action = CallRecordingService.ACTION_START
                            putExtra(CallRecordingService.EXTRA_DIRECTION, currentDirection)
                            putExtra(CallRecordingService.EXTRA_CALLER_NUMBER, currentCallerNumber)
                            putExtra(CallRecordingService.EXTRA_CALL_START_TIME, callStartTime)
                        }
                    )
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (lastState == TelephonyManager.CALL_STATE_IDLE) return // 중복 IDLE 무시

                val callEndTime = System.currentTimeMillis()
                val callDurationMs = if (callStartTime > 0L) callEndTime - callStartTime else 0L

                Log.d(TAG, "통화 종료: duration=${callDurationMs / 1000}초, wasOffhook=$isOffhook")

                when (mode) {
                    RecordingMode.SAMSUNG -> {
                        // 실제 통화 연결이 있었던 경우만 push
                        // isOffhook=false(부재중)이면 녹음 파일 자체가 없으므로 push 불필요
                        if (isOffhook) {
                            PendingCallMeta.push(
                                direction = currentDirection,
                                callerNumber = currentCallerNumber,
                                callStartTime = callStartTime,
                                callEndTime = callEndTime
                            )
                        }
                    }
                    RecordingMode.DIRECT_MIC -> {
                        context.startForegroundService(
                            Intent(context, CallRecordingService::class.java).apply {
                                action = CallRecordingService.ACTION_STOP
                                putExtra(CallRecordingService.EXTRA_CALL_DURATION_MS, callDurationMs)
                            }
                        )
                    }
                }

                // 상태 초기화
                lastState = TelephonyManager.CALL_STATE_IDLE
                isOffhook = false
                incomingNumber = ""
                currentCallerNumber = ""
                currentDirection = ""
                callStartTime = 0L
            }
        }
    }
}
