package com.fiwly.rgb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.LinearGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class ColorWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    var hsv: FloatArray = floatArrayOf(220f, 0.81f, 1f)
        set(value) {
            field = value.copyOf()
            invalidate()
        }
    var onColorChanged: ((Int) -> Unit)? = null

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
    private var radius = 0f
    private var cx = 0f
    private var cy = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        cx = width / 2f
        cy = height / 2f
        radius = min(width, height) * 0.42f

        val colors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
            Color.BLUE, Color.MAGENTA, Color.RED
        )
        wheelPaint.shader = SweepGradient(cx, cy, colors, null)
        canvas.drawCircle(cx, cy, radius, wheelPaint)

        val value = hsv[2].coerceIn(0f, 1f)
        valuePaint.shader = android.graphics.RadialGradient(
            cx, cy, radius,
            Color.WHITE, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, valuePaint)
        valuePaint.shader = android.graphics.RadialGradient(
            cx, cy, radius,
            Color.TRANSPARENT, Color.BLACK,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, valuePaint)

        val angle = Math.toRadians((hsv[0] - 90f).toDouble())
        val mx = cx + cos(angle).toFloat() * radius * hsv[1]
        val my = cy + sin(angle).toFloat() * radius * hsv[1]
        markerPaint.color = Color.WHITE
        canvas.drawCircle(mx, my, 12f, markerPaint)
        markerPaint.color = Color.BLACK
        markerPaint.strokeWidth = 2f
        canvas.drawCircle(mx, my, 12f, markerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE) return true
        val dx = event.x - cx
        val dy = event.y - cy
        val distance = sqrt(dx * dx + dy * dy)
        val saturation = (distance / radius).coerceIn(0f, 1f)
        var hue = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        if (hue < 0) hue += 360f
        if (hue >= 360f) hue -= 360f
        hsv = floatArrayOf(hue, saturation, hsv[2])
        onColorChanged?.invoke(Color.HSVToColor(hsv))
        return true
    }
}
