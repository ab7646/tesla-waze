package com.subzero.teslawaze

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View

/**
 * A small live sparkline for the telemetry card: a neon mint line with a fill that fades into the
 * dark background. push() a new value each second; it keeps the last N and redraws.
 */
class SparklineView(context: Context) : View(context) {

    private val values = FloatArray(44)
    var maxValue = 35f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF00F5D4.toInt()
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(9f, 0f, 0f, 0x8000F5D4.toInt()) // colored glow, not a black shadow
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        // setShadowLayer needs software rendering.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun push(value: Float) {
        System.arraycopy(values, 1, values, 0, values.size - 1)
        values[values.size - 1] = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val n = values.size
        val dx = w / (n - 1)
        val pad = 6f
        fun y(v: Float) = h - pad - (v.coerceIn(0f, maxValue) / maxValue) * (h - 2 * pad)

        val linePath = Path()
        val areaPath = Path()
        for (i in 0 until n) {
            val x = i * dx
            val py = y(values[i])
            if (i == 0) { linePath.moveTo(x, py); areaPath.moveTo(x, h) }
            linePath.lineTo(x, py)
            areaPath.lineTo(x, py)
        }
        areaPath.lineTo(w, h)
        areaPath.close()

        fill.shader = LinearGradient(0f, 0f, 0f, h, 0x4700F5D4, 0x0000F5D4, Shader.TileMode.CLAMP)
        canvas.drawPath(areaPath, fill)
        canvas.drawPath(linePath, line)
    }
}
