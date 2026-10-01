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
            scope.launch { monitorConnection() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private suspend fun monitorConnection() {
        var restoreNeeded = true
        var lastConnected = false

        while (scope.isActive) {
            try {
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                if (!prefs.getBoolean("auto_enabled", false)) {
                    restoreNeeded = true
                    lastConnected = false
                    delay(1500L)
                    continue
                }

                val color = Ds4Color(
                    prefs.getInt("auto_r", 0),
                    prefs.getInt("auto_g", 0),
                    prefs.getInt("auto_b", 0),
                    100
                )

                val t = transport
                if (t == null) {
                    delay(1500L)
                    continue
                }

                if (!t.isConnected()) {
                    restoreNeeded = true
                    lastConnected = false
                    t.connect()
                    delay(1000L)
                    continue
                }

                val connectedNow = t.isConnected()

                // Restore only when the DS4 becomes connected again.
                // Do not continuously overwrite the lightbar while a game is running.
                if (connectedNow && (!lastConnected || restoreNeeded)) {
                    val result = t.setLightbar(color)
                    if (result.isSuccess) {
                        restoreNeeded = false
                    } else {
                        restoreNeeded = true
                    }
                }

                lastConnected = connectedNow
                delay(1000L)
            } catch (_: Throwable) {
                lastConnected = false
                restoreNeeded = true
                delay(1500L)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DS4 RGB Keeper",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DS4 RGB")
            .setContentText("Auto-restoring DS4 lightbar color")
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
