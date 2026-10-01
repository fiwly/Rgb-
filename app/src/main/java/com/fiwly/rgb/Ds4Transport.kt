package com.fiwly.rgb

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import java.lang.reflect.InvocationTargetException
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
            try { adapter.closeProfileProxy(HID_HOST_PROFILE, existing) } catch (_: Throwable) {}
            proxy = null
            device = null
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

    override suspend fun isConnected(): Boolean {
        val p = proxy ?: return false
        val d = device ?: findConnectedDs4(p) ?: return false
        device = d
        return try {
            p.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED
        } catch (_: Throwable) {
            false
        }
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

            val hostClass = Class.forName("android.bluetooth.BluetoothHidHost")
            val attempts = mutableListOf<String>()

            try {
                val value = HiddenApiBypass.invoke(
                    hostClass,
                    p,
                    "sendData",
                    d,
                    hex
                ) as? Boolean

                attempts += "BluetoothHidHost.sendData(2)=$value"
                if (value == true) return Result.success(Unit)
            } catch (t: Throwable) {
                attempts += "BluetoothHidHost.sendData(2) threw ${shortError(t)}"
            }

            try {
                val value = HiddenApiBypass.invoke(
                    hostClass,
                    p,
                    "setReport",
                    d,
                    0x02.toByte(),
                    hex
                ) as? Boolean

                attempts += "BluetoothHidHost.setReport(3)=$value"
                if (value == true) return Result.success(Unit)
            } catch (t: Throwable) {
                attempts += "BluetoothHidHost.setReport(3) threw ${shortError(t)}"
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

    private fun shortError(t: Throwable): String {
        val root = when (t) {
            is InvocationTargetException -> t.targetException ?: t
            else -> t.cause ?: t
        }
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
