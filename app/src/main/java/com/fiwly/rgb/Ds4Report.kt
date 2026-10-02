package com.fiwly.rgb

import java.util.zip.CRC32

object Ds4Report {
    fun bluetoothLightbar(red: Int, green: Int, blue: Int): ByteArray {
        // Official DS4 Bluetooth output report (78 bytes).
        // RGB are at bytes 6..8. Byte 3 enables LED/motor output.
        val p = ByteArray(78)
        p[0] = 0x11
        p[1] = 0xC0.toByte()
        p[2] = 0x00
        p[3] = 0x07
        p[4] = 0x00
        p[5] = 0x00
        p[6] = red.coerceIn(0, 255).toByte()
        p[7] = green.coerceIn(0, 255).toByte()
        p[8] = blue.coerceIn(0, 255).toByte()
        p[9] = 0x00
        p[10] = 0x00

        // CRC32 is CRC-32/BZIP2 with seed byte 0xA2 over report bytes 0..73.
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
