package com.fiwly.rgb

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import java.lang.reflect.Method
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidHidHostTransport(
    private val context: android.content.Context,
    private val adapter: BluetoothAdapter
) : Ds4Transport {
    override val name = "Android HID Host (hidden API)"
    private var proxy: Any? = null
    private var device: BluetoothDevice? = null
    private var listener: BluetoothProfile.ServiceListener? = null

    override suspend fun connect(): Result<Unit> =
        suspendCancellableCoroutine { cont ->
            try {
                val serviceListener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                        proxy = p
                        val ds4 = try {
                            adapter.bondedDevices.firstOrNull {
                                val n = it.name ?: ""
                                n.contains("Wireless Controller", true) || n.contains("DUALSHOCK", true)
                            }
                        } catch (_: Throwable) { null }
                        if (ds4 == null) cont.resume(Result.failure<Unit>(IllegalStateException("Pair the DS4 first")))
                        else { device = ds4; cont.resume(Result.success(Unit)) }
                    }
                    override fun onServiceDisconnected(profile: Int) { proxy = null }
                }
                listener = serviceListener
                val ok = adapter.getProfileProxy(context, serviceListener, 4)
                if (!ok) cont.resume(Result.failure<Unit>(IllegalStateException("HID Host profile is unavailable")))
                cont.invokeOnCancellation { listener = null }
            } catch (t: Throwable) { cont.resume(Result.failure<Unit>(t)) }
        }

    override suspend fun setLightbar(color: Ds4Color): Result<Unit> {
        val p = proxy ?: return Result.failure<Unit>(IllegalStateException("HID Host is not connected"))
        val d = device ?: return Result.failure<Unit>(IllegalStateException("DS4 is not selected"))
        return try {
            val report = Ds4Report.bluetoothLightbar(color.red, color.green, color.blue)
            val hex = report.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            val method: Method = p.javaClass.methods.firstOrNull {
                it.name == "sendData" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == BluetoothDevice::class.java &&
                    it.parameterTypes[1] == String::class.java
            } ?: return Result.failure<Unit>(UnsupportedOperationException("BluetoothHidHost.sendData is unavailable on this Android build"))
            val sent = method.invoke(p, d, hex) as? Boolean ?: false
            if (sent) Result.success(Unit) else Result.failure<Unit>(IllegalStateException("HID Host rejected the output report"))
        } catch (t: Throwable) { Result.failure<Unit>(t.cause ?: t) }
    }

    override fun close() {
        try { proxy?.let { adapter.closeProfileProxy(4, it as BluetoothProfile) } } catch (_: Throwable) {}
        proxy = null; device = null; listener = null
    }
}
