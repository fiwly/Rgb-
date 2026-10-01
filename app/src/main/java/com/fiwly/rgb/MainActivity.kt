package com.fiwly.rgb

import android.Manifest
import android.bluetooth.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity:ComponentActivity(){
 private lateinit var status:TextView;private lateinit var preview:View;private lateinit var rgbText:TextView
 private lateinit var wheel:ColorWheelView;private lateinit var brightness:SeekBar;private lateinit var slots:LinearLayout
 private lateinit var rSeek:SeekBar;private lateinit var gSeek:SeekBar;private lateinit var bSeek:SeekBar
 private lateinit var rText:TextView;private lateinit var gText:TextView;private lateinit var bText:TextView
 private var syncing=false
 private lateinit var transport:Ds4Transport
 private val prefs by lazy{getSharedPreferences("ds4_rgb_slots",MODE_PRIVATE)}
 private val adapter:BluetoothAdapter? by lazy{(getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter}
 private val ask=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){updateBluetoothStatus()}
 override fun onCreate(b:Bundle?){
  super.onCreate(b);setContentView(R.layout.activity_main)
  status=findViewById(R.id.status);preview=findViewById(R.id.preview);rgbText=findViewById(R.id.rgbText)
  wheel=findViewById(R.id.colorWheel);brightness=findViewById(R.id.brightness);slots=findViewById(R.id.slotsContainer)
  rSeek=findViewById(R.id.rSeek);gSeek=findViewById(R.id.gSeek);bSeek=findViewById(R.id.bSeek);rText=findViewById(R.id.rText);gText=findViewById(R.id.gText);bText=findViewById(R.id.bText)
  transport=AndroidHidHostTransport(this,adapter?:BluetoothAdapter.getDefaultAdapter())
  wheel.onColorChanged={if(!syncing) syncRgbFromWheel();updatePreview()};
  val rgbListener=object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){if(!syncing) syncWheelFromRgb();updatePreview()}
   override fun onStartTrackingTouch(s:SeekBar?){}
   override fun onStopTrackingTouch(s:SeekBar?){ }
  }
  rSeek.setOnSeekBarChangeListener(rgbListener);gSeek.setOnSeekBarChangeListener(rgbListener);bSeek.setOnSeekBarChangeListener(rgbListener)
  brightness.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){updatePreview()}
   override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}
  })
  findViewById<Button>(R.id.connect).setOnClickListener{connectDs4()}
  findViewById<Button>(R.id.apply).setOnClickListener{sendCurrentColor()}
  findViewById<Button>(R.id.setHex).setOnClickListener{setHex(findViewById<EditText>(R.id.hexText))}
  buildSlots();syncRgbFromWheel();updatePreview();updateBluetoothStatus()
 }
 private fun connectDs4(){
  if(!permission())return
  if(adapter?.isEnabled!=true){status.text="Bluetooth is turned off.";return}
  lifecycleScope.launch{status.text="Connecting to Android HID Host...";val r=transport.connect();status.text=r.fold({"DS4 connected through "+transport.name},{"HID Host: "+(it.message?:it.javaClass.simpleName)})}
 }
 private fun sendCurrentColor(){
  if(!permission())return
  val c=currentColor()
  persistAutoColor(c)
  lifecycleScope.launch{status.text="Sending RGB "+c.red+", "+c.green+", "+c.blue+"...";val r=transport.setLightbar(c);status.text=r.fold({"Sent RGB "+c.red+", "+c.green+", "+c.blue},{"Send failed: "+(it.message?:it.javaClass.simpleName)})}
 }
 private fun persistAutoColor(c:Ds4Color){prefs.edit().putInt("auto_r",c.red).putInt("auto_g",c.green).putInt("auto_b",c.blue).putBoolean("auto_enabled",true).apply()}
 private fun currentColor():Ds4Color{val rgb=Color.HSVToColor(wheel.hsv);return Ds4Color(Color.red(rgb),Color.green(rgb),Color.blue(rgb),brightness.progress).scaled()}
 private fun updatePreview(){
  val c=currentColor();preview.setBackgroundColor(Color.rgb(c.red,c.green,c.blue));rgbText.text="RGB "+c.red+", "+c.green+", "+c.blue
  if(::rText.isInitialized){rText.text="R "+c.red;gText.text="G "+c.green;bText.text="B "+c.blue}
  if(::prefs.isInitialized&&!syncing)persistAutoColor(c)
 }
 private fun syncRgbFromWheel(){
  syncing=true
  val rgb=Color.HSVToColor(wheel.hsv);rSeek.progress=Color.red(rgb);gSeek.progress=Color.green(rgb);bSeek.progress=Color.blue(rgb)
  syncing=false
 }
 private fun syncWheelFromRgb(){
  syncing=true
  val rgb=Color.rgb(rSeek.progress,gSeek.progress,bSeek.progress);val h=FloatArray(3);Color.colorToHSV(rgb,h);wheel.hsv=h
  syncing=false
 }
 private fun setHex(e:EditText){
  try{val s=e.text.toString().trim().removePrefix("#");if(s.length!=6)throw IllegalArgumentException();val rgb=Color.parseColor("#"+s);val h=FloatArray(3);Color.colorToHSV(rgb,h);wheel.hsv=h;brightness.progress=100;syncRgbFromWheel();updatePreview();status.text="Color set to #"+s.uppercase()}
  catch(_:Throwable){status.text="Enter a valid HEX color, for example #7C4DFF"}
 }
 private fun buildSlots(){
  slots.removeAllViews()
  for(i in 0 until 8){
   val n=i+1;val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
   val swatch=View(this).apply{layoutParams=LinearLayout.LayoutParams(dp(44),dp(44)).apply{marginEnd=dp(10)}}
   val name=TextView(this).apply{text="Slot "+n;textSize=16f;setTextColor(Color.WHITE);layoutParams=LinearLayout.LayoutParams(0,dp(52),1f)}
   val use=Button(this).apply{text="Use";isAllCaps=false;setOnClickListener{if(load(i))sendCurrentColor()}}
   val save=Button(this).apply{text="Save";isAllCaps=false;setOnClickListener{save(i);refreshSlots();status.text="Saved current color to Slot "+n}}
   row.addView(swatch);row.addView(name);row.addView(use,LinearLayout.LayoutParams(dp(70),dp(52)));row.addView(save,LinearLayout.LayoutParams(dp(70),dp(52)));slots.addView(row)
  };refreshSlots()
 }
 private fun save(i:Int){
  val rgb=Color.HSVToColor(wheel.hsv);val c=Ds4Color(Color.red(rgb),Color.green(rgb),Color.blue(rgb),brightness.progress)
  prefs.edit().putInt(k(i,"r"),c.red).putInt(k(i,"g"),c.green).putInt(k(i,"b"),c.blue).putInt(k(i,"br"),c.brightness).putBoolean(k(i,"ok"),true).apply()
 }
 private fun load(i:Int):Boolean{
  if(!prefs.getBoolean(k(i,"ok"),false)){status.text="Slot "+(i+1)+" is empty.";return false}
  val rgb=Color.rgb(prefs.getInt(k(i,"r"),0),prefs.getInt(k(i,"g"),0),prefs.getInt(k(i,"b"),0));val h=FloatArray(3);Color.colorToHSV(rgb,h);wheel.hsv=h;brightness.progress=prefs.getInt(k(i,"br"),100);updatePreview();status.text="Loaded Slot "+(i+1);return true
 }
 private fun refreshSlots(){
  for(i in 0 until slots.childCount){val v=(slots.getChildAt(i) as LinearLayout).getChildAt(0);if(!prefs.getBoolean(k(i,"ok"),false))v.setBackgroundColor(Color.DKGRAY)else{val rgb=Color.rgb(prefs.getInt(k(i,"r"),0),prefs.getInt(k(i,"g"),0),prefs.getInt(k(i,"b"),0));val br=prefs.getInt(k(i,"br"),100);v.setBackgroundColor(Color.rgb(Color.red(rgb)*br/100,Color.green(rgb)*br/100,Color.blue(rgb)*br/100))}}
 }
 private fun k(i:Int,s:String)="slot_"+i+"_"+s
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 private fun permission():Boolean{
  if(Build.VERSION.SDK_INT<Build.VERSION_CODES.S)return true
  val c=ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
  val s=ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
  if(c&&s)return true
  ask.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN));return false
 }
 private fun updateBluetoothStatus(){
  if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S&&!permissionGranted()){status.text="Allow Nearby devices permission.";return}
  status.text=when{adapter==null->"Bluetooth is not available.";adapter?.isEnabled!=true->"Bluetooth is turned off.";else->"Bluetooth ready. Connect the DS4 in Android Bluetooth settings."}
 }
 private fun permissionGranted()=ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED&&ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
 override fun onResume(){super.onResume();if(::status.isInitialized){updateBluetoothStatus()}}
 override fun onDestroy(){transport.close();super.onDestroy()}
}