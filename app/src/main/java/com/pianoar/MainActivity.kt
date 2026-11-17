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
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.pianoar.ar.CameraFeed
import com.pianoar.ar.CameraPane
import com.pianoar.ar.KeyboardCalibration
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
    private val calibration by lazy { KeyboardCalibration(prefs) }
    private var camera: CameraFeed? = null

    private var glasses: GlassesPresentation? = null
    private val pressed = BooleanArray(128)
    private var session: PracticeSession? = null
    private var currentSong: Song? = null
    private var onHome = true
    private var arActive = false
    private var phoneAnimated: View? = null
    private var phonePane: CameraPane? = null

    private var statusView: TextView? = null
    private var midiText = "MIDI: –"
    private var glassesText = "Brille: –"
    private var soundText = "Klang: lädt Klavier…"
    private var appSound = true

    private var rangeCapture = 0          // 0 off, 1 waiting for lowest key, 2 waiting for highest
    private var rangeLow = 0
    private var rangeLabel: Button? = null
    private var pendingArSong: Song? = null
    private var pendingArSession: PracticeSession? = null

    private val importDir by lazy { File(filesDir, "imports").apply { mkdirs() } }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            session?.tick(System.nanoTime())
            phoneAnimated?.invalidate()
            glasses?.animated?.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateGlasses()
        override fun onDisplayRemoved(displayId: Int) = updateGlasses()
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateTransforms() else updateGlasses()
        }
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
        if (arActive) openCamera()
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frame)
        session?.let { if (it.playing) it.togglePlay() }
        camera?.close()
        super.onPause()
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        glasses?.dismiss()
        camera?.close()
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
        if (on && rangeCapture > 0 && !fromTouch) {
            if (rangeCapture == 1) {
                rangeLow = note; rangeCapture = 2
                toast("Tiefste Taste: ${Music.noteName(note)}. Jetzt die HÖCHSTE Taste drücken.")
            } else {
                calibration.setRange(rangeLow, note)
                calibration.save()
                rangeCapture = 0
                rangeLabel?.text = rangeText()
                toast("Tastenbereich: ${rangeText()}")
            }
            return
        }
        pressed[note] = on
        if (fromTouch || appSound) {
            if (on) audio.noteOn(note, velocity) else audio.noteOff(note)
        }
        session?.onUserNote(note, on)
        if (on && session == null) glasses?.setInfo("Piano AR · ${Music.noteName(note)}")
    }

    // ───────────────────────── Screens ─────────────────────────

    private fun leavePlayer() {
        session?.let { if (it.playing) it.togglePlay() }
        audio.allOff()
        if (arActive) {
            arActive = false
            camera?.close()
        }
        phonePane = null
        phoneAnimated = null
        rangeCapture = 0
    }

    private fun showHome() {
        leavePlayer()
        onHome = true
        session = null
        currentSong = null
        configureGlasses()

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
        buttons.addView(button("📷 Kamera-AR frei") { showPlayer(null, ar = true) })
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
                song?.let { showPlayer(it, ar = prefs.getBoolean("lastAr", false)) }
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

    private fun showPlayer(song: Song?, ar: Boolean = false, keep: PracticeSession? = null) {
        if (ar && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingArSong = song
            pendingArSession = keep
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM)
            return
        }
        leavePlayer()
        onHome = false
        arActive = ar
        prefs.edit().putBoolean("lastAr", ar).apply()
        val s = keep ?: song?.let { PracticeSession(it, audio) }
        session = s
        currentSong = song
        statusView = null

        val root = column()
        val bar = row()
        val tools = row().apply { visibility = View.GONE }
        bar.addView(button("←") { showHome() })
        lateinit var play: Button
        if (s != null) {
            play = button(if (s.playing) "⏸ Pause" else "▶ Start") { s.togglePlay(); play.text = if (s.playing) "⏸ Pause" else "▶ Start" }
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
            val tempoLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(12, 0, 12, 0) }
            fun showTempo() { tempoLabel.text = "Tempo ${(s.tempo * 100).roundToInt()} %" }
            bar.addView(button("−") { s.tempo -= 0.1; showTempo() })
            bar.addView(tempoLabel)
            bar.addView(button("+") { s.tempo += 0.1; showTempo() })
            showTempo()
            s.onFinished = { st ->
                play.text = "▶ Start"
                showResults(s, st, play)
            }
        }
        bar.addView(button(if (ar) "🎵 Wasserfall" else "📷 Kamera-AR") { showPlayer(song, !ar, s) })

        if (ar) {
            bar.addView(button("📐 Kalibrieren") {
                val on = !(phonePane?.calibrating ?: false)
                phonePane?.calibrating = on
                glasses?.cameraPane?.calibrating = on
                tools.visibility = if (on) View.VISIBLE else View.GONE
                if (on) toast("Ziehe die gelben Punkte auf die 4 Ecken der Tastatur")
            })
            rangeLabel = button(rangeText()) {
                val presets = KeyboardCalibration.PRESETS
                val idx = presets.indexOfFirst { it.second == calibration.lowNote && it.third == calibration.highNote }
                val next = presets[(idx + 1) % presets.size]
                calibration.setRange(next.second, next.third)
                calibration.save()
                rangeLabel?.text = rangeText()
            }
            tools.addView(rangeLabel)
            tools.addView(button("🎹 Bereich per MIDI") {
                rangeCapture = 1
                toast("Drücke die TIEFSTE Taste deines Keyboards")
            })
            tools.addView(button("🔄 Kamera vorne/hinten") {
                calibration.useFrontCamera = !calibration.useFrontCamera
                calibration.save()
                camera?.useFront = calibration.useFrontCamera
                camera?.reopen()
                updateTransforms()
            })
            tools.addView(button("⇋ Spiegeln") {
                calibration.mirror = !calibration.mirror
                calibration.save()
                updateTransforms()
            })
            tools.addView(button("Notenbahn −") { calibration.laneLength = (calibration.laneLength - 1f).coerceAtLeast(1f); calibration.save() })
            tools.addView(button("Notenbahn +") { calibration.laneLength = (calibration.laneLength + 1f).coerceAtMost(10f); calibration.save() })
            tools.addView(button("Ecken zurücksetzen") { calibration.reset(); calibration.save(); phonePane?.overlay?.invalidate() })
        }

        bar.addView(TextView(this).apply {
            text = "  " + (song?.title ?: if (ar) "Kamera-AR – frei spielen" else "Frei spielen – Keyboard anschließen oder Tasten tippen")
            setTextColor(Color.rgb(170, 170, 190))
            textSize = 15f
        })
        root.addView(HorizontalScrollView(this).apply { addView(bar) })
        root.addView(HorizontalScrollView(this).apply { addView(tools) })

        val content: View = if (ar) {
            val feed = ensureCamera()
            CameraPane(this, feed, calibration, ::phoneRotation, interactive = true).also { p ->
                p.session = s
                p.pressed = pressed
                p.onCalibrationChanged = { glasses?.cameraPane?.overlay?.invalidate() }
                phonePane = p
                phoneAnimated = p.overlay
            }
        } else {
            PianoStageView(this).also { v ->
                v.session = s
                v.pressed = pressed
                v.onTouchNote = { n, on -> onInput(n, 90, on, fromTouch = true) }
                v.post { v.fitTo(song) }
                phoneAnimated = v
            }
        }
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        configureGlasses()
        if (ar) {
            openCamera()
            if (!prefs.getBoolean("arHintShown", false)) {
                prefs.edit().putBoolean("arHintShown", true).apply()
                AlertDialog.Builder(this)
                    .setTitle("Kamera-AR einrichten")
                    .setMessage(
                        "1. Stell das Handy so auf, dass die Kamera die ganze Tastatur sieht (z. B. auf einem Stativ schräg hinter dir oder auf dem Notenständer mit Frontkamera).\n\n" +
                        "2. Tippe auf „📐 Kalibrieren“ und ziehe die 4 gelben Punkte auf die Ecken der Tastatur.\n\n" +
                        "3. Wähle die Tastenanzahl oder nutze „Bereich per MIDI“.\n\n" +
                        "Mit XREAL-Brille siehst du das Kamerabild mit den Noten groß vor dir."
                    )
                    .setPositiveButton("Los geht's", null)
                    .show()
            }
        }
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

    // ───────────────────────── Camera AR ─────────────────────────

    private fun ensureCamera(): CameraFeed = camera ?: CameraFeed(this).also { c ->
        c.onError = { msg -> toast(msg) }
        c.onPreviewSizeChanged = { runOnUiThread { updateTransforms() } }
        camera = c
    }

    private fun openCamera() {
        val c = ensureCamera()
        c.useFront = calibration.useFrontCamera
        c.open()
    }

    private fun updateTransforms() {
        phonePane?.updateTransform()
        glasses?.cameraPane?.updateTransform()
    }

    private fun phoneRotation(): Int = when (displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun rangeText() = "🎹 ${Music.noteName(calibration.lowNote)}–${Music.noteName(calibration.highNote)}"

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
            showPlayer(song, ar = prefs.getBoolean("lastAr", false))
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
        val scanner = BleMidiScanner(this) { d ->
            found += d
            adapter.add(d.name ?: d.address)
        }
        if (!scanner.isAvailable) { toast("Kein Bluetooth verfügbar"); return }
        if (!scanner.isEnabled) { toast("Bitte Bluetooth einschalten"); return }
        AlertDialog.Builder(this)
            .setTitle("Bluetooth-MIDI suchen… (Keyboard in Pairing-Modus)")
            .setAdapter(adapter) { _, which ->
                scanner.stop()
                midi.openBluetooth(found[which])
                toast("Verbinde mit ${adapter.getItem(which)}…")
            }
            .setNegativeButton("Abbrechen", null)
            .setOnDismissListener { scanner.stop() }
            .show()
        scanner.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQ_BT -> if (granted) startBluetooth() else toast("Ohne Bluetooth-Berechtigung keine Bluetooth-MIDI-Suche")
            REQ_CAM -> if (granted) showPlayer(pendingArSong, ar = true, keep = pendingArSession)
                       else toast("Ohne Kamera-Berechtigung kein Kamera-AR")
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
                GlassesPresentation(this, display).also { it.show() }
            } catch (e: WindowManager.InvalidDisplayException) {
                null
            }
            configureGlasses()
        }
        val mode = display.mode
        glassesText = if (glasses != null)
            "Brille: ${display.name} · ${mode.physicalWidth}×${mode.physicalHeight} @ ${mode.refreshRate.roundToInt()} Hz"
        else "Brille: ${display.name} gefunden, Anzeige fehlgeschlagen"
        updateStatus()
    }

    /** Puts the right content on the glasses for the current screen. */
    private fun configureGlasses() {
        val g = glasses ?: return
        if (arActive) {
            val feed = ensureCamera()
            val pane = g.showCamera { ctx -> CameraPane(ctx, feed, calibration, ::phoneRotation, interactive = false) }
            pane.session = session
            pane.pressed = pressed
            pane.calibrating = phonePane?.calibrating ?: false
        } else {
            g.showStage()
            g.stage.session = session
            g.stage.pressed = pressed
            g.stage.post { g.stage.fitTo(currentSong) }
        }
        g.setInfo(currentSong?.let { "Piano AR · ${it.title}" } ?: "Piano AR")
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

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16, 4, 16, 4)
    }

    private fun button(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener(onClick)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_MIDI = 1
        private const val REQ_BT = 2
        private const val REQ_CAM = 3
        private val ACCENT = Color.rgb(0, 229, 255)
    }
}
