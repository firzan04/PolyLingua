# Third-party notices

PolyLingua Edge is distributed under the **GNU General Public License v3.0** (see `LICENSE`)
because it statically includes TarsosDSP (GPL-3.0). The components below keep their own
licences; their copyright notices are reproduced or referenced here as those licences require.

## Bundled in the APK

| Component | Use in PolyLingua | Licence |
|---|---|---|
| [TarsosDSP](https://github.com/JorenSix/TarsosDSP) (vendored in `tarsosdsp/`) | YIN / MPM pitch tracking, WSOLA time-stretching | GPL-3.0 |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8 (prebuilt C API) | Streaming ASR, VAD, punctuation, offline TTS runtime | Apache-2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) (shipped inside sherpa-onnx) | Neural inference, GOP phone classifier | MIT |
| [llama.cpp / ggml](https://github.com/ggml-org/llama.cpp) tag b12000 | Offline small-language-model coach | MIT |
| [Oboe](https://github.com/google/oboe) 1.9.0 | Low-latency full-duplex sidetone | Apache-2.0 |
| [stream-webrtc-android](https://github.com/GetStream/webrtc-android) 1.2.2 (libwebrtc) | Peer-to-peer calls | Apache-2.0 / BSD-3-Clause (WebRTC) |
| AndroidX, Jetpack Compose, Media3 ExoPlayer, CameraX, Room, Hilt/Dagger | App framework | Apache-2.0 |
| [ML Kit Face Detection](https://developers.google.com/ml-kit/vision/face-detection) 16.1.7 (bundled model) | Offline eye-contact / expression analysis of interview videos | [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) |
| [OkHttp](https://github.com/square/okhttp) 4.12 | Model downloads, WebSocket signalling | Apache-2.0 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) 1.27.1 | Streaming `.tar.bz2` model extraction | Apache-2.0 |
| [CMU Pronouncing Dictionary](http://www.speech.cs.cmu.edu/cgi-bin/cmudict) 0.7b | Canonical pronunciations, IPA, stress | BSD-2-Clause style (see `assets/phonetics/CMUDICT_LICENSE.txt`) |
| [CEFR-J Vocabulary Profile 1.5](https://github.com/openlanguageprofiles/olp-en-cefrj) | A1-B2 word levels | Free for research and commercial use with citation: *CEFR-J Wordlist Version 1.5, compiled by Yukio Tono, Tokyo University of Foreign Studies.* |
| [Octanove Vocabulary Profile C1/C2 1.0](https://github.com/openlanguageprofiles/olp-en-cefrj) | C1-C2 word levels | CC BY-SA 4.0 (Octanove Labs) |
| charsiu `en_w2v2_fc_10ms` phone vocabulary | Phone label map for GOP | MIT (see `assets/phonetics/CHARSIU_LICENSE.txt`) |
| Plus Jakarta Sans, Noto Sans, JetBrains Mono | UI typography | SIL Open Font License 1.1 |

## Downloaded at runtime (not in the APK)

Models are fetched only when the learner taps *Download* in **Models & settings**:

| Model | Source | Licence |
|---|---|---|
| Silero VAD | k2-fsa/sherpa-onnx releases | MIT |
| Streaming Zipformer EN 20M | k2-fsa/sherpa-onnx releases | Apache-2.0 |
| NeMo FastConformer streaming transducer EN 480 ms (int8) | k2-fsa/sherpa-onnx releases (NVIDIA NeMo export) | CC-BY-4.0 |
| Nemotron Speech Streaming EN 0.6B (int8) | k2-fsa/sherpa-onnx releases (NVIDIA export) | NVIDIA Open Model License |
| Parakeet TDT 0.6B v2 (int8), Parakeet TDT-CTC 110M (int8) | k2-fsa/sherpa-onnx releases (NVIDIA NeMo export) | CC-BY-4.0 |
| Moonshine Tiny EN (int8) | k2-fsa/sherpa-onnx releases | MIT |
| Online punctuation EN | k2-fsa/sherpa-onnx releases | Apache-2.0 |
| Kokoro 82M v0.19 (int8) | k2-fsa/sherpa-onnx releases | Apache-2.0 |
| Kitten Nano v0.2 | k2-fsa/sherpa-onnx releases | Apache-2.0 |
| Piper `en_GB-alan-medium`, `en_US-lessac-medium` | k2-fsa/sherpa-onnx releases | MIT (voices: see Piper model cards) |
| charsiu wav2vec2 phone classifier (int8 ONNX) | huggingface.co/mnaoizyyy/charsiu-js-models | MIT |
| Qwen2.5-1.5B-Instruct Q4_0 GGUF | huggingface.co/bartowski | Apache-2.0 |
| SmolLM2-360M-Instruct Q8_0 GGUF | huggingface.co/bartowski | Apache-2.0 |

## Cloud services (optional, only with your own API key)

Google Gemini API, Anthropic Claude API and OpenAI-compatible endpoints are called only when the
learner selects them and provides a key; their own terms and pricing apply. Keys are stored
encrypted on the device (Android Keystore) and are sent only to the chosen provider.

## Assessment content

IELTS® is a registered trademark of the British Council, IDP: IELTS Australia and Cambridge
University Press & Assessment; this app is not affiliated with or endorsed by them. The band and
CEFR descriptors shown in the app are **original paraphrases** written for practice feedback;
they are not the official public band descriptors or the Council of Europe's CEFR text. Scores are
automated estimates for practice and are not official IELTS results.
