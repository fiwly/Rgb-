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
        var lastConnected = false
        var restoreNeeded = true
        var proxyReady = false

        while (scope.isActive) {
            try {
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                if (!prefs.getBoolean("auto_enabled", false)) {
                    lastConnected = false
                    restoreNeeded = true
                    proxyReady = false
                    delay(1000L)
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
                    delay(1000L)
                    continue
                }

                if (!proxyReady) {
                    val r = t.connect()
                    proxyReady = r.isSuccess
                    if (!proxyReady) {
                        delay(500L)
                        continue
                    }
                    restoreNeeded = true
                }

                // Do not trust HID connection-state reporting on HyperOS.
                // The actual HID output call is the connection test.
                if (restoreNeeded || !lastConnected) {
                    val result = t.setLightbar(color)
                    if (result.isSuccess) {
                        restoreNeeded = false
                        lastConnected = true
                    } else {
                        lastConnected = false
                        restoreNeeded = true
                        delay(400L)
                    }
                } else {
                    delay(700L)
                }
            } catch (_: Throwable) {
                lastConnected = false
                restoreNeeded = true
                proxyReady = false
                delay(1000L)
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
