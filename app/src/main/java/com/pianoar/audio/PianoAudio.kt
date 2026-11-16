package com.pianoar.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Audio facade: the additive synth plays while the piano samples load in the background,
 * then the sampled piano takes over. If loading fails, the synth stays.
 */
class PianoAudio(private val context: Context) {
    private val synth = SimpleSynth()
    @Volatile private var sampler: PianoSampler? = null
    private val main = Handler(Looper.getMainLooper())

    var onReady: ((sampled: Boolean) -> Unit)? = null
    var isSampled = false
        private set

    fun start() {
        synth.start()
        Thread({
            try {
                val s = PianoSampler.load(context.assets)
                main.post {
                    synth.stop()
                    s.start()
                    sampler = s
                    isSampled = true
                    onReady?.invoke(true)
                }
            } catch (e: Exception) {
                Log.e("PianoAR", "Piano samples failed to load", e)
                main.post { onReady?.invoke(false) }
            }
        }, "PianoAR-Loader").start()
    }

    fun noteOn(note: Int, velocity: Int) { sampler?.noteOn(note, velocity) ?: synth.noteOn(note, velocity) }
    fun noteOff(note: Int) { sampler?.noteOff(note) ?: synth.noteOff(note) }
    fun setSustain(on: Boolean) { sampler?.setSustain(on) }
    fun allOff() { sampler?.allOff(); for (n in 0..127) synth.noteOff(n) }

    fun stop() {
        sampler?.stop()
        synth.stop()
    }
}
