# Third-party notices

## Coopanion native preview engine

- Upstream: https://github.com/Pal-AI-Lab/Coopanion
- Author: Pal AI Lab and Coopanion contributors.
- Source files: `packages/cortico-world-desktop-pet/web/kit/body.js` and `rig.js`.
- Included source copies: `preview/kit/body.js` and `preview/kit/rig.js`.
- License: GNU Affero General Public License, version 3 or later, as declared by the upstream package. The license text is included as `preview/LICENSE-Coopanion.txt`.
- Copies were taken from the locally installed Coopanion 0.2.17. The two JavaScript files are preserved without modifications.

The downloadable standalone HTML preview embeds those engine sources. Their corresponding readable sources and the HTML assembly script are included in this repository. The character ZIP uses the host application's provided `kit` and does not bundle the preview engine.

The upstream engine's license notice does not assign a new license to the supplied character artwork.

## sherpa-onnx (offline speech recognition)

- Upstream: https://github.com/k2-fsa/sherpa-onnx
- Author: k2-fsa / Next-gen Kaldi developers and contributors.
- Included binary: `app/libs/sherpa-onnx-2.0.1.aar` (prebuilt Android library from the v2.0.1 release, bundling ONNX Runtime).
- License: Apache License 2.0. ONNX Runtime is MIT licensed.
- The Chinese ASR model `sherpa-onnx-paraformer-zh-small-2024-03-09` (Paraformer, FunAudioLLM) is **not** bundled in the APK; its two files (`model.int8.onnx`, `tokens.txt`) are downloaded on explicit user consent from hf-mirror.com (Hugging Face mirror) or huggingface.co into app-private storage. Model license: Apache 2.0 (see the upstream model card).

## sherpa-onnx (offline speech synthesis)

- Upstream: https://github.com/k2-fsa/sherpa-onnx and https://github.com/k2-fsa/ZipVoice
- License: Apache License 2.0.
- The synthesis engine `sherpa-onnx-zipvoice-distill-int8-zh-en-emilia` (ZipVoice, zero-shot voice cloning) is **not** bundled in the APK. Its five files (`tokens.txt`, `encoder.int8.onnx`, `decoder.int8.onnx`, `lexicon.txt`, `vocos_24khz.onnx`) are downloaded on explicit user consent from this project's GitHub `tts-models` release into app-private storage. Vocos is MIT licensed.
- The two bundled timbres (`assets/voice-packs/timbre1` and `timbre2`) are user-supplied reference recordings, 3 seconds each, stored as 24 kHz mono WAV. They are voice *prompts*, not model weights: synthesis happens locally and no reference audio ever leaves the device.

