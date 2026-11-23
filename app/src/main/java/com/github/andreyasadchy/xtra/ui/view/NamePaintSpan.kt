package com.github.andreyasadchy.xtra.ui.view

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.text.style.ReplacementSpan
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import kotlin.math.max
import kotlin.math.min

class NamePaintSpan(
    private val name: String,
    private val type: String,
    private val colors: IntArray,
    private val colorPositions: FloatArray,
    private val angle: Int?,
    private val repeat: Boolean?,
    private val shadows: List<NamePaint.Shadow>?,
) : ReplacementSpan() {

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            val paintFm = paint.fontMetrics
            fm.ascent = paintFm.ascent.toInt()
            fm.bottom = paintFm.bottom.toInt()
            fm.descent = paintFm.descent.toInt()
            fm.leading = paintFm.leading.toInt()
            fm.top = paintFm.top.toInt()
        }
        return paint.measureText(name).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val yOffset = y.toFloat()
        val prevShader = paint.shader
        val prevColor = paint.color
        paint.shader = null
        shadows?.forEach {
            val safeRadius = it.radius.coerceIn(0.5f, 24f)
            paint.setShadowLayer(safeRadius, it.xOffset, it.yOffset, it.color)
            canvas.drawText(name, x, yOffset, paint)
        }
        paint.clearShadowLayer()
        when (type) {
            "LINEAR_GRADIENT" -> {
                val width = max(1f, paint.measureText(name))
                val height = max(1f, (bottom - top).toFloat())
                val topFloat = top.toFloat()
                val points = floatArrayOf(x, topFloat, x + width, topFloat)
                val matrix = Matrix()
                matrix.setRotate(((angle ?: 0) - 90).toFloat(), x + (width / 2), top + (height / 2))
                matrix.mapPoints(points)
                paint.shader = LinearGradient(
                    points[0],
                    min(points[1], bottom.toFloat()),
                    points[2],
                    max(points[3], topFloat),
                    colors,
                    colorPositions,
                    if (repeat == true) {
                        Shader.TileMode.REPEAT
                    } else {
                        Shader.TileMode.CLAMP
                    }
                )
            }
            "RADIAL_GRADIENT" -> {
                val halfWidth = max(1f, paint.measureText(name) / 2f)
                val height = max(1f, (bottom - top).toFloat())
                paint.shader = RadialGradient(
                    x + halfWidth,
                    top + (height / 2),
                    halfWidth,
                    colors,
                    colorPositions,
                    if (repeat == true) {
                        Shader.TileMode.REPEAT
                    } else {
                        Shader.TileMode.CLAMP
                    }
                )
            }
        }
        canvas.drawText(name, x, yOffset, paint)
        paint.shader = prevShader
        paint.color = prevColor
    }
}