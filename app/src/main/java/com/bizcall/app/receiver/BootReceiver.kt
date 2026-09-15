package com.bizcall.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bizcall.app.queue.RescanWorker
import com.bizcall.app.service.PhoneStateService
import com.bizcall.app.util.PreferenceManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!PreferenceManager.isRegistered(context)) return

        Log.d(TAG, "부팅 완료 — PhoneStateService 재시작 + 즉시 재스캔")

        try {
            val serviceIntent = Intent(context, PhoneStateService::class.java)
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            // ★ 백그라운드 FGS 시작 제약 등으로 실패해도 무해 — 주기 워커가 다음 기회에 복구
            Log.e(TAG, "서비스 시작 오류: ${e.message}")
        }

        // ★ 미업로드 파일 재스캔/재업로드 + 실패 큐 재소비 + 서비스 생존 확인은
        //   RescanWorker가 일괄 수행한다.
        //   - Samsung 녹음 폴더(업로드 후 원본 보존) + 내부 폴더(DIRECT_MIC → 업로드 후 삭제) 모두 스캔
        //   - 기존 "재시도 파일은 종료 시각 알 수 없음 → null 폴백" 규칙은 워커 내 수정 시각 폴백으로 계승
        RescanWorker.requestImmediate(context)
    }
}
