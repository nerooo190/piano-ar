package com.pianoar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.pianoar.audio.PianoAudio
import com.pianoar.display.GlassesPresentation
import com.pianoar.midi.BleMidiScanner
import com.pianoar.midi.MidiHub
import com.pianoar.practice.PracticeSession
import com.pianoar.song.BuiltinSongs
import com.pianoar.song.MidiFileParser
import com.pianoar.song.Music
import com.pianoar.song.Song
import com.pianoar.ui.PianoStageView
import java.io.File
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var audio: PianoAudio
    private lateinit var midi: MidiHub
    private lateinit var displayManager: DisplayManager
    private val prefs by lazy { getSharedPreferences("pianoar", MODE_PRIVATE) }

    private var glasses: GlassesPresentation? = null
    private val pressed = BooleanArray(128)
    private var session: PracticeSession? = null
    private var stage: PianoStageViewRef = PianoStageViewRef()
    private var statusView: TextView? = null
    private var midiText = "MIDI: –"
    private var glassesText = "Brille: –"
    private var soundText = "Klang: lädt Klavier…"
    private var appSound = true
    private var onHome = true

    private val importDir by lazy { File(filesDir, "imports").apply { mkdirs() } }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            session?.tick(System.nanoTime())
            stage.phone?.invalidate()
            glasses?.stage?.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateGlasses()
        override fun onDisplayRemoved(displayId: Int) = updateGlasses()
        override fun onDisplayChanged(displayId: Int) = updateGlasses()
    }

    // ───────────────────────── Lifecycle ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        appSound = prefs.getBoolean("appSound", true)

        audio = PianoAudio(this).apply {
            onReady = { sampled ->
                soundText = if (sampled) "Klang: Salamander Grand Piano" else "Klang: einfacher Synth (Samples nicht ladbar)"
                updateStatus()
            }
            start()
        }

        midi = MidiHub(this,
            onNote = { n, v, on -> onInput(n, v, on, fromTouch = false) },
            onSustain = { on -> if (appSound) audio.setSustain(on) },
            onDevicesChanged = { names ->
                midiText = when {
                    !midi.isSupported -> "MIDI: nicht unterstützt"
                    names.isEmpty() -> "MIDI: kein Keyboard (USB anstecken oder Bluetooth suchen)"
                    else -> "MIDI: " + names.joinToString()
                }
                updateStatus()
            })
        midi.start()

        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, null)

        showHome()
        updateGlasses()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frame)
        session?.let { if (it.playing) it.togglePlay() }
        super.onPause()
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        glasses?.dismiss()
        midi.stop()
        audio.stop()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (onHome) super.onBackPressed() else showHome()
    }

    // ───────────────────────── Input ─────────────────────────

    private fun onInput(note: Int, velocity: Int, on: Boolean, fromTouch: Boolean) {
        if (note !in 0..127) return
        pressed[note] = on
        if (fromTouch || appSound) {
            if (on) audio.noteOn(note, velocity) else audio.noteOff(note)
        }
        session?.onUserNote(note, on)
        if (on && session == null) glasses?.setInfo("Piano AR · ${Music.noteName(note)}")
    }

    // ───────────────────────── Screens ─────────────────────────

    private fun showHome() {
        onHome = true
        session?.let { if (it.playing) it.togglePlay() }
        session = null
        stage.phone = null
        audio.allOff()
        glasses?.stage?.apply { session = null; fitTo(null) }
        glasses?.setInfo("Piano AR")

        val root = column().apply { setPadding(40, 24, 40, 16) }
        root.addView(TextView(this).apply {
            text = "🎹 Piano AR"
            textSize = 28f
            setTextColor(ACCENT)
        })
        statusView = TextView(this).apply { textSize = 14f; setTextColor(Color.rgb(170, 170, 190)) }
        root.addView(statusView)
        updateStatus()

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 16, 0, 8) }
        buttons.addView(button("📂 MIDI-Datei öffnen") { pickMidiFile() })
        buttons.addView(button("📶 Bluetooth-MIDI") { startBluetooth() })
        buttons.addView(button("🎹 Frei spielen") { showPlayer(null) })
        root.addView(HorizontalScrollView(this).apply { addView(buttons) })

        root.addView(CheckBox(this).apply {
            text = "App-Klang auch bei MIDI-Keyboard (aus, wenn dein Keyboard eigene Lautsprecher hat)"
            setTextColor(Color.rgb(200, 200, 215))
            isChecked = appSound
            setOnCheckedChangeListener { _, c -> appSound = c; prefs.edit().putBoolean("appSound", c).apply() }
        })

        root.addView(TextView(this).apply {
            text = "Songs"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 4)
        })

        data class Entry(val label: String, val file: File?, val song: Song?)
        val entries = BuiltinSongs.all.map { Entry("★  ${it.title}  ·  ${it.artist}", null, it) } +
            (importDir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList())
                .map { Entry("📄  ${it.nameWithoutExtension}", it, null) }

        val list = ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, entries.map { it.label })
            setOnItemClickListener { _, _, pos, _ ->
                val e = entries[pos]
                val song = e.song ?: runCatching {
                    MidiFileParser.parse(e.file!!.readBytes(), e.file.nameWithoutExtension)
                }.getOrElse { toast("Datei fehlerhaft: ${it.message}"); null }
                song?.let { showPlayer(it) }
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                val f = entries[pos].file ?: return@setOnItemLongClickListener false
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("„${f.nameWithoutExtension}“ löschen?")
                    .setPositiveButton("Löschen") { _, _ -> f.delete(); showHome() }
                    .setNegativeButton("Abbrechen", null)
                    .show()
                true
            }
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun showPlayer(song: Song?) {
        onHome = false
        val s = song?.let { PracticeSession(it, audio) }
        session = s
        statusView = null

        val root = column()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
        }
        bar.addView(button("←") { showHome() })
        if (s != null) {
            lateinit var play: Button
            lateinit var tempoLabel: TextView
            play = button("▶ Start") { s.togglePlay(); play.text = if (s.playing) "⏸ Pause" else "▶ Start" }
            bar.addView(play)
            bar.addView(button("⟲") { s.restart(); audio.allOff(); play.text = "▶ Start" })
            bar.addView(button(s.mode.label) { v ->
                s.mode = PracticeSession.Mode.entries[(s.mode.ordinal + 1) % PracticeSession.Mode.entries.size]
                (v as Button).text = s.mode.label; play.text = "▶ Start"; audio.allOff()
            })
            bar.addView(button(s.hands.label) { v ->
                s.hands = PracticeSession.Hands.entries[(s.hands.ordinal + 1) % PracticeSession.Hands.entries.size]
                (v as Button).text = s.hands.label; play.text = "▶ Start"; audio.allOff()
            })
            tempoLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(12, 0, 12, 0) }
            fun showTempo() { tempoLabel.text = "Tempo ${(s.tempo * 100).roundToInt()} %" }
            bar.addView(button("−") { s.tempo -= 0.1; showTempo() })
            bar.addView(tempoLabel)
            bar.addView(button("+") { s.tempo += 0.1; showTempo() })
            showTempo()
            bar.addView(TextView(this).apply {
                text = "  ${song.title}"
                setTextColor(Color.rgb(170, 170, 190))
                textSize = 15f
            })
            s.onFinished = { st ->
                play.text = "▶ Start"
                showResults(s, st, play)
            }
            glasses?.setInfo("Piano AR · ${song.title}")
        } else {
            bar.addView(TextView(this).apply {
                text = "  Frei spielen – Keyboard anschließen oder auf die Tasten tippen"
                setTextColor(Color.rgb(170, 170, 190))
            })
        }
        root.addView(HorizontalScrollView(this).apply { addView(bar) })

        val v = PianoStageView(this).apply {
            session = s
            pressed = this@MainActivity.pressed
            onTouchNote = { n, on -> onInput(n, 90, on, fromTouch = true) }
            post { fitTo(song) }
        }
        stage.phone = v
        root.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        glasses?.stage?.apply { session = s; pressed = this@MainActivity.pressed; fitTo(song) }
    }

    private fun showResults(s: PracticeSession, st: PracticeSession.Stats, play: Button) {
        val msg = buildString {
            appendLine("Treffer: ${st.hits} / ${st.total}  (${st.accuracy.roundToInt()} %)")
            if (s.mode == PracticeSession.Mode.PLAY) appendLine("Verpasst: ${st.missed}")
            appendLine("Falsche Tasten: ${st.wrong}")
            st.meanErrorMs?.let { appendLine("Ø Timing-Abweichung: ${it.roundToInt()} ms") }
            if (st.rightTotal > 0) appendLine("Rechte Hand: ${st.rightHits} / ${st.rightTotal}")
            if (st.leftTotal > 0) appendLine("Linke Hand: ${st.leftHits} / ${st.leftTotal}")
        }
        glasses?.setInfo("Ergebnis: ${st.accuracy.roundToInt()} % · ${st.wrong} falsch")
        AlertDialog.Builder(this)
            .setTitle(if (st.accuracy >= 90) "Super! 🎉" else "Ergebnis")
            .setMessage(msg)
            .setPositiveButton("Nochmal") { _, _ -> s.restart(); s.togglePlay(); play.text = "⏸ Pause" }
            .setNegativeButton("Zurück") { _, _ -> showHome() }
            .setNeutralButton("Schließen", null)
            .show()
    }

    // ───────────────────────── MIDI files ─────────────────────────

    @Suppress("DEPRECATION")
    private fun pickMidiFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/midi", "audio/x-midi", "audio/mid", "application/x-midi", "application/octet-stream"))
        }
        startActivityForResult(i, REQ_MIDI)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MIDI && resultCode == RESULT_OK) data?.data?.let(::importUri)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let(::importUri)
    }

    private fun importUri(uri: Uri) {
        try {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: "Import"
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val title = name.substringBeforeLast('.')
            val song = MidiFileParser.parse(bytes, title)
            val safe = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80)
            File(importDir, "$safe.mid").writeBytes(bytes)
            toast("Importiert: $title (${song.notes.size} Noten)")
            showPlayer(song)
        } catch (e: Exception) {
            toast("Import fehlgeschlagen: ${e.message}")
        }
    }

    // ───────────────────────── Bluetooth MIDI ─────────────────────────

    private fun startBluetooth() {
        val perms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(perms, REQ_BT)
            return
        }
        val found = ArrayList<BluetoothDevice>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        lateinit var dialog: AlertDialog
        val scanner = BleMidiScanner(this) { d ->
            found += d
            adapter.add(d.name ?: d.address)
        }
        if (!scanner.isAvailable) { toast("Kein Bluetooth verfügbar"); return }
        if (!scanner.isEnabled) { toast("Bitte Bluetooth einschalten"); return }
        dialog = AlertDialog.Builder(this)
            .setTitle("Bluetooth-MIDI suchen… (Keyboard in Pairing-Modus)")
            .setAdapter(adapter) { _, which ->
                scanner.stop()
                midi.openBluetooth(found[which])
                toast("Verbinde mit ${adapter.getItem(which)}…")
            }
            .setNegativeButton("Abbrechen", null)
            .setOnDismissListener { scanner.stop() }
            .create()
        dialog.show()
        scanner.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startBluetooth()
            else toast("Ohne Bluetooth-Berechtigung keine Bluetooth-MIDI-Suche")
        }
    }

    // ───────────────────────── Glasses ─────────────────────────

    private fun updateGlasses() {
        val display = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (display == null) {
            glasses?.dismiss()
            glasses = null
            glassesText = "Brille: nicht verbunden (XREAL per USB-C, DeX aus)"
            updateStatus()
            return
        }
        if (glasses?.display?.displayId != display.displayId) {
            glasses?.dismiss()
            glasses = try {
                GlassesPresentation(this, display).also { g ->
                    g.show()
                    g.stage.session = session
                    g.stage.pressed = pressed
                    g.stage.post { g.stage.fitTo(session?.song) }
                }
            } catch (e: WindowManager.InvalidDisplayException) {
                null
            }
        }
        val mode = display.mode
        glassesText = if (glasses != null)
            "Brille: ${display.name} · ${mode.physicalWidth}×${mode.physicalHeight} @ ${mode.refreshRate.roundToInt()} Hz"
        else "Brille: ${display.name} gefunden, Anzeige fehlgeschlagen"
        updateStatus()
    }

    // ───────────────────────── Helpers ─────────────────────────

    private fun updateStatus() {
        statusView?.text = "$midiText\n$glassesText\n$soundText"
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(PianoStageView.BG)
        fitsSystemWindows = true
    }

    private fun button(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener(onClick)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Holder so the frame callback can reach the current phone stage view. */
    private class PianoStageViewRef { var phone: PianoStageView? = null }

    companion object {
        private const val REQ_MIDI = 1
        private const val REQ_BT = 2
        private val ACCENT = Color.rgb(0, 229, 255)
    }
}
