package com.fiwly.rgb

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class ColorWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var hsv = floatArrayOf(220f, 0.81f, 1f)
        set(value) { field = value.copyOf(); invalidate() }

    var onColorChanged: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private var cx = 0f
    private var cy = 0f
    private var outerRadius = 0f
    private var innerRadius = 0f
    private var selectingWheel = true

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        cx = width / 2f
        cy = height / 2f

        outerRadius = min(width, height) * 0.46f
        innerRadius = outerRadius * 0.72f

        // Accurate hue ring.
        val hueColors = IntArray(361) { i ->
            Color.HSVToColor(floatArrayOf(i.toFloat(), 1f, 1f))
        }
        paint.shader = SweepGradient(cx, cy, hueColors, null)
        canvas.drawCircle(cx, cy, (outerRadius + innerRadius) / 2f, paint)

        // Cut the middle to create a clean hue ring.
        paint.shader = null
        paint.color = Color.rgb(16, 16, 20)
        canvas.drawCircle(cx, cy, innerRadius - 3f, paint)

        // HSV saturation/value square rendered as a circular selection area.
        val svRadius = innerRadius * 0.94f
        val base = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        paint.shader = LinearGradient(
            cx - svRadius, cy, cx + svRadius, cy,
            Color.WHITE, base, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, svRadius, paint)

        paint.shader = LinearGradient(
            0f, cy - svRadius, 0f, cy + svRadius,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, svRadius, paint)
        paint.shader = null

        // Hue marker.
        val hueAngle = Math.toRadians((hsv[0] - 90f).toDouble())
        val hx = cx + cos(hueAngle).toFloat() * ((outerRadius + innerRadius) / 2f)
        val hy = cy + sin(hueAngle).toFloat() * ((outerRadius + innerRadius) / 2f)
        marker.strokeWidth = 5f
        marker.color = Color.WHITE
        canvas.drawCircle(hx, hy, 11f, marker)
        marker.strokeWidth = 2f
        marker.color = Color.BLACK
        canvas.drawCircle(hx, hy, 11f, marker)

        // SV marker.
        val sx = cx + (hsv[1] - 0.5f) * 2f * svRadius
        val sy = cy + (1f - hsv[2]) * 2f * svRadius
        marker.strokeWidth = 5f
        marker.color = Color.WHITE
        canvas.drawCircle(sx, sy, 11f, marker)
        marker.strokeWidth = 2f
        marker.color = Color.BLACK
        canvas.drawCircle(sx, sy, 11f, marker)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN &&
            event.action != MotionEvent.ACTION_MOVE) return true

        val dx = event.x - cx
        val dy = event.y - cy
        val distance = hypot(dx, dy)

        if (distance >= innerRadius * 0.84f && distance <= outerRadius + 20f) {
            selectingWheel = true
            var hue = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
            hue = ((hue % 360f) + 360f) % 360f
            hsv = floatArrayOf(hue, hsv[1], hsv[2])
        } else if (distance < innerRadius) {
            selectingWheel = false
            val svRadius = innerRadius * 0.94f
            val sx = (dx / svRadius * 0.5f + 0.5f).coerceIn(0f, 1f)
            val sy = (1f - dy / svRadius * 0.5f).coerceIn(0f, 1f)
            hsv = floatArrayOf(hsv[0], sx, sy)
        }

        onColorChanged?.invoke(Color.HSVToColor(hsv))
        invalidate()
        return true
    }
}
