package com.polylingua.edge

import android.app.Service
import android.content.Intent
import android.os.IBinder

class PolyLinguaForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
