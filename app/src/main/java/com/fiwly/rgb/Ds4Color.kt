package com.fiwly.rgb
data class Ds4Color(val red:Int,val green:Int,val blue:Int,val brightness:Int=100){
 fun scaled():Ds4Color{val b=brightness.coerceIn(0,100);return Ds4Color(red*b/100,green*b/100,blue*b/100,b)}
}