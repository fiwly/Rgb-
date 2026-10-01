package com.fiwly.rgb

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

/**
 * Attempts to use Android's built-in HID Host profile through its hidden
 * BluetoothHidHost API. The profile exists in AOSP, but the class/methods are
 * @hide, so Android's hidden-API policy may reject reflection on some builds.
 *
 * This does NOT require root by design. If the firmware blocks hidden API
 * access, the transport reports a clear failure and the app remains usable.
 */
class AndroidHidHostTransport(private val adapter: BluetoothAdapter) : Ds4Transport {
    override val name = "Android HID Host (hidden API)"
    private var proxy: Any? = null
    private var device: BluetoothDevice? = null
    private var listener: BluetoothProfile.ServiceListener? = null

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val l = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                    proxy = p
                }
                override fun onServiceDisconnected(profile: Int) {
                    proxy = null
                }
            }
            listener = l

            // BluetoothProfile.HID_HOST is hidden in the SDK; AOSP assigns it
            // profile id 4. We keep the value local so this project compiles
            // against the public SDK.
            val ok = adapter.getProfileProxy(
                AppContextHolder.context,
                l,
                4
            )
            if (!ok) return@withContext Result.failure(IllegalStateException("HID Host profile is unavailable"))

            val bonded = adapter.bondedDevices
            val ds4 = bonded.firstOrNull {
                val n = runCatching { it.name }.getOrNull() ?: ""
                n.contains("Wireless Controller", ignoreCase = true) ||
                    n.contains("DUALSHOCK", ignoreCase = true) ||
                    n.contains("DualSense", ignoreCase = true)
            } ?: return@withContext Result.failure(IllegalStateException("Pair the DS4 first"))

            device = ds4
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun setLightbar(color: Ds4Color): Result<Unit> = withContext(Dispatchers.Main) {
        val p = proxy ?: return@withContext Result.failure(IllegalStateException("HID Host is not connected"))
        val d = device ?: return@withContext Result.failure(IllegalStateException("DS4 is not selected"))

        try {
            val report = Ds4Report.bluetoothLightbar(color.red, color.green, color.blue)
            val hex = report.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

            // AOSP BluetoothHidHost.sendData(BluetoothDevice,String) sends
            // a HID Send_Data command to the connected HID input device.
            // The method is @hide and therefore reached only by reflection.
            val method: Method = p.javaClass.methods.firstOrNull {
                it.name == "sendData" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == BluetoothDevice::class.java &&
                    it.parameterTypes[1] == String::class.java
            } ?: return@withContext Result.failure(
                UnsupportedOperationException("BluetoothHidHost.sendData is unavailable on this Android build")
            )

            val sent = method.invoke(p, d, hex) as? Boolean ?: false
            if (sent) Result.success(Unit)
            else Result.failure(IllegalStateException("HID Host rejected the output report"))
        } catch (t: Throwable) {
            Result.failure(t.cause ?: t)
        }
    }

    override fun close() {
        try {
            proxy?.let { adapter.closeProfileProxy(4, it as BluetoothProfile) }
        } catch (_: Throwable) {}
        proxy = null
        device = null
        listener = null
    }
}
