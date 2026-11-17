package com.pianoar.practice

/**
 * Display options shared by all views.
 * Memory Engine: each level removes more help until you play from memory.
 */
object Visual {
    var memoryLevel = 0
    var showFingers = true

    val memoryLabels = arrayOf(
        "Alle Hilfen",
        "Ohne Namen & Finger",
        "Noten nur kurz vorher",
        "Nur Tastenleuchten",
        "Aus dem Gedächtnis",
    )

    val showLabels get() = memoryLevel == 0
    /** Seconds of song time user notes stay visible before they must be played (null = always). */
    val userNotePreview: Double? get() = when (memoryLevel) { 0, 1 -> null; 2 -> 0.7; else -> 0.0 }
    val showKeyGuide get() = memoryLevel <= 3
}
