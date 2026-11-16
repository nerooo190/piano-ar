package com.pianoar.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Tiny polyphonic additive synth with a piano-like decay.
 * Placeholder until the sampled piano (Salamander Grand) arrives in M1.
 */
class SimpleSynth {
    private val rate = 48000
    private val lock = Any()
    private val voices = ArrayList<Voice>()
    @Volatile private var running = false
    private var thread: Thread? = null

    private class Voice(val note: Int, val step: Double, val vel: Float) {
        var phase = 0.0
        var amp = 1f
        var released = false
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "PianoAR-Synth").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    fun noteOn(note: Int, velocity: Int) {
        val freq = 440.0 * 2.0.pow((note - 69) / 12.0)
        synchronized(lock) {
            voices.removeAll { it.note == note }
            if (voices.size >= 24) voices.removeAt(0)
            voices += Voice(note, 2 * PI * freq / rate, (velocity / 127f).coerceIn(0.05f, 1f))
        }
    }

    fun noteOff(note: Int) {
        synchronized(lock) { voices.forEach { if (it.note == note) it.released = true } }
    }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val buf = FloatArray(192)
        while (running) {
            synchronized(lock) {
                for (s in buf.indices) {
                    var sum = 0.0
                    for (v in voices) {
                        val p = v.phase
                        sum += (sin(p) + 0.45 * sin(2 * p) + 0.2 * sin(3 * p) + 0.08 * sin(4 * p)) * v.amp * v.vel
                        v.phase = p + v.step
                        v.amp *= if (v.released) 0.9993f else 0.99997f
                    }
                    buf[s] = tanh(sum * 0.18).toFloat()
                }
                voices.removeAll { it.amp < 1e-4f }
            }
            track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
        }
        track.stop()
        track.release()
    }
}
