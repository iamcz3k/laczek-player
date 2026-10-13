package com.v3x.laczekplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Lightweight touch-friendly zigzag playback slider. */
class SnakeSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var max: Int = 1000
        set(value) { field = kotlin.math.max(1, value); invalidate() }
    var progress: Int = 0
        set(value) { field = value.coerceIn(0, max); invalidate() }
    var trackColor: Int = Color.rgb(65, 67, 76)
    var progressColor: Int = Color.rgb(255, 138, 61)
    var onProgressChanged: ((Int, Boolean) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(7f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val measure = PathMeasure()
    private var dragging = false
    private val inset get() = dp(8f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp(38f).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + inset
        val right = width - paddingRight - inset
        val top = paddingTop + dp(5f)
        val bottom = height - paddingBottom - dp(5f)
        if (right <= left || bottom <= top) return

        path.reset()
        val segments = 12
        val step = (right - left) / segments
        val center = (top + bottom) / 2f
        val amplitude = min(dp(7f), (bottom - top) * .32f)
        path.moveTo(left, center)
        for (i in 1..segments) {
            val x = left + step * i
            val y = if (i % 2 == 0) center - amplitude else center + amplitude
            path.lineTo(x, y)
        }

        val fraction = progress.toFloat() / max.toFloat()
        trackPaint.color = trackColor
        canvas.drawPath(path, trackPaint)

        measure.setPath(path, false)
        val length = measure.length
        val end = (length * fraction).coerceIn(0f, length)
        val filled = Path()
        measure.getSegment(0f, end, filled, true)
        glowPaint.color = progressColor
        glowPaint.alpha = 70
        canvas.drawPath(filled, glowPaint)
        trackPaint.color = progressColor
        trackPaint.strokeWidth = dp(3.5f)
        canvas.drawPath(filled, trackPaint)

        val point = FloatArray(2)
        if (measure.getPosTan(end, point, null)) {
            headPaint.color = progressColor
            headPaint.setShadowLayer(dp(5f), 0f, 0f, progressColor)
            canvas.drawCircle(point[0], point[1], dp(if (dragging) 6f else 4.5f), headPaint)
            headPaint.clearShadowLayer()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                updateFromX(event.x, true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                updateFromX(event.x, true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                updateFromX(event.x, true)
                dragging = false
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromX(x: Float, fromUser: Boolean) {
        val available = (width - paddingLeft - paddingRight - inset * 2).coerceAtLeast(1f)
        val start = paddingLeft + inset
        progress = (((x - start) / available).coerceIn(0f, 1f) * max).toInt()
        onProgressChanged?.invoke(progress, fromUser)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
