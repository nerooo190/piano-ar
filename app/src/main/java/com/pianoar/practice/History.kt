package com.pianoar.practice

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Practice history stored as JSON lines in the app's private storage. */
class History(dir: File) {

    data class Entry(
        val time: Long, val song: String, val mode: String, val hands: String, val tempo: Double,
        val accuracy: Double, val hits: Int, val total: Int, val wrong: Int, val meanErrorMs: Double?,
        val seconds: Double, val fullSong: Boolean, val memoryLevel: Int,
    ) {
        val date: LocalDate get() = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    private val file = File(dir, "history.jsonl")

    fun add(e: Entry) {
        val o = JSONObject()
            .put("t", e.time).put("song", e.song).put("mode", e.mode).put("hands", e.hands)
            .put("tempo", e.tempo).put("acc", e.accuracy).put("hits", e.hits).put("total", e.total)
            .put("wrong", e.wrong).put("sec", e.seconds).put("full", e.fullSong).put("mem", e.memoryLevel)
        e.meanErrorMs?.let { o.put("err", it) }
        file.appendText(o.toString() + "\n")
    }

    fun all(): List<Entry> = if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
        runCatching {
            val o = JSONObject(line)
            Entry(
                o.getLong("t"), o.getString("song"), o.optString("mode"), o.optString("hands"), o.optDouble("tempo", 1.0),
                o.optDouble("acc", 0.0), o.optInt("hits"), o.optInt("total"), o.optInt("wrong"),
                if (o.has("err")) o.getDouble("err") else null, o.optDouble("sec", 0.0), o.optBoolean("full", true), o.optInt("mem", 0),
            )
        }.getOrNull()
    }

    /** Consecutive days with practice, ending today or yesterday. */
    fun streak(entries: List<Entry>): Int {
        val days = entries.map { it.date }.toSet()
        var d = LocalDate.now()
        if (d !in days) d = d.minusDays(1)
        var n = 0
        while (d in days) { n++; d = d.minusDays(1) }
        return n
    }

    /** Practice minutes for each of the last [count] days (oldest first). */
    fun minutesPerDay(entries: List<Entry>, count: Int): List<Pair<LocalDate, Double>> {
        val today = LocalDate.now()
        val byDay = entries.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.seconds } / 60.0 }
        return (count - 1 downTo 0).map { today.minusDays(it.toLong()) }.map { it to (byDay[it] ?: 0.0) }
    }
}
