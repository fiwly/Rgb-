package com.fiwly.rgb

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import java.lang.reflect.Method
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AndroidHidHostTransport(
    private val context: android.content.Context,
    private val adapter: BluetoothAdapter
) : Ds4Transport {
    companion object {
        private const val HID_HOST_PROFILE = 4
        private const val CONNECT_TIMEOUT_MS = 8_000L
    }

    override val name = "Android HID Host"
    private var proxy: BluetoothProfile? = null
    private var device: BluetoothDevice? = null
    private var listener: BluetoothProfile.ServiceListener? = null

    override suspend fun connect(): Result<Unit> {
        val existing = proxy
        if (existing != null) {
            val existingDevice = findConnectedDs4(existing)
            if (existingDevice != null) {
                device = existingDevice
                return Result.success(Unit)
            }
        }

        val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<Unit>> { cont ->
                try {
                    val serviceListener = object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                            proxy = p

                            val connected = findConnectedDs4(p)
                            if (connected != null) {
                                device = connected
                                cont.resume(Result.success(Unit))
                                return
                            }

                            val paired = findBondedDs4()
                            if (paired == null) {
                                cont.resume(Result.failure<Unit>(
                                    IllegalStateException(
                                        "No paired DualShock 4 found. Pair the controller in Android Bluetooth settings."
                                    )
                                ))
                                return
                            }

                            val state = try {
                                p.getConnectionState(paired)
                            } catch (t: Throwable) {
                                cont.resume(Result.failure<Unit>(
                                    IllegalStateException(
                                        "Cannot read HID connection state: ${shortError(t)}"
                                    )
                                ))
                                return
                            }

                            cont.resume(Result.failure<Unit>(
                                IllegalStateException(
                                    "DS4 found: ${paired.name ?: "Wireless Controller"}; " +
                                        "HID state=${stateName(state)}. " +
                                        "Android must show the controller as connected before RGB output can be sent."
                                )
                            ))
                        }

                        override fun onServiceDisconnected(profile: Int) {
                            proxy = null
                            device = null
                        }
                    }

                    listener = serviceListener
                    val ok = adapter.getProfileProxy(
                        context,
                        serviceListener,
                        HID_HOST_PROFILE
                    )

                    if (!ok) {
                        cont.resume(Result.failure<Unit>(
                            IllegalStateException(
                                "Android could not provide the HID Host profile."
                            )
                        ))
                    }

                    cont.invokeOnCancellation {
                        listener = null
                    }
                } catch (t: Throwable) {
                    cont.resume(Result.failure<Unit>(t))
                }
            }
        }

        return result ?: Result.failure(
            IllegalStateException(
                "Timed out waiting for Android HID Host. Keep the DS4 connected over Bluetooth and try again."
            )
        )
    }

    override suspend fun setLightbar(color: Ds4Color): Result<Unit> {
        if (proxy == null || device == null) {
            val connection = connect()
            if (connection.isFailure) return connection
        }

        val p = proxy ?: return Result.failure<Unit>(
            IllegalStateException("HID Host proxy is unavailable")
        )
        val d = device ?: return Result.failure<Unit>(
            IllegalStateException("DS4 is not selected")
        )

        val state = try {
            p.getConnectionState(d)
        } catch (t: Throwable) {
            return Result.failure<Unit>(
                IllegalStateException("Cannot read HID state before sending: ${shortError(t)}")
            )
        }

        if (state != BluetoothProfile.STATE_CONNECTED) {
            return Result.failure<Unit>(
                IllegalStateException(
                    "DS4 HID state is ${stateName(state)}. Android has not exposed an active HID connection."
                )
            )
        }

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
                    clazz,
                    "sendData",
                    BluetoothDevice::class.java,
                    String::class.java
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
                    clazz,
                    "setReport",
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

            Result.failure<Unit>(
                UnsupportedOperationException(
                    "HID output failed for connected DS4. attempts=${attempts.joinToString(" | ")}"
                )
            )
        } catch (t: Throwable) {
            Result.failure<Unit>(t.cause ?: t)
        }
    }

    private fun findBondedDs4(): BluetoothDevice? {
        return try {
            adapter.bondedDevices.firstOrNull { isDs4(it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun findConnectedDs4(profile: BluetoothProfile): BluetoothDevice? {
        return try {
            profile.connectedDevices.firstOrNull { isDs4(it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun isDs4(device: BluetoothDevice): Boolean {
        val n = try { device.name ?: "" } catch (_: Throwable) { "" }
        return n.contains("Wireless Controller", true) ||
            n.contains("DUALSHOCK", true)
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        else -> "UNKNOWN($state)"
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
                adapter.closeProfileProxy(HID_HOST_PROFILE, it)
            }
        } catch (_: Throwable) {
        }
        proxy = null
        device = null
        listener = null
    }
}
