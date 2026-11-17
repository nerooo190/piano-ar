package com.pianoar.song

object Hand {
    const val LEFT = 0
    const val RIGHT = 1
}

data class Note(
    val pitch: Int,
    val start: Double,     // seconds (at 100 % tempo)
    val duration: Double,
    val velocity: Int,
    val hand: Int,
) {
    val end get() = start + duration
}

class Song(val title: String, val artist: String, notes: List<Note>) {
    val notes: List<Note> = notes.sortedWith(compareBy({ it.start }, { it.pitch }))
    val duration: Double = this.notes.maxOfOrNull { it.end } ?: 0.0
    val lowest: Int = this.notes.minOfOrNull { it.pitch } ?: 60
    val highest: Int = this.notes.maxOfOrNull { it.pitch } ?: 72

    /** Finger number (1–5) for each note, computed on first use. */
    val fingers: IntArray by lazy { Fingering.compute(this.notes) }

    /** Practice sections (phrases), as [start, end) times. */
    val sections: List<ClosedFloatingPointRange<Double>> by lazy { computeSections() }

    private fun computeSections(): List<ClosedFloatingPointRange<Double>> {
        val onsets = ArrayList<Double>()
        for (n in notes) if (onsets.isEmpty() || n.start - onsets.last() > 0.03) onsets += n.start
        if (onsets.size < 2) return listOf(0.0..duration)
        val iois = onsets.zipWithNext { a, b -> b - a }.sorted()
        val median = iois[iois.size / 2]
        val out = ArrayList<ClosedFloatingPointRange<Double>>()
        var secStart = onsets[0]
        for (k in 1 until onsets.size) {
            val len = onsets[k] - secStart
            val gap = onsets[k] - onsets[k - 1]
            if ((len >= 4.0 && gap >= median * 1.4) || len >= 10.0) {
                out += secStart..onsets[k]
                secStart = onsets[k]
            }
        }
        if (duration - secStart < 2.0 && out.isNotEmpty()) {
            val last = out.removeAt(out.size - 1)
            out += last.start..duration
        } else out += secStart..duration
        return out
    }

    /** Index of the first note whose start >= t (binary search). */
    fun lowerBound(t: Double): Int {
        var lo = 0
        var hi = notes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[mid].start < t) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

object Music {
    private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun isBlack(n: Int) = when (n % 12) { 1, 3, 6, 8, 10 -> true; else -> false }
    fun noteName(n: Int) = "${NAMES[n % 12]}${n / 12 - 1}"
}
