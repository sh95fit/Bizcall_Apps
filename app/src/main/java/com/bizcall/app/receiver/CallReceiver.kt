package com.bizcall.app.receiver

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.provider.CallLog
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
                if (isOffhook) return

                currentDirection = if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    "incoming"
                } else {
                    "outgoing"
                }

                // 발신이면 CallLog에서 최근 발신 번호 조회
                currentCallerNumber = if (currentDirection == "incoming") {
                    incomingNumber
                } else {
                    getLastOutgoingNumber(context) ?: ""
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

    /**
     * CallLog에서 가장 최근 발신 번호를 조회한다.
     * READ_CALL_LOG 권한 사용 (PROCESS_OUTGOING_CALLS 불필요)
     * OFFHOOK 직후 호출 시 아직 CallLog에 기록 안 됐을 수 있어서
     * 최대 3회 재시도 (300ms 간격)
     */
    private fun getLastOutgoingNumber(context: Context): String? {
        repeat(3) { attempt ->
            val number = queryLastOutgoing(context.contentResolver)
            if (!number.isNullOrBlank()) {
                Log.d(TAG, "발신 번호 조회 성공 (attempt=${attempt + 1}): $number")
                return number
            }
            if (attempt < 2) Thread.sleep(300)
        }
        Log.w(TAG, "발신 번호 조회 실패 → 빈 값 처리")
        return null
    }

    private fun queryLastOutgoing(cr: ContentResolver): String? {
        return try {
            cr.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(CallLog.Calls.OUTGOING_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "CallLog 조회 오류: $e")
            null
        }
    }
}
