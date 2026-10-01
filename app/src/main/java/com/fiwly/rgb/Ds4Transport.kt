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
    override val name = "Android HID Host"
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
            ?: return Result.failure<Unit>(IllegalStateException("HID Host is not connected"))
        val d = device
            ?: return Result.failure<Unit>(IllegalStateException("DS4 is not selected"))

        return try {
            val report = Ds4Report.bluetoothLightbar(color.red, color.green, color.blue)
            val hex = report.joinToString("") {
                "%02x".format(it.toInt() and 0xFF)
            }

            val attempts = mutableListOf<String>()
            val hostClass = try {
                Class.forName("android.bluetooth.BluetoothHidHost")
            } catch (_: Throwable) {
                null
            }

            val candidates = buildList {
                if (hostClass != null) add(hostClass)
                add(p.javaClass)
            }.distinct()

            for (clazz in candidates) {
                val send = findMethod(
                    clazz, "sendData",
                    BluetoothDevice::class.java, String::class.java
                )
                if (send != null) {
                    try {
                        val value = invokeBoolean(p, send, d, hex)
                        attempts += "${clazz.name}.sendData(2)=$value"
                        if (value) return Result.success(Unit)
                    } catch (t: Throwable) {
                        attempts += "${clazz.name}.sendData(2) threw ${shortError(t)}"
                    }
                } else {
                    attempts += "${clazz.name}.sendData(2)=not-found"
                }

                val setReport = findMethod(
                    clazz, "setReport",
                    BluetoothDevice::class.java,
                    Byte::class.javaPrimitiveType!!,
                    String::class.java
                )
                if (setReport != null) {
                    try {
                        val value = invokeBoolean(p, setReport, d, 0x02.toByte(), hex)
                        attempts += "${clazz.name}.setReport(3)=$value"
                        if (value) return Result.success(Unit)
                    } catch (t: Throwable) {
                        attempts += "${clazz.name}.setReport(3) threw ${shortError(t)}"
                    }
                } else {
                    attempts += "${clazz.name}.setReport(3)=not-found"
                }
            }

            val methodNames = p.javaClass.methods
                .filter { it.name == "sendData" || it.name == "setReport" }
                .joinToString(",") { m ->
                    m.name + "/" + m.parameterTypes.joinToString(";") { it.simpleName }
                }
                .ifEmpty { "none-visible" }

            Result.failure<Unit>(
                UnsupportedOperationException(
                    "HID output unavailable. methods=$methodNames; attempts=${attempts.joinToString(" | ")}"
                )
            )
        } catch (t: Throwable) {
            Result.failure<Unit>(t.cause ?: t)
        }
    }

    private fun findMethod(
        clazz: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? {
        return try {
            clazz.getDeclaredMethod(name, *parameterTypes).also {
                it.isAccessible = true
            }
        } catch (_: Throwable) {
            try {
                clazz.methods.firstOrNull {
                    it.name == name && it.parameterTypes.contentEquals(parameterTypes)
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun invokeBoolean(target: Any, method: Method, vararg args: Any): Boolean {
        return (method.invoke(target, *args) as? Boolean) == true
    }

    private fun shortError(t: Throwable): String {
        val root = t.cause ?: t
        return root.javaClass.simpleName + ":" + (root.message ?: "no-message")
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
