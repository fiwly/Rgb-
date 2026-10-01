package com.fiwly.rgb

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

class Ds4ReconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED &&
            action != "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED") return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences("ds4_rgb_slots", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("auto_enabled", false)) return@launch

                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (device != null) {
                    val name = try { device.name ?: "" } catch (_: Throwable) { "" }
                    if (!name.contains("Wireless Controller", true) &&
                        !name.contains("DUALSHOCK", true)) return@launch
                }

                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: return@launch

                val color = Ds4Color(
                    prefs.getInt("auto_r", 0),
                    prefs.getInt("auto_g", 0),
                    prefs.getInt("auto_b", 0),
                    100
                )

                val transport = AndroidHidHostTransport(context, adapter)

                // HID Host can become ready several seconds after Bluetooth reports
                // the controller connection. Keep retrying until the profile is ready.
                repeat(15) { attempt ->
                    delay(if (attempt == 0) 1000L else 1000L)
                    if (transport.setLightbar(color).isSuccess) {
                        transport.close()
                        return@launch
                    }
                }

                transport.close()
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }
}
