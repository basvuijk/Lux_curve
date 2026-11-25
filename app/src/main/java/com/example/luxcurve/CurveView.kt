package com.example.luxcurve

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Editable brightness curve. X axis: ambient light (log scale, 1 - 10 000 lux).
 * Y axis: screen brightness (0 - 100 %). Drag a dot up or down to change the curve.
 * Colours come from the current Material theme, so it follows dark mode and dynamic color.
 */
class CurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Brightness percentage of every control point. */
    var points: FloatArray = Curve.DEFAULT.copyOf()
        set(value) {
            field = value.copyOf()
            invalidate()
        }

    /** Current light sensor reading in lux, or a negative number for "unknown". Draws a live marker. */
    var liveLux: Float = -1f
        set(value) {
            field = value
            invalidate()
        }

    /** Called when the user lifts their finger after editing. */
    var onCurveChanged: ((FloatArray) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val plot = RectF()
    private val labelRect = RectF()
    private val linePath = Path()
    private val fillPath = Path()
    private var active = -1

    private fun themed(attr: Int, fallback: Int): Int = MaterialColors.getColor(context, attr, fallback)

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private val cPrimary = themed(com.google.android.material.R.attr.colorPrimary, 0xFFFF6D00.toInt())
    private val cOutline = themed(com.google.android.material.R.attr.colorOutlineVariant, 0xFF888888.toInt())
    private val cText = themed(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888.toInt())
    private val cLive = themed(com.google.android.material.R.attr.colorTertiary, 0xFF00668B.toInt())
    private val cOnLive = themed(com.google.android.material.R.attr.colorOnTertiary, 0xFFFFFFFF.toInt())
    private val cCard = themed(com.google.android.material.R.attr.colorSurfaceContainer, 0xFFFFFFFF.toInt())

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(cOutline, 150)
        strokeWidth = 1f * dp
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cText
        textSize = 11f * dp
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cPrimary
        strokeWidth = 3.5f * dp
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cPrimary }
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cCard }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(cPrimary, 60) }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cLive
        strokeWidth = 1.5f * dp
    }
    private val liveTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cOnLive
        textSize = 11f * dp
        isFakeBoldText = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        plot.set(40f * dp, 34f * dp, w - 16f * dp, h - 28f * dp)
        fillPaint.shader = LinearGradient(
            0f, plot.top, 0f, plot.bottom,
            withAlpha(cPrimary, 90), withAlpha(cPrimary, 0),
            Shader.TileMode.CLAMP
        )
    }

    private fun xForLog(log: Float): Float =
        plot.left + (log - Curve.LOG_MIN) / (Curve.LOG_MAX - Curve.LOG_MIN) * plot.width()

    private fun xForPoint(i: Int): Float =
        plot.left + i.toFloat() / (Curve.POINT_COUNT - 1) * plot.width()

    private fun yForPercent(p: Float): Float = plot.bottom - p / 100f * plot.height()

    override fun onDraw(canvas: Canvas) {
        // Horizontal grid and percent labels.
        for (p in 0..100 step 25) {
            val y = yForPercent(p.toFloat())
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            if (p % 50 == 0) {
                val label = "$p%"
                canvas.drawText(label, plot.left - 8f * dp - textPaint.measureText(label), y + 4f * dp, textPaint)
            }
        }

        // Vertical grid: one line per decade of lux.
        val xLabels = arrayOf("1", "10", "100", "1k", "10k")
        for (d in 0..4) {
            val x = xForLog(d.toFloat())
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            val w = textPaint.measureText(xLabels[d])
            canvas.drawText(xLabels[d], x - w / 2f, plot.bottom + 18f * dp, textPaint)
        }

        // Curve and the soft gradient below it.
        linePath.reset()
        fillPath.reset()
        for (i in 0 until Curve.POINT_COUNT) {
            val x = xForPoint(i)
            val y = yForPercent(points[i])
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, plot.bottom)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(plot.right, plot.bottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, curvePaint)

        // Live marker for the current light reading.
        if (liveLux >= 0f) {
            val log = Curve.logLux(liveLux)
            val x = xForLog(log)
            val y = yForPercent(Curve.percentForLog(log, points))
            val label = "${liveLux.roundToInt()} lx"
            val textWidth = liveTextPaint.measureText(label)
            val half = textWidth / 2f + 8f * dp
            val cx = x.coerceIn(half, width - half)
            labelRect.set(cx - half, 4f * dp, cx + half, 24f * dp)
            canvas.drawLine(x, labelRect.bottom, x, plot.bottom, livePaint)
            canvas.drawRoundRect(labelRect, 10f * dp, 10f * dp, livePaint)
            canvas.drawText(label, cx - textWidth / 2f, 18f * dp, liveTextPaint)
            canvas.drawCircle(x, y, 6f * dp, gapPaint)
            canvas.drawCircle(x, y, 4f * dp, livePaint)
        }

        // Control points: coloured dot with a gap ring, plus a halo while dragging.
        for (i in 0 until Curve.POINT_COUNT) {
            val x = xForPoint(i)
            val y = yForPercent(points[i])
            if (i == active) canvas.drawCircle(x, y, 22f * dp, haloPaint)
            canvas.drawCircle(x, y, 10f * dp, gapPaint)
            canvas.drawCircle(x, y, 7f * dp, dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                active = nearestPoint(event.x)
                moveActiveTo(event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (active >= 0) moveActiveTo(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active >= 0) onCurveChanged?.invoke(points.copyOf())
                active = -1
                invalidate()
            }
        }
        return true
    }

    private fun nearestPoint(x: Float): Int {
        var best = 0
        for (i in 1 until Curve.POINT_COUNT) {
            if (abs(xForPoint(i) - x) < abs(xForPoint(best) - x)) best = i
        }
        return best
    }

    private fun moveActiveTo(y: Float) {
        val percent = ((plot.bottom - y) / plot.height() * 100f)
            .coerceIn(0f, 100f)
            .roundToInt()
            .toFloat()
        if (percent != points[active]) {
            points[active] = percent
            if (percent % 5f == 0f) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }
    }
}
