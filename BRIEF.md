# English Ai — handoff brief (read fully before touching anything)

## What this is
A finished, tested web app (PWA, single `index.html`) for practicing **American English by voice**, 100% offline after first launch. An on-device Gemma 4 E4B model (≈3 GB, file `gemma-4-E4B-it-web.task`) is loaded **by the user** from Settings → "Choose file". It is stored in the browser's private storage (OPFS) and reused on every launch.

**The UI/UX is final and approved by the user. Do NOT redesign, restyle, rename screens, or "improve" the layout.** Your job is packaging: GitHub repo + GitHub Pages + an installable Android APK. App name: **English Ai**. Suggested package id: `com.englishai.app`.

## Files
- `index.html` – the entire app (HTML/CSS/JS, no build step, no framework).
- `sw.js` – service worker (offline cache). `manifest.json` – PWA manifest (name "English Ai").
- `icon-192.png`, `icon-512.png` (transparent), `icon-maskable-512.png` (full-bleed, #14306F background).
- `android-res/` – ready Android launcher resources (mipmap-* incl. adaptive foreground, `ic_launcher_background` = #14306F). Copy into `app/src/main/res/` if building a native wrapper.

## Step 1 — Publish
Repo root = these files. Enable GitHub Pages (branch main, root). HTTPS is required for mic, service worker and storage. Verify the Pages URL opens and "Install app" appears in Chrome.

## Step 2 — APK (choose in this order)
**A (recommended): Trusted Web Activity** wrapping the Pages URL (Bubblewrap CLI inside a GitHub Actions workflow, or a minimal Gradle project with `androidx.browser` TWA). It runs inside Chrome's engine, so these keep working unchanged: WebGPU (needed by the model), `webkitSpeechRecognition` (voice input), `speechSynthesis`, OPFS (model storage), service worker. Name "English Ai", icons from this package, portrait, theme/background `#000000`. If Digital Asset Links cannot be verified (project-site URLs), a top bar may show; that is acceptable — mention it in the README.
**B (fallback): Capacitor** with the built files as `webDir`. WARNING: Android WebView has **no `webkitSpeechRecognition`**; you must add `@capacitor-community/speech-recognition` and adapt the `listen()` function in `index.html` (keep its behavior: live partial transcript, tap again to send, state names). Also confirm WebGPU works in the WebView; if `navigator.gpu` is undefined the model cannot load.
Deliver a **signed APK** (debug-signed is OK for sideloading) as a GitHub Actions artifact and a Release asset, plus the Pages URL.

## Do not change (behavior contract)
- Tap the orb (or mic button) → starts listening; tap again → sends. While thinking/speaking the same control becomes a red **Stop** that stops speech and generation immediately.
- Captions in English with the spoken word highlighted; replies stream word by word; sentence-by-sentence speech (US English voice).
- Conversations ("projects") saved in localStorage (`echo.chats`); app resumes on the last screen/conversation.
- Settings sheet: model upload, remove model, voice, speed, read-aloud, captions, hands-free, sounds, delete all.
- Gemma 4 prompt format in `Brain.reply` (`<|turn>system … <turn|>`, `<|turn>user`, `<|turn>model`). Do not switch to Gemma 3 tokens.
- MediaPipe engine is loaded from jsDelivr (`@mediapipe/tasks-genai`, versions list in `VERS`) on first online launch and cached by `sw.js`. If load fails, the Settings sheet shows the exact error — report it, don't hide it.

## Acceptance checklist
1. Opens offline (airplane mode) after one online launch.
2. Settings → Choose file accepts the 3 GB model; progress bar; status chip turns "Gemma ready · offline"; survives app restart without re-uploading.
3. Typing a message streams a reply and speaks it when "Read replies aloud" is on.
4. Stop button silences speech instantly.
5. Conversations persist after closing/reopening.
6. Launcher icon is the robot icon, label "English Ai".

## Known limits (tell the user honestly)
- Chrome's speech recognition may need internet; fully offline voice input needs an on-device recognizer (e.g. Whisper) — optional later work.
- Needs a phone with WebGPU and enough free RAM for a ~3 GB model.
- Android back button currently leaves the app; nice-to-have: map it to "close panel / return to voice".
