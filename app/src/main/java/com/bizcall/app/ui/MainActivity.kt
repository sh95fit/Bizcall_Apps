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
import com.bizcall.app.service.SamsungRecordingDetector
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
    ) { _ -> updatePermissionStatus() }

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
    }

    private fun bindViews() {
        layoutUnregistered = findViewById(R.id.layoutUnregistered)
        etToken = findViewById(R.id.etToken)
        btnRegister = findViewById(R.id.btnRegister)
        btnScanQr = findViewById(R.id.btnScanQr)
        tvStatus = findViewById(R.id.tvStatus)

        layoutRegistered = findViewById(R.id.layoutRegistered)
        tvPhoneId = findViewById(R.id.tvPhoneId)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        tvRecordingMode = findViewById(R.id.tvRecordingMode)
        tvWatchPath = findViewById(R.id.tvWatchPath)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        btnCheckPermissions = findViewById(R.id.btnCheckPermissions)
        tvFailedCount = findViewById(R.id.tvFailedCount)
        btnRetryFailed = findViewById(R.id.btnRetryFailed)
        btnManualUpload = findViewById(R.id.btnManualUpload)
        btnUnregister = findViewById(R.id.btnUnregister)
    }

    // ── 등록 전 화면 ──────────────────────────────────────────────

    private fun showUnregisteredState() {
        layoutUnregistered.visibility = View.VISIBLE
        layoutRegistered.visibility = View.GONE

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

        val deviceId = DeviceIdManager.getOrCreateDeviceId(this)

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.phoneApi.register(RegisterRequest(token = token, device_id = deviceId))
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
        layoutRegistered.visibility = View.VISIBLE

        // 등록 정보 표시
        val phoneId = PreferenceManager.getPhoneId(this) ?: "-"
        val deviceId = PreferenceManager.getDeviceId(this) ?: "-"
        tvPhoneId.text = "Phone ID: ${phoneId.take(8)}…"
        tvDeviceId.text = "Device ID: ${deviceId.take(8)}…"

        // 녹음 모드 표시 (현재 Samsung 고정)
        val mode = PreferenceManager.getRecordingMode(this)
        tvRecordingMode.text = mode.displayName

        // 감시 경로 표시
        val watchPath = DeviceDetector.resolveRecordingPath()
        tvWatchPath.text = watchPath ?: "경로 없음"
        if (watchPath == null) {
            tvWatchPath.setTextColor(getColor(android.R.color.holo_red_light))
        }

        // 서비스 상태 표시 및 시작
        startPhoneStateService()
        tvServiceStatus.text = "실행 중"
        tvServiceStatus.setTextColor(getColor(android.R.color.holo_green_dark))

        // 권한 상태
        updatePermissionStatus()

        // 업로드 실패 큐 관찰
        observeFailedQueue()

        // 수동 업로드
        btnManualUpload.setOnClickListener {
            filePickerLauncher.launch("audio/*")
        }

        // 실패 재시도
        btnRetryFailed.setOnClickListener {
            retryFailedUploads()
        }

        // 등록 해제
        btnUnregister.setOnClickListener {
            showUnregisterDialog()
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
                // 권한 재요청 시도, 완전 거부 상태면 설정으로 이동
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
                    context = this@MainActivity,
                    filePath = item.localFilePath,
                    direction = item.direction,
                    callerNumber = item.callerNumber,
                    callStartTime = item.callStartTime
                )
                withContext(Dispatchers.IO) { db.dao().deleteById(item.id) }
            }
            Toast.makeText(this@MainActivity, "${failed.size}건 재시도 등록 완료", Toast.LENGTH_SHORT).show()
        }
    }

    // ── 수동 업로드 메타데이터 입력 다이얼로그 ──────────────────────

    /**
     * 파일 선택 후 발신/수신 방향과 전화번호를 입력받아 업로드
     * 이전: direction=unknown, callerNumber=unknown 하드코딩
     * 이후: 담당자가 직접 입력 → 파이프라인에서 정확한 메타로 분석 가능
     */
    private fun showManualUploadMetaDialog(uris: List<Uri>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_upload_meta, null)
        val etNumber = dialogView.findViewById<EditText>(R.id.etCallerNumber)
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
                        context = this@MainActivity,
                        filePath = filePath,
                        direction = direction,
                        callerNumber = callerNumber,
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

    // ── 등록 해제 ──────────────────────────────────────────────────

    private fun showUnregisterDialog() {
        AlertDialog.Builder(this)
            .setTitle("기기 등록 해제")
            .setMessage("등록을 해제하면 통화 녹음 및 업로드가 중단됩니다.\n계속하시겠습니까?")
            .setPositiveButton("해제") { _, _ ->
                stopService(Intent(this, PhoneStateService::class.java))
                PreferenceManager.clear(this)
                showUnregisteredState()
                Toast.makeText(this, "등록이 해제되었습니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
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
