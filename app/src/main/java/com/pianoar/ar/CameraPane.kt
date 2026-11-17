package com.pianoar.ar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.pianoar.practice.PracticeSession
import com.pianoar.practice.Visual
import com.pianoar.song.Hand
import com.pianoar.song.Music
import com.pianoar.ui.PianoStageView
import kotlin.math.hypot
import kotlin.math.min

/**
 * Camera image with the keyboard overlay drawn in perspective on top of the real keys.
 * Used on the phone (with touch calibration) and on the glasses (display only).
 */
@SuppressLint("ViewConstructor")
class CameraPane(
    context: Context,
    private val feed: CameraFeed,
    private val cal: KeyboardCalibration,
    private val phoneRotationDegrees: () -> Int,
    private val interactive: Boolean,
) : FrameLayout(context) {

    val texture = TextureView(context)
    val overlay = Overlay(context)
    var session: PracticeSession? = null
    var pressed = BooleanArray(128)
    var calibrating = false
        set(v) { field = v; overlay.invalidate() }
    var onCalibrationChanged: (() -> Unit)? = null

    /** Where the camera image is drawn inside this view (after rotation/fit). */
    private val content = RectF()

    init {
        setBackgroundColor(Color.BLACK)
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        feed.attach(texture)
    }

    override fun onDetachedFromWindow() {
        feed.detach(texture)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransform()
    }

    /** Rotates the portrait camera buffer upright for the phone's current landscape rotation and fits it. */
    fun updateTransform() {
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw == 0f || vh == 0f) return
        val pw = feed.previewSize.width.toFloat(); val ph = feed.previewSize.height.toFloat()
        val cx = vw / 2; val cy = vh / 2
        val m = Matrix()
        val buffer = RectF(0f, 0f, ph, pw).apply { offset(cx - centerX(), cy - centerY()) }
        m.setRectToRect(RectF(0f, 0f, vw, vh), buffer, Matrix.ScaleToFit.FILL)
        val s = min(vw / pw, vh / ph)
        m.postScale(s, s, cx, cy)
        m.postRotate(-phoneRotationDegrees().toFloat(), cx, cy)
        if (cal.mirror) m.postScale(-1f, 1f, cx, cy)
        texture.setTransform(m)
        content.set(cx - pw * s / 2, cy - ph * s / 2, cx + pw * s / 2, cy + ph * s / 2)
        overlay.invalidate()
    }

    inner class Overlay(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        private val path = Path()
        private val p = FloatArray(2)
        private val key = FloatArray(4)
        private val guide = IntArray(128)
        private var dragging = -1

        private fun toView(u: Double, v: Double): Boolean {
            val h = cal.homography ?: return false
            val w = h.map(u, v, p)
            if (w <= 0.02) return false
            p[0] = content.left + p[0] * content.width()
            p[1] = content.top + p[1] * content.height()
            return true
        }

        /** Builds a path for the quad u0..u1 × v0..v1 in keyboard space. */
        private fun quad(u0: Float, u1: Float, v0: Float, v1: Float): Boolean {
            path.reset()
            val us = floatArrayOf(u0, u1, u1, u0)
            val vs = floatArrayOf(v0, v0, v1, v1)
            for (i in 0 until 4) {
                if (!toView(us[i].toDouble(), vs[i].toDouble())) return false
                if (i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1])
            }
            path.close()
            return true
        }

        override fun onDraw(c: Canvas) {
            guide.fill(0)
            val s = session
            if (s != null && !calibrating) drawNotes(c, s)
            drawKeys(c)
            if (calibrating) drawCalibration(c)
        }

        private fun drawNotes(c: Canvas, s: PracticeSession) {
            val notes = s.song.notes
            val t = s.time
            val look = PianoStageView.LOOKAHEAD
            val lane = cal.laneLength
            var i = s.song.lowerBound(t - 10.0)
            while (i < notes.size && notes[i].start <= t + look) {
                val n = notes[i]
                if (n.end >= t && cal.keyRect(n.pitch, key)) {
                    val user = s.isUserNote(i)
                    if (user && n.start <= t + 0.03) guide[n.pitch] = n.hand + 1
                    val preview = Visual.userNotePreview
                    if (user && preview != null && n.start - t > preview) { i++; continue }
                    // Notes travel along the keyboard plane from behind the keys (v > 1) toward the back edge (v = 1).
                    val vNear = 1f + (((n.start - t) / look).toFloat() * lane).coerceAtLeast(0f)
                    val vFar = 1f + (((n.end - t) / look).toFloat() * lane).coerceAtMost(lane)
                    if (vFar > vNear && quad(key[0], key[1], vNear, vFar)) {
                        fill.color = when (s.states[i]) {
                            PracticeSession.State.HIT -> PianoStageView.HIT_COLOR
                            PracticeSession.State.MISSED -> PianoStageView.MISS_COLOR
                            else -> if (n.hand == Hand.LEFT) PianoStageView.LEFT_COLOR else PianoStageView.RIGHT_COLOR
                        }
                        fill.alpha = if (user) 215 else 90
                        c.drawPath(path, fill)
                        if (user && Visual.showLabels && Visual.showFingers && toView(((key[0] + key[1]) / 2).toDouble(), vNear + 0.15)) {
                            c.drawText(s.song.fingers[i].toString(), p[0] - label.textSize * 0.3f, p[1], label)
                        }
                    }
                }
                i++
            }
            // Hit line at the back edge of the keys
            if (quad(0f, 1f, 0.995f, 1.005f)) {
                fill.color = Color.argb(220, 0, 229, 255)
                c.drawPath(path, fill)
            }
        }

        private fun drawKeys(c: Canvas) {
            for (pass in 0..1) for (n in cal.lowNote..cal.highNote) {
                if (Music.isBlack(n) != (pass == 1)) continue
                val g = guide[n]
                val color = when {
                    pressed[n] && g != 0 -> PianoStageView.HIT_COLOR
                    pressed[n] -> PianoStageView.PRESS_COLOR
                    g == 1 && Visual.showKeyGuide -> PianoStageView.LEFT_COLOR
                    g == 2 && Visual.showKeyGuide -> PianoStageView.RIGHT_COLOR
                    else -> continue
                }
                if (!cal.keyRect(n, key) || !quad(key[0], key[1], key[2], key[3])) continue
                fill.color = color
                fill.alpha = 150
                c.drawPath(path, fill)
            }
        }

        private fun drawCalibration(c: Canvas) {
            // Key grid so the user can check alignment
            line.color = Color.argb(200, 0, 229, 255)
            line.strokeWidth = 2f
            for (n in cal.lowNote..cal.highNote) {
                if (cal.keyRect(n, key) && quad(key[0], key[1], key[2], key[3])) {
                    if (Music.isBlack(n)) { fill.color = Color.argb(90, 0, 0, 0); c.drawPath(path, fill) }
                    c.drawPath(path, line)
                }
            }
            line.strokeWidth = 4f
            line.color = Color.YELLOW
            if (quad(0f, 1f, 0f, 1f)) c.drawPath(path, line)
            // Corner handles
            for (i in 0 until 4) {
                val x = content.left + cal.corners[2 * i] * content.width()
                val y = content.top + cal.corners[2 * i + 1] * content.height()
                fill.color = if (i == dragging) Color.WHITE else Color.YELLOW
                fill.alpha = 230
                c.drawCircle(x, y, HANDLE, fill)
                c.drawText(CORNER_LABELS[i], x + HANDLE + 8, y - HANDLE, label)
            }
            c.drawText("Ziehe die 4 gelben Punkte auf die Ecken deiner Tastatur", content.left + 24, content.top + 48, label)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!interactive || !calibrating || content.width() == 0f) return false
            val nx = (e.x - content.left) / content.width()
            val ny = (e.y - content.top) / content.height()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = (0 until 4).minByOrNull {
                        hypot(cal.corners[2 * it] - nx, cal.corners[2 * it + 1] - ny)
                    } ?: -1
                    if (dragging >= 0) {
                        val dx = (cal.corners[2 * dragging] - nx) * content.width()
                        val dy = (cal.corners[2 * dragging + 1] - ny) * content.height()
                        if (hypot(dx, dy) > HANDLE * 4) dragging = -1
                    }
                }
                MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                    cal.setCorner(dragging, nx, ny)
                    onCalibrationChanged?.invoke()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging >= 0) { cal.save(); onCalibrationChanged?.invoke() }
                    dragging = -1
                }
            }
            invalidate()
            return true
        }
    }

    companion object {
        private const val HANDLE = 22f
        private val CORNER_LABELS = arrayOf("vorne links (tiefste Taste)", "vorne rechts", "hinten rechts", "hinten links")
    }
}
