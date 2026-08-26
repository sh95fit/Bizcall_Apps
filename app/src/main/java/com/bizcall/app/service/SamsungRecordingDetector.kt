package com.bizcall.app.service

import android.os.FileObserver
import android.util.Log
import com.bizcall.app.util.DeviceDetector
import com.bizcall.app.util.PendingCallMeta
import java.io.File

/**
 * Samsung One UI 자동 녹음 파일 감지기
 *
 * 파일 감지(CLOSE_WRITE) 시 PendingCallMeta.pop()으로 메타데이터 소비
 * → CallLog 재조회 없음, 타이밍 불일치 및 연속 통화 크로스 매핑 원천 차단
 *
 * 케이스별 동작:
 *   정상      : 통화종료(push) → 파일저장(pop) → 정확한 메타로 업로드
 *   타이밍역전 : 파일저장 → 통화종료(push) 순서여도 최대 2초 대기 후 pop 성공
 *   연속통화   : pushA → pushB → popA → popB (FIFO 보장)
 *   만료/앱재시작: 2초 대기 후에도 null → unknown 폴백
 */
class SamsungRecordingDetector(
    private val onNewRecording: (filePath: String, meta: PendingCallMeta.CallMeta?) -> Unit
) {

    companion object {
        private const val TAG = "SamsungRecordingDetector"
        private val SUPPORTED_EXTENSIONS = setOf("m4a", "mp4", "3gp", "aac", "amr")
        private const val MIN_FILE_SIZE_BYTES = 1024L

        // 삼성 기기에서 CLOSE_WRITE가 IDLE보다 먼저 발생하는 타이밍 역전 대응
        private const val META_WAIT_MAX_MS = 2000L
        private const val META_WAIT_INTERVAL_MS = 100L
    }

    private var fileObserver: FileObserver? = null
    private var watchPath: String? = null

    fun start(): Boolean {
        val path = DeviceDetector.resolveRecordingPath() ?: run {
            Log.w(TAG, "삼성 녹음 경로를 찾을 수 없음")
            return false
        }

        File(path).also { if (!it.exists()) it.mkdirs() }
        watchPath = path
        Log.d(TAG, "감시 시작: $path (One UI ${DeviceDetector.getOneUiVersion()})")

        fileObserver = object : FileObserver(path, CLOSE_WRITE) {
            override fun onEvent(event: Int, fileName: String?) {
                if (event != CLOSE_WRITE || fileName == null) return

                val ext = fileName.substringAfterLast(".", "").lowercase()
                if (ext !in SUPPORTED_EXTENSIONS) return

                val fullPath = "$path/$fileName"
                val file = File(fullPath)

                if (!file.exists() || file.length() < MIN_FILE_SIZE_BYTES) {
                    Log.w(TAG, "파일 크기 미달 무시: $fullPath (${file.length()} bytes)")
                    return
                }

                Log.d(TAG, "새 녹음 파일 감지: $fullPath")

                // 타이밍 역전 대응:
                // 삼성 기기에서 CLOSE_WRITE(파일 저장 완료)가
                // IDLE 브로드캐스트(CallReceiver push)보다 먼저 도달하는 경우가 있음
                // FileObserver.onEvent는 별도 스레드에서 실행되므로 sleep 안전
                val meta = waitAndPop(fullPath)
                onNewRecording(fullPath, meta)
            }
        }

        fileObserver?.startWatching()
        return true
    }

    /**
     * PendingCallMeta 큐에 항목이 생길 때까지 최대 META_WAIT_MAX_MS 대기 후 pop
     *
     * - 즉시 pop 성공  → 반환 (정상 케이스)
     * - 큐 비어있음    → 100ms 간격으로 재시도 (타이밍 역전 케이스)
     * - 2초 후에도 없음 → null 반환 → unknown 폴백 (앱 재시작·만료 케이스)
     */
    private fun waitAndPop(fullPath: String): PendingCallMeta.CallMeta? {
        val deadline = System.currentTimeMillis() + META_WAIT_MAX_MS
        var elapsed = 0L

        while (System.currentTimeMillis() < deadline) {
            val meta = PendingCallMeta.pop()
            if (meta != null) {
                if (elapsed > 0) {
                    Log.d(TAG, "타이밍 역전 보정 성공: ${elapsed}ms 대기 후 메타 확보 → $fullPath")
                }
                return meta
            }
            Thread.sleep(META_WAIT_INTERVAL_MS)
            elapsed += META_WAIT_INTERVAL_MS
        }

        Log.w(TAG, "메타 대기 ${META_WAIT_MAX_MS}ms 초과 → unknown 폴백: $fullPath")
        return null
    }

    fun stop() {
        fileObserver?.stopWatching()
        fileObserver = null
        Log.d(TAG, "감시 중지: $watchPath")
    }

    fun getWatchPath(): String? = watchPath
}
