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
 *   정상     : 통화종료(push) → 파일저장(pop) → 정확한 메타로 업로드
 *   연속통화  : pushA → pushB → popA → popB (FIFO 보장)
 *   만료/앱재시작: pop() = null → unknown 폴백
 */
class SamsungRecordingDetector(
    private val onNewRecording: (filePath: String, meta: PendingCallMeta.CallMeta?) -> Unit
) {

    companion object {
        private const val TAG = "SamsungRecordingDetector"
        private val SUPPORTED_EXTENSIONS = setOf("m4a", "mp4", "3gp", "aac", "amr")
        private const val MIN_FILE_SIZE_BYTES = 1024L
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

                // 통화 종료(IDLE) 후 파일 저장이 이뤄지므로
                // 정상 흐름에서는 pop() 항상 성공
                // null이면 unknown 폴백 (앱 재시작 직후 이전 파일 감지 등 비정상 상황)
                val meta = PendingCallMeta.pop()
                onNewRecording(fullPath, meta)
            }
        }

        fileObserver?.startWatching()
        return true
    }

    fun stop() {
        fileObserver?.stopWatching()
        fileObserver = null
        Log.d(TAG, "감시 중지: $watchPath")
    }

    fun getWatchPath(): String? = watchPath
}
