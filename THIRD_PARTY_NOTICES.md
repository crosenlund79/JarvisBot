# Third-party components

| Component | Used for | License |
|---|---|---|
| [openWakeWord](https://github.com/dscripka/openWakeWord) 0.5.1 – streaming pipeline (ported to Kotlin in `core/`) | wake-word inference logic | Apache-2.0 (code) |
| openWakeWord pretrained models `hey_jarvis_v0.1.onnx`, `melspectrogram.onnx` (from the openwakeword 0.5.1 PyPI package) | wake-word detection | **CC BY-NC-SA 4.0** (non-commercial; attribution; share-alike) |
| `embedding_model.onnx` – re-implementation of Google's [speech_embedding](https://tfhub.dev/google/speech_embedding/1) (shipped with openWakeWord) | audio embeddings | Apache-2.0 (Google model) / distributed by openWakeWord under CC BY-NC-SA 4.0 |
| [Silero VAD](https://github.com/snakers4/silero-vad) v4 `silero_vad.onnx` (as bundled with openWakeWord 0.5.1) | voice activity detection | MIT |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) (`onnxruntime-android`) | on-device inference | MIT |
| Android `SpeechRecognizer` (system service on the phone, not bundled) | speech-to-text | provided by the phone vendor / Google |

Model files are unmodified copies; SHA-256 checksums are shown in the app ("Show device / recognizer / engine info") and in every diagnostics export (`device.json`).

**Licensing consequence:** the pretrained openWakeWord models are licensed for **non-commercial use only**. That is fine for this
personal feasibility prototype. A commercial product would need a differently licensed or self-trained wake-word model.
