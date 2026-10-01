package com.fiwly.rgb

import android.app.*
import android.bluetooth.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class Ds4RgbService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transport: Ds4Transport? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1001, NotificationCompat.Builder(this, "ds4_rgb_restore")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("DS4 RGB")
            .setContentText("Auto restore is active")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build())
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter != null) transport = AndroidHidHostTransport(this, adapter)
        scope.launch {
            while (isActive) {
                try {
                    val prefs = getSharedPreferences("ds4_rgb_slots", MODE_PRIVATE)
                    if (prefs.getBoolean("auto_enabled", false) && adapter?.isEnabled == true) {
                        transport?.setLightbar(Ds4Color(
                            prefs.getInt("auto_r", 0),
                            prefs.getInt("auto_g", 0),
                            prefs.getInt("auto_b", 0),
                            100
                        ))
                    }
                } catch (_: Throwable) {}
                delay(700)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        transport?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("ds4_rgb_restore", "DS4 RGB Auto Restore",
                NotificationManager.IMPORTANCE_LOW)
        )
    }
}
