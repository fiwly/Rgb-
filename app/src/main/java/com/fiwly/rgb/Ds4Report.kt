package com.fiwly.rgb

import java.util.zip.CRC32

object Ds4Report {
    fun bluetoothLightbar(red: Int, green: Int, blue: Int): ByteArray {
        // DualShock 4 Bluetooth output report:
        // 0x11 report ID, 0xC0 = HID + CRC32, and LED-valid flag 0x02.
        // R/G/B are bytes 8/9/10 in the 78-byte report.
        val p = ByteArray(78)
        p[0] = 0x11
        p[1] = 0xC0.toByte()
        p[2] = 0x00
        p[3] = 0x02
        p[4] = 0x00
        p[5] = 0x00
        p[6] = 0x00
        p[7] = 0x00
        p[8] = red.coerceIn(0, 255).toByte()
        p[9] = green.coerceIn(0, 255).toByte()
        p[10] = blue.coerceIn(0, 255).toByte()

        val crc = CRC32()
        crc.update(byteArrayOf(0xA2.toByte()))
        crc.update(p, 0, 74)
        val value = crc.value

        p[74] = (value and 0xFF).toByte()
        p[75] = ((value ushr 8) and 0xFF).toByte()
        p[76] = ((value ushr 16) and 0xFF).toByte()
        p[77] = ((value ushr 24) and 0xFF).toByte()
        return p
    }

    fun usbLightbar(red: Int, green: Int, blue: Int): ByteArray {
        val p = ByteArray(32)
        p[0] = 0x05
        p[1] = 0x02
        p[2] = 0x00
        p[3] = 0x00
        p[4] = 0x00
        p[5] = 0x00
        p[6] = red.coerceIn(0, 255).toByte()
        p[7] = green.coerceIn(0, 255).toByte()
        p[8] = blue.coerceIn(0, 255).toByte()
        return p
    }
}
