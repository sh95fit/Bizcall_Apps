package com.bizcall.app.util

import android.content.Context

/**
 * 업로드 완료 파일 마커
 *
 * Samsung 원본 녹음 파일은 업로드 후에도 보존(deleteAfterUpload=false)되므로,
 * 재스캔 워커가 동일 파일을 매 주기마다 재업로드하지 않도록 완료/대기 경로를 기록한다.
 *
 * - SharedPreferences commit(동기) 사용: 워커·메인 스레드 동시성 대비
 * - MAX_ENTRIES 초과 시 리셋: 재업로드가 발생해도 파이프라인의
 *   s3_key 유니크 제약(23505 → is_duplicate_insert_error)이 중복 insert를
 *   차단하므로 데이터 무결성에 영향 없음
 */
object UploadMarker {

    private const val PREF_NAME = "bizcall_upload_marker"
    private const val KEY_UPLOADED = "uploaded_paths"
    private const val KEY_PENDING = "pending_paths"
    private const val MAX_ENTRIES = 1000

    /**
     * 업로드 성공 확정 시 호출 — pending 마커도 함께 제거
     */
    fun markUploaded(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val uploaded = HashSet(prefs.getStringSet(KEY_UPLOADED, emptySet()) ?: emptySet())
        val pending = HashSet(prefs.getStringSet(KEY_PENDING, emptySet()) ?: emptySet())
        uploaded.add(path)
        pending.remove(path)
        if (uploaded.size > MAX_ENTRIES) uploaded.clear()
        prefs.edit()
            .putStringSet(KEY_UPLOADED, uploaded)
            .putStringSet(KEY_PENDING, pending)
            .commit()
    }

    /**
     * 재스캔 워커가 큐에 등록한 파일 — 다음 주기의 중복 등록 방지
     */
    fun markPending(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val pending = HashSet(prefs.getStringSet(KEY_PENDING, emptySet()) ?: emptySet())
        pending.add(path)
        if (pending.size > MAX_ENTRIES) pending.clear()
        prefs.edit().putStringSet(KEY_PENDING, pending).commit()
    }

    /**
     * 이미 업로드됐거나 큐에 등록 중인 파일이면 true
     */
    fun isUploaded(context: Context, path: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val uploaded = prefs.getStringSet(KEY_UPLOADED, emptySet()) ?: emptySet()
        val pending = prefs.getStringSet(KEY_PENDING, emptySet()) ?: emptySet()
        return path in uploaded || path in pending
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
