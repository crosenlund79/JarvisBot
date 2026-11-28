# Project J.A.R.V.I.S. – Prototype 001 "Wake Up, Jarvis"

A technical feasibility experiment, **not** a product. It answers one question: can an Android phone reliably hear
"Hey Jarvis", capture the spoken command that follows, and turn it into text hands-free? And when it can't, why not?

## Install

* **Newest build (direct phone download):**
  https://github.com/crosenlund79/JarvisBot/releases/latest/download/jarvis-proto001-latest.apk
* All builds: https://github.com/crosenlund79/JarvisBot/releases. Every CI run also uploads the APK as an Actions artifact.
* On the phone, open the downloaded APK and allow "Install unknown apps" for your browser or file manager when Android asks.
* Updates install over the previous build (same application ID `dk.jarvisbot.app` and the same signing key), so your recordings and logs are kept.

The APK contains ARM libraries only (`arm64-v8a`, `armeabi-v7a`), which covers practically all phones.

## Main screen (build 6+)

One screen holds everything needed for everyday testing:

* **START / STOP LISTENING**, the live level bar (orange line = recording threshold) and listening / saving status.
* **Sensitivity slider**: sounds louder than the threshold are saved, including the 1 s before them. A recording ends after 2 s of quiet.
* **Raw 30 s microphone test**: saves everything for 30 s, ignoring the threshold.
* **Image**: *Choose Image* (gallery or files) and *Show Image* (full screen, aspect ratio kept). Listening continues while the image is shown.
* **Diagnostic folder** (normally Documents/Jarvis/Dumps): recordings, diagnostics, transcriptions and daily logs, organised by day and hour, ready to copy over USB.
* **Export**: a ZIP with *Raw*, *Voice-enhanced* or *Both*, plus all diagnostic logs.
* **Storage**: one limit for all Jarvis data (500 MB – 10 GB, default 2 GB), usage, *Clean up…*; the **recording list** (tap = play, long-press = recognize / analyze / label / delete), and the **speech recognition language**.

**Wake word & recognition** opens the original wake-word prototype: enable/disable, wake threshold, Diagnostic Mode, recognizer tests, Commands and Events.

Technical parameters (audio source, buffers, timings, thresholds …) are no longer shown. They use internal defaults, any earlier saved values are kept, and everything is recorded in the logs.

### Diagnostic folder (USB retrieval)
1. Main screen → **Diagnostic folder** → **Choose folder…** → in the picker open *Documents*, create *Jarvis* and then *Dumps*, open it, tap **Use this folder** → **Allow**.
2. Jarvis writes everything there, organised by the **original recording time** (phone's local time):
   ```
   Documents/Jarvis/Dumps/
     2026-11-25/
       08/
         2026-11-25_08-12-03-517_raw.wav            original audio, never processed
         2026-11-25_08-12-03-517_diagnostics.json   levels, threshold, events, recognitions, transcription
       09/ …
       2026-11-25_Transcription.txt                 all transcriptions of the day, in order
       Daily_Diagnostics.json                       device/app info, recording settings, storage, sessions,
                                                    interruptions, process exit reasons, counts, cleanups
       Daily_Events.csv                             the complete event log of the day
       Daily_Recognitions.csv                       every recognition attempt incl. errors
       2026-11-25_08-00-01-123_session-log.csv      listening session log (+ _session.json at Stop)
     2026-11-26/ …
   ```
   * Wake-word screen recordings carry their type in the name (`_wake`, `_command`, …).
   * Daily files are refreshed every 30 min and when listening stops.
   * A recording's diagnostics are rewritten once it has been transcribed.
3. **USB**: connect the phone, choose *File transfer* in Android's USB notification, then on the PC open
   *This PC › [phone] › Internal shared storage › Documents › Jarvis › Dumps* and copy the day folders.
   You may delete copied folders from the phone; Jarvis does not copy a recording twice.
4. Files are written under a hidden `.…partial` name and renamed when complete, so incomplete files never appear.
5. If the folder is unreachable, recordings stay in the app and are copied later.
6. When the folder is chosen, and once after updating from build 11 or older:
   * The old flat layout is reorganised into day/hour folders. Files are moved, and a copy is verified by size and SHA-256 before an original is removed.
   * Recordings still in the app that were never delivered are copied.
7. Sync apps still work (e.g. OneSync, folder pair *including subfolders*). With "Upload then delete", Jarvis keeps its phone copies until it has verified the folder copy.

### Storage limit
One limit (500 MB, 1 GB, **2 GB default**, 5 GB, 10 GB) covers all Jarvis data:
* recordings on the phone;
* logs and transcriptions;
* Jarvis's files in the diagnostic folder.

Exported ZIPs in the folder are shown but not counted or deleted. Android always keeps at least 1 GB (or 5 % of the storage) free. When over the limit, or low on space:
1. Phone copies of recordings already verified in the diagnostic folder are removed first (they are duplicates).
2. Then the oldest recordings and session logs are deleted from the phone and the folder; labelled recordings go last.
3. Daily transcription and diagnostics files are kept.

Each cleanup is shown on the main screen and logged (`STORAGE_CLEANUP`). Recordings whose phone copy was removed still play, export and transcribe from the folder copy.

### Voice-enhanced export
Choose *Voice-enhanced* or *Both* before exporting. Each enhanced copy is computed only during that export, by the built-in method `jarvis-speech-v1`, in pure Kotlin and fully offline:
1. 80 Hz high-pass filter (removes rumble).
2. Wiener noise suppression, as in WebRTC-style denoisers. The noise is estimated from the quietest 15% of the recording, and suppression is capped at 12 dB so quiet speech and consonants survive.
3. +3 dB presence lift between 1.5 and 4.5 kHz.
4. Gentle 1.5:1 compression.
5. Normalisation to about −20 dBFS.
6. Soft limiter at −1 dBFS.

Files have matching names (`…_raw.wav`, `…_enhanced.wav`, `…_diagnostics.json`) and the ZIP uses the same day/hour folders as the diagnostic folder (Save ZIP… starts in that folder). The JSON records the method and its measurements. If enhancement fails, the original is exported instead and the failure is reported. Original recordings are never modified.

### Daily transcription
One setting on the main screen, under *Transcription*:
* **Off**
* **Automatic**: every completed recording is transcribed in the background, newest first.
* **During export only**: transcription happens when you make a ZIP.

Recognition is fully offline: OpenAI's **Whisper small** (multilingual), int8 ONNX, bundled in the APK (about 255 MB). It recognises Danish and English and picks the more likely of the two for each piece of speech.
1. Silero VAD first looks for speech. Audio without speech is never sent to Whisper (Whisper tends to invent text for noise) and is listed as **[NO SPEECH RECOGNIZED]**.
2. Long recordings are cut at quiet points into chunks of at most 20 s.
3. Each chunk is decoded greedily without timestamps. The text is exactly what the engine produced.

Results go into one UTF-8 file per day, named after the recording's own date: `2026-11-25_Transcription.txt`.
* Entries are in recording order.
* Each entry has the start/end time and the audio file name (as in the diagnostic folder and ZIP), plus the language and the recognised text.
* An entry also shows the voice-enhanced transcription if one was made, and uncertainty notes: low confidence, possibly not speech, language unsure, repetitive output, errors.
* Reprocessing replaces an entry; it never adds a second one.
* The daily file is written to the day's folder in the diagnostic folder (complete before it appears, at most every 2 min while busy) and included in ZIP exports.
* An enhanced transcription is made when you export with *Voice-enhanced* or *Both*, because enhanced audio only exists during an export.
* **View daily transcription…** shows a file on the phone.

Transcription runs on one low-priority background thread and never touches the microphone. The model is loaded on demand and released after 5 min idle.

To build locally, first run `tools/whisper/fetch_model.sh`. It downloads the model at a pinned revision, verifies its checksums, and shrinks the decoder for the phone (`tools/whisper/shrink_decoder.py`). CI does the same.

## First start (wake word)

1. Open **Jarvis Proto 001** and grant **Microphone** and **Notifications**.
2. Pick the recognition language (da-DK / en-US / en-GB) at the bottom of the main screen, then tap **Wake word & recognition**.
3. *(Recommended)* Tap **Allow background use** and choose Allow.
4. Tick **Diagnostic Mode**, then tap **ENABLE wake word**.
5. A persistent notification "Listening for "Hey Jarvis"" appears. You can now leave the app, lock the phone, or turn the screen off.
6. Say "Hey Jarvis" → beep → say the command → pause. Or say it in one go: "Hey Jarvis, remind me to buy milk tomorrow."
7. The result appears in the **Commands** tab, and every step is in **Events**.

The notification has two buttons:
* **Missed it!** saves the last 15 s of audio, with wake-word scores, as a `MISSED_MARK` recording.
* **Stop** disables Jarvis.

## How it works

```
AudioRecord 16 kHz mono PCM16  (one shared capture inside a microphone foreground service)
  └─ every 80 ms chunk ─► level meter ─► 20 s rolling buffer
                        ─► Silero VAD (speech probability)
                        ─► openWakeWord: melspectrogram → embedding → hey_jarvis_v0.1 → score 0..1
                        ─► state machine:
                             STANDBY  ── score ≥ threshold ──► beep, save WAKE clip (3 s before activation)
                             COMMAND  ── pre-roll (1.5 s before activation) + audio until 1.3 s silence / 10 s max / 5 s no speech
                                       ─► save COMMAND WAV ─► SpeechRecognizer fed with that exact WAV ─► text
                        ─► Diagnostic Mode: every speech / wake-candidate region saved as a SEGMENT WAV
```

* **Wake word.** openWakeWord's pretrained `hey_jarvis_v0.1` model, running fully offline. The streaming pipeline was ported to Kotlin and checked against the original Python implementation on test clips: the scores are numerically identical (`core/src/test`).
* **Continuous sentences.** The command recording starts 1.5 s *before* the activation point. "Hey Jarvis, remind me…" is therefore never cut off; the wake phrase is stripped from the transcript afterwards (the raw transcript is kept too).
* **Speech recognition.** Android `SpeechRecognizer`, which is a system service, not bundled. It is given the **saved WAV itself** through `EXTRA_AUDIO_SOURCE` (Android 13+).
  * After recognition the app checks how many bytes the recognizer actually read from the file (`audio_binding` = `FULL` / `PARTIAL` / `NOT_READ`).
  * If it read nothing, the transcript is stored only as `unverified_text` and is **never** attached to the recording.
* **Recognizer modes** (Settings):
  * `ON_DEVICE` (default): `createOnDeviceSpeechRecognizer`, offline. Needs Android 12+ and the language pack installed. Use *Check language support* and *Request on-device model download* on the Control tab.
  * `DEFAULT_SERVICE` (explicit opt-in with a confirmation): the phone's default recognizer, usually Google, which **may send audio to its servers**.
* **Privacy.** The app has **no INTERNET permission** (it is explicitly removed from the manifest), so it cannot upload anything itself. All data stays in app-private storage until you export it.

## Diagnostic tools (Control tab)

| Tool | Purpose |
|---|---|
| Live status | Service and microphone state, OS-silenced flag, digital-silence flag, level (dBFS), VAD, wake score (current / 5 s max), threshold, engine init status, heartbeat age, last error, battery drain %/h |
| Start/Stop manual recording | Records via the same pipeline, without the wake word |
| Recognize last manual recording | Tests speech recognition on stored audio, independent of the wake word |
| Live recognizer test | The recognizer uses the mic itself (pipeline paused). Isolates "is the recognizer working at all?" No stored audio. |
| Raw 10/30/60 s | Unconditional raw capture. Answers "is the mic delivering anything?" when VAD seems dead |
| It MISSED my "Hey Jarvis" | Saves the last 15 s plus score/VAD/level traces |
| Threshold slider | Applies live |
| Check language support / Request model download | Android 13+ recognizer APIs |

* **Recordings tab:** tap to play. Long-press to recognize, label (`MISSED_WAKE`, `FALSE_WAKE`, `CORRECT_WAKE`, `NOT_WAKE`), add a note, see details or delete.
  * Long-press → **Analyze with wake engine (offline)** runs a fresh wake-word and VAD instance over that WAV and reports the peak score and its time. Record yourself saying "Hey Jarvis" manually, then analyze it: this tells you whether the engine recognizes *your* voice at all, independent of the live microphone path.
* **Commands tab:** tap plays the exact recognized audio. Long-press to correct the text, mark a false or correct activation, or re-recognize.
* **Events tab:** filterable technical log; tap an event for its full JSON.

### What gets recorded

* **Always:**
  * events (`LEVEL` every 10 s, also during silence; `HEARTBEAT`)
  * `WAKE_CANDIDATE` for every score peak ≥ the candidate threshold (0.15), with outcome `TRIGGERED` / `BELOW_THRESHOLD` / `SUPPRESSED_*`
  * `SPEECH_REGION` for every VAD speech region
  * `WAKE` clips and `COMMAND` recordings
* **Diagnostic Mode adds** `SEGMENT` WAVs of every speech or candidate region, with category `TRIGGERED` / `CANDIDATE` / `SPEECH` and per-80 ms wake-score and VAD traces. This is how you find "Hey Jarvis" attempts that did not trigger.
* **Storage limit** (default 500 MB, in Settings): the oldest recordings are deleted first, unlabelled before labelled.

### Telling the failure modes apart

| Symptom | Where to look |
|---|---|
| Mic delivers nothing | `MIC_INIT_FAILED`, `MIC_START_FAILED`, `MIC_PERMISSION_DENIED`; `LEVEL.zeroChunks`; `MIC_DIGITAL_SILENCE_START`; raw capture |
| Mic too quiet | `LEVEL.avgDb/maxDb` (speech near the phone is typically −40…−20 dBFS); try the `gain` or `audioSource` settings |
| Speech not detected | `SPEECH_REGION` missing while `LEVEL.maxDb` rises; listen to a raw capture |
| Wake engine not processing | `WAKE_ENGINE_INIT_FAILED`, `WAKE_ENGINE_ERROR`; `LEVEL.wakeChunksProcessed` should equal `LEVEL.chunks` |
| Engine hears it but doesn't trigger | `WAKE_CANDIDATE` with `BELOW_THRESHOLD` and its peak score; `SEGMENT/CANDIDATE` audio; offline analysis of a manual recording |
| Wake OK, command recording fails | `COMMAND_RECORDING_STOPPED.reason` (`NO_SPEECH_TIMEOUT`…), `RECORDING_SAVE_FAILED` |
| Recording OK, recognition fails | `RECOGNITION_ERROR` (`ON_DEVICE_UNAVAILABLE`, `ERROR_LANGUAGE_UNAVAILABLE`, …), `recognition_errors.csv` |
| Wrong text | Commands tab: correct it → `corrections.csv` |
| Android killed / froze the service | `SUSPECTED_UNCLEAN_TERMINATION` (heartbeat gap), `PROCESS_EXIT_REASON` (Android's own reason, Android 11+), `SERVICE_RESTARTED_BY_SYSTEM`, `AUDIO_GAP`, `AUDIO_STALL`, `FGS_START_FAILED` |
| Another app took the mic | `MIC_CLIENT_SILENCED` (Android 10+), `RECORDING_CLIENTS_CHANGED`, `MIC_DIGITAL_SILENCE_*`, `AUDIO_MODE_CHANGED` (calls) |

## Export

Export tab → **Save diagnostics ZIP…** (Android document picker: Downloads, Drive, USB…) or **Share diagnostics ZIP…** (share sheet: e-mail, Nearby Share…).
Nothing is ever sent without that action. The ZIP contains `README.txt` describing every file:

* `device.json`, `settings.json`, `exit_reasons.json`, `storage.json`
* `recordings.json/.csv`, `recognitions.json/.csv`, `commands.csv`, `corrections.csv`, `recognition_errors.csv`
* `events.jsonl/.csv` plus subsets: `wake_events.csv`, `microphone_events.csv`, `service_events.csv`, `recognition_events.csv`, `errors.csv`
* `audio/*.wav`

To get it to a computer:
* Save to *Downloads*, then copy over USB.
* Or share it to e-mail or cloud storage yourself.
* Or with `adb`: export to Downloads, then run `adb pull /sdcard/Download/jarvis-diagnostics-*.zip`.

## Known Android restrictions

* **Starting the listener.** Android 14+ only allows a *microphone* foreground service to be **started while the app is visible**. Jarvis is started from the app and keeps running afterwards. After a reboot, or if Android kills the process, open the app once: it restarts listening automatically when Jarvis is enabled. Background restarts may be refused; this is logged as `FGS_START_FAILED`.
* **Battery management.** Vendor battery managers (Samsung, Xiaomi, OnePlus, …) may still kill or freeze the app. Allow "ignore battery optimizations", and on Samsung set the app to *Unrestricted*.
* **Privacy indicator.** Android shows its green microphone indicator the whole time Jarvis listens.
* **Mic priority.** Phone calls, other recording apps and the system assistant take priority over us. Our capture is then silenced and logged.
* **Recorded-audio recognition** requires Android 13+. On older versions only the live recognizer test works.

## Build locally

```bash
./gradlew :app:assembleRelease     # APK → app/build/outputs/apk/release/app-release.apk
JARVIS_TESTDATA=/path/to/testdata ./gradlew :core:test   # optional parity test vs. Python openWakeWord
```

* Requires JDK 17+ and the Android SDK (platform 35).
* `JARVIS_ABIS=x86_64` builds an emulator APK.

## Signing key

`keystore/jarvis-dev.jks` is a **development key committed on purpose**, so every CI build has the same signature and updates install
without losing data. Because the repository is public, anyone could sign an APK with it. Only install APKs from this repository's
Releases or Actions. Moving to a private key later (stored in GitHub Secrets via the `JARVIS_KEYSTORE_*` env vars) requires one uninstall/reinstall.

## Licences

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The pretrained openWakeWord models are **CC BY-NC-SA 4.0 (non-commercial)**, which is fine for this personal prototype.
