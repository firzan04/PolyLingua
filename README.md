# PolyLingua Edge

**Offline-first IELTS & CEFR speaking coach for Android**, tuned for the POCO M4 Pro 4G (MediaTek
Helio G96, 90 Hz AMOLED, MIUI). It combines Stimuler-style IELTS drills and full mocks,
ELSA-style phoneme scoring, HelloTalk/Tandem-style conversation (humans online, on the LAN, or
12 offline AI natives), Cake-style shadowing and a classic quiz/flashcard arena.

**Latest signed APK:** <https://github.com/firzan04/PolyLingua/releases/latest/download/PolyLingua_POCO_M4_Pro_Release.apk>

> Scores are automated estimates for practice. IELTS® is a trademark of its owners; this app is
> not affiliated with them. Descriptors in the app are original paraphrases.

## What works offline

| Module | Highlights |
|---|---|
| **Stimuler arena** | 60-second drill or full Part 1 → Part 2 (1-min prep, 2-min talk) → Part 3 mock; examiner voice; 3-s countdown; live captions + IPA, waveform, F0 contour, WPM gauge. |
| **Rubric engine** | FC (WPM, phonation-time ratio, pauses > 600 ms, filled pauses incl. acoustic "uh/um" detection, 220 discourse markers, Band 8-9 gate 130 ≤ WPM ≤ 165, PTR ≥ 0.78, ≤ 1.5 pauses/min, marker variety ≥ 5), LR (MATTR-50, CEFR-J/Octanove profile, collocations, 616 C1/C2 upgrades with 3 alternatives), GRA (26 complex structures, ~45 L1-error rules, error-free clause ratio), P (GOP phoneme scores, word stress, σF0 < 1.8 st monotone penalty). Overall band uses IELTS rounding (.25 → .5, .75 → next band) and maps to CEFR (A1 ≤ 2.5 … C2 8.5-9.0). Every report includes a Band 7.5 natural polish and a Band 9 C2 rewrite, playable with TTS. |
| **Bento dashboard** | Overall band + radar, fluency/lexis/grammar/pronunciation cards, phoneme heatmap with remediation sheets, transcript with tap-to-replay, model answers, optional offline-AI grammar review. Saved as `saved_results/IELTS_Speaking_Tech_test N.json/.csv/.wav`; any JSON can re-hydrate the dashboard. |
| **ELSA lab** | Sentence drills scored per phoneme (Viterbi forced alignment + GOP, Green ≥ 80 / Amber 50-79 / Red < 50), stress dots, intonation overlay vs. a neural model voice, 44-phoneme library with articulation + Indonesian-L1 notes and minimal pairs, ear trainer, roleplay coach, "weakest sounds". |
| **HelloTalk / Tandem** | Tier 1 worldwide humans (WebRTC P2P, MQTT-over-WSS signalling, padlocked offline); Tier 2 LAN (NSD/mDNS) or router-less Wi-Fi Direct; Tier 3 twelve offline AI natives (Liam/London RP, Sarah/California GA, Chloe/Melbourne AU, Budi & Emma EN↔ID …) with red/green corrections, debate mode and CameraX self-view. |
| **Cake shadowing** | Built-in lines with neural TTS slowed by WSOLA (0.60/0.75/0.85/1.00/1.25×) or your own videos/audio via ExoPlayer; dual subtitles (EN + IPA + ID); A-B loop; shadow recorder scored 40 % phonetics / 35 % intonation / 25 % rhythm. |
| **Quiz & flashcards** | A-D MCQ with per-option explanations (columns H-K), HH:MM:SS timer, shuffle, flags, review; SM-2 flashcards. |
| **Excel workbooks** | `[+ Lampirkan File Master Excel (.xlsx)]` → pick up to 1,000 sheets, 11- or 16-column schema with header/module/CEFR inference, cached in Room. Built-in 1,020 cases (170 per CEFR level, 34 domains) exportable to `Documents/PolyLingua_Master_1000_Cases.xlsx`. |

## Hardware tuning (Helio G96)

* All neural inference (sherpa-onnx, ONNX Runtime, llama.cpp) runs on threads pinned to the two
  Cortex-A76 cores with `sched_setaffinity`; native worker pools inherit the mask
  (`n_threads = 2`). Pitch tracking, WSOLA and file I/O run on the Cortex-A55 cluster.
* A residency governor keeps loaded models under 1.8 GB (LRU eviction).
* 3.5 mm headset → Oboe full-duplex engine with near-zero-latency sidetone; speakers →
  `AudioRecord` with AcousticEchoCanceler / NoiseSuppressor / AutomaticGainControl.
* 90 Hz: the activity requests the highest refresh-rate display mode; true-black AMOLED theme.
* MIUI: foreground service (`microphone | mediaPlayback | dataSync`), battery-optimisation and
  Autostart shortcuts (`miui.intent.action.APP_PERM_EDITOR`).
* Models can be stored on the microSD card (`getExternalFilesDirs`).

## First run

1. Install the APK (allow "install unknown apps" for your browser/file manager).
2. Grant microphone and notification permissions.
3. **Models & settings → Get all** downloads the essential offline bundle (~435 MB, once):
   Silero VAD, Zipformer streaming ASR, punctuation, charsiu GOP phone classifier, Kokoro voices.
   Optional: Moonshine second-pass ASR, Kitten/Piper voices, Qwen2.5-1.5B or SmolLM2-360M coach.
4. On MIUI tap **Autostart & pop-ups** and **Battery exemption** on the home screen.

Without the speech pack the arena falls back to the Android recogniser (transcript only, no GOP
or pitch analysis); TTS falls back to the Android system voices.

## Building

```bash
./build_apk.sh            # Linux / macOS
build_apk.bat             # Windows
```

Needs JDK 17+, Android SDK 34, NDK r26b+ (27.2.12479018 recommended) and CMake 3.22.1; the
scripts check these, fetch sherpa-onnx / ONNX Runtime headers / llama.cpp, build, run the unit
tests and copy `release-output/PolyLingua_POCO_M4_Pro_Release.apk`.

**Signing.** No key material is stored in git. CI derives the release key deterministically from
the `KEYSTORE_PASSWORD` repository secret (`scripts/derive_release_keystore.py`); set the same
variable locally to produce APKs that can update CI builds. Without it, builds are debug-signed.

Every push to `main` builds on GitHub Actions and publishes a release; on failure the Gradle log is
pushed to the `ci-logs` branch.

## Architecture

```
app/src/main/cpp/           JNI: CPU affinity, sherpa-onnx bridge, ORT GOP posteriors, llama.cpp, Oboe sidetone
app/src/main/java/com/polylingua/edge/
  core/                     big.LITTLE executors, settings, telemetry, MIUI helpers, linguistic assets
  dsp/                      capture engine, TarsosDSP pitch/prosody, WSOLA, GOP analyser, shadowing scorer
  speech/                   model manager, ASR/VAD/punctuation, TTS, LLM, GOP engine, assessment pipeline
  domain/                   IELTS/CEFR rubric engine, lexicons, grammar detector, phoneme database
  data/                     Room, Excel ingestion/export, 1,020-case curriculum, session vault (JSON/CSV/WAV)
  network/                  connectivity tiers, MQTT signalling, NSD/Wi-Fi Direct, WebRTC, AI personas
  media/                    subtitle parser, media PCM extraction
  ui/                       Compose theme, components, screens, navigation
```

## Licence

GPL-3.0 (TarsosDSP is GPL-3.0). Third-party components and data: see `THIRD_PARTY_NOTICES.md`.
