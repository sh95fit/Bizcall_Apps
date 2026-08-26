package com.bizcall.app.service

import android.os.FileObserver
import android.util.Log
import com.bizcall.app.util.DeviceDetector
import java.io.File

class SamsungRecordingDetector(
    private val onNewRecording: (filePath: String) -> Unit
) {

    companion object {
        private const val TAG = "SamsungRecordingDetector"
        // 삼성 자동 녹음 파일 확장자
        private val SUPPORTED_EXTENSIONS = setOf("m4a", "mp4", "3gp", "aac", "amr")
    }

    private var fileObserver: FileObserver? = null
    private var watchPath: String? = null

    fun start(): Boolean {
        val path = DeviceDetector.resolveRecordingPath()
        if (path == null) {
            Log.w(TAG, "삼성 녹음 경로를 찾을 수 없음")
            return false
        }

        // 경로가 없으면 폴더 생성 시도
        val dir = File(path)
        if (!dir.exists()) {
            dir.mkdirs()
            Log.d(TAG, "녹음 폴더 생성: $path")
        }

        watchPath = path
        Log.d(TAG, "감시 시작: $path (One UI ${DeviceDetector.getOneUiVersion()})")

        fileObserver = object : FileObserver(path, CLOSE_WRITE) {
            override fun onEvent(event: Int, fileName: String?) {
                if (event != CLOSE_WRITE || fileName == null) return

                val ext = fileName.substringAfterLast(".", "").lowercase()
                if (ext !in SUPPORTED_EXTENSIONS) return

                val fullPath = "$path/$fileName"
                val file = File(fullPath)

                // 최소 파일 크기 확인 (1KB 미만이면 무시 — 빈 파일 방지)
                if (!file.exists() || file.length() < 1024) {
                    Log.w(TAG, "파일 크기 미달 무시: $fullPath (${file.length()} bytes)")
                    return
                }

                Log.d(TAG, "새 녹음 파일 감지: $fullPath")
                onNewRecording(fullPath)
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
