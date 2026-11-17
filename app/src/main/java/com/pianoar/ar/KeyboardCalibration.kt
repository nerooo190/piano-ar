package com.pianoar.ar

import android.content.SharedPreferences
import com.pianoar.song.Music

/**
 * Where the real keyboard is in the camera image (4 corners, normalized),
 * which notes it spans, and camera options. Persisted in SharedPreferences.
 */
class KeyboardCalibration(private val prefs: SharedPreferences) {

    /** Front-left, front-right, back-right, back-left (x,y each, 0..1 of the visible camera image). */
    val corners = FloatArray(8)
    var lowNote = 21
        private set
    var highNote = 108
        private set
    var useFrontCamera = false
    var mirror = false
    var laneLength = 4f   // how far behind the keys notes appear, in key depths
    var homography: Homography? = null
        private set

    init { load() }

    fun setRange(low: Int, high: Int) {
        lowNote = minOf(low, high).coerceIn(0, 127)
        highNote = maxOf(low, high).coerceIn(0, 127)
        rebuild()
    }

    fun setCorner(i: Int, x: Float, y: Float) {
        corners[2 * i] = x.coerceIn(0f, 1f)
        corners[2 * i + 1] = y.coerceIn(0f, 1f)
        rebuild()
    }

    fun reset() {
        DEFAULT.copyInto(corners)
        rebuild()
    }

    // ── Key geometry in keyboard space ──
    private var whiteIndex = IntArray(128)
    private var whiteCount = 52

    private fun rebuild() {
        homography = Homography.fromUnitSquare(corners)
        whiteIndex = IntArray(128)
        var wi = 0
        for (n in lowNote..highNote) { whiteIndex[n] = wi; if (!Music.isBlack(n)) wi++ }
        whiteCount = wi.coerceAtLeast(1)
    }

    /** Returns u0, u1, v0, v1 for a key in keyboard space, or null if outside the calibrated range. */
    fun keyRect(note: Int, out: FloatArray): Boolean {
        if (note < lowNote || note > highNote) return false
        val ww = 1f / whiteCount
        val wi = whiteIndex[note]
        if (Music.isBlack(note)) {
            val c = wi * ww
            out[0] = c - ww * 0.3f; out[1] = c + ww * 0.3f; out[2] = BLACK_FRONT; out[3] = 1f
        } else {
            out[0] = wi * ww; out[1] = (wi + 1) * ww; out[2] = 0f; out[3] = 1f
        }
        return true
    }

    fun load() {
        val s = prefs.getString("cal.corners", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
        if (s != null && s.size == 8) s.forEachIndexed { i, f -> corners[i] = f } else DEFAULT.copyInto(corners)
        lowNote = prefs.getInt("cal.low", 21)
        highNote = prefs.getInt("cal.high", 108)
        useFrontCamera = prefs.getBoolean("cal.front", false)
        mirror = prefs.getBoolean("cal.mirror", false)
        laneLength = prefs.getFloat("cal.lane", 4f)
        rebuild()
    }

    fun save() {
        prefs.edit()
            .putString("cal.corners", corners.joinToString(","))
            .putInt("cal.low", lowNote)
            .putInt("cal.high", highNote)
            .putBoolean("cal.front", useFrontCamera)
            .putBoolean("cal.mirror", mirror)
            .putFloat("cal.lane", laneLength)
            .apply()
    }

    companion object {
        const val BLACK_FRONT = 0.36f
        private val DEFAULT = floatArrayOf(0.12f, 0.78f, 0.88f, 0.78f, 0.84f, 0.58f, 0.16f, 0.58f)

        /** Common keyboard sizes: label, lowest note, highest note. */
        val PRESETS = listOf(
            Triple("88 Tasten (A0–C8)", 21, 108),
            Triple("76 Tasten (E1–G7)", 28, 103),
            Triple("61 Tasten (C2–C7)", 36, 96),
            Triple("49 Tasten (C2–C6)", 36, 84),
            Triple("37 Tasten (C3–C6)", 48, 84),
            Triple("32 Tasten (F3–C6)", 53, 84),
            Triple("25 Tasten (C3–C5)", 48, 72),
        )
    }
}
