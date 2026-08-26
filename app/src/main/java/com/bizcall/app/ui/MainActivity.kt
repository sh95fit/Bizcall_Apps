package com.bizcall.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import com.bizcall.app.util.DeviceIdManager
import com.bizcall.app.util.PreferenceManager
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var etToken: EditText
    private lateinit var btnRegister: Button
    private lateinit var btnScanQr: Button
    private lateinit var tvStatus: TextView
    private lateinit var btnManualUpload: Button
    private lateinit var tvFailedCount: TextView
    private lateinit var btnRetryFailed: Button

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
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
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            tvStatus.text = "권한 허용 완료"
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            tvStatus.text = "일부 권한이 거부되었습니다. 설정에서 허용해주세요."
            tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
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

    // 수동 업로드: 다중 파일 선택 (audio/*)
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        handleManualUpload(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        etToken = findViewById(R.id.etToken)
        btnRegister = findViewById(R.id.btnRegister)
        btnScanQr = findViewById(R.id.btnScanQr)
        tvStatus = findViewById(R.id.tvStatus)
        btnManualUpload = findViewById(R.id.btnManualUpload)
        tvFailedCount = findViewById(R.id.tvFailedCount)
        btnRetryFailed = findViewById(R.id.btnRetryFailed)

        // 등록 완료 상태면 바로 등록 완료 화면으로
        if (PreferenceManager.isRegistered(this)) {
            showRegisteredState()
            checkAndRequestPermissions()
            observeFailedQueue()
            return
        }

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

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val denied = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("권한 필요")
            .setMessage("통화 녹음 및 업무폰 기능을 위해\n다음 권한이 필요합니다.\n\n• 마이크 (통화 녹음)\n• 전화 상태 (통화 감지)\n• 통화 기록 (발신 번호 확인)")
            .setPositiveButton("허용") { _, _ -> permissionLauncher.launch(denied.toTypedArray()) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun registerPhone(token: String) {
        btnRegister.isEnabled = false
        btnScanQr.isEnabled = false
        tvStatus.text = "등록 중..."
        tvStatus.setTextColor(getColor(android.R.color.darker_gray))

        val deviceId = DeviceIdManager.getOrCreate(this)

        lifecycleScope.launch {
            try {
                val response = ApiClient.phoneApi.register(
                    RegisterRequest(token = token, device_id = deviceId)
                )
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    PreferenceManager.savePhoneInfo(
                        context = this@MainActivity,
                        phoneId = body.phone_id,
                        deviceId = deviceId
                    )
                    showRegisteredState()
                    checkAndRequestPermissions()
                    observeFailedQueue()
                } else {
                    val msg = when (response.code()) {
                        401 -> "유효하지 않은 토큰입니다"
                        409 -> "이미 다른 기기에 등록된 토큰입니다"
                        403 -> "비활성화된 업무폰입니다"
                        else -> "등록 실패 (${response.code()})"
                    }
                    tvStatus.text = msg
                    tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                    btnRegister.isEnabled = true
                    btnScanQr.isEnabled = true
                }
            } catch (e: Exception) {
                tvStatus.text = "네트워크 오류: ${e.message}"
                tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                btnRegister.isEnabled = true
                btnScanQr.isEnabled = true
            }
        }
    }

    private fun showRegisteredState() {
        val phoneId = PreferenceManager.getPhoneId(this)
        tvTitle.text = "BizCall 활성화됨"
        tvSubtitle.text = "업무폰으로 사용 중이며\n자동으로 통화가 기록됩니다"
        etToken.visibility = View.GONE
        btnRegister.visibility = View.GONE
        btnScanQr.visibility = View.GONE
        tvStatus.text = "Phone ID: $phoneId"
        tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))

        // 수동 업로드 / 실패 목록 UI 표시
        btnManualUpload.visibility = View.VISIBLE
        tvFailedCount.visibility = View.VISIBLE
        btnRetryFailed.visibility = View.VISIBLE

        btnManualUpload.setOnClickListener {
            filePickerLauncher.launch("audio/*")
        }

        btnRetryFailed.setOnClickListener {
            retryFailedUploads()
        }

        startForegroundService(Intent(this, PhoneStateService::class.java))
    }

    /**
     * 수동 업로드: 담당자가 선택한 파일을 S3Uploader 큐에 등록
     * 메타데이터는 파일명 파싱 시도, 없으면 unknown으로 처리
     */
    private fun handleManualUpload(uris: List<Uri>) {
        lifecycleScope.launch {
            var count = 0
            uris.forEach { uri ->
                val filePath = getFilePathFromUri(uri) ?: return@forEach
                S3Uploader.enqueue(
                    context = this@MainActivity,
                    filePath = filePath,
                    direction = "unknown",
                    callerNumber = "unknown",
                    callStartTime = System.currentTimeMillis()
                )
                count++
            }
            Toast.makeText(
                this@MainActivity,
                "${count}개 파일이 업로드 대기열에 추가됐습니다",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * FailedUploadQueue에서 실패 목록을 가져와 재시도 큐에 등록
     */
    private fun retryFailedUploads() {
        lifecycleScope.launch {
            val db = FailedUploadDatabase.getInstance(this@MainActivity)
            val failedList = db.dao().getAllOnce()

            if (failedList.isEmpty()) {
                Toast.makeText(this@MainActivity, "재시도할 파일이 없습니다", Toast.LENGTH_SHORT).show()
                return@launch
            }

            failedList.forEach { item ->
                S3Uploader.enqueue(
                    context = this@MainActivity,
                    filePath = item.localFilePath,
                    direction = item.direction,
                    callerNumber = item.callerNumber,
                    callStartTime = item.callStartTime
                )
                db.dao().deleteById(item.id)
            }

            Toast.makeText(
                this@MainActivity,
                "${failedList.size}개 파일 재시도 등록 완료",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 실패 큐 개수 실시간 관찰 → UI 업데이트
     */
    private fun observeFailedQueue() {
        val db = FailedUploadDatabase.getInstance(this)
        lifecycleScope.launch {
            db.dao().getCount().collect { count ->
                tvFailedCount.text = if (count > 0) {
                    "업로드 실패: ${count}개"
                } else {
                    "업로드 실패 없음"
                }
                tvFailedCount.setTextColor(
                    if (count > 0) getColor(android.R.color.holo_red_light)
                    else getColor(android.R.color.holo_green_dark)
                )
                btnRetryFailed.isEnabled = count > 0
            }
        }
    }

    /**
     * Uri → 실제 파일 경로 변환
     * ContentResolver로 임시 복사 후 경로 반환
     */
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
