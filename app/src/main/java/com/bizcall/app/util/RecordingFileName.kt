package com.bizcall.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 녹음 파일명 파서
 *
 * 형식: <phone_id>_<direction>_<callerNumber>_<yyyyMMddHHmmss>.m4a
 * 예)  550e8400-..._incoming_01012345678_20260914143000.m4a
 *
 * phone_id·callerNumber에 "_"가 포함될 가능성(공백 없는 정규 문자)이 있으므로
 * 앞부분은 유연하게 두고 뒤에서부터(direction/callerNumber/timestamp) 안전하게 파싱한다.
 * (파이프라인 src/pipeline/main.py의 parse_s3_key와 동일한 규칙)
 */
object RecordingFileName {

    data class Parsed(
        val direction: String,
        val callerNumber: String,
        val callStartTime: Long
    )

    fun parse(fileName: String): Parsed? {
        return try {
            val base = fileName.substringBeforeLast(".m4a")
            if (base.isEmpty()) return null

            val parts = base.split("_")
            if (parts.size < 4) return null

            // 뒤에서부터 3개: timestamp, callerNumber, direction
            val timestampStr = parts.last()
            val callerNumber = parts[parts.size - 2]
            val direction = parts[parts.size - 3]

            // SimpleDateFormat은 thread-safe 하지 않으므로 호출마다 생성
            val callStartTime = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
                .parse(timestampStr)?.time ?: return null

            Parsed(
                direction = direction.ifEmpty { "unknown" },
                callerNumber = callerNumber.ifEmpty { "unknown" },
                callStartTime = callStartTime
            )
        } catch (e: Exception) {
            null
        }
    }
}
