package com.fiwly.rgb

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import java.lang.reflect.Method
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Attempts to use Android's built-in HID Host profile through its hidden
 * BluetoothHidHost API. The profile exists in AOSP, but the class/methods are
 * @hide, so Android's hidden-API policy may reject reflection on some builds.
 *
 * This does NOT require root by design. If the firmware blocks hidden API
 * access, the transport reports a clear failure and the app remains usable.
 */
class AndroidHidHostTransport(private val context: android.content.Context, private val adapter: BluetoothAdapter) : Ds4Transport {
    override val name = "Android HID Host (hidden API)"
    private var proxy: Any? = null
    private var device: BluetoothDevice? = null
    private var listener: BluetoothProfile.ServiceListener? = null

    override suspend fun connect(): Result<Unit> = suspendCancellableCoroutine { cont ->
        try {
            val l = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                    proxy = p
                    val ds4 = try {
                        adapter.bondedDevices.firstOrNull {
                            val n = it.name ?: ""
                            n.contains("Wireless Controller", ignoreCase = true) ||
                                n.contains("DUALSHOCK", ignoreCase = true)
                        }
                    } catch (t: Throwable) {
                        null
                    }
                    if (ds4 == null) {
                        cont.resume(Result.failure(IllegalStateException("Pair the DS4 first")))
                    } else {
                        device = ds4
                        cont.resume(Result.success(Unit))
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    proxy = null
                }
            }
            listener = l
            val ok = adapter.getProfileProxy(context, l, 4)
            if (!ok) cont.resume(Result.failure(IllegalStateException("HID Host profile is unavailable")))
            cont.invokeOnCancellation { listener = null }
        } catch (t: Throwable) {
            cont.resume(Result.failure(t))
        }
    }

    override suspend fun setLightbar(color: Ds4Color): Result<Unit> {
        val p = proxy ?: return Result.failure(IllegalStateException("HID Host is not connected"))
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
            if (sent) return Result.success(Unit)
            return Result.failure(IllegalStateException("HID Host rejected the output report"))
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
