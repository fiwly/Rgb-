package com.fiwly.rgb

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.lsposed.hiddenapibypass.HiddenApiBypass

class AndroidHidHostTransport(
    private val context: android.content.Context,
    private val adapter: BluetoothAdapter
) : Ds4Transport {
    companion object {
        private const val HID_HOST_PROFILE = 4
        // Hidden/System API values from BluetoothProfile. They are not exposed
        // in the public SDK, so keep the stable platform values locally.
        private const val CONNECTION_POLICY_ALLOWED = 100
        private const val CONNECTION_POLICY_FORBIDDEN = 0
        private const val CONNECTION_POLICY_UNKNOWN = -1
        private const val HID_CONTROL_PSM = 0x11
        private const val HID_INTERRUPT_PSM = 0x13
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
            return ensureHidConnected(existing, paired, CONNECT_TIMEOUT_MS)
        }

        val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
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

        device = paired
        return ensureHidConnected(p, paired, CONNECT_TIMEOUT_MS)
    }

    private suspend fun ensureHidConnected(
        p: BluetoothProfile,
        d: BluetoothDevice,
        timeoutMs: Long
    ): Result<Unit> {
        var state = connectionState(p, d)

        if (state == BluetoothProfile.STATE_CONNECTED) {
            return Result.success(Unit)
        }

        // Android HID Host requires the connection policy to be ALLOWED
        // before its internal handler performs nativeConnect(). A plain
        // connect() can otherwise be accepted but never reach CONNECTED.
        val policyResult = trySetConnectionPolicyAllowed(p, d)
        if (policyResult == true && waitForConnected(p, d, timeoutMs)) {
            return Result.success(Unit)
        }

        state = connectionState(p, d)

        if (state == BluetoothProfile.STATE_DISCONNECTING) {
            waitForState(p, d, BluetoothProfile.STATE_DISCONNECTED, 4_000L)
            state = connectionState(p, d)
        }

        if (state == BluetoothProfile.STATE_CONNECTING) {
            if (waitForConnected(p, d, timeoutMs)) {
                return Result.success(Unit)
            }
            state = connectionState(p, d)
        }

        if (state == BluetoothProfile.STATE_DISCONNECTED) {
            val accepted = requestHidConnect(p, d)
            if (accepted != false && waitForConnected(p, d, timeoutMs)) {
                return Result.success(Unit)
            }
            state = connectionState(p, d)
        }

        val policy = getConnectionPolicy(p, d)
        val policyText = when (policy) {
            CONNECTION_POLICY_ALLOWED -> "ALLOWED"
            CONNECTION_POLICY_FORBIDDEN -> "FORBIDDEN"
            CONNECTION_POLICY_UNKNOWN -> "UNKNOWN"
            else -> "POLICY($policy)"
        }

        return Result.failure(
            IllegalStateException(
                "DS4 HID is ${stateName(state)} after reconnect request; policy=$policyText."
            )
        )
    }

    private suspend fun waitForState(
        p: BluetoothProfile,
        d: BluetoothDevice,
        wantedState: Int,
        timeoutMs: Long
    ) {
        val attempts = (timeoutMs / 250L).toInt().coerceAtLeast(1)
        repeat(attempts) {
            if (connectionState(p, d) == wantedState) return
            delay(250L)
        }
    }

    private fun connectionState(p: BluetoothProfile, d: BluetoothDevice): Int =
        try {
            p.getConnectionState(d)
        } catch (_: Throwable) {
            BluetoothProfile.STATE_DISCONNECTED
        }

    private fun getConnectionPolicy(p: BluetoothProfile, d: BluetoothDevice): Int {
        return try {
            val hostClass = Class.forName("android.bluetooth.BluetoothHidHost")
            (HiddenApiBypass.invoke(hostClass, p, "getConnectionPolicy", d) as? Int)
                ?: CONNECTION_POLICY_UNKNOWN
        } catch (_: Throwable) {
            CONNECTION_POLICY_UNKNOWN
        }
    }

    private fun trySetConnectionPolicyAllowed(p: BluetoothProfile, d: BluetoothDevice): Boolean? {
        return try {
            val hostClass = Class.forName("android.bluetooth.BluetoothHidHost")
            (HiddenApiBypass.invoke(
                hostClass,
                p,
                "setConnectionPolicy",
                d,
                CONNECTION_POLICY_ALLOWED
            ) as? Boolean) ?: false
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            false
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

    private fun requestHidConnect(p: BluetoothProfile, d: BluetoothDevice): Boolean? {
        return try {
            val hostClass = Class.forName("android.bluetooth.BluetoothHidHost")
            HiddenApiBypass.invoke(hostClass, p, "connect", d) as? Boolean
        } catch (_: Throwable) {
            null
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
            val connection = ensureHidConnected(profile, ds4, 6_000L)
            if (connection.isFailure) {
                // Android 16/HyperOS protects BluetoothHidHost.connect() with
                // BLUETOOTH_PRIVILEGED. Fall back to the DS4's native classic
                // Bluetooth HID L2CAP channels; no root or USB is required.
                val raw = rawL2capLightbar(ds4, color)
                if (raw.isSuccess) return raw
                return Result.failure(
                    IllegalStateException(
                        connection.exceptionOrNull()?.message +
                            "; rawL2cap=" + raw.exceptionOrNull()?.message
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

    private suspend fun rawL2capLightbar(
        ds4: BluetoothDevice,
        color: Ds4Color
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var control: android.bluetooth.BluetoothSocket? = null
        var interrupt: android.bluetooth.BluetoothSocket? = null
        try {
            val deviceClass = BluetoothDevice::class.java
            control = HiddenApiBypass.invoke(
                deviceClass, ds4, "createL2capSocket", HID_CONTROL_PSM
            ) as? android.bluetooth.BluetoothSocket
                ?: throw IOException("createL2capSocket(control) returned null")

            control.connect()

            interrupt = HiddenApiBypass.invoke(
                deviceClass, ds4, "createL2capSocket", HID_INTERRUPT_PSM
            ) as? android.bluetooth.BluetoothSocket
                ?: throw IOException("createL2capSocket(interrupt) returned null")
            interrupt.connect()

            val report = Ds4Report.bluetoothLightbar(color.red, color.green, color.blue)
            val packet = ByteArray(report.size + 1)
            packet[0] = 0xA2.toByte()
            report.copyInto(packet, 1)

            interrupt.outputStream.use { out ->
                out.write(packet)
                out.flush()
            }

            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t.cause ?: t)
        } finally {
            try { interrupt?.close() } catch (_: Throwable) {}
            try { control?.close() } catch (_: Throwable) {}
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
