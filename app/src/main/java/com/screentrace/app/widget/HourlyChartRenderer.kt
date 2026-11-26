package com.screentrace.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * 批 12b（§5.12）：通过 Android Canvas 离屏绘制高清透明底 Bitmap，
 * 供 Glance Image(ImageProvider(bitmap)) 渲染 24 小时胶囊柱图与日/夜双色比例条。
 */
object HourlyChartRenderer {

    fun renderHourlyChartBitmap(
        model: WidgetChartModel,
        colors: WidgetColors,
        widthPx: Int,
        heightPx: Int,
        density: Float
    ): Bitmap {
        val w = widthPx.coerceAtLeast(160)
        val h = heightPx.coerceAtLeast(72)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val d = density.coerceAtLeast(1f)
        val padLeft = 2f * d
        val padTop = 8f * d
        val padRight = 24f * d
        val padBottom = 15f * d
        val chartW = (w - padLeft - padRight).coerceAtLeast(48f)
        val chartH = (h - padTop - padBottom).coerceAtLeast(24f)

        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.guideLine
            style = Paint.Style.STROKE
            strokeWidth = 1f * d
            pathEffect = DashPathEffect(floatArrayOf(3.5f * d, 3.5f * d), 0f)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.onSurfaceVariant
            textSize = 9.5f * d
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.barTrack
            style = Paint.Style.FILL
        }
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.primary
            style = Paint.Style.FILL
        }

        // 两条水平虚线参考线：顶部 ceilingMinutes（默认 60m）与中线 midMinutes（默认 30m）
        val topY = padTop
        val midY = padTop + chartH * 0.5f
        val bottomY = padTop + chartH
        canvas.drawLine(padLeft, topY, padLeft + chartW, topY, guidePaint)
        canvas.drawLine(padLeft, midY, padLeft + chartW, midY, guidePaint)

        labelPaint.textAlign = Paint.Align.LEFT
        val rightLabelX = padLeft + chartW + 3f * d
        canvas.drawText("${model.ceilingMinutes}m", rightLabelX, topY + 3.5f * d, labelPaint)
        canvas.drawText("${model.midMinutes}m", rightLabelX, midY + 3.5f * d, labelPaint)

        // 24 根细圆角胶囊柱
        val slotW = chartW / 24f
        val barW = (slotW * 0.54f).coerceAtLeast(2f * d)
        val radius = barW / 2f
        val minBarH = 2f * d
        val rect = RectF()

        for (i in 0 until 24) {
            val left = padLeft + i * slotW + (slotW - barW) / 2f
            val right = left + barW
            rect.set(left, topY, right, bottomY)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)

            val ratio = model.bars.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            if (ratio > 0f) {
                val barH = (chartH * ratio).coerceAtLeast(minBarH)
                rect.set(left, (bottomY - barH).coerceAtLeast(topY), right, bottomY)
                canvas.drawRoundRect(rect, radius, radius, barPaint)
            }
        }

        // 横轴刻度：0 / 6 / 12 / 18
        labelPaint.textAlign = Paint.Align.CENTER
        val axisY = h - 3f * d
        listOf(0, 6, 12, 18).forEach { hour ->
            val cx = padLeft + hour * slotW + slotW / 2f
            canvas.drawText(hour.toString(), cx, axisY, labelPaint)
        }

        return bitmap
    }

    fun renderDayNightBarBitmap(
        dayPercent: Int?,
        colors: WidgetColors,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        val w = widthPx.coerceAtLeast(80)
        val h = heightPx.coerceAtLeast(10)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = h / 2f
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())

        if (dayPercent == null) {
            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.progressTrack
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            return bitmap
        }

        val pct = dayPercent.coerceIn(0, 100)
        val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.dayBar
            style = Paint.Style.FILL
        }
        val nightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.nightBar
            style = Paint.Style.FILL
        }

        when (pct) {
            0 -> canvas.drawRoundRect(rect, radius, radius, nightPaint)
            100 -> canvas.drawRoundRect(rect, radius, radius, dayPaint)
            else -> {
                val gap = (w * 0.02f).coerceIn(2f, 4f)
                val splitX = (w * (pct / 100f)).coerceIn(h.toFloat() * 0.6f, w - h.toFloat() * 0.6f)
                val leftRect = RectF(0f, 0f, (splitX - gap / 2f).coerceAtLeast(radius), h.toFloat())
                val rightRect = RectF((splitX + gap / 2f).coerceAtMost(w - radius), 0f, w.toFloat(), h.toFloat())
                canvas.drawRoundRect(leftRect, radius, radius, dayPaint)
                canvas.drawRoundRect(rightRect, radius, radius, nightPaint)
            }
        }
        return bitmap
    }
}
