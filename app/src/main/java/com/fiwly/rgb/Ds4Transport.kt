package com.fiwly.rgb

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.lsposed.hiddenapibypass.HiddenApiBypass

class AndroidHidHostTransport(
    private val context: android.content.Context,
    private val adapter: BluetoothAdapter
) : Ds4Transport {
    companion object {
        private const val HID_HOST_PROFILE = 4
        private const val PROXY_TIMEOUT_MS = 3_000L
    }

    override val name = "Android HID Host"
    private var proxy: BluetoothProfile? = null
    private var device: BluetoothDevice? = null
    private var listener: BluetoothProfile.ServiceListener? = null

    override suspend fun connect(): Result<Unit> {
        val paired = findBondedDs4()
            ?: return Result.failure(
                IllegalStateException(
                    "No paired DualShock 4 found. Pair the controller in Android Bluetooth settings."
                )
            )

        device = paired

        val existing = proxy
        if (existing != null) {
            return Result.success(Unit)
        }

        val result = withTimeoutOrNull(PROXY_TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<Unit>> { cont ->
                try {
                    val serviceListener = object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                            proxy = p
                            device = paired
                            if (cont.isActive) cont.resume(Result.success(Unit))
                        }

                        override fun onServiceDisconnected(profile: Int) {
                            proxy = null
                            device = null
                        }
                    }

                    listener = serviceListener
                    val ok = adapter.getProfileProxy(context, serviceListener, HID_HOST_PROFILE)

                    if (!ok && cont.isActive) {
                        cont.resume(
                            Result.failure(
                                IllegalStateException(
                                    "Android could not provide the HID Host profile."
                                )
                            )
                        )
                    }

                    cont.invokeOnCancellation { listener = null }
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resume(Result.failure(t))
                }
            }
        }

        if (result == null) {
            return Result.failure(IllegalStateException("Timed out waiting for Android HID Host."))
        }
        if (result.isFailure) return result

        val p = proxy ?: return Result.failure(
            IllegalStateException("HID Host proxy disappeared.")
        )

        // IMPORTANT: do not call BluetoothHidHost.connect().
        // Android owns the HID connection lifecycle. Calling the hidden connect()
        // method from a normal app can cause the DS4 to be dropped and produces
        // the exact "DISCONNECTED after reconnect request" seen on the device.
        return Result.success(Unit)
    }

    private suspend fun waitForNaturalConnection(
        p: BluetoothProfile,
        d: BluetoothDevice,
        timeoutMs: Long
    ): Result<Unit> {
        var connected = isHidConnected(p, d)
        if (connected) return Result.success(Unit)

        val attempts = (timeoutMs / 200L).toInt().coerceAtLeast(1)
        repeat(attempts) {
            connected = isHidConnected(p, d)
            if (connected) return Result.success(Unit)
            delay(200L)
        }

        return Result.failure(
            IllegalStateException(
                "DS4 HID is not connected. Turn on the controller and let Android reconnect it first."
            )
        )
    }

    private fun isHidConnected(p: BluetoothProfile, d: BluetoothDevice): Boolean {
        try {
            if (p.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED) return true
        } catch (_: Throwable) {}

        return try {
            val hostClass = Class.forName("android.bluetooth.BluetoothHidHost")
            val result = HiddenApiBypass.invoke(hostClass, p, "getConnectedDevices")
            @Suppress("UNCHECKED_CAST")
            (result as? List<BluetoothDevice>)?.any { sameDevice(it, d) } == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun sameDevice(a: BluetoothDevice, b: BluetoothDevice): Boolean {
        return try {
            a.address.equals(b.address, ignoreCase = true)
        } catch (_: Throwable) {
            a == b
        }
    }

    override suspend fun isConnected(): Boolean {
        val p = proxy
        val d = device ?: findBondedDs4() ?: return false
        if (p == null) return false
        device = d
        return isHidConnected(p, d)
    }

    override suspend fun setLightbar(color: Ds4Color): Result<Unit> {
        val ds4 = device ?: findBondedDs4()
            ?: return Result.failure(IllegalStateException("No paired DualShock 4 found"))
        device = ds4

        if (proxy == null) {
            val connection = connect()
            if (connection.isFailure) return connection
        }

        val report = Ds4Report.bluetoothLightbar(color.red, color.green, color.blue)
        val hex = report.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val hostClass = Class.forName("android.bluetooth.BluetoothHidHost")
        val attempts = mutableListOf<String>()

        val profile = proxy
        if (profile != null) {
            try {
                val value = HiddenApiBypass.invoke(hostClass, profile, "sendData", ds4, hex) as? Boolean
                attempts += "sendData=$value"
                if (value == true) return Result.success(Unit)
            } catch (t: Throwable) {
                attempts += "sendData threw ${shortError(t)}"
            }

            try {
                val value = HiddenApiBypass.invoke(hostClass, profile, "setReport", ds4, 0x02.toByte(), hex) as? Boolean
                attempts += "setReport=$value"
                if (value == true) return Result.success(Unit)
            } catch (t: Throwable) {
                attempts += "setReport threw ${shortError(t)}"
            }
        }

        val direct = directL2capSend(ds4, report)
        if (direct.isSuccess) return direct
        attempts += "directL2cap=${direct.exceptionOrNull()?.message ?: "failed"}"

        return Result.failure(
            UnsupportedOperationException("HID output failed. ${attempts.joinToString(" | ")}")
        )
    }

    private suspend fun directL2capSend(ds4: BluetoothDevice, report: ByteArray): Result<Unit> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var last: Throwable? = null
            repeat(3) { attempt ->
                var control: android.bluetooth.BluetoothSocket? = null
                var interrupt: android.bluetooth.BluetoothSocket? = null
                try {
                    if (attempt > 0) delay(350L)

                    val createSecure = ds4.javaClass.getDeclaredMethod(
                        "createL2capSocket", Int::class.javaPrimitiveType
                    ).apply { isAccessible = true }
                    val createInsecure = ds4.javaClass.getDeclaredMethod(
                        "createInsecureL2capSocket", Int::class.javaPrimitiveType
                    ).apply { isAccessible = true }

                    control = try {
                        createSecure.invoke(ds4, 0x11) as android.bluetooth.BluetoothSocket
                    } catch (_: Throwable) {
                        createInsecure.invoke(ds4, 0x11) as android.bluetooth.BluetoothSocket
                    }
                    control.connect()
                    control.close()
                    control = null

                    interrupt = try {
                        createSecure.invoke(ds4, 0x13) as android.bluetooth.BluetoothSocket
                    } catch (_: Throwable) {
                        createInsecure.invoke(ds4, 0x13) as android.bluetooth.BluetoothSocket
                    }
                    interrupt.connect()

                    interrupt.outputStream.use { out ->
                        out.write(0xA2)
                        out.write(report)
                        out.flush()
                    }
                    return@withContext Result.success(Unit)
                } catch (t: Throwable) {
                    last = t.cause ?: t
                } finally {
                    try { control?.close() } catch (_: Throwable) {}
                    try { interrupt?.close() } catch (_: Throwable) {}
                }
            }
            Result.failure(last ?: IllegalStateException("Direct L2CAP failed"))
        }

    private fun findBondedDs4(): BluetoothDevice? {
        return try {
            adapter.bondedDevices.firstOrNull { isDs4(it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun isDs4(device: BluetoothDevice): Boolean {
        val n = try { device.name ?: "" } catch (_: Throwable) { "" }
        return n.contains("Wireless Controller", true) || n.contains("DUALSHOCK", true)
    }

    private fun shortError(t: Throwable): String {
        val root = when (t) {
            is InvocationTargetException -> t.targetException ?: t
            else -> t.cause ?: t
        }
        return root.javaClass.simpleName + ":" + (root.message ?: "no-message")
    }

    override fun close() {
        try {
            proxy?.let { adapter.closeProfileProxy(HID_HOST_PROFILE, it) }
        } catch (_: Throwable) {}
        proxy = null
        device = null
        listener = null
    }
}
