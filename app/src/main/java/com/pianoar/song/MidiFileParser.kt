package com.pianoar.song

/**
 * Standard MIDI File (format 0/1) parser.
 * Converts ticks to seconds with the full tempo map and assigns hands:
 * track names ("left", "links", "LH", "bass" / "right", "rechts", "RH") win,
 * otherwise tracks/channels are split by average pitch, and single-part files are split at middle C.
 */
object MidiFileParser {

    private class Raw(val tick: Long, val pitch: Int, val velocity: Int, val on: Boolean, val part: Int)

    fun parse(bytes: ByteArray, title: String): Song {
        val r = Reader(bytes)
        require(r.str(4) == "MThd") { "Keine MIDI-Datei" }
        val headerLen = r.u32()
        r.u16() // format
        val trackCount = r.u16()
        val division = r.u16()
        r.skip(headerLen - 6)
        require(division and 0x8000 == 0) { "SMPTE-Zeitbasis wird nicht unterstützt" }
        val ppq = division.toDouble()

        val events = ArrayList<Raw>()
        val tempos = ArrayList<Pair<Long, Int>>()   // tick -> microseconds per quarter
        val partNames = HashMap<Int, String>()

        var track = 0
        while (track < trackCount && r.remaining() >= 8) {
            val id = r.str(4)
            val len = r.u32()
            val end = r.pos + len
            if (id != "MTrk") { r.pos = end; continue }
            var tick = 0L
            var running = 0
            var trackName: String? = null
            while (r.pos < end) {
                tick += r.varLen()
                var status = r.peek()
                if (status < 0x80) status = running else { r.pos++; if (status < 0xF0) running = status }
                when {
                    status == 0xFF -> {
                        val type = r.u8()
                        val l = r.varLen()
                        when (type) {
                            0x51 -> tempos += tick to ((r.u8() shl 16) or (r.u8() shl 8) or r.u8()).also { r.skip(l - 3) }
                            0x03 -> trackName = r.str(l)
                            else -> r.skip(l)
                        }
                    }
                    status == 0xF0 || status == 0xF7 -> r.skip(r.varLen())
                    status >= 0x80 -> {
                        val hi = status and 0xF0
                        val ch = status and 0x0F
                        val d1 = r.u8()
                        val d2 = if (hi == 0xC0 || hi == 0xD0) 0 else r.u8()
                        if (ch == 9) continue // drums
                        val part = track * 16 + ch
                        when {
                            hi == 0x90 && d2 > 0 -> events += Raw(tick, d1, d2, true, part)
                            hi == 0x80 || (hi == 0x90 && d2 == 0) -> events += Raw(tick, d1, 0, false, part)
                        }
                    }
                    else -> r.pos = end // corrupt: skip rest of track
                }
            }
            trackName?.let { name -> (0 until 16).forEach { partNames[track * 16 + it] = name } }
            r.pos = end
            track++
        }

        // Tempo map → seconds
        tempos.sortBy { it.first }
        val segTick = ArrayList<Long>().apply { add(0L) }
        val segSec = ArrayList<Double>().apply { add(0.0) }
        val segTempo = ArrayList<Int>().apply { add(500_000) }
        for ((t, us) in tempos) {
            val last = segTick.size - 1
            val sec = segSec[last] + (t - segTick[last]) / ppq * segTempo[last] / 1e6
            if (t == segTick[last]) { segTempo[last] = us } else { segTick += t; segSec += sec; segTempo += us }
        }
        fun toSec(tick: Long): Double {
            var i = segTick.size - 1
            while (i > 0 && segTick[i] > tick) i--
            return segSec[i] + (tick - segTick[i]) / ppq * segTempo[i] / 1e6
        }

        // Pair note on/off
        class Open(val start: Long, val vel: Int)
        val open = HashMap<Int, ArrayDeque<Open>>()
        data class Pre(val pitch: Int, val start: Double, val end: Double, val vel: Int, val part: Int)
        val pre = ArrayList<Pre>()
        events.sortedWith(compareBy<Raw>({ it.tick }, { if (it.on) 1 else 0 })).forEach { e ->
            val key = e.part * 128 + e.pitch
            if (e.on) open.getOrPut(key) { ArrayDeque() }.addLast(Open(e.tick, e.velocity))
            else open[key]?.removeFirstOrNull()?.let { o ->
                pre += Pre(e.pitch, toSec(o.start), toSec(e.tick), o.vel, e.part)
            }
        }
        require(pre.isNotEmpty()) { "Keine Noten gefunden" }

        // Hand assignment
        val parts = pre.groupBy { it.part }
        val handOf = HashMap<Int, Int?>()
        for (part in parts.keys) {
            val n = partNames[part]?.lowercase() ?: ""
            handOf[part] = when {
                listOf("left", "links", "lh", "bass", "l.h").any { n.contains(it) } -> Hand.LEFT
                listOf("right", "rechts", "rh", "melod", "r.h").any { n.contains(it) } -> Hand.RIGHT
                else -> null
            }
        }
        val unnamed = parts.keys.filter { handOf[it] == null }
        if (parts.size == 1) {
            handOf[parts.keys.first()] = null // split by pitch
        } else if (unnamed.isNotEmpty()) {
            val avg = unnamed.associateWith { p -> parts[p]!!.map { it.pitch }.average() }
            val mean = avg.values.average()
            unnamed.forEach { handOf[it] = if (unnamed.size == 1) (if (avg[it]!! < 60) Hand.LEFT else Hand.RIGHT)
                                           else if (avg[it]!! < mean) Hand.LEFT else Hand.RIGHT }
        }

        val notes = pre.map { p ->
            val hand = handOf[p.part] ?: if (p.pitch < 60) Hand.LEFT else Hand.RIGHT
            Note(p.pitch, p.start, (p.end - p.start).coerceAtLeast(0.05), p.vel, hand)
        }
        // Shift so the first note starts at 0
        val first = notes.minOf { it.start }
        return Song(title, "Import", notes.map { it.copy(start = it.start - first) })
    }

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun remaining() = b.size - pos
        fun u8(): Int { if (pos >= b.size) throw IllegalArgumentException("Datei abgeschnitten"); return b[pos++].toInt() and 0xFF }
        fun peek(): Int = if (pos < b.size) b[pos].toInt() and 0xFF else throw IllegalArgumentException("Datei abgeschnitten")
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun skip(n: Int) { pos += n.coerceAtLeast(0) }
        fun str(n: Int): String { val s = String(b, pos, n.coerceAtMost(b.size - pos), Charsets.ISO_8859_1); pos += n; return s }
        fun varLen(): Int {
            var v = 0
            repeat(4) {
                val c = u8()
                v = (v shl 7) or (c and 0x7F)
                if (c and 0x80 == 0) return v
            }
            return v
        }
    }
}
