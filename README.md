# Lokale KI – Chat-App für Android

Eine einfache Android-App, mit der du dich mit einer **KI unterhalten kannst, die komplett auf deinem Handy läuft**. Sie braucht kein Internet und schickt keine Daten weg.

## Funktionen

- Chatfenster mit Live-Antworten (der Text erscheint, während die KI schreibt)
- Die KI merkt sich den bisherigen Gesprächsverlauf; mit „Neuer Chat“ beginnst du von vorn
- **Dateien anhängen** (Büroklammer): Textdateien wie `.txt`, `.md`, `.csv`, `.json`, `.xml` oder Quellcode. Der Inhalt geht mit deiner Frage an die KI (bis 8000 Zeichen pro Datei, längere Dateien werden gekürzt)
- Wahl zwischen CPU und GPU (Menü ⋮ → „GPU verwenden“)
- Helles/dunkles Design, das sich an dein System anpasst

Die KI läuft über [MediaPipe LLM Inference](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android) von Google.

## APK herunterladen

Bei jedem Push baut GitHub Actions automatisch eine APK:

1. Im Repository auf **Actions** → **Android APK bauen** → den neuesten Lauf öffnen
2. Unten unter *Artifacts* **LokaleKI-debug-apk** herunterladen und entpacken
3. Die `app-debug.apk` aufs Handy kopieren und installieren. Dafür musst du „Installation aus unbekannten Quellen“ erlauben

## Ein KI-Modell besorgen

Die App enthält selbst kein Modell, weil die Dateien mehrere hundert MB groß sind. Empfehlung für den Anfang:

| Modell | Größe | Hinweis |
|---|---|---|
| **Gemma3-1B-IT** (int4, `.task`) | ca. 550 MB | schnell, läuft auf den meisten Handys |
| Gemma-3n E2B (`.task`) | ca. 3 GB | deutlich klüger, braucht ein Gerät mit 6–8 GB RAM |

Download unter <https://huggingface.co/litert-community>. Für Gemma brauchst du ein kostenloses Hugging-Face-Konto und musst die Lizenz einmal akzeptieren.

Dann in der App auf **„Modell auswählen“** tippen und die heruntergeladene `.task`-Datei wählen. Die App kopiert sie einmal in ihren eigenen Speicher; danach kannst du die Datei im Download-Ordner löschen.

## Selbst bauen

Voraussetzungen: Android Studio (oder JDK 17 + Android SDK 35).

```bash
./gradlew assembleDebug
# APK liegt danach unter app/build/outputs/apk/debug/app-debug.apk
```

## Projektstruktur

```
app/src/main/java/de/localai/chat/
├── MainActivity.kt      Einstiegspunkt
├── ChatViewModel.kt     Chat-Zustand, Modell laden, Nachrichten senden
├── LlmEngine.kt         Anbindung an MediaPipe (lokale Inferenz)
├── FileUtils.kt         Modell-Import und Lesen angehängter Dateien
└── ui/
    ├── ChatScreen.kt    Chat-Oberfläche (Jetpack Compose)
    └── Theme.kt
```

## Grenzen

- Kleine Modelle haben ein begrenztes Gedächtnis (4096 Tokens). Wird das Gespräch zu lang, setzt die App den Kontext zurück und meldet das
- PDFs, Word-Dateien und Bilder werden noch nicht unterstützt, nur Textdateien
- Der Chatverlauf wird nicht dauerhaft gespeichert
