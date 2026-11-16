# 🎹 Piano AR

Kostenlose Open-Source-App zum Klavierlernen für Android: fallende Noten, AR über die Kamera, Unterstützung für die **XREAL 1S** und andere USB-C-Smart-Glasses. Inspiriert von PianoVision, aber eigenständig und **komplett gratis**.

> Status: **M0**, ein Testbuild. Er prüft MIDI-Keyboard, Sound und die Ausgabe auf der Brille. Die Roadmap steht in [PLAN.md](PLAN.md).

## Download
Die neueste APK, automatisch gebaut:
**https://github.com/nerooo190/piano-ar/releases/download/nightly/PianoAR.apk**

## Testen (M0)
1. APK installieren und die App öffnen
2. MIDI-Keyboard per USB anschließen. Die Tasten leuchten auf, und die App spielt einen Ton.
3. XREAL 1S per USB-C anschließen. Auf der Brille erscheint die "Floating Stage" mit dem Keyboard.
   - Samsung: **DeX ausschalten**, sonst übernimmt DeX das Display
   - Ohne Keyboard kannst du auch auf dem Handy-Display spielen

## Bauen
Die APK wird über GitHub Actions gebaut (`.github/workflows/build.yml`). Lokal geht es mit Gradle 8.11 und JDK 17: `gradle assembleDebug`.

## Lizenz
GPL-3.0
