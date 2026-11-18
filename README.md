# Lokale KI – Chat-App für Android

Eine einfache Android-App, mit der du dich mit einer **KI unterhalten kannst, die komplett auf deinem Handy läuft**. Sie braucht kein Internet und schickt keine Daten weg.

## Funktionen

- **Modelle direkt in der App herunterladen**: Auswahl aus mehreren KI-Modellen, mit Fortschrittsanzeige. Unterbrochene Downloads lassen sich fortsetzen. Mehrere Modelle können gespeichert bleiben, und du kannst jederzeit wechseln.
- Chatfenster mit Live-Antworten (der Text erscheint, während die KI schreibt)
- Die KI merkt sich den bisherigen Gesprächsverlauf; mit „Neuer Chat“ beginnst du von vorn
- **Dateien anhängen** (Büroklammer): Textdateien wie `.txt`, `.md`, `.csv`, `.json`, `.xml` oder Quellcode. Der Inhalt geht mit deiner Frage an die KI (je nach Modell bis 8000 Zeichen pro Datei, längere Dateien werden gekürzt)
- Wahl zwischen CPU und GPU (Menü ⋮ → „GPU verwenden“)
- Helles/dunkles Design, das sich an dein System anpasst

Die KI läuft über [MediaPipe LLM Inference](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android) von Google.

## APK herunterladen

Bei jedem Push baut GitHub Actions automatisch eine APK:

1. Im Repository auf **Actions** → **Android APK bauen** → den neuesten Lauf öffnen
2. Unten unter *Artifacts* **LokaleKI-debug-apk** herunterladen und entpacken
3. Die `app-debug.apk` aufs Handy kopieren und installieren. Dafür musst du „Installation aus unbekannten Quellen“ erlauben

## KI-Modelle

Beim ersten Start öffnet sich die Modellauswahl, später erreichst du sie über Menü ⋮ → **Modelle verwalten**. Ein Modell wird einmal heruntergeladen (WLAN empfohlen) und läuft danach offline.

| Modell | Größe | RAM | Hinweis |
|---|---|---|---|
| **Qwen 2.5 · 1.5B** | 1,6 GB | ab 6 GB | Empfohlen, ordentliches Deutsch |
| Qwen 2.5 · 0.5B | 0,5 GB | ab 3 GB | klein und schnell |
| Gemma 3 · 1B | 0,6 GB | ab 4 GB | braucht einen Hugging-Face-Token |
| DeepSeek R1 Distill · 1.5B | 1,8 GB | ab 6 GB | denkt sichtbar nach, gut für Logik |
| Phi-4 mini | 3,9 GB | ab 8 GB | am klügsten, aber langsam |
| SmolLM · 135M | 0,2 GB | ab 2 GB | nur zum Testen |

**Gemma-Token:** Konto auf huggingface.co anlegen, auf der Seite `litert-community/Gemma3-1B-IT` die Lizenz akzeptieren, unter *Settings → Access Tokens* einen „Read“-Token erstellen und in der App eintragen.

Eigene MediaPipe-kompatible `.task`-Dateien lassen sich weiterhin über „Eigene .task-Datei importieren“ laden.

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
├── ModelCatalog.kt     Liste der herunterladbaren Modelle
├── ModelDownloader.kt  Download mit Fortsetzen
├── FileUtils.kt         Modell-Import und Lesen angehängter Dateien
└── ui/
    ├── ChatScreen.kt    Chat-Oberfläche (Jetpack Compose)
    └── Theme.kt
```

## Grenzen

- Kleine Modelle haben ein begrenztes Gedächtnis (je nach Modell 1280–4096 Tokens). Angehängte Dateien werden entsprechend gekürzt. Wird das Gespräch zu lang, setzt die App den Kontext zurück und meldet das
- PDFs, Word-Dateien und Bilder werden noch nicht unterstützt, nur Textdateien
- Der Chatverlauf wird nicht dauerhaft gespeichert
