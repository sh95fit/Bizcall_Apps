package com.bizcall.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bizcall.app.R
import com.bizcall.app.api.ApiClient
import com.bizcall.app.api.RegisterRequest
import com.bizcall.app.queue.FailedUploadDatabase
import com.bizcall.app.service.PhoneStateService
import com.bizcall.app.upload.S3Uploader
import com.bizcall.app.util.DeviceDetector
import com.bizcall.app.util.DeviceIdManager
import com.bizcall.app.util.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    // ── 등록 전 뷰 ──
    private lateinit var layoutUnregistered: LinearLayout
    private lateinit var etToken: EditText
    private lateinit var btnRegister: Button
    private lateinit var btnScanQr: Button
    private lateinit var tvStatus: TextView

    // ── 등록 후 뷰 ──
    private lateinit var layoutRegistered: LinearLayout
    private lateinit var tvPhoneId: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var tvRecordingMode: TextView
    private lateinit var tvWatchPath: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvPermissionStatus: TextView
    private lateinit var btnCheckPermissions: Button
    private lateinit var tvFailedCount: TextView
    private lateinit var btnRetryFailed: Button
    private lateinit var btnManualUpload: Button
    private lateinit var btnUnregister: Button

    private val requiredPermissions = mutableListOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 권한 팝업 결과 후 상태 갱신
        if (PreferenceManager.isRegistered(this)) {
            updatePermissionStatus()
        }
    }

    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val token = result.data?.getStringExtra("token") ?: return@registerForActivityResult
            registerPhone(token)
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        showManualUploadMetaDialog(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        if (PreferenceManager.isRegistered(this)) {
            showRegisteredState()
        } else {
            showUnregisteredState()
        }

        // ★ 최초 설치 시 권한 요청 — 등록 여부와 무관하게 항상 실행
        requestPermissionsIfNeeded()
    }

    // ★ 시스템 설정에서 권한 허용 후 복귀 시 상태 갱신
    override fun onResume() {
        super.onResume()
        if (PreferenceManager.isRegistered(this)) {
            updatePermissionStatus()
        }
    }

    private fun bindViews() {
        layoutUnregistered  = findViewById(R.id.layoutUnregistered)
        etToken             = findViewById(R.id.etToken)
        btnRegister         = findViewById(R.id.btnRegister)
        btnScanQr           = findViewById(R.id.btnScanQr)
        tvStatus            = findViewById(R.id.tvStatus)

        layoutRegistered    = findViewById(R.id.layoutRegistered)
        tvPhoneId           = findViewById(R.id.tvPhoneId)
        tvDeviceId          = findViewById(R.id.tvDeviceId)
        tvRecordingMode     = findViewById(R.id.tvRecordingMode)
        tvWatchPath         = findViewById(R.id.tvWatchPath)
        tvServiceStatus     = findViewById(R.id.tvServiceStatus)
        tvPermissionStatus  = findViewById(R.id.tvPermissionStatus)
        btnCheckPermissions = findViewById(R.id.btnCheckPermissions)
        tvFailedCount       = findViewById(R.id.tvFailedCount)
        btnRetryFailed      = findViewById(R.id.btnRetryFailed)
        btnManualUpload     = findViewById(R.id.btnManualUpload)
        btnUnregister       = findViewById(R.id.btnUnregister)
    }

    // ── 권한 최초 요청 ─────────────────────────────────────────────

    private fun requestPermissionsIfNeeded() {
        val denied = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isNotEmpty()) {
            permissionLauncher.launch(denied.toTypedArray())
        }
    }

    // ── 등록 전 화면 ──────────────────────────────────────────────

    private fun showUnregisteredState() {
        layoutUnregistered.visibility = View.VISIBLE
        layoutRegistered.visibility   = View.GONE

        btnScanQr.setOnClickListener {
            qrLauncher.launch(Intent(this, QrScanActivity::class.java))
        }

        btnRegister.setOnClickListener {
            val token = etToken.text.toString().trim()
            if (token.isEmpty()) {
                tvStatus.text = "토큰을 입력해주세요"
                tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                return@setOnClickListener
            }
            registerPhone(token)
        }
    }

    private fun registerPhone(token: String) {
        tvStatus.text = "등록 중..."
        tvStatus.setTextColor(getColor(android.R.color.holo_orange_light))

        // ★ 수정: getOrCreateDeviceId → getOrCreate
        val deviceId = DeviceIdManager.getOrCreate(this)

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.phoneApi.register(
                        RegisterRequest(token = token, device_id = deviceId)
                    )
                }
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    PreferenceManager.savePhoneInfo(this@MainActivity, body.phone_id, deviceId)
                    startPhoneStateService()
                    showRegisteredState()
                } else {
                    tvStatus.text = "등록 실패: ${response.code()}"
                    tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                }
            } catch (e: Exception) {
                tvStatus.text = "오류: ${e.message}"
                tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
            }
        }
    }

    // ── 등록 후 화면 ──────────────────────────────────────────────

    private fun showRegisteredState() {
        layoutUnregistered.visibility = View.GONE
        layoutRegistered.visibility   = View.VISIBLE

        val phoneId  = PreferenceManager.getPhoneId(this)  ?: "-"
        val deviceId = PreferenceManager.getDeviceId(this) ?: "-"
        tvPhoneId.text  = "Phone ID: ${phoneId.take(8)}…"
        tvDeviceId.text = "Device ID: ${deviceId.take(8)}…"

        val mode = PreferenceManager.getRecordingMode(this)
        tvRecordingMode.text = mode.displayName

        val watchPath = DeviceDetector.resolveRecordingPath()
        tvWatchPath.text = watchPath ?: "경로 없음"
        if (watchPath == null) {
            tvWatchPath.setTextColor(getColor(android.R.color.holo_red_light))
        }

        startPhoneStateService()
        tvServiceStatus.text = "실행 중"
        tvServiceStatus.setTextColor(getColor(android.R.color.holo_green_dark))

        updatePermissionStatus()
        observeFailedQueue()

        btnManualUpload.setOnClickListener {
            filePickerLauncher.launch("audio/*")
        }

        btnRetryFailed.setOnClickListener {
            retryFailedUploads()
        }

        // ★ 수정: 등록 해제 → 담당자 문의 안내로 제한
        btnUnregister.setOnClickListener {
            showUnregisterContactDialog()
        }
    }

    // ── 권한 상태 ──────────────────────────────────────────────────

    private fun updatePermissionStatus() {
        val denied = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (denied.isEmpty()) {
            tvPermissionStatus.text = "모두 허용됨"
            tvPermissionStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            btnCheckPermissions.visibility = View.GONE
        } else {
            tvPermissionStatus.text = "미허용 ${denied.size}개"
            tvPermissionStatus.setTextColor(getColor(android.R.color.holo_red_light))
            btnCheckPermissions.visibility = View.VISIBLE
            btnCheckPermissions.setOnClickListener {
                // ★ shouldShowRequestPermissionRationale: 완전 거부(다시 묻지 않음) 여부 확인
                // true = 재요청 가능 / false = 설정 화면으로 이동
                val canRequest = denied.any { shouldShowRequestPermissionRationale(it) }
                if (canRequest) {
                    permissionLauncher.launch(denied.toTypedArray())
                } else {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                    )
                }
            }
        }
    }

    // ── 업로드 실패 큐 관찰 ────────────────────────────────────────

    private fun observeFailedQueue() {
        val db = FailedUploadDatabase.getInstance(this)
        lifecycleScope.launch {
            db.dao().getCount().collect { count ->
                if (count > 0) {
                    tvFailedCount.text = "${count}건"
                    tvFailedCount.setTextColor(getColor(android.R.color.holo_red_light))
                    btnRetryFailed.isEnabled = true
                } else {
                    tvFailedCount.text = "없음"
                    tvFailedCount.setTextColor(getColor(android.R.color.holo_green_dark))
                    btnRetryFailed.isEnabled = false
                }
            }
        }
    }

    private fun retryFailedUploads() {
        val db = FailedUploadDatabase.getInstance(this)
        lifecycleScope.launch {
            val failed = withContext(Dispatchers.IO) { db.dao().getAllOnce() }
            if (failed.isEmpty()) {
                Toast.makeText(this@MainActivity, "재시도할 파일이 없습니다", Toast.LENGTH_SHORT).show()
                return@launch
            }
            failed.forEach { item ->
                S3Uploader.enqueue(
                    context       = this@MainActivity,
                    filePath      = item.localFilePath,
                    direction     = item.direction,
                    callerNumber  = item.callerNumber,
                    callStartTime = item.callStartTime
                )
                withContext(Dispatchers.IO) { db.dao().deleteById(item.id) }
            }
            Toast.makeText(
                this@MainActivity,
                "${failed.size}건 재시도 등록 완료",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ── 수동 업로드 메타데이터 입력 다이얼로그 ──────────────────────

    private fun showManualUploadMetaDialog(uris: List<Uri>) {
        val dialogView  = layoutInflater.inflate(R.layout.dialog_manual_upload_meta, null)
        val etNumber    = dialogView.findViewById<EditText>(R.id.etCallerNumber)
        val btnIncoming = dialogView.findViewById<Button>(R.id.btnDirectionIncoming)
        val btnOutgoing = dialogView.findViewById<Button>(R.id.btnDirectionOutgoing)

        var selectedDirection = "incoming"
        btnIncoming.backgroundTintList = getColorStateList(android.R.color.holo_blue_light)
        btnOutgoing.backgroundTintList = getColorStateList(android.R.color.darker_gray)

        btnIncoming.setOnClickListener {
            selectedDirection = "incoming"
            btnIncoming.backgroundTintList = getColorStateList(android.R.color.holo_blue_light)
            btnOutgoing.backgroundTintList = getColorStateList(android.R.color.darker_gray)
        }
        btnOutgoing.setOnClickListener {
            selectedDirection = "outgoing"
            btnOutgoing.backgroundTintList = getColorStateList(android.R.color.holo_blue_light)
            btnIncoming.backgroundTintList = getColorStateList(android.R.color.darker_gray)
        }

        AlertDialog.Builder(this)
            .setTitle("업로드 정보 입력")
            .setView(dialogView)
            .setPositiveButton("업로드") { _, _ ->
                val callerNumber = etNumber.text.toString().trim().ifEmpty { "unknown" }
                handleManualUpload(uris, selectedDirection, callerNumber)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun handleManualUpload(uris: List<Uri>, direction: String, callerNumber: String) {
        lifecycleScope.launch {
            var successCount = 0
            uris.forEach { uri ->
                val filePath = withContext(Dispatchers.IO) { getFilePathFromUri(uri) }
                if (filePath != null) {
                    S3Uploader.enqueue(
                        context       = this@MainActivity,
                        filePath      = filePath,
                        direction     = direction,
                        callerNumber  = callerNumber,
                        callStartTime = System.currentTimeMillis()
                    )
                    successCount++
                }
            }
            Toast.makeText(
                this@MainActivity,
                "${successCount}개 파일 업로드 큐 등록 완료",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ── 등록 해제 (담당자 문의 제한) ───────────────────────────────

    // ★ 수정: 실제 해제 대신 담당자 문의 안내 팝업으로 제한
    private fun showUnregisterContactDialog() {
        AlertDialog.Builder(this)
            .setTitle("기기 등록 해제")
            .setMessage("기기 등록 해제는 비즈옵스팀 담당자에게 문의해주세요.")
            .setPositiveButton("확인", null)
            .show()
    }

    // ── 공통 유틸 ──────────────────────────────────────────────────

    private fun startPhoneStateService() {
        val intent = Intent(this, PhoneStateService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun getFilePathFromUri(uri: Uri): String? {
        return try {
            val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: "upload_${System.currentTimeMillis()}.m4a"

            val tempFile = File(filesDir, "manual_upload_$fileName")
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            tempFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "URI 변환 실패: ${e.message}")
            null
        }
    }
}
