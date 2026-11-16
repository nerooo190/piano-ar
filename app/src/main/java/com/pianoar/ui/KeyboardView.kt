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

/**
 * 88-key piano keyboard (A0..C8).
 * floating = true draws an outline style for optical see-through glasses,
 * where black pixels are transparent.
 */
class KeyboardView(context: Context, private val floating: Boolean = false) : View(context) {

    var lowNote = 21
        private set
    var highNote = 108
        private set

    var onTouchNote: ((note: Int, on: Boolean) -> Unit)? = null

    private val active = BooleanArray(128)
    private val rects = arrayOfNulls<RectF>(128)
    private val pointerNotes = SparseIntArray()
    private var blackHeight = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val accentRight = Color.rgb(0, 229, 255)

    fun setRange(low: Int, high: Int) {
        lowNote = low; highNote = high
        layoutKeys(width, height)
        invalidate()
    }

    fun setActive(note: Int, on: Boolean) {
        if (note !in 0..127 || active[note] == on) return
        active[note] = on
        postInvalidateOnAnimation()
    }

    fun copyStateFrom(other: KeyboardView) {
        other.active.copyInto(active)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys(w, h)

    private fun layoutKeys(w: Int, h: Int) {
        rects.fill(null)
        if (w == 0 || h == 0) return
        val whites = (lowNote..highNote).count { !isBlack(it) }
        val ww = w.toFloat() / whites
        val bw = ww * 0.6f
        blackHeight = h * 0.62f
        var wi = 0
        for (n in lowNote..highNote) {
            if (isBlack(n)) {
                val x = wi * ww
                rects[n] = RectF(x - bw / 2, 0f, x + bw / 2, blackHeight)
            } else {
                rects[n] = RectF(wi * ww, 0f, (wi + 1) * ww, h.toFloat())
                wi++
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        // White keys first, black keys on top.
        for (pass in 0..1) for (n in lowNote..highNote) {
            if (isBlack(n) != (pass == 1)) continue
            val r = rects[n] ?: continue
            fill.color = when {
                active[n] -> accentRight
                floating -> if (isBlack(n)) Color.BLACK else Color.rgb(24, 24, 28)
                else -> if (isBlack(n)) Color.rgb(20, 20, 24) else Color.rgb(245, 245, 240)
            }
            canvas.drawRect(r, fill)
            stroke.color = if (floating) Color.rgb(90, 90, 110) else Color.rgb(60, 60, 60)
            canvas.drawRect(r, stroke)
        }
    }

    private fun noteAt(x: Float, y: Float): Int {
        if (y < blackHeight) {
            for (n in lowNote..highNote) if (isBlack(n) && rects[n]?.contains(x, y) == true) return n
        }
        for (n in lowNote..highNote) if (!isBlack(n) && rects[n]?.let { x >= it.left && x < it.right } == true) return n
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
        fun isBlack(n: Int) = when (n % 12) { 1, 3, 6, 8, 10 -> true; else -> false }
        private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        fun noteName(n: Int) = "${NAMES[n % 12]}${n / 12 - 1}"
    }
}
