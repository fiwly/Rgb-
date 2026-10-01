package com.fiwly.rgb
interface Ds4Transport{val name:String;suspend fun connect():Result<Unit>;suspend fun setLightbar(color:Ds4Color):Result<Unit>;fun close()}
class AndroidPublicTransport:Ds4Transport{
 override val name="Android public HID"
 override suspend fun connect()=Result.failure<Unit>(UnsupportedOperationException("Raw DS4 HID output is not exposed by the public Android SDK"))
 override suspend fun setLightbar(color:Ds4Color)=Result.failure<Unit>(UnsupportedOperationException("DS4 Light Bar output report requires HID host/raw HID access"))
 override fun close()=Unit
}