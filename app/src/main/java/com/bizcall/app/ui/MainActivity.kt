package com.bizcall.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bizcall.app.R
import com.bizcall.app.api.ApiClient
import com.bizcall.app.api.RegisterRequest
import com.bizcall.app.service.PhoneStateService
import com.bizcall.app.util.DeviceIdManager
import com.bizcall.app.util.PreferenceManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var etToken: EditText
    private lateinit var btnRegister: Button
    private lateinit var btnScanQr: Button
    private lateinit var tvStatus: TextView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        etToken = findViewById(R.id.etToken)
        btnRegister = findViewById(R.id.btnRegister)
        btnScanQr = findViewById(R.id.btnScanQr)
        tvStatus = findViewById(R.id.tvStatus)

        if (PreferenceManager.isRegistered(this)) {
            showRegisteredState()
            checkAndRequestPermissions()
            return
        }

        btnScanQr.setOnClickListener {
            val intent = Intent(this@MainActivity, QrScanActivity::class.java)
            qrLauncher.launch(intent)
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
        val deniedPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (deniedPermissions.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("권한 필요")
            .setMessage("통화 녹음 및 업무폰 기능을 위해\n다음 권한이 필요합니다.\n\n• 마이크 (통화 녹음)\n• 전화 상태 (통화 감지)\n• 통화 기록 (발신 번호 확인)")
            .setPositiveButton("허용") { _, _ ->
                permissionLauncher.launch(deniedPermissions.toTypedArray())
            }
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
                        token = token,
                        deviceId = deviceId
                    )
                    showRegisteredState()
                    checkAndRequestPermissions()
                } else {
                    tvStatus.text = "등록 실패: 서버 오류 (${response.code()})"
                    tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                    btnRegister.isEnabled = true
                    btnScanQr.isEnabled = true
                }
            } catch (e: Exception) {
                tvStatus.text = "등록 실패: ${e.message}"
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

        val serviceIntent = Intent(this, PhoneStateService::class.java)
        startForegroundService(serviceIntent)
    }
}
