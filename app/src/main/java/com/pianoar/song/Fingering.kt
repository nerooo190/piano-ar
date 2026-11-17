package com.pianoar.song

import kotlin.math.abs

/**
 * Automatic piano fingering (1 = thumb … 5 = little finger), per hand.
 * Melodic lines: dynamic programming (Viterbi) over a hand-position cost model
 * (stretch from the natural five-finger position, thumb-under/finger-over crossings,
 * thumb on black keys, repeated-note changes). Chords get standard shapes.
 */
object Fingering {

    /** Natural semitone offset of each finger in a five-finger position (index 1..5). */
    private val POS = intArrayOf(0, 0, 2, 4, 5, 7)

    private class Event(val idx: IntArray, val pitches: IntArray)

    fun compute(notes: List<Note>): IntArray {
        val out = IntArray(notes.size)
        for (hand in listOf(Hand.LEFT, Hand.RIGHT)) {
            val ids = notes.indices.filter { notes[it].hand == hand }
            if (ids.isEmpty()) continue
            // Group simultaneous notes (within 30 ms) into chords
            val events = ArrayList<Event>()
            var i = 0
            while (i < ids.size) {
                var j = i + 1
                while (j < ids.size && notes[ids[j]].start - notes[ids[i]].start < 0.03) j++
                val group = ids.subList(i, j).sortedBy { notes[it].pitch }
                events += Event(group.toIntArray(), group.map { notes[it].pitch }.toIntArray())
                i = j
            }
            assign(events, hand, out)
        }
        return out
    }

    private fun chordShape(p: IntArray, hand: Int): IntArray {
        val k = p.size
        val span = p.last() - p.first()
        val asc = when {
            k == 1 -> intArrayOf(1)
            k == 2 -> when { span >= 7 -> intArrayOf(1, 5); span >= 5 -> intArrayOf(1, 4); span >= 3 -> intArrayOf(1, 3); else -> intArrayOf(1, 2) }
            k == 3 -> if (span <= 5) intArrayOf(1, 2, 4) else intArrayOf(1, 3, 5)
            k == 4 -> intArrayOf(1, 2, 3, 5)
            else -> IntArray(k) { (it + 1).coerceAtMost(5) }
        }
        // Right hand: thumb on the lowest note. Left hand: thumb on the highest note.
        return if (hand == Hand.RIGHT) asc else asc.reversedArray()
    }

    private fun transitionCost(p1: Int, f1: Int, p2: Int, f2: Int, hand: Int): Double {
        val d = if (hand == Hand.RIGHT) p2 - p1 else p1 - p2 // positive = moving away from the thumb
        if (d == 0) return if (f1 == f2) 0.0 else 1.0
        if (f1 == f2) return 6.0 + abs(d) * 0.5
        var c: Double
        val expected = POS[f2] - POS[f1]
        if (d > 0 && f2 < f1) {
            // Moving outward but to a lower finger: only thumb-under is acceptable
            c = if (f2 == 1 && f1 in 2..4) 2.5 + abs(d - 1) * 0.6 else 25.0
        } else if (d < 0 && f2 > f1) {
            // Moving toward the thumb but to a higher finger: only finger-over-thumb is acceptable
            c = if (f1 == 1 && f2 in 2..4) 2.5 + abs(-d - 1) * 0.6 else 25.0
        } else {
            c = abs(d - expected) * 0.8
            if (abs(d) > abs(expected) + 4) c += 4.0 // big stretch
        }
        if (Music.isBlack(p2) && f2 == 1) c += 2.0
        if (Music.isBlack(p2) && f2 == 5) c += 0.5
        return c
    }

    private fun assign(events: List<Event>, hand: Int, out: IntArray) {
        val n = events.size
        // State per event: for single notes 5 options, for chords 1 fixed shape.
        val options = events.map { e -> if (e.pitches.size == 1) (1..5).map { intArrayOf(it) } else listOf(chordShape(e.pitches, hand)) }
        val cost = Array(n) { DoubleArray(options[it].size) }
        val back = Array(n) { IntArray(options[it].size) }
        // Entry/exit note of each event for transitions (thumb-side note of chords).
        fun repr(e: Event, f: IntArray, entry: Boolean): Pair<Int, Int> {
            if (e.pitches.size == 1) return e.pitches[0] to f[0]
            val k = if ((hand == Hand.RIGHT) == entry) 0 else e.pitches.size - 1
            return e.pitches[k] to f[k]
        }
        for (s in options[0].indices) {
            val (p, f) = repr(events[0], options[0][s], true)
            cost[0][s] = (if (Music.isBlack(p) && f == 1) 2.0 else 0.0) + abs(f - 2) * 0.1 // slight preference for 2/3
        }
        for (k in 1 until n) for (s in options[k].indices) {
            val (p2, f2) = repr(events[k], options[k][s], true)
            var best = Double.MAX_VALUE
            var arg = 0
            for (r in options[k - 1].indices) {
                val (p1, f1) = repr(events[k - 1], options[k - 1][r], false)
                val c = cost[k - 1][r] + transitionCost(p1, f1, p2, f2, hand)
                if (c < best) { best = c; arg = r }
            }
            cost[k][s] = best
            back[k][s] = arg
        }
        var s = cost[n - 1].indices.minByOrNull { cost[n - 1][it] } ?: 0
        for (k in n - 1 downTo 0) {
            val f = options[k][s]
            events[k].idx.forEachIndexed { j, noteIdx -> out[noteIdx] = f[j.coerceAtMost(f.size - 1)] }
            s = back[k][s]
        }
    }
}
