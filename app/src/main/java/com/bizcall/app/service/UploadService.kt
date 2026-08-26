package com.bizcall.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class UploadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    // 추후 구현
}