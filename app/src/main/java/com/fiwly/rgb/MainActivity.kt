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
    private lateinit var redBar: SeekBar
    private lateinit var greenBar: SeekBar
    private lateinit var blueBar: SeekBar
    private lateinit var brightnessBar: SeekBar
    private lateinit var slotsContainer: LinearLayout

    private val preferences by lazy {
        getSharedPreferences("ds4_rgb_slots", MODE_PRIVATE)
    }

    private val slotCount = 8

    private val btAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val connectGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                result[Manifest.permission.BLUETOOTH_CONNECT] == true ||
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
            val scanGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                result[Manifest.permission.BLUETOOTH_SCAN] == true ||
                hasPermission(Manifest.permission.BLUETOOTH_SCAN)
            if (connectGranted && scanGranted) updateBluetoothStatus()
            else status.text = "Bluetooth permission denied. Open App info > Permissions and allow Nearby devices."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        preview = findViewById(R.id.preview)
        rgbText = findViewById(R.id.rgbText)
        slotsContainer = findViewById(R.id.slotsContainer)

        val adapter = btAdapter ?: BluetoothAdapter.getDefaultAdapter()
        transport = AndroidHidHostTransport(this, adapter)

        val connect = findViewById<Button>(R.id.connect)
        val apply = findViewById<Button>(R.id.apply)
        redBar = findViewById(R.id.red)
        greenBar = findViewById(R.id.green)
        blueBar = findViewById(R.id.blue)
        brightnessBar = findViewById(R.id.brightness)

        listOf(redBar, greenBar, blueBar, brightnessBar).forEach { seekBar ->
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                    updatePreview(
                        redBar.progress,
                        greenBar.progress,
                        blueBar.progress,
                        brightnessBar.progress
                    )
                }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }

        connect.setOnClickListener {
            if (!ensureBluetoothPermission()) return@setOnClickListener
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
            sendCurrentColor()
        }

        buildSlots()
        updatePreview(
            redBar.progress,
            greenBar.progress,
            blueBar.progress,
            brightnessBar.progress
        )
        updateBluetoothStatus()
    }

    private fun sendCurrentColor() {
        if (!ensureBluetoothPermission()) return

        val c = currentColor()
        lifecycleScope.launch {
            status.text = "Sending RGB " + c.red + ", " + c.green + ", " + c.blue + "..."
            val result = transport.setLightbar(c)
            status.text = result.fold(
                { "Sent RGB " + c.red + ", " + c.green + ", " + c.blue },
                { "Send failed: " + (it.message ?: it.javaClass.simpleName) }
            )
        }
    }

    private fun currentColor(): Ds4Color =
        Ds4Color(
            redBar.progress,
            greenBar.progress,
            blueBar.progress,
            brightnessBar.progress
        ).scaled()

    private fun buildSlots() {
        slotsContainer.removeAllViews()

        for (index in 0 until slotCount) {
            val slotNumber = index + 1
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val colorView = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    marginEnd = dp(12)
                }
            }

            val name = TextView(this).apply {
                text = "Slot " + slotNumber
                textSize = 16f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
            }

            val use = Button(this).apply {
                text = "Use"
                isAllCaps = false
                setOnClickListener {
                    if (loadSlot(index)) sendCurrentColor()
                }
            }

            val save = Button(this).apply {
                text = "Save"
                isAllCaps = false
                setOnClickListener {
                    saveSlot(index)
                    refreshSlotPreviews()
                    status.text = "Saved current color to Slot " + slotNumber
                }
            }

            row.addView(colorView)
            row.addView(name)
            row.addView(use, LinearLayout.LayoutParams(dp(72), dp(48)).apply {
                marginStart = dp(4)
            })
            row.addView(save, LinearLayout.LayoutParams(dp(72), dp(48)).apply {
                marginStart = dp(4)
            })
            slotsContainer.addView(row)
        }

        refreshSlotPreviews()
    }

    private fun saveSlot(index: Int) {
        val c = Ds4Color(
            redBar.progress,
            greenBar.progress,
            blueBar.progress,
            brightnessBar.progress
        )

        preferences.edit()
            .putInt(key(index, "r"), c.red)
            .putInt(key(index, "g"), c.green)
            .putInt(key(index, "b"), c.blue)
            .putInt(key(index, "brightness"), c.brightness)
            .putBoolean(key(index, "saved"), true)
            .apply()
    }

    private fun loadSlot(index: Int): Boolean {
        if (!preferences.getBoolean(key(index, "saved"), false)) {
            status.text = "Slot " + (index + 1) + " is empty. Save a color to it first."
            return false
        }

        redBar.progress = preferences.getInt(key(index, "r"), 0)
        greenBar.progress = preferences.getInt(key(index, "g"), 0)
        blueBar.progress = preferences.getInt(key(index, "b"), 0)
        brightnessBar.progress = preferences.getInt(key(index, "brightness"), 100)

        updatePreview(
            redBar.progress,
            greenBar.progress,
            blueBar.progress,
            brightnessBar.progress
        )
        status.text = "Loaded Slot " + (index + 1)
        return true
    }

    private fun refreshSlotPreviews() {
        for (index in 0 until slotsContainer.childCount) {
            val row = slotsContainer.getChildAt(index) as LinearLayout
            val colorView = row.getChildAt(0)
            val saved = preferences.getBoolean(key(index, "saved"), false)

            if (!saved) {
                colorView.setBackgroundColor(Color.rgb(55, 55, 62))
                continue
            }

            val c = Ds4Color(
                preferences.getInt(key(index, "r"), 0),
                preferences.getInt(key(index, "g"), 0),
                preferences.getInt(key(index, "b"), 0),
                preferences.getInt(key(index, "brightness"), 100)
            ).scaled()

            colorView.setBackgroundColor(Color.rgb(c.red, c.green, c.blue))
        }
    }

    private fun key(index: Int, suffix: String): String =
        "slot_" + index + "_" + suffix

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun ensureBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val connect = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        val scan = hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        if (connect && scan) return true
        status.text = "Requesting Nearby devices permission..."
        bluetoothPermissionLauncher.launch(arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        ))
        return false
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun updateBluetoothStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connect = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
            val scan = hasPermission(Manifest.permission.BLUETOOTH_SCAN)
            if (!connect || !scan) {
                status.text = "Nearby devices permission is required. Press Connect or Apply."
                return
            }
        }
        val adapter = btAdapter
        status.text = when {
            adapter == null -> "Bluetooth is not available on this device."
            !adapter.isEnabled -> "Bluetooth is turned off. Turn it on and try again."
            else -> "Bluetooth ready. Make sure DualShock 4 is connected in Android Bluetooth settings."
        }
    }

    private fun updatePreview(r: Int, g: Int, b: Int, brightness: Int) {
        val c = Ds4Color(r, g, b, brightness).scaled()
        preview.setBackgroundColor(Color.rgb(c.red, c.green, c.blue))
        rgbText.text = "RGB " + c.red + ", " + c.green + ", " + c.blue
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) updateBluetoothStatus()
    }

    override fun onDestroy() {
        transport.close()
        super.onDestroy()
    }
}