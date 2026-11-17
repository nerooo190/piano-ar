package com.pianoar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Bar chart: practice minutes per day. */
class DayBarsView(context: Context, private val days: List<Pair<LocalDate, Double>>) : View(context) {

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PianoStageView.RIGHT_COLOR }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 170, 190); textAlign = Paint.Align.CENTER; textSize = 26f }
    private val grid = Paint().apply { color = Color.rgb(50, 50, 64); strokeWidth = 2f }

    override fun onDraw(c: Canvas) {
        if (days.isEmpty()) return
        val maxMin = days.maxOf { it.second }.coerceAtLeast(5.0)
        val top = 40f
        val bottom = height - 40f
        val slot = width.toFloat() / days.size
        c.drawLine(0f, bottom, width.toFloat(), bottom, grid)
        days.forEachIndexed { i, (date, min) ->
            val x = slot * i + slot / 2
            val h = ((min / maxMin) * (bottom - top)).toFloat()
            bar.alpha = if (date == LocalDate.now()) 255 else 170
            c.drawRoundRect(x - slot * 0.32f, bottom - h, x + slot * 0.32f, bottom, 8f, 8f, bar)
            if (min >= 0.5) c.drawText("${min.roundToInt()}", x, bottom - h - 8f, text)
            c.drawText(date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.GERMAN), x, height - 8f, text)
        }
    }
}
