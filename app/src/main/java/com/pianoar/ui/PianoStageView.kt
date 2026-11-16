package com.pianoar.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View
import com.pianoar.practice.PracticeSession
import com.pianoar.song.Hand
import com.pianoar.song.Music
import com.pianoar.song.Song
import kotlin.math.ceil

/**
 * Falling-notes waterfall + keyboard, shared by phone and glasses.
 * floating = true: outline style for optical see-through glasses (black = transparent).
 */
class PianoStageView(context: Context, private val floating: Boolean = false) : View(context) {

    var session: PracticeSession? = null
    var pressed: BooleanArray = BooleanArray(128)
    var onTouchNote: ((note: Int, on: Boolean) -> Unit)? = null

    private var lowNote = 21
    private var highNote = 108
    private val rects = arrayOfNulls<RectF>(128)
    private var kbTop = 0f
    private var blackBottom = 0f
    private val pointerNotes = SparseIntArray()
    private val guide = IntArray(128) // 0 none, 1 left, 2 right

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE }
    private val noteRect = RectF()

    fun fitTo(song: Song?) {
        if (song == null) { lowNote = 21; highNote = 108 } else {
            var lo = song.lowest - song.lowest % 12          // down to C
            var hi = song.highest + (11 - song.highest % 12) // up to B
            while (hi - lo < 35) { lo -= 12; if (hi - lo < 35) hi += 12 }
            lowNote = lo.coerceAtLeast(21)
            highNote = hi.coerceAtMost(108)
        }
        layoutKeys(width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys(w, h)

    private fun layoutKeys(w: Int, h: Int) {
        rects.fill(null)
        if (w == 0 || h == 0) return
        val kbH = h * if (floating) 0.30f else 0.24f
        kbTop = h - kbH
        val whites = (lowNote..highNote).count { !Music.isBlack(it) }
        val ww = w.toFloat() / whites
        val bw = ww * 0.6f
        blackBottom = kbTop + kbH * 0.62f
        var wi = 0
        for (n in lowNote..highNote) {
            if (Music.isBlack(n)) {
                rects[n] = RectF(wi * ww - bw / 2, kbTop, wi * ww + bw / 2, blackBottom)
            } else {
                rects[n] = RectF(wi * ww, kbTop, (wi + 1) * ww, h.toFloat())
                wi++
            }
        }
        text.textSize = (ww * 0.42f).coerceIn(10f, 28f)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(if (floating) Color.BLACK else BG)
        guide.fill(0)

        // Octave lanes
        fill.color = if (floating) Color.rgb(30, 30, 40) else Color.rgb(32, 32, 44)
        for (n in lowNote..highNote) if (n % 12 == 0) rects[n]?.let { c.drawRect(it.left, 0f, it.left + 2f, kbTop, fill) }

        val s = session
        if (s != null) drawNotes(c, s)

        // Hit line
        fill.color = Color.argb(200, 0, 229, 255)
        c.drawRect(0f, kbTop - 3f, width.toFloat(), kbTop, fill)

        drawKeyboard(c)

        if (s != null && s.time < 0 && s.playing) {
            val labelSize = text.textSize
            text.textSize = height * 0.18f
            text.color = Color.argb(200, 255, 255, 255)
            c.drawText(ceil(-s.time).toInt().toString(), width / 2f, kbTop * 0.55f, text)
            text.color = Color.WHITE
            text.textSize = labelSize
        }
    }

    private fun drawNotes(c: Canvas, s: PracticeSession) {
        val notes = s.song.notes
        val t = s.time
        val pps = kbTop / LOOKAHEAD
        var i = s.song.lowerBound(t - 10.0)
        while (i < notes.size && notes[i].start <= t + LOOKAHEAD) {
            val n = notes[i]
            val r = rects.getOrNull(n.pitch)
            if (n.end >= t && r != null) {
                val user = s.isUserNote(i)
                if (user && n.start <= t + 0.03 && n.end > t) guide[n.pitch] = n.hand + 1
                val yb = (kbTop - (n.start - t) * pps).toFloat().coerceAtMost(kbTop)
                val yt = (kbTop - (n.end - t) * pps).toFloat()
                val base = if (n.hand == Hand.LEFT) LEFT_COLOR else RIGHT_COLOR
                fill.color = when (s.states[i]) {
                    PracticeSession.State.HIT -> HIT_COLOR
                    PracticeSession.State.MISSED -> MISS_COLOR
                    else -> base
                }
                if (!user) fill.alpha = 80
                val inset = r.width() * 0.08f
                noteRect.set(r.left + inset, yt, r.right - inset, yb)
                c.drawRoundRect(noteRect, 8f, 8f, fill)
                if (yb - yt > text.textSize * 1.4f && r.width() > 16f) {
                    c.drawText(Music.noteName(n.pitch).dropLast(1), noteRect.centerX(), yb - text.textSize * 0.4f, text)
                }
            }
            i++
        }
    }

    private fun drawKeyboard(c: Canvas) {
        for (pass in 0..1) for (n in lowNote..highNote) {
            val black = Music.isBlack(n)
            if (black != (pass == 1)) continue
            val r = rects[n] ?: continue
            val g = guide[n]
            fill.color = when {
                pressed[n] && g != 0 -> HIT_COLOR
                pressed[n] -> PRESS_COLOR
                g == 1 -> LEFT_COLOR
                g == 2 -> RIGHT_COLOR
                floating -> if (black) Color.BLACK else Color.rgb(22, 22, 28)
                else -> if (black) Color.rgb(18, 18, 22) else Color.rgb(245, 245, 240)
            }
            c.drawRect(r, fill)
            stroke.color = if (floating) Color.rgb(90, 90, 110) else Color.rgb(60, 60, 60)
            c.drawRect(r, stroke)
        }
        // C labels on the white keys
        text.color = if (floating) Color.rgb(140, 140, 160) else Color.rgb(90, 90, 90)
        for (n in lowNote..highNote) if (n % 12 == 0) rects[n]?.let {
            c.drawText(Music.noteName(n), it.centerX(), it.bottom - text.textSize * 0.5f, text)
        }
        text.color = Color.WHITE
    }

    private fun noteAt(x: Float, y: Float): Int {
        if (y < kbTop) return -1
        if (y < blackBottom) for (n in lowNote..highNote) if (Music.isBlack(n) && rects[n]?.contains(x, y) == true) return n
        for (n in lowNote..highNote) if (!Music.isBlack(n) && rects[n]?.let { x >= it.left && x < it.right } == true) return n
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cb = onTouchNote ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val n = noteAt(e.getX(i), e.getY(i))
                pointerNotes.put(e.getPointerId(i), n)
                if (n >= 0) cb(n, true)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                val old = pointerNotes.get(id, -1)
                val n = noteAt(e.getX(i), e.getY(i))
                if (n != old) {
                    if (old >= 0) cb(old, false)
                    if (n >= 0) cb(n, true)
                    pointerNotes.put(id, n)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val n = pointerNotes.get(id, -1)
                if (n >= 0) cb(n, false)
                pointerNotes.delete(id)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (k in 0 until pointerNotes.size()) pointerNotes.valueAt(k).takeIf { it >= 0 }?.let { cb(it, false) }
                pointerNotes.clear()
            }
        }
        return true
    }

    companion object {
        const val LOOKAHEAD = 3.0 // seconds of song time visible above the keys
        val BG = Color.rgb(14, 14, 20)
        val RIGHT_COLOR = Color.rgb(0, 170, 255)
        val LEFT_COLOR = Color.rgb(255, 140, 0)
        val HIT_COLOR = Color.rgb(60, 220, 120)
        val MISS_COLOR = Color.rgb(110, 50, 60)
        val PRESS_COLOR = Color.rgb(0, 229, 255)
    }
}
