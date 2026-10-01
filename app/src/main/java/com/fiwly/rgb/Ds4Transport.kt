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
        private const val CONNECT_TIMEOUT_MS = 15_000L
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

        val existing = proxy
        if (existing != null) {
            device = paired
            requestHidConnect(existing, paired)
            return if (waitForConnected(existing, paired, CONNECT_TIMEOUT_MS)) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("DS4 HID did not become CONNECTED after reconnect request."))
            }
        }

        val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<Unit>> { cont ->
                try {
                    val serviceListener = object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                            proxy = p
                            device = paired
                            requestHidConnect(p, paired)
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
                        cont.resume(Result.failure<Unit>(
                            IllegalStateException("Android could not provide the HID Host profile.")
                        ))
                    }

                    cont.invokeOnCancellation { listener = null }
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resume(Result.failure<Unit>(t))
                }
            }
        }

        if (result == null) {
            return Result.failure(IllegalStateException("Timed out waiting for Android HID Host."))
        }
        if (result.isFailure) return result

        val p = proxy ?: return Result.failure(
            IllegalStateException("HID Host proxy disappeared during reconnect.")
        )

        return if (waitForConnected(p, paired, CONNECT_TIMEOUT_MS)) {
            device = paired
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException("DS4 HID did not become CONNECTED after reconnect request.")
            )
        }
    }

    private suspend fun waitForConnected(
        p: BluetoothProfile,
        d: BluetoothDevice,
        timeoutMs: Long
    ): Boolean {
        val attempts = (timeoutMs / 250L).toInt().coerceAtLeast(1)
        repeat(attempts) {
            val state = try {
                p.getConnectionState(d)
            } catch (_: Throwable) {
                BluetoothProfile.STATE_DISCONNECTED
            }
            if (state == BluetoothProfile.STATE_CONNECTED) return true
            delay(250L)
        }
        return false
    }

    private fun requestHidConnect(p: BluetoothProfile, d: BluetoothDevice) {
        try {
            val hostClass = Class.forName("android.bluetooth.BluetoothHidHost")
            HiddenApiBypass.invoke(hostClass, p, "connect", d)
        } catch (_: Throwable) {
            // Normal Android HID service may still complete a pending connection.
        }
    }

    override suspend fun isConnected(): Boolean {
        val p = proxy ?: return false
        val d = device ?: findBondedDs4() ?: return false
        device = d
        return try {
            p.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun setLightbar(color: Ds4Color): Result<Unit> {
        var p = proxy
        var d = device ?: findBondedDs4()

        if (p == null || d == null) {
            val connection = connect()
            if (connection.isFailure) return connection
            p = proxy
            d = device
        }

        val profile = p ?: return Result.failure(IllegalStateException("HID Host proxy is unavailable"))
        val ds4 = d ?: return Result.failure(IllegalStateException("DS4 is not selected"))

        var state = try {
            profile.getConnectionState(ds4)
        } catch (t: Throwable) {
            return Result.failure(
                IllegalStateException("Cannot read HID state before sending: ${shortError(t)}")
            )
        }

        if (state != BluetoothProfile.STATE_CONNECTED) {
            requestHidConnect(profile, ds4)
            if (!waitForConnected(profile, ds4, 6_000L)) {
                state = try {
                    profile.getConnectionState(ds4)
                } catch (_: Throwable) {
                    BluetoothProfile.STATE_DISCONNECTED
                }
                return Result.failure(
                    IllegalStateException(
                        "DS4 HID state is ${stateName(state)} after reconnect request."
                    )
                )
            }
        }

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

            Result.failure<Unit>(
                UnsupportedOperationException(
                    "HID output failed. attempts=${attempts.joinToString(" | ")}"
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

    private fun isDs4(device: BluetoothDevice): Boolean {
        val n = try { device.name ?: "" } catch (_: Throwable) { "" }
        return n.contains("Wireless Controller", true) || n.contains("DUALSHOCK", true)
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        else -> "UNKNOWN($state)"
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
