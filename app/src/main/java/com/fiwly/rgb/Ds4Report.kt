package com.fiwly.rgb
import java.util.zip.CRC32
object Ds4Report{
 fun bluetoothLightbar(red:Int,green:Int,blue:Int):ByteArray{
  val p=ByteArray(78);p[0]=0x11;p[1]=0xC0.toByte();p[2]=0;p[3]=0x02;p[6]=red.coerceIn(0,255).toByte();p[7]=green.coerceIn(0,255).toByte();p[8]=blue.coerceIn(0,255).toByte()
  val crc=CRC32();crc.update(byteArrayOf(0xA2.toByte()));crc.update(p,0,74);val v=crc.value
  p[74]=(v and 255).toByte();p[75]=((v ushr 8) and 255).toByte();p[76]=((v ushr 16) and 255).toByte();p[77]=((v ushr 24) and 255).toByte();return p
 }
 fun usbLightbar(red:Int,green:Int,blue:Int):ByteArray{
  val p=ByteArray(32);p[0]=0x05;p[1]=0x02;p[6]=red.coerceIn(0,255).toByte();p[7]=green.coerceIn(0,255).toByte();p[8]=blue.coerceIn(0,255).toByte();return p
 }
}