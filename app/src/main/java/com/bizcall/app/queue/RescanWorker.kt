package com.bizcall.app.queue

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bizcall.app.service.PhoneStateService
import com.bizcall.app.upload.S3Uploader
import com.bizcall.app.util.DeviceDetector
import com.bizcall.app.util.PreferenceManager
import com.bizcall.app.util.RecordingFileName
import com.bizcall.app.util.UploadMarker
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 주기 재스캔 워커 — 절전 킬로 감시·업로드가 멈춰도 자동 복구
 *
 * 기존 구조의 결함: SamsungRecordingDetector(FileObserver)와 업로드 큐가
 * PhoneStateService(앱 프로세스) 안에서만 돌아서, 삼성 절전으로 프로세스가
 * 죽으면 "인식 자체가 안 일어나" 업로드 요청이 생성되지 않았다(요청 0건).
 *
 * WorkManager는 앱 프로세스와 독립적으로 시스템이 예약·재기동하므로,
 * 이 워커가 15분마다 다음 3가지를 수행해 어떤 절전 상황에서도 복구한다:
 *   1. PhoneStateService 생존 확인 → 사망 시 재시작
 *   2. FailedUploadQueue 자동 재소비 (기존엔 앱 열 때/부팅 시에만)
 *   3. 삼성 녹음 폴더 + 내부 녹음 폴더 스캔 → 미업로드 파일 재업로드
 *
 * ★ 기존 BootReceiver의 "부팅 시 미업로드 파일 재시도" 로직을 통합한 워커.
 *   기존 주석(재시도 파일은 종료 시각 알 수 없음 → null 폴백, DIRECT_MIC 파일 → 업로드 후 삭제)은
 *   아래 스캔·재소비 로직에 각각 계승한다.
 */
class RescanWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "RescanWorker"
        const val UNIQUE_PERIODIC_NAME = "bizcall_rescan_periodic"
        const val UNIQUE_IMMEDIATE_NAME = "bizcall_rescan_immediate"

        private const val PERIOD_MINUTES = 15L
        private const val MIN_FILE_SIZE_BYTES = 1024L

        /**
         * 주기 스캔 예약 (등록 시 1회 호출, 이후 KEEP)
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RescanWorker>(
                PERIOD_MINUTES, TimeUnit.MINUTES
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "주기 재스캔 예약 완료 (${PERIOD_MINUTES}분 간격)")
        }

        /**
         * 부팅 직후 등 즉시 1회 스캔 요청 (중복 실행 시 REPLACE)
         */
        fun requestImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<RescanWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_IMMEDIATE_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "즉시 재스캔 1회 요청")
        }
    }

    override suspend fun doWork(): Result {
        if (!PreferenceManager.isRegistered(context)) return Result.success()

        // 1. 절전 킬 감시 서비스 복구
        ensureServiceAlive()

        // 2. 실패 큐 자동 재소비
        retryFailedQueue()

        // 3. 미업로드 파일 재스캔 & 재업로드
        rescanPendingFiles()

        return Result.success()
    }

    // ── 1. 서비스 보호 ──────────────────────────────────────────────

    private fun ensureServiceAlive() {
        if (isServiceRunning(PhoneStateService::class.java)) return
        Log.w(TAG, "PhoneStateService 사망 감지 — 재시작 시도")
        try {
            val intent = Intent(context, PhoneStateService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            // ★ Android 12+ 백그라운드 FGS 시작 제약(ForegroundServiceStartNotAllowedException)으로
            //    조용히 실패할 수 있음 — 이 경우 다음 주기 워커나 앱 실행 시 재시도한다.
            Log.e(TAG, "서비스 재시작 실패(다음 주기 재시도): ${e.message}")
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == serviceClass.name
            }
        } catch (e: Exception) {
            false
        }
    }

    // ── 2. 실패 큐 자동 재소비 ──────────────────────────────────────

    private suspend fun retryFailedQueue() {   // ★ suspend 추가 — Room suspend DAO 호출 가능
        try {
            val db = FailedUploadDatabase.getInstance(context)
            val failed = db.dao().getAllOnce()
            if (failed.isEmpty()) return

            Log.d(TAG, "실패 큐 ${failed.size}건 자동 재시도")
            failed.forEach { item ->
                val file = File(item.localFilePath)
                if (!file.exists()) {
                    // 원본 파일까지 사라진 건 무의미 — 큐에서 제거
                    db.dao().deleteById(item.id)
                    return@forEach
                }
                S3Uploader.enqueue(
                    context          = context,
                    filePath         = item.localFilePath,
                    direction        = item.direction,
                    callerNumber     = item.callerNumber,
                    callStartTime    = item.callStartTime,
                    callEndTime      = item.callEndTime,
                    // ★ 기존 동작 계승: 재시도는 업로드 성공 시 삭제 정책을 그대로 따른다.
                    //   Samsung 원본(deleteAfterUpload=false)은 v2부터 보존 복원됨.
                    deleteAfterUpload = item.deleteAfterUpload
                )
                db.dao().deleteById(item.id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "실패 큐 재소비 오류: ${e.message}")
        }
    }


    // ── 3. 미업로드 파일 재스캔 ────────────────────────────────────

    private fun rescanPendingFiles() {
        val handled = HashSet<String>()

        // 삼성 자동 녹음 폴더 (One UI 버전별 후보) — 업로드 후 원본 보존
        val samsungRoots = DeviceDetector.getSamsungRecordingPaths()
            .filter { File(it).exists() }

        // 내부 녹음 폴더 (DIRECT_MIC / 수동 업로드 임시 파일) — 업로드 후 삭제
        // ★ 기존 BootReceiver 설계 계승: 내부 폴더 전체를 스캔해 업로드 후 삭제
        val internalDir = File(context.filesDir, "recordings")
        val internalRoots = if (internalDir.exists()) {
            listOf(internalDir.absolutePath)
        } else {
            emptyList()
        }

        // ★ 전체 스캔 (시각 창 제한 없음): 업데이트 전 밀려 있던 오래된 파일도 다시 올린다.
        //   중복 업로드는 UploadMarker(성공/대기 마킹)와 파이프라인 s3_key 유니크 제약이 차단.
        scanAndEnqueue(samsungRoots, handled, deleteAfterUpload = false)
        scanAndEnqueue(internalRoots, handled, deleteAfterUpload = true)
    }

    private fun scanAndEnqueue(
        roots: List<String>,
        handled: MutableSet<String>,
        deleteAfterUpload: Boolean
    ) {
        roots.forEach { root ->
            val dir = File(root)
            val files = dir.listFiles { f ->
                f.isFile &&
                        f.name.substringAfterLast(".", "").lowercase() == "m4a" &&
                        f.length() >= MIN_FILE_SIZE_BYTES
            } ?: return@forEach

            files.sortedBy { it.lastModified() }.forEach { file ->
                val path = file.absolutePath
                if (path in handled) return@forEach
                if (UploadMarker.isUploaded(context, path)) return@forEach

                // ★ 재스캔 파일은 통화 메타(방향/번호)를 잃은 상태이므로 기존 규칙(unknown 폴백)을 따른다.
                //   - 내부 폴더 파일(DIRECT_MIC/수동)은 우리 키 포맷 → RecordingFileName.parse 정상 동작
                //   - 삼성 원본 파일명은 우리 키 포맷이 아님 → unknown 폴백 (기존 "앱 재시작·만료" 케이스와 동일)
                val parsed = RecordingFileName.parse(file.name)

                S3Uploader.enqueue(
                    context          = context,
                    filePath         = path,
                    direction        = parsed?.direction ?: "unknown",
                    callerNumber     = parsed?.callerNumber ?: "unknown",
                    callStartTime    = parsed?.callStartTime ?: file.lastModified(),
                    // ★ 기존 주석 계승: 재시도/재스캔 파일은 정확한 종료 시각을 모름 → 수정 시각 폴백
                    callEndTime      = file.lastModified(),
                    deleteAfterUpload = deleteAfterUpload
                )

                UploadMarker.markPending(context, path)
                handled.add(path)
                Log.d(TAG, "재스캔 발견 → 업로드 큐: $path (deleteAfterUpload=$deleteAfterUpload)")
            }
        }
    }
}
