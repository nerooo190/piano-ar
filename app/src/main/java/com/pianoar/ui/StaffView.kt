package com.pianoar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.pianoar.practice.PracticeSession
import com.pianoar.practice.Visual
import com.pianoar.song.Hand

/**
 * Auto-generated, scrolling grand staff (treble + bass) synced to the song clock.
 * Right-hand notes go on the treble staff, left-hand notes on the bass staff;
 * horizontal position is proportional to time, so it reads like sheet music in motion.
 */
class StaffView(context: Context) : View(context) {

    var session: PracticeSession? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 200, 210); strokeWidth = 2f }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 220, 230) }
    private val oval = RectF()

    // Diatonic step per pitch class (C=0 … B=6) and whether it needs a sharp
    private val STEP = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
    private val SHARP = booleanArrayOf(false, true, false, true, false, false, true, false, true, false, true, false)

    private fun diatonic(pitch: Int) = (pitch / 12 - 1) * 7 + STEP[pitch % 12]

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(24, 24, 32))
        val h = height.toFloat()
        val gap = h / 22f                   // distance between staff lines
        val trebleBottom = h * 0.42f        // E4 line
        val bassTop = h * 0.58f             // A3 line
        val left = 90f
        val playX = width * 0.22f

        // Staff lines
        for (k in 0 until 5) {
            c.drawLine(left, trebleBottom - k * gap, width.toFloat(), trebleBottom - k * gap, line)
            c.drawLine(left, bassTop + k * gap, width.toFloat(), bassTop + k * gap, line)
        }
        text.textSize = gap * 4.2f
        c.drawText(if (text.hasGlyph("\uD834\uDD1E")) "\uD834\uDD1E" else "G", 12f, trebleBottom + gap * 0.6f, text)
        text.textSize = gap * 3.2f
        c.drawText(if (text.hasGlyph("\uD834\uDD22")) "\uD834\uDD22" else "F", 14f, bassTop + gap * 2.6f, text)

        val s = session ?: return
        val notes = s.song.notes
        val t = s.time
        val pxPerSec = width * 0.28f
        val e4 = diatonic(64)
        val a3 = diatonic(57)

        var i = s.song.lowerBound(t - (playX - left) / pxPerSec - 0.5)
        val tEnd = t + (width - playX) / pxPerSec
        while (i < notes.size && notes[i].start <= tEnd) {
            val n = notes[i]
            val x = playX + ((n.start - t) * pxPerSec).toFloat()
            if (x < left) { i++; continue }
            val user = s.isUserNote(i)
            val preview = Visual.userNotePreview
            if (user && preview != null && n.start - t > preview) { i++; continue }
            val treble = n.hand == Hand.RIGHT
            val d = diatonic(n.pitch)
            val y = if (treble) trebleBottom - (d - e4) * gap / 2 else bassTop - (d - a3) * gap / 2

            // Ledger lines
            line.strokeWidth = 2f
            if (treble) {
                var ld = e4 - 2; while (ld >= d) { val ly = trebleBottom - (ld - e4) * gap / 2; c.drawLine(x - gap, ly, x + gap, ly, line); ld -= 2 }
                ld = e4 + 10; while (ld <= d) { val ly = trebleBottom - (ld - e4) * gap / 2; c.drawLine(x - gap, ly, x + gap, ly, line); ld += 2 }
            } else {
                var ld = a3 + 2; while (ld <= d) { val ly = bassTop - (ld - a3) * gap / 2; c.drawLine(x - gap, ly, x + gap, ly, line); ld += 2 }
                ld = a3 - 10; while (ld >= d) { val ly = bassTop - (ld - a3) * gap / 2; c.drawLine(x - gap, ly, x + gap, ly, line); ld -= 2 }
            }

            head.color = when (s.states[i]) {
                PracticeSession.State.HIT -> PianoStageView.HIT_COLOR
                PracticeSession.State.MISSED -> Color.rgb(200, 70, 80)
                else -> if (treble) PianoStageView.RIGHT_COLOR else PianoStageView.LEFT_COLOR
            }
            if (!user) head.alpha = 110
            // Duration bar behind the head
            val durPx = (n.duration * pxPerSec).toFloat()
            head.alpha = if (user) 70 else 40
            c.drawRect(x, y - gap * 0.18f, x + durPx, y + gap * 0.18f, head)
            head.alpha = if (user) 255 else 120
            oval.set(x - gap * 0.62f, y - gap * 0.45f, x + gap * 0.62f, y + gap * 0.45f)
            c.drawOval(oval, head)
            if (SHARP[n.pitch % 12]) {
                text.textSize = gap * 1.3f
                c.drawText("♯", x - gap * 1.6f, y + gap * 0.45f, text)
            }
            if (user && Visual.showLabels && Visual.showFingers) {
                text.textSize = gap * 1.1f
                val fy = if (treble) trebleBottom - gap * 5.2f else bassTop + gap * 5.9f
                c.drawText(s.song.fingers[i].toString(), x - gap * 0.3f, fy, text)
            }
            i++
        }

        // Playhead
        line.color = Color.argb(220, 0, 229, 255)
        line.strokeWidth = 4f
        c.drawLine(playX, trebleBottom - gap * 6, playX, bassTop + gap * 6, line)
        line.color = Color.rgb(200, 200, 210)
        line.strokeWidth = 2f
    }
}
