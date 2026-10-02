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

        var p = proxy
        if (p == null) {
            val connection = connect()
            if (connection.isFailure) return connection
            p = proxy
        }

        val profile = p
            ?: return Result.failure(IllegalStateException("HID Host proxy is unavailable"))

        return try {
            val report = Ds4Report.bluetoothLightbar(color.red, color.green, color.blue)
            val hex = report.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            val hostClass = Class.forName("android.bluetooth.BluetoothHidHost")
            val attempts = mutableListOf<String>()

            try {
                val value = HiddenApiBypass.invoke(
                    hostClass, profile, "sendData", ds4, hex
                ) as? Boolean
                attempts += "sendData=$value"
                if (value == true) return Result.success(Unit)
            } catch (t: Throwable) {
                attempts += "sendData threw ${shortError(t)}"
            }

            try {
                val value = HiddenApiBypass.invoke(
                    hostClass, profile, "setReport", ds4, 0x02.toByte(), hex
                ) as? Boolean
                attempts += "setReport=$value"
                if (value == true) return Result.success(Unit)
            } catch (t: Throwable) {
                attempts += "setReport threw ${shortError(t)}"
            }

            Result.failure(
                UnsupportedOperationException(
                    "HID output failed. attempts=${attempts.joinToString(" | ")}"
                )
            )
        } catch (t: Throwable) {
            Result.failure(t.cause ?: t)
        }
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
