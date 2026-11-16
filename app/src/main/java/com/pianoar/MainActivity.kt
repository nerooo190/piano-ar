package com.pianoar

import android.app.Activity
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.pianoar.audio.SimpleSynth
import com.pianoar.display.GlassesPresentation
import com.pianoar.midi.MidiHub
import com.pianoar.ui.KeyboardView

/**
 * M0 test screen: proves MIDI input, sound, and glasses output work on the device.
 */
class MainActivity : Activity() {

    private lateinit var keyboard: KeyboardView
    private lateinit var midiStatus: TextView
    private lateinit var glassesStatus: TextView
    private lateinit var lastNote: TextView

    private lateinit var midi: MidiHub
    private lateinit var displayManager: DisplayManager
    private val synth = SimpleSynth()
    private var glasses: GlassesPresentation? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateGlasses()
        override fun onDisplayRemoved(displayId: Int) = updateGlasses()
        override fun onDisplayChanged(displayId: Int) = updateGlasses()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 12, 18))
            fitsSystemWindows = true
            setPadding(32, 24, 32, 24)
        }
        root.addView(TextView(this).apply {
            text = "Piano AR"
            textSize = 26f
            setTextColor(Color.rgb(0, 229, 255))
        })
        midiStatus = label(root)
        glassesStatus = label(root)
        lastNote = label(root).apply { textSize = 20f; setTextColor(Color.WHITE) }

        keyboard = KeyboardView(this).apply { onTouchNote = { n, on -> handleNote(n, 90, on) } }
        root.addView(keyboard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        synth.start()
        midi = MidiHub(this, ::handleNote) { names ->
            midiStatus.text = when {
                !midi.isSupported -> "MIDI: nicht unterstützt"
                names.isEmpty() -> "MIDI: kein Keyboard verbunden (USB anstecken)"
                else -> "MIDI: " + names.joinToString()
            }
        }
        midi.start()

        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, null)
        updateGlasses()
    }

    private fun label(root: LinearLayout) = TextView(this).apply {
        textSize = 15f
        setTextColor(Color.rgb(180, 180, 200))
        root.addView(this)
    }

    private fun handleNote(note: Int, velocity: Int, on: Boolean) {
        if (on) synth.noteOn(note, velocity) else synth.noteOff(note)
        keyboard.setActive(note, on)
        glasses?.keyboard?.setActive(note, on)
        if (on) {
            val text = "${KeyboardView.noteName(note)}  ·  Velocity $velocity"
            lastNote.text = text
            glasses?.setInfo("Piano AR · $text")
        }
    }

    private fun updateGlasses() {
        val display = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (display == null) {
            glasses?.dismiss()
            glasses = null
            glassesStatus.text = "Brille: nicht verbunden (XREAL per USB-C anstecken, DeX aus)"
            return
        }
        if (glasses?.display?.displayId != display.displayId) {
            glasses?.dismiss()
            glasses = try {
                GlassesPresentation(this, display).also {
                    it.show()
                    it.keyboard.copyStateFrom(keyboard)
                }
            } catch (e: WindowManager.InvalidDisplayException) {
                null
            }
        }
        val mode = display.mode
        glassesStatus.text = if (glasses != null)
            "Brille: ${display.name} · ${mode.physicalWidth}×${mode.physicalHeight} @ ${mode.refreshRate.toInt()} Hz"
        else
            "Brille: ${display.name} gefunden, Anzeige fehlgeschlagen"
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        glasses?.dismiss()
        midi.stop()
        synth.stop()
        super.onDestroy()
    }
}
