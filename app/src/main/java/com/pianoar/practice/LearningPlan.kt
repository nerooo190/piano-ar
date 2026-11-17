package com.pianoar.practice

import com.pianoar.song.Hand

/**
 * Learning Engine: splits the song into sections (phrases) and walks each one through
 * a path of steps (right hand slow → left hand slow → both slow → both full tempo).
 * A step is passed at ≥ 85 % accuracy with at most 3 wrong keys, otherwise it repeats.
 * After the last section the whole song is played once at full tempo.
 */
class LearningPlan(private val s: PracticeSession) {

    data class Step(val hands: PracticeSession.Hands, val tempo: Double) {
        val label get() = "${hands.label} ${(tempo * 100).toInt()} %"
    }

    private val sections = s.song.sections
    private val steps = buildList {
        if (s.hasHand(Hand.RIGHT) && s.hasHand(Hand.LEFT)) {
            add(Step(PracticeSession.Hands.RIGHT, 0.7))
            add(Step(PracticeSession.Hands.LEFT, 0.7))
            add(Step(PracticeSession.Hands.BOTH, 0.7))
        } else {
            add(Step(PracticeSession.Hands.BOTH, 0.7))
        }
        add(Step(PracticeSession.Hands.BOTH, 1.0))
    }

    var section = 0
        private set
    var step = 0
        private set
    var finalRun = false
        private set
    var done = false
        private set
    var attempts = 0
        private set

    val sectionCount get() = sections.size

    val label: String
        get() = when {
            done -> "Lernplan abgeschlossen 🎉"
            finalRun -> "Finale: ganzer Song"
            else -> "Abschnitt ${section + 1}/${sections.size} · ${steps[step].label}" +
                if (attempts > 0) " · Versuch ${attempts + 1}" else ""
        }

    fun apply() {
        if (finalRun) {
            s.hands = PracticeSession.Hands.BOTH
            s.tempo = 1.0
            s.clearRange()
        } else {
            val st = steps[step]
            s.hands = st.hands
            s.tempo = st.tempo
            val r = sections[section]
            s.setRange(r.start, r.endInclusive)
        }
    }

    /** Evaluates a finished run, advances the plan and returns a short message. */
    fun onResult(st: PracticeSession.Stats): String {
        val passed = st.total == 0 || (st.accuracy >= 85.0 && st.wrong <= 3)
        val acc = st.accuracy.toInt()
        val msg: String
        if (!passed) {
            attempts++
            msg = "Nochmal: $acc % (Ziel 85 %)"
        } else {
            attempts = 0
            when {
                finalRun -> { done = true; msg = "Ganzer Song geschafft: $acc % 🎉" }
                step < steps.size - 1 -> { step++; msg = "✓ $acc % – weiter: ${steps[step].label}" }
                section < sections.size - 1 -> { section++; step = 0; msg = "✓ Abschnitt geschafft! Weiter mit Abschnitt ${section + 1}" }
                else -> { finalRun = true; msg = "✓ Alle Abschnitte geschafft – jetzt der ganze Song!" }
            }
        }
        if (!done) apply()
        return msg
    }

    fun jumpTo(sec: Int) {
        section = sec.coerceIn(0, sections.size - 1)
        step = 0; attempts = 0; finalRun = false; done = false
        apply()
    }
}
