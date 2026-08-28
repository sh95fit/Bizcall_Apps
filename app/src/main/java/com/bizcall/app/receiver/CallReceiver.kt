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

        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var callStartTime: Long = 0L
        private var incomingNumber = ""
        private var isOffhook = false
        private var currentDirection = ""
        private var currentCallerNumber = ""

        // 발신 번호를 NEW_OUTGOING_CALL broadcast에서 미리 저장
        var pendingOutgoingNumber = ""
    }

    override fun onReceive(context: Context, intent: Intent) {

        // ── 발신 전화 번호 사전 캡처 ──────────────────────────────────────
        if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
            pendingOutgoingNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: ""
            Log.d(TAG, "발신 번호 캡처: $pendingOutgoingNumber")
            return
        }

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
                if (isOffhook) return

                currentDirection = if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    "incoming"
                } else {
                    "outgoing"
                }

                // 발신이면 사전 캡처한 pendingOutgoingNumber 사용
                currentCallerNumber = if (currentDirection == "incoming") {
                    incomingNumber
                } else {
                    pendingOutgoingNumber
                }

                callStartTime = System.currentTimeMillis()
                isOffhook = true
                lastState = TelephonyManager.CALL_STATE_OFFHOOK

                Log.d(TAG, "통화 연결: direction=$currentDirection, number=$currentCallerNumber")

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
                if (lastState == TelephonyManager.CALL_STATE_IDLE) return

                val callEndTime = System.currentTimeMillis()
                val callDurationMs = if (callStartTime > 0L) callEndTime - callStartTime else 0L

                Log.d(TAG, "통화 종료: duration=${callDurationMs / 1000}초, wasOffhook=$isOffhook")

                when (mode) {
                    RecordingMode.SAMSUNG -> {
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

                // 상태 초기화 (pendingOutgoingNumber도 초기화)
                lastState = TelephonyManager.CALL_STATE_IDLE
                isOffhook = false
                incomingNumber = ""
                currentCallerNumber = ""
                currentDirection = ""
                callStartTime = 0L
                pendingOutgoingNumber = ""
            }
        }
    }
}
