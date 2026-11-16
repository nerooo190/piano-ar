package com.pianoar.song

/**
 * Built-in public-domain pieces and exercises, written in a tiny text format:
 * "C4/2" = C4 for 2 beats, "C4+E4+G4/4" = chord, "R/1" = rest, "|" = bar line (ignored).
 */
object BuiltinSongs {

    val all: List<Song> by lazy {
        listOf(
            build(
                "C-Dur Tonleiter", "Übung", 90.0,
                rh = "C4 D4 E4 F4 G4 A4 B4 C5 | B4 A4 G4 F4 E4 D4 C4/2",
                lh = "C3 D3 E3 F3 G3 A3 B3 C4 | B3 A3 G3 F3 E3 D3 C3/2",
            ),
            build(
                "Alle meine Entchen", "Volkslied", 110.0,
                rh = "C4 D4 E4 F4 | G4/2 G4/2 | A4 A4 A4 A4 | G4/4 | A4 A4 A4 A4 | G4/4 | " +
                     "F4 F4 F4 F4 | E4/2 E4/2 | D4 D4 D4 D4 | C4/4",
                lh = "",
            ),
            build(
                "Morgen kommt der Weihnachtsmann", "Volkslied", 100.0,
                rh = "C4 C4 G4 G4 | A4 A4 G4/2 | F4 F4 E4 E4 | D4 D4 C4/2 | " +
                     "G4 G4 F4 F4 | E4 E4 D4/2 | G4 G4 F4 F4 | E4 E4 D4/2 | " +
                     "C4 C4 G4 G4 | A4 A4 G4/2 | F4 F4 E4 E4 | D4 D4 C4/2",
                lh = "C3/4 | F3/2 C3/2 | F3/2 C3/2 | G2/2 C3/2 | " +
                     "C3/2 G2/2 | C3/2 G2/2 | C3/2 G2/2 | C3/2 G2/2 | " +
                     "C3/4 | F3/2 C3/2 | F3/2 C3/2 | G2/2 C3/2",
            ),
            build(
                "Ode an die Freude", "L. v. Beethoven", 100.0,
                rh = "E4 E4 F4 G4 | G4 F4 E4 D4 | C4 C4 D4 E4 | E4/1.5 D4/0.5 D4/2 | " +
                     "E4 E4 F4 G4 | G4 F4 E4 D4 | C4 C4 D4 E4 | D4/1.5 C4/0.5 C4/2",
                lh = "C3+G3/4 | G2+D3/4 | C3+G3/4 | G2+D3/4 | " +
                     "C3+G3/4 | G2+D3/4 | C3+G3/4 | G2+D3/2 C3+G3/2",
            ),
            build(
                "Für Elise (Anfang)", "L. v. Beethoven", 300.0, // unit = sixteenth note
                rh = "E5 D#5 | E5 D#5 E5 B4 D5 C5 | A4/3 C4 E4 A4 | B4/3 E4 G#4 B4 | C5/3 E4 E5 D#5 | " +
                     "E5 D#5 E5 B4 D5 C5 | A4/3 C4 E4 A4 | B4/3 E4 C5 B4 | A4/6",
                lh = "R/2 | R/6 | A2 E3 A3 R/3 | E2 E3 G#3 R/3 | A2 E3 A3 R/3 | " +
                     "R/6 | A2 E3 A3 R/3 | E2 E3 G#3 R/3 | A2/2 E3/2 A3/2",
            ),
            build(
                "Akkorde: C – F – G – C", "Übung", 70.0,
                rh = "C4+E4+G4/4 | C4+F4+A4/4 | B3+D4+G4/4 | C4+E4+G4/4",
                lh = "C3/4 | F2/4 | G2/4 | C3/4",
            ),
        )
    }

    private fun build(title: String, artist: String, bpm: Double, rh: String, lh: String) =
        Song(title, artist, parse(rh, Hand.RIGHT, bpm) + parse(lh, Hand.LEFT, bpm))

    private fun parse(text: String, hand: Int, bpm: Double): List<Note> {
        val beat = 60.0 / bpm
        var t = 0.0
        val out = ArrayList<Note>()
        for (tok in text.split(Regex("[\\s|]+")).filter { it.isNotEmpty() }) {
            val parts = tok.split("/")
            val beats = parts.getOrNull(1)?.toDouble() ?: 1.0
            if (parts[0] != "R") parts[0].split("+").forEach {
                out += Note(pitch(it), t, beats * beat * 0.92, 80, hand)
            }
            t += beats * beat
        }
        return out
    }

    fun pitch(s: String): Int {
        val base = when (s[0]) {
            'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9; 'B' -> 11
            else -> throw IllegalArgumentException("Note $s")
        }
        var i = 1
        var acc = 0
        if (s[i] == '#') { acc = 1; i++ } else if (s[i] == 'b') { acc = -1; i++ }
        return 12 * (s.substring(i).toInt() + 1) + base + acc
    }
}
