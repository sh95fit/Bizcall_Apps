package com.bizcall.app.util

import android.os.Build

object DeviceDetector {

    /**
     * Samsung One UI 여부 및 버전 감지
     * Build.VERSION.SEM_PLATFORM_INT 필드를 리플렉션으로 읽어 판별
     * One UI 1.x = 90000번대, One UI 8.x = 170000번대 수준
     */
    fun getOneUiVersion(): Float {
        return try {
            val semClass = Class.forName("com.samsung.android.sdk.SemSdk")
            val method = semClass.getMethod("getSemSdkVersion")
            val semVersion = method.invoke(null) as? Int ?: 0
            // SEM 버전을 One UI 버전으로 변환 (10000 단위)
            semVersion / 10000f
        } catch (e: Exception) {
            try {
                // 폴백: Build 필드 직접 접근
                val field = Build.VERSION::class.java.getDeclaredField("SEM_PLATFORM_INT")
                field.isAccessible = true
                val semInt = field.getInt(null)
                when {
                    semInt >= 150000 -> 7.0f  // One UI 7+
                    semInt >= 140000 -> 6.0f  // One UI 6
                    semInt >= 130000 -> 5.0f  // One UI 5
                    semInt >= 120000 -> 4.0f  // One UI 4
                    semInt >= 110000 -> 3.0f  // One UI 3
                    semInt >= 100000 -> 2.0f  // One UI 2
                    semInt >= 90000  -> 1.0f  // One UI 1
                    else -> 0f
                }
            } catch (e2: Exception) {
                0f // Samsung이 아님
            }
        }
    }

    fun isSamsungOneUi(): Boolean {
        if (Build.BRAND.lowercase() != "samsung") return false
        return getOneUiVersion() >= 1.0f
    }

    /**
     * One UI 버전별 통화 녹음 파일 저장 경로 목록 반환
     * 우선순위 순서로 정렬 (앞쪽부터 존재 여부 확인)
     *
     * One UI 1~2: /storage/emulated/0/Call/
     * One UI 3~4: /storage/emulated/0/Recordings/Call/ 또는 /Call/
     * One UI 5~6: /storage/emulated/0/Recordings/Call/
     * One UI 7~8: /storage/emulated/0/Recordings/Call/ (기본)
     *             일부 기기: /storage/emulated/0/DCIM/Call/ (비표준)
     */
    fun getSamsungRecordingPaths(): List<String> {
        val oneUiVersion = getOneUiVersion()
        return when {
            oneUiVersion >= 7.0f -> listOf(
                "/storage/emulated/0/Recordings/Call",
                "/storage/emulated/0/Call",
                "/sdcard/Recordings/Call"
            )
            oneUiVersion >= 5.0f -> listOf(
                "/storage/emulated/0/Recordings/Call",
                "/storage/emulated/0/Call",
                "/sdcard/Recordings/Call"
            )
            oneUiVersion >= 3.0f -> listOf(
                "/storage/emulated/0/Recordings/Call",
                "/storage/emulated/0/Call",
                "/sdcard/Call"
            )
            oneUiVersion >= 1.0f -> listOf(
                "/storage/emulated/0/Call",
                "/storage/emulated/0/Recordings/Call",
                "/sdcard/Call"
            )
            else -> listOf(
                "/storage/emulated/0/Recordings/Call",
                "/storage/emulated/0/Call"
            )
        }
    }

    /**
     * 실제 존재하는 첫 번째 경로 반환
     */
    fun resolveRecordingPath(): String? {
        return getSamsungRecordingPaths().firstOrNull {
            java.io.File(it).exists()
        }
    }
}
