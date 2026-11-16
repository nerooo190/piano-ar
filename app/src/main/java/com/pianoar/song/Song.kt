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
