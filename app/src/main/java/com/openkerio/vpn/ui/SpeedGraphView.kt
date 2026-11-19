package com.openkerio.vpn.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.openkerio.vpn.R
import java.util.ArrayDeque

/** Lightweight sparkline of recent ↓/↑ samples (bytes/sec). */
class SpeedGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val maxPoints = 60
    private val rx = ArrayDeque<Long>(maxPoints)
    private val tx = ArrayDeque<Long>(maxPoints)
    private val rxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ContextCompat.getColor(context, R.color.ok_accent)
    }
    private val txPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ContextCompat.getColor(context, R.color.ok_muted)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ContextCompat.getColor(context, R.color.ok_stroke)
    }
    private val path = Path()

    fun push(rxBps: Long, txBps: Long) {
        if (rx.size >= maxPoints) rx.removeFirst()
        if (tx.size >= maxPoints) tx.removeFirst()
        rx.addLast(rxBps.coerceAtLeast(0))
        tx.addLast(txBps.coerceAtLeast(0))
        invalidate()
    }

    fun clear() {
        rx.clear()
        tx.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRect(0f, 0f, w, h, gridPaint)
        val peak = (rx.maxOrNull() ?: 0L).coerceAtLeast(tx.maxOrNull() ?: 0L).coerceAtLeast(1L)
        drawSeries(canvas, rx, rxPaint, w, h, peak)
        drawSeries(canvas, tx, txPaint, w, h, peak)
    }

    private fun drawSeries(
        canvas: Canvas,
        data: ArrayDeque<Long>,
        paint: Paint,
        w: Float,
        h: Float,
        peak: Long,
    ) {
        if (data.size < 2) return
        path.reset()
        val step = w / (maxPoints - 1).coerceAtLeast(1)
        var i = 0
        val start = maxPoints - data.size
        for (value in data) {
            val x = (start + i) * step
            val y = h - (value.toFloat() / peak.toFloat()) * (h - 4f) - 2f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            i++
        }
        canvas.drawPath(path, paint)
    }
}
