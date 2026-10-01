package com.fiwly.rgb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

class Ds4ReconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "android.bluetooth.device.action.ACL_CONNECTED") return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences("ds4_rgb_slots", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("auto_enabled", false)) return@launch

                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
                    ?: return@launch
                val transport = AndroidHidHostTransport(context, adapter)
                val color = Ds4Color(
                    prefs.getInt("auto_r", 0),
                    prefs.getInt("auto_g", 0),
                    prefs.getInt("auto_b", 0),
                    100
                )
                repeat(4) { attempt ->
                    delay(if (attempt == 0) 1200L else 700L)
                    if (transport.setLightbar(color).isSuccess) return@launch
                }
                transport.close()
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }
}
