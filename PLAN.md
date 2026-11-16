# Projektplan: **Piano AR** (kostenloser PianoVision-Klon für Android)

Stand: 16.11.2026 · Version 2 · nur Planung, noch kein Code

**Rahmen (vom Nutzer festgelegt):**
- XREAL 1S **ohne** XREAL Eye
- Kein PC oder Mac, die Entwicklung läuft komplett vom Handy (Galaxy S24 Ultra) aus
- **Komplett kostenlos:** keine Käufe, kein Abo, keine Werbung, keine laufenden Serverkosten
- Name: **Piano AR**

---

## 1. Was PianoVision kann (Recherche auf pianovision.com)

| Bereich | PianoVision-Feature |
|---|---|
| Eingabe | Piano oder MIDI-Keyboard (USB-C, DIN über Adapter, BLE nur per Dongle), virtuelles Piano per Hand-Tracking |
| Kern | Fallende Noten in Mixed Reality direkt auf den echten Tasten |
| Kalibrierung | Zeigefinger auf die unterste und oberste Taste, danach Höhe und Breite feinjustieren |
| Üben | Wait Mode, Ghost Hands (zeigen die Fingerhaltung), Sheet-Music-Modus |
| Learning Engine | Teilt Songs in Snippets und baut den Übeablauf passend zum Ziel um |
| Memory Engine | Blendet Hilfen schrittweise aus |
| Statistik | Timing, Genauigkeit, Handedness pro Durchlauf und über die Zeit |
| Multiplayer | Echtzeit mit geringer Latenz |
| Inhalte | Gemeinfreie Songs, Jazz-Generator, MIDI-Import, Akkorde und Skalen, Holiday-Pack, KI-Assistent, automatische Noten und Fingersätze, MR-Umgebungen, (Plus: 10.000 lizenzierte Popsongs) |
| Extras | Desktop-App für MIDI über WLAN und Datei-Upload, eingebaute Klavier-Sounds |
| Plattform | Nur Meta Quest |

---

## 2. Neue Grundsatzentscheidungen

### 2.1 Native Android-App statt Unity
Ohne PC gibt es kein Unity, und das XREAL SDK gibt es nur für Unity. Ohne Eye bringt das SDK aber ohnehin nur 3DoF, und **die 1S stabilisiert 3DoF selbst** (der X1-Chip verankert den Bildschirm ohne App und ohne SDK).
➡️ Wir brauchen **kein XREAL SDK.** Die Brille ist für uns ein externes Display (USB-C DisplayPort), und Android steuert sie über die **Presentation API** an.

Ein Vorteil für später: Native Kotlin ist genau der Weg, den **Android XR (Jetpack XR)** vorsieht, also auch für XREAL Project Aura und andere Android-XR-Brillen.

### 2.2 Bauen ohne PC über GitHub Actions
- Auf dem Handy ist nur noch **1 GB frei**. Android SDK und Gradle brauchen über 5 GB, ein lokaler Build ist also nicht möglich.
- Lösung: Der Code liegt in einem GitHub-Repo (Account `nerooo190` ist im `gh` eingeloggt). **GitHub Actions baut die APK kostenlos**, danach `gh run download` und installieren.
- Ablauf: Claude schreibt den Code in Termux → `git push` → CI baut (etwa 3–5 Min.) → APK aufs Handy → testen.
- Debuggen: Logcat per Wireless ADB direkt auf dem Handy (`pkg install android-tools`).

### 2.3 Komplett kostenlos
- Kein Billing und keine Accounts, alles **offline-first** und lokal gespeichert (Room-Datenbank)
- Multiplayer ohne Server: **lokal** über WLAN oder Nearby Connections. Online-Multiplayer erst später über kostenloses P2P (WebRTC mit öffentlichem Signaling).
- KI-Assistent **optional mit eigenem API-Key** des Nutzers (Claude) oder on-device. Ohne Key funktioniert alles andere trotzdem.
- Lizenz: Open Source (z. B. GPL-3.0), Verteilung über GitHub Releases und F-Droid

---

## 3. Was mit XREAL 1S ohne Eye geht (ehrlich)

Die 1S weiß nicht, *wo* sie im Raum ist, nur wie der Kopf gedreht ist. Noten **exakt auf die echten Tasten** zu legen geht damit nicht direkt. Darum gibt es drei Brillen-Modi:

| Modus | Idee | Genauigkeit |
|---|---|---|
| **A: Kamera-Spiegel (Video-AR)** ⭐ | Das Handy steht auf dem Notenständer und filmt die Tasten. ARCore oder Bildanalyse legt die Noten **exakt auf die Tasten im Kamerabild**. Dieses Bild erscheint groß in der Brille, die Hände bleiben frei. | Tastengenau |
| **B: Floating Stage** | Ein virtuelles Keyboard mit Wasserfall schwebt im Blickfeld über dem echten Piano. Gedrückte Tasten leuchten per MIDI mit. Die 1S verankert den Bildschirm über 3DoF. | Optisch neben den Tasten, gut lesbar |
| **C: Notenblatt / HUD** | Scrollende Noten, Fingersätze und Statistik als ruhige Anzeige | Kein Overlay |

Ohne Brille gibt es zusätzlich:
- **Phone AR:** Handy-Display mit Kamerabild und Overlay
- **Classic 2D:** Wasserfall wie Synthesia, auch auf Tablets

Später, sobald es Android-XR-Brillen gibt: echtes optisches 6DoF-Overlay mit Hand-Tracking über Jetpack XR. Die Architektur ist darauf vorbereitet.

---

## 4. Tech-Stack (alles kostenlos, in CI baubar)

| Schicht | Technologie |
|---|---|
| Sprache und UI | Kotlin, Jetpack Compose (Menüs) |
| Rendering | OpenGL ES 3 (eigener Wasserfall-Renderer, 60–120 fps) |
| Brille | `DisplayManager` + `Presentation` (zweites Display), eigenes Layout für das 16:10-Format der 1S |
| AR | ARCore SDK (Ebenen-Erkennung, Kamerabild), dazu OpenCV- und Tastenerkennung (Schwarz-Weiß-Muster der Tasten) für die automatische Kalibrierung |
| Hand-Tracking | MediaPipe Hand Landmarker (Kamera), für Fingersatz-Prüfung und virtuelles Piano |
| MIDI | `android.media.midi`: **USB- und Bluetooth-LE-MIDI nativ**, Hotplug |
| Sound | Phase 1: AudioTrack + gesampeltes Klavier (Salamander Grand, CC-BY). Phase 2: Oboe/NDK Low-Latency. |
| Akustisches Klavier | Mikrofon mit Basic Pitch (Spotify, Apache-2.0, TFLite) für polyphone Tonerkennung |
| MIDI-Dateien | Eigener SMF-Parser (Kotlin, klein), dazu MusicXML-Import |
| Notenblatt | Verovio (läuft als WebView/JS, keine NDK-Kompilierung nötig) |
| Datenbank | Room (Songs, Statistik, Kalibrierprofile) |
| Multiplayer | Nearby Connections / UDP im LAN |
| Build | Gradle in **GitHub Actions** (`ubuntu-latest`), signierte Release-APK als Artefakt |

---

## 5. Feature-Liste (alle PianoVision-Features, kostenlos)

### Eingabe
- [ ] USB-MIDI und **Bluetooth-MIDI direkt** (PianoVision braucht dafür einen Dongle)
- [ ] Akustisches Klavier per Mikrofon
- [ ] Touch-Piano auf dem Display
- [ ] Virtuelles Piano auf dem Tisch (Kamera + MediaPipe, experimentell)
- [ ] Latenz-Kalibrierung (Tap-Test)

### Kalibrierung
- [ ] **MIDI-assistiert:** tiefste und höchste Taste drücken, Tastenanzahl wird erkannt (25/49/61/76/88)
- [ ] **Automatische Tastenerkennung im Kamerabild** (Modus A): Ränder und schwarze Tasten finden
- [ ] Manuell: zwei Punkte im Bild antippen, dann Höhe und Breite feinjustieren
- [ ] Profile pro Instrument und Aufstellort

### Üben
- [ ] Fallende Noten, Handfarben, Notennamen, Tastenleuchten
- [ ] **Wait Mode**, Play-Along, Tempo 25–150 %, Tempo-Ramp
- [ ] Hände getrennt (die andere Hand spielt die App), A/B-Loop, Metronom, Count-in
- [ ] **Ghost Hands** (eingeblendete Hände mit Fingersatz)
- [ ] **Learning Engine:** Snippets, Übe-Plan, schwache Stellen automatisch wiederholen
- [ ] **Memory Engine:** 5 Stufen, in denen die Hilfen verschwinden
- [ ] **Sheet-Music-Modus** mit mitlaufender Markierung
- [ ] **Automatischer Fingersatz** (Dynamic Programming)
- [ ] **Automatisch erzeugte Noten** aus MIDI

### Inhalte (alles frei)
- [ ] Gemeinfreie Klassik und Volkslieder (Mutopia, CC0)
- [ ] **Unbegrenzter MIDI- und MusicXML-Import** direkt vom Handy (Dateien, Downloads, Teilen-Menü)
- [ ] Jazz-Generator, Akkorde, Skalen, Arpeggien, Übungen, Holiday-Pack

### Statistik
- [ ] Timing in ms, Treffer, falsche und fehlende Noten, Velocity, je Hand
- [ ] Heatmap pro Stück, Verlauf, Streaks, Tagesziel

### Social und Extras
- [ ] Lokaler Multiplayer: Duett, Lehrer sieht die Tasten des Schülers
- [ ] KI-Assistent (optional, mit eigenem Key)
- [ ] Aufnahme: MIDI, Audio und Video (Modus A)
- [ ] Themes und Umgebungen (Hintergründe in Modus B und Classic)
- [ ] Deutsch und Englisch

---

## 6. Architektur

```
app/
├── core/        SongClock (Audio-Zeit als Master), Settings, Room-DB
├── input/       MidiSource ← UsbBleMidi, MicPitch, TouchPiano, HandPiano
├── song/        SmfParser, MusicXml, Fingering, Segmenter, Quantizer
├── practice/    WaitMode, PlayMode, Loop, LearningEngine, MemoryEngine, Scoring
├── render/      GL-Waterfall, Keyboard, GhostHands, SheetView (Verovio)
├── display/     DisplayRouter: Phone | XREAL/externes Display (Presentation) | später Android XR
├── ar/          ARCore-Session, KeyDetector (OpenCV), Kalibrierung
├── audio/       PianoSampler, Metronom
├── social/      NearbyMultiplayer, AiAssistant
└── ui/          Compose: Home, Bibliothek, Player, Statistik, Einstellungen
.github/workflows/build.yml   → APK-Artefakt
```

---

## 7. Meilensteine

| Phase | Inhalt | Ergebnis |
|---|---|---|
| **M0: Setup** | GitHub-Repo, CI-Workflow, "Hello Piano AR"-APK über Actions installieren | Build-Pipeline läuft |
| **M1: Spielbar** | MIDI-Parser, GL-Wasserfall, Klavier-Sound, USB- und BLE-MIDI, Wait und Play Mode, Scoring | Classic-2D-App |
| **M2: Brille** | Ausgabe auf die XREAL 1S (Presentation), Modus B (Floating Stage) und C, Handy als Fernbedienung | XREAL-1S-Support |
| **M3: AR** | ARCore-Kamera, Tastenerkennung, Kalibrierung, Modus A (Kamera-Spiegel in der Brille) und Phone AR | Tastengenaues AR |
| **M4: Lernen** | Learning und Memory Engine, Fingersatz, Notenblatt, Ghost Hands, Statistik | Feature-Parität |
| **M5: Extras** | Mikrofon-Modus, Jazz-Generator, Akkorde und Skalen, lokaler Multiplayer, KI, Aufnahme | Vollversion |
| **M6: Release** | Tests mit verschiedenen Keyboards, Performance, GitHub Release und F-Droid | v1.0 |

---

## 8. Risiken

| Risiko | Gegenmaßnahme |
|---|---|
| Kein exaktes optisches Overlay ohne Eye | Modus A (Video-AR) als Hauptmodus, Android XR später |
| Wenig Speicher auf dem Handy (1 GB) | Kein lokaler Build, alles über GitHub Actions |
| Ohne PC fehlt Android Studio und Layout-Vorschau | Screenshots per ADB/Logcat, kleine Iterationen |
| Latenz bei Bluetooth und Mikrofon | Latenzmessung, Oboe in Phase 2 |
| Tastenerkennung bei schlechtem Licht | Manuelle 2-Punkt-Kalibrierung als Fallback |
| Lizenzen | Nur gemeinfreie und CC-Inhalte mitliefern, Popsongs nur per Nutzer-Import |
| Marke | Eigener Name "Piano AR", eigenes Design |

---

## 9. Nächster Schritt
**M0** starten: ein (öffentliches?) GitHub-Repo `piano-ar` anlegen, das Kotlin-Grundgerüst und den CI-Workflow schreiben, die erste APK bauen und installieren.
