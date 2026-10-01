package com.fiwly.rgb

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var preview: View
    private lateinit var rgbText: TextView
    private lateinit var transport: Ds4Transport

    private val btAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                (result[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                 result[Manifest.permission.BLUETOOTH_SCAN] == true)
            if (granted) updateBluetoothStatus()
            else status.text = "Bluetooth permission is required to control the DS4."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        preview = findViewById(R.id.preview)
        rgbText = findViewById(R.id.rgbText)

        val adapter = btAdapter ?: BluetoothAdapter.getDefaultAdapter()
        transport = AndroidHidHostTransport(this, adapter)

        val connect = findViewById<Button>(R.id.connect)
        val apply = findViewById<Button>(R.id.apply)
        val r = findViewById<SeekBar>(R.id.red)
        val g = findViewById<SeekBar>(R.id.green)
        val b = findViewById<SeekBar>(R.id.blue)
        val brightness = findViewById<SeekBar>(R.id.brightness)

        listOf(r, g, b, brightness).forEach { seekBar ->
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                    updatePreview(r.progress, g.progress, b.progress, brightness.progress)
                }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }

        connect.setOnClickListener {
            if (!hasBluetoothPermission()) { requestBluetoothPermission(); return@setOnClickListener }
            if (btAdapter?.isEnabled != true) {
                status.text = "Bluetooth is turned off. Turn it on and try again."
                return@setOnClickListener
            }
            lifecycleScope.launch {
                status.text = "Connecting to Android HID Host..."
                val result = transport.connect()
                status.text = result.fold(
                    { "DS4 connected through " + transport.name },
                    { "HID Host: " + (it.message ?: it.javaClass.simpleName) }
                )
            }
        }

        apply.setOnClickListener {
            if (!hasBluetoothPermission()) { requestBluetoothPermission(); return@setOnClickListener }
            val c = Ds4Color(r.progress, g.progress, b.progress, brightness.progress).scaled()
            lifecycleScope.launch {
                status.text = "Sending RGB " + c.red + ", " + c.green + ", " + c.blue + "..."
                val result = transport.setLightbar(c)
                status.text = result.fold(
                    { "Sent RGB " + c.red + ", " + c.green + ", " + c.blue },
                    { "Send failed: " + (it.message ?: it.javaClass.simpleName) }
                )
            }
        }

        updatePreview(r.progress, g.progress, b.progress, brightness.progress)
        updateBluetoothStatus()
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
             ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ))
        }
    }

    private fun updateBluetoothStatus() {
        if (!hasBluetoothPermission()) {
            status.text = "Allow Bluetooth permission, then press Connect."
            return
        }
        status.text = when {
            btAdapter == null -> "Bluetooth is not available on this device."
            !btAdapter!!.isEnabled -> "Bluetooth is turned off. Turn it on and try again."
            else -> "Bluetooth is ready. Pair the DualShock 4 in Android Bluetooth settings."
        }
    }

    private fun updatePreview(r: Int, g: Int, b: Int, brightness: Int) {
        val c = Ds4Color(r, g, b, brightness).scaled()
        preview.setBackgroundColor(Color.rgb(c.red, c.green, c.blue))
        rgbText.text = "RGB " + c.red + ", " + c.green + ", " + c.blue
    }

    override fun onDestroy() {
        transport.close()
        super.onDestroy()
    }
}
