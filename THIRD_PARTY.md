# Third-party components

The application code is MIT (see `LICENSE`). Everything below keeps its own licence and terms.

## Bundled model — **not** MIT

| | |
|---|---|
| Model | `functiongemma-270m-it` (Google, a Gemma 3 270M fine-tune for function calling) |
| Quantised by | [ggml-org/functiongemma-270m-it-GGUF](https://huggingface.co/ggml-org/functiongemma-270m-it-GGUF) |
| File | `functiongemma-270m-it-q8_0.gguf`, 291,557,792 bytes, sha256 `83940d4d…5270` |
| Licence | [Gemma Terms of Use](https://ai.google.dev/gemma/terms) |

The GGUF file is **not** committed here. `./gradlew fetchModel` downloads it from the pinned URL in
`gradle.properties` and verifies that hash; the file is then embedded in the APK you build.

If you distribute a built APK, you are distributing a Gemma model: you must pass the Gemma Terms of
Use on to your users and follow their use restrictions. This repository's MIT licence does not apply
to those weights.

## Runtime

| Component | Version | Licence |
|---|---|---|
| [llama.cpp](https://github.com/ggml-org/llama.cpp) (pinned tag `b11879`, fetched by CMake at build time) | b11879 | MIT |
| AndroidX / Jetpack Compose / Material 3 | via BOM 2026.09.00 | Apache-2.0 |
| Kotlin standard library & kotlinx-coroutines | 2.4.x / 1.11.0 | Apache-2.0 |
| JUnit 4 (tests only) | 4.13.2 | EPL-1.0 |

## Data the app touches

No third-party SDK, analytics, crash reporter or network client is included. The app declares no
`INTERNET` permission, so nothing can be uploaded. Events and tasks stay in the app's SQLite database
and in the phone's `CalendarProvider`, which the user's own calendar apps sync on their own terms.
