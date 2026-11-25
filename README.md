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

## First start

1. Open **Jarvis Proto 001** and grant **Microphone** and **Notifications**.
2. *(Recommended)* Under Control → *Request: ignore battery optimizations*, choose Allow.
3. Tick **Diagnostic Mode**, pick the recognition language (da-DK / en-US / en-GB), then tap **ENABLE Jarvis**.
4. A persistent notification "Listening for "Hey Jarvis"" appears. You can now leave the app, lock the phone, or turn the screen off.
5. Say "Hey Jarvis" → beep → say the command → pause. Or say it in one go: "Hey Jarvis, remind me to buy milk tomorrow."
6. The result appears in the **Commands** tab, and every step is in **Events**.

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
| Engine hears it but doesn't trigger | `WAKE_CANDIDATE` with `BELOW_THRESHOLD` and its peak score; `SEGMENT/CANDIDATE` audio |
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
