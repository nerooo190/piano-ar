package com.pianoar.practice

import com.pianoar.audio.PianoAudio
import com.pianoar.song.Hand
import com.pianoar.song.Song
import kotlin.math.abs
import kotlin.math.max

/**
 * Drives one song: song clock, Wait/Play/Listen modes, hand selection,
 * auto-accompaniment of the hand the user is not playing, and scoring.
 */
class PracticeSession(val song: Song, private val audio: PianoAudio) {

    enum class Mode(val label: String) { WAIT("Warten"), PLAY("Spielen"), LISTEN("Anhören") }
    enum class Hands(val label: String) { BOTH("Beide Hände"), RIGHT("Rechte Hand"), LEFT("Linke Hand") }
    enum class State { PENDING, HIT, MISSED }

    data class Stats(
        val total: Int, val hits: Int, val missed: Int, val wrong: Int,
        val meanErrorMs: Double?, val leftHits: Int, val leftTotal: Int, val rightHits: Int, val rightTotal: Int,
    ) {
        val accuracy get() = if (total == 0) 0.0 else hits * 100.0 / total
    }

    private val notes = song.notes
    var mode = Mode.WAIT
        set(v) { field = v; restart() }
    var hands = Hands.BOTH
        set(v) { field = v; restart() }
    var tempo = 1.0
        set(v) { field = v.coerceIn(0.25, 1.5) }

    var time = LEAD_IN
        private set
    var playing = false
        private set
    var waiting = false
        private set
    var finished = false
        private set

    val states = Array(notes.size) { State.PENDING }
    private val errors = DoubleArray(notes.size) { Double.NaN }
    private var wrong = 0
    private var autoIdx = 0
    private var missIdx = 0
    private var waitIdx = 0
    private val autoSounding = ArrayList<Int>()
    private var lastNanos = 0L

    var onFinished: ((Stats) -> Unit)? = null

    fun isUserNote(i: Int): Boolean = mode != Mode.LISTEN && when (hands) {
        Hands.BOTH -> true
        Hands.RIGHT -> notes[i].hand == Hand.RIGHT
        Hands.LEFT -> notes[i].hand == Hand.LEFT
    }

    fun togglePlay() {
        if (finished) restart()
        playing = !playing
        lastNanos = 0
        if (!playing) stopAuto()
    }

    fun restart() {
        stopAuto()
        time = LEAD_IN
        states.fill(State.PENDING)
        errors.fill(Double.NaN)
        wrong = 0; autoIdx = 0; missIdx = 0; waitIdx = 0
        finished = false; waiting = false; playing = false
        lastNanos = 0
    }

    private fun stopAuto() {
        autoSounding.forEach { audio.noteOff(notes[it].pitch) }
        autoSounding.clear()
    }

    private fun firstPendingUserStart(): Double? {
        while (waitIdx < notes.size && (!isUserNote(waitIdx) || states[waitIdx] != State.PENDING)) waitIdx++
        return if (waitIdx < notes.size) notes[waitIdx].start else null
    }

    /** Called once per frame with System.nanoTime-based timestamps. */
    fun tick(now: Long) {
        if (!playing || finished) { lastNanos = 0; return }
        if (lastNanos == 0L) { lastNanos = now; return }
        val dt = (now - lastNanos) / 1e9 * tempo
        lastNanos = now

        var target = time + dt
        waiting = false
        if (mode == Mode.WAIT) {
            firstPendingUserStart()?.let { w -> if (target >= w) { target = max(time, w); waiting = true } }
        }
        time = target

        // Auto-play notes that are not the user's.
        while (autoIdx < notes.size && notes[autoIdx].start <= time) {
            if (!isUserNote(autoIdx)) {
                audio.noteOn(notes[autoIdx].pitch, notes[autoIdx].velocity)
                autoSounding += autoIdx
            }
            autoIdx++
        }
        autoSounding.removeAll { i ->
            (notes[i].end <= time).also { done -> if (done) audio.noteOff(notes[i].pitch) }
        }

        // Missed notes (only in real-time Play mode).
        if (mode == Mode.PLAY) {
            val win = HIT_WINDOW * tempo
            while (missIdx < notes.size && notes[missIdx].start + win < time) {
                if (isUserNote(missIdx) && states[missIdx] == State.PENDING) states[missIdx] = State.MISSED
                missIdx++
            }
        }

        if (time > song.duration + 0.8 && (mode != Mode.WAIT || firstPendingUserStart() == null)) {
            finished = true
            playing = false
            stopAuto()
            onFinished?.invoke(stats())
        }
    }

    /** User pressed a key. Returns true if it matched an expected note. */
    fun onUserNote(pitch: Int, on: Boolean): Boolean {
        if (!on || mode == Mode.LISTEN || !playing) return false
        val win = HIT_WINDOW * tempo
        var best = -1
        var bestD = Double.MAX_VALUE
        var i = song.lowerBound(time - win)
        while (i < notes.size && notes[i].start <= time + win) {
            if (notes[i].pitch == pitch && states[i] == State.PENDING && isUserNote(i)) {
                val d = abs(notes[i].start - time)
                if (d < bestD) { bestD = d; best = i }
            }
            i++
        }
        if (best < 0) { wrong++; return false }
        states[best] = State.HIT
        if (mode == Mode.PLAY) errors[best] = (time - notes[best].start) / tempo
        return true
    }

    fun stats(): Stats {
        var total = 0; var hits = 0; var missed = 0
        var lh = 0; var lt = 0; var rh = 0; var rt = 0
        var errSum = 0.0; var errN = 0
        for (i in notes.indices) {
            if (!isUserNote(i)) continue
            total++
            val hit = states[i] == State.HIT
            if (hit) hits++
            if (states[i] == State.MISSED) missed++
            if (notes[i].hand == Hand.LEFT) { lt++; if (hit) lh++ } else { rt++; if (hit) rh++ }
            if (!errors[i].isNaN()) { errSum += abs(errors[i]); errN++ }
        }
        return Stats(total, hits, missed, wrong, if (errN > 0) errSum / errN * 1000 else null, lh, lt, rh, rt)
    }

    companion object {
        const val LEAD_IN = -2.0
        const val HIT_WINDOW = 0.2 // seconds, real time
    }
}
