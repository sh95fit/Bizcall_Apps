package com.bizcall.app.util

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * CallReceiver ↔ SamsungRecordingDetector 간 통화 메타데이터 공유 큐
 *
 * 흐름:
 *   CallReceiver IDLE → push(direction, number, startTime, endTime)
 *   SamsungRecordingDetector CLOSE_WRITE → pop() → S3 업로드에 사용
 *
 * FIFO 보장: 연속 통화 시 A메타→A파일, B메타→B파일 순서 보장
 * 만료 처리: push 후 EXPIRE_MS(30초) 내 미소비 항목 자동 제거 → unknown 폴백
 */
object PendingCallMeta {

    private const val TAG = "PendingCallMeta"
    private const val EXPIRE_MS = 30_000L

    data class CallMeta(
        val direction: String,
        val callerNumber: String,
        val callStartTime: Long,
        val callEndTime: Long,
        val pushedAt: Long = System.currentTimeMillis()
    )

    private val queue: ConcurrentLinkedQueue<CallMeta> = ConcurrentLinkedQueue()

    /**
     * CallReceiver의 IDLE 처리 시점에 호출
     * callStartTime > 0 인 경우만 push (통화 실제 연결된 경우만)
     * 부재중(OFFHOOK 없음)은 callStartTime = 0 → push 안 함
     */
    fun push(
        direction: String,
        callerNumber: String,
        callStartTime: Long,
        callEndTime: Long
    ) {
        if (callStartTime <= 0L) {
            Log.w(TAG, "callStartTime 없음 — push 생략 (부재중 또는 즉시 종료)")
            return
        }
        val meta = CallMeta(
            direction = direction,
            callerNumber = callerNumber,
            callStartTime = callStartTime,
            callEndTime = callEndTime
        )
        queue.offer(meta)
        Log.d(TAG, "push: direction=$direction, number=$callerNumber, queueSize=${queue.size}")
    }

    /**
     * SamsungRecordingDetector의 CLOSE_WRITE 시점에 호출
     * 1) 만료 항목 제거 (연속 통화 크로스 매핑 방지)
     * 2) 유효한 가장 오래된 항목 반환 후 큐에서 제거
     * 3) 없으면 null → 호출부에서 unknown 폴백
     */
    fun pop(): CallMeta? {
        val now = System.currentTimeMillis()

        // 만료된 항목 앞에서부터 제거
        while (true) {
            val head = queue.peek() ?: break
            if (now - head.pushedAt > EXPIRE_MS) {
                queue.poll()
                Log.w(TAG, "만료 메타 제거: direction=${head.direction}, number=${head.callerNumber}, age=${now - head.pushedAt}ms")
            } else {
                break
            }
        }

        val meta = queue.poll()
        if (meta == null) {
            Log.w(TAG, "pop: 큐 비어있음 → unknown 폴백")
        } else {
            Log.d(TAG, "pop: direction=${meta.direction}, number=${meta.callerNumber}, remaining=${queue.size}")
        }
        return meta
    }

    fun size(): Int = queue.size

    fun clear() {
        queue.clear()
        Log.d(TAG, "큐 초기화")
    }
}
