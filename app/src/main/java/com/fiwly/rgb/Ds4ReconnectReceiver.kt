package com.fiwly.rgb

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class Ds4ReconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        val relevant = action == BluetoothDevice.ACTION_ACL_CONNECTED ||
            action == "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED" ||
            action == BluetoothDevice.ACTION_BOND_STATE_CHANGED ||
            action == "android.bluetooth.adapter.action.STATE_CHANGED"

        if (!relevant) return

        val prefs = context.getSharedPreferences(
            Ds4ColorKeeperService.PREFS,
            Context.MODE_PRIVATE
        )
        if (!prefs.getBoolean("auto_enabled", false)) return

        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        if (device != null) {
            val name = try { device.name ?: "" } catch (_: Throwable) { "" }
            if (!name.contains("Wireless Controller", true) &&
                !name.contains("DUALSHOCK", true)) return
        }

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, Ds4ColorKeeperService::class.java)
            )
        } catch (_: Throwable) {
            // The service may already be running. MainActivity can also start it
            // when the user opens the app.
        }
    }
}
