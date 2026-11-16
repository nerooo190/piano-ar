package com.pianoar.audio

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Sampled grand piano (Salamander Grand Piano, CC-BY 3.0, one sample every minor third).
 * Notes between samples are pitch-shifted by resampling; sustain pedal supported.
 */
class PianoSampler private constructor(private val nearest: Array<Sample?>) {

    class Sample(val data: ShortArray, val rate: Int, val root: Int)

    private class Voice(val note: Int, val s: Sample, val step: Double, val gain: Float) {
        var pos = 0.0
        var env = 1f
        var releasing = false
        var keyDown = true
    }

    private val outRate = 48000
    private val lock = Any()
    private val voices = ArrayList<Voice>()
    private var sustain = false
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "PianoAR-Sampler").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    fun noteOn(note: Int, velocity: Int) {
        val s = nearest.getOrNull(note) ?: return
        val step = 2.0.pow((note - s.root) / 12.0) * s.rate / outRate
        val gain = (velocity / 127f).coerceIn(0.03f, 1f).pow(1.5f)
        synchronized(lock) {
            voices.forEach { if (it.note == note) it.releasing = true }
            if (voices.size >= 64) voices.removeAt(0)
            voices += Voice(note, s, step, gain)
        }
    }

    fun noteOff(note: Int) = synchronized(lock) {
        voices.forEach {
            if (it.note == note && it.keyDown) {
                it.keyDown = false
                if (!sustain) it.releasing = true
            }
        }
    }

    fun setSustain(on: Boolean) = synchronized(lock) {
        sustain = on
        if (!on) voices.forEach { if (!it.keyDown) it.releasing = true }
    }

    fun allOff() = synchronized(lock) { voices.forEach { it.releasing = true } }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(outRate)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBuf)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val buf = FloatArray(192)
        while (running) {
            synchronized(lock) {
                buf.fill(0f)
                val it = voices.iterator()
                while (it.hasNext()) {
                    val v = it.next()
                    val d = v.s.data
                    var alive = true
                    for (i in buf.indices) {
                        val idx = v.pos.toInt()
                        if (idx + 1 >= d.size) { alive = false; break }
                        val frac = (v.pos - idx).toFloat()
                        val x = (d[idx] * (1 - frac) + d[idx + 1] * frac) / 32768f
                        buf[i] += x * v.gain * v.env
                        v.pos += v.step
                        if (v.releasing) v.env *= 0.9996f
                    }
                    if (!alive || v.env < 1e-4f) it.remove()
                }
                for (i in buf.indices) buf[i] = tanh(buf[i] * 0.7f)
            }
            track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
        }
        track.stop()
        track.release()
    }

    companion object {
        private const val MAX_SECONDS = 8

        fun load(assets: AssetManager): PianoSampler {
            val samples = assets.list("piano").orEmpty().filter { it.endsWith(".mp3") }.map { name ->
                assets.openFd("piano/$name").use { fd ->
                    val (pcm, rate) = decode(fd.fileDescriptor, fd.startOffset, fd.length)
                    Sample(pcm, rate, rootOf(name.removeSuffix(".mp3")))
                }
            }
            require(samples.isNotEmpty()) { "Keine Samples" }
            val nearest = Array(128) { n -> samples.minByOrNull { abs(it.root - n) } }
            return PianoSampler(nearest)
        }

        /** "C4", "Ds4", "Fs1", "A0" → MIDI note */
        private fun rootOf(name: String): Int {
            val base = when (name[0]) { 'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9; 'B' -> 11; else -> 0 }
            val sharp = name.getOrNull(1) == 's'
            val oct = name.substring(if (sharp) 2 else 1).toInt()
            return 12 * (oct + 1) + base + if (sharp) 1 else 0
        }

        private fun decode(fd: java.io.FileDescriptor, offset: Long, length: Long): Pair<ShortArray, Int> {
            val ex = MediaExtractor()
            ex.setDataSource(fd, offset, length)
            ex.selectTrack(0)
            val format = ex.getTrackFormat(0)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var out = ShortArray(rate * MAX_SECONDS)
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val bb = codec.getOutputBuffer(o)!!
                    bb.position(info.offset)
                    bb.limit(info.offset + info.size)
                    val sb = bb.order(ByteOrder.nativeOrder()).asShortBuffer()
                    while (sb.remaining() >= channels && n < out.size) {
                        var sum = 0
                        repeat(channels) { sum += sb.get() }
                        out[n++] = (sum / channels).toShort()
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || n >= out.size) outDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (out.size != rate * MAX_SECONDS && n == 0) out = ShortArray(rate * MAX_SECONDS)
                }
            }
            codec.stop(); codec.release(); ex.release()
            // Trim MP3 encoder delay / leading silence so notes attack immediately.
            var startIdx = 0
            while (startIdx < n && abs(out[startIdx].toInt()) < 150) startIdx++
            startIdx = (startIdx - 16).coerceAtLeast(0)
            return out.copyOfRange(startIdx, n) to rate
        }
    }
}
