package com.fiwly.rgb

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.AttributionSource
import android.os.Build
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
                                n.contains("Wireless Controller", true) ||
                                    n.contains("DUALSHOCK", true)
                            }
                        } catch (_: Throwable) {
                            null
                        }

                        if (ds4 == null) {
                            cont.resume(
                                Result.failure<Unit>(
                                    IllegalStateException("Pair the DS4 first")
                                )
                            )
                        } else {
                            device = ds4
                            cont.resume(Result.success(Unit))
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        proxy = null
                    }
                }

                listener = serviceListener
                val ok = adapter.getProfileProxy(context, serviceListener, 4)
                if (!ok) {
                    cont.resume(
                        Result.failure<Unit>(
                            IllegalStateException("HID Host profile is unavailable")
                        )
                    )
                }

                cont.invokeOnCancellation { listener = null }
            } catch (t: Throwable) {
                cont.resume(Result.failure<Unit>(t))
            }
        }

    override suspend fun setLightbar(color: Ds4Color): Result<Unit> {
        val p = proxy
            ?: return Result.failure<Unit>(
                IllegalStateException("HID Host is not connected")
            )
        val d = device
            ?: return Result.failure<Unit>(
                IllegalStateException("DS4 is not selected")
            )

        return try {
            val report = Ds4Report.bluetoothLightbar(
                color.red,
                color.green,
                color.blue
            )
            val hex = report.joinToString("") {
                "%02x".format(it.toInt() and 0xFF)
            }

            // Android 12+ adds AttributionSource to the hidden HID-host
            // methods. Older Android releases use the 2/3-argument forms.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val source = context.attributionSource

                val sendData3 = findMethod(
                    p,
                    "sendData",
                    BluetoothDevice::class.java,
                    String::class.java,
                    AttributionSource::class.java
                )
                if (sendData3 != null) {
                    val sent = invokeBoolean(p, sendData3, d, hex, source)
                    if (sent) return Result.success(Unit)
                }

                val setReport4 = findMethod(
                    p,
                    "setReport",
                    BluetoothDevice::class.java,
                    Byte::class.javaPrimitiveType!!,
                    String::class.java,
                    AttributionSource::class.java
                )
                if (setReport4 != null) {
                    val sent = invokeBoolean(
                        p,
                        setReport4,
                        d,
                        0x02.toByte(),
                        hex,
                        source
                    )
                    if (sent) return Result.success(Unit)
                }
            }

            val sendData2 = findMethod(
                p,
                "sendData",
                BluetoothDevice::class.java,
                String::class.java
            )
            if (sendData2 != null) {
                val sent = invokeBoolean(p, sendData2, d, hex)
                if (sent) return Result.success(Unit)
            }

            val setReport3 = findMethod(
                p,
                "setReport",
                BluetoothDevice::class.java,
                Byte::class.javaPrimitiveType!!,
                String::class.java
            )
            if (setReport3 != null) {
                val sent = invokeBoolean(p, setReport3, d, 0x02.toByte(), hex)
                if (sent) return Result.success(Unit)
            }

            Result.failure<Unit>(
                UnsupportedOperationException(
                    "Android HID Host output API is blocked or unavailable on this device"
                )
            )
        } catch (t: Throwable) {
            Result.failure<Unit>(t.cause ?: t)
        }
    }

    private fun findMethod(
        target: Any,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? {
        val publicMatch = target.javaClass.methods.firstOrNull {
            it.name == name &&
                it.parameterTypes.contentEquals(parameterTypes)
        }
        if (publicMatch != null) return publicMatch

        return try {
            target.javaClass.getDeclaredMethod(name, *parameterTypes).also {
                it.isAccessible = true
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun invokeBoolean(target: Any, method: Method, vararg args: Any): Boolean {
        return (method.invoke(target, *args) as? Boolean) == true
    }

    override fun close() {
        try {
            proxy?.let {
                adapter.closeProfileProxy(4, it as BluetoothProfile)
            }
        } catch (_: Throwable) {
        }
        proxy = null
        device = null
        listener = null
    }
}
