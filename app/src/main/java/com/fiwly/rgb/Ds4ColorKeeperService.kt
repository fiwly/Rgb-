package com.fiwly.rgb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class Ds4ColorKeeperService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transport: Ds4Transport? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter != null) {
            transport = AndroidHidHostTransport(this, adapter)
            scope.launch { keepColor() }
        }
    }

    private suspend fun keepColor() {
        while (isActive) {
            try {
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                if (prefs.getBoolean("auto_enabled", false)) {
                    val color = Ds4Color(
                        prefs.getInt("auto_r", 0),
                        prefs.getInt("auto_g", 0),
                        prefs.getInt("auto_b", 0),
                        100
                    )
                    transport?.setLightbar(color)
                }
            } catch (_: Throwable) {
            }
            delay(2000L)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DS4 RGB Keeper",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DS4 RGB")
            .setContentText("Keeping the selected controller color")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    override fun onDestroy() {
        scope.cancel()
        transport?.close()
        transport = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS = "ds4_rgb_slots"
        private const val CHANNEL_ID = "ds4_rgb_keeper"
        private const val NOTIFICATION_ID = 7101
    }
}
