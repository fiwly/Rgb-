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
        var startupGrace = true

        // Give Android/HyperOS time to finish rebuilding the DS4 HID connection
        // after Bluetooth is enabled or the controller is powered on.
        delay(3500L)
        startupGrace = false

        while (scope.isActive) {
            try {
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                if (!prefs.getBoolean("auto_enabled", false)) {
                    lastConnected = false
                    restoreNeeded = true
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
                    lastConnected = false
                    restoreNeeded = true

                    // Keep the HID Host proxy alive. Repeatedly closing/recreating
                    // it can race Android's Bluetooth HID service on reconnect.
                    var reconnected = false
                    repeat(20) {
                        if (!scope.isActive) return@repeat
                        val result = t.connect()
                        if (result.isSuccess && t.isConnected()) {
                            reconnected = true
                            return@repeat
                        }
                        delay(1500L)
                    }

                    if (!reconnected) {
                        // HID Host reconnect is privileged on current Android.
                        // Give the transport its raw DS4 L2CAP fallback one
                        // chance to wake the controller/link and restore RGB.
                        val rawRestore = t.setLightbar(color)
                        if (rawRestore.isSuccess) {
                            restoreNeeded = false
                            lastConnected = false
                            delay(2500L)
                        } else {
                            delay(3000L)
                        }
                    }
                    continue
                }

                val connectedNow = t.isConnected()

                if (connectedNow && (!lastConnected || restoreNeeded)) {
                    // HID can report CONNECTED a little before the controller is
                    // ready for output. Retry the actual RGB report several times,
                    // but only during a reconnect/restore event.
                    var restored = false
                    // Give the controller/HID output channel a longer window after
                    // reconnect. This is only active during a restore event, so it
                    // does not fight games while the controller remains connected.
                    repeat(16) {
                        if (!scope.isActive || !t.isConnected()) return@repeat
                        val result = t.setLightbar(color)
                        if (result.isSuccess) {
                            restored = true
                            return@repeat
                        }
                        delay(1000L)
                    }
                    restoreNeeded = !restored
                }

                lastConnected = connectedNow
                delay(1200L)
            } catch (_: Throwable) {
                lastConnected = false
                restoreNeeded = true
                delay(2000L)
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
