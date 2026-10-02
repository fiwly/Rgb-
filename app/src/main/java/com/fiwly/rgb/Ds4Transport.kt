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
        private const val HID_CONTROL_PSM = 0x11
        private const val HID_INTERRUPT_PSM = 0x13
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val RESTORE_CONNECT_TIMEOUT_MS = 1_500L
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
        p: BluetoothProfile, d: BluetoothDevice, timeoutMs: Long
    ): Result<Unit> {
        var state = connectionState(p, d)
        if (state == BluetoothProfile.STATE_CONNECTED) return Result.success(Unit)

        // Do not use HID connection-policy APIs here. They require a
        // privileged permission on current Android and can report UNKNOWN
        // to normal apps even when the paired controller is usable.
        if (state == BluetoothProfile.STATE_DISCONNECTING) {
            waitForState(p, d, BluetoothProfile.STATE_DISCONNECTED, 1_500L)
            state = connectionState(p, d)
        }
        if (state == BluetoothProfile.STATE_CONNECTING) {
            if (waitForConnected(p, d, timeoutMs)) return Result.success(Unit)
            state = connectionState(p, d)
        }
        if (state == BluetoothProfile.STATE_DISCONNECTED) {
            val accepted = requestHidConnect(p, d)
            if (accepted != false && waitForConnected(p, d, timeoutMs)) return Result.success(Unit)
            state = connectionState(p, d)
        }
        return Result.failure(IllegalStateException("DS4 HID is " + stateName(state) + " after reconnect request."))
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
        val ds4 = device ?: findBondedDs4()
            ?: return Result.failure(IllegalStateException("No paired DualShock 4 found"))
        device = ds4

        // Fast path: use raw DS4 HIDP when the classic Bluetooth ACL is ready.
        // This bypasses the privileged HID connection-policy API completely.
        val raw = rawL2capLightbar(ds4, color)
        if (raw.isSuccess) return raw

        var p = proxy
        if (p == null) {
            val connection = connect()
            if (connection.isFailure) return Result.failure(
                IllegalStateException("DS4 HID unavailable; rawL2cap=" + (raw.exceptionOrNull()?.message ?: "failed"))
            )
            p = proxy
        }

        val profile = p ?: return Result.failure(IllegalStateException("HID Host proxy is unavailable"))
        var state = connectionState(profile, ds4)
        if (state != BluetoothProfile.STATE_CONNECTED) {
            val connection = ensureHidConnected(profile, ds4, RESTORE_CONNECT_TIMEOUT_MS)
            if (connection.isFailure) return Result.failure(
                IllegalStateException(connection.exceptionOrNull()?.message + "; rawL2cap=" + (raw.exceptionOrNull()?.message ?: "failed"))
            )
            state = connectionState(profile, ds4)
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
