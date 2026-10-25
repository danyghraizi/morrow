# Verification record

What was actually run, where, and what it produced. Numbers are measurements, not estimates.
Anything not listed here is unverified.

## Environments

| | |
|---|---|
| Phone | Samsung Galaxy S21 FE (`SM-G990B`), Android 16 (SDK 36), arm64-v8a, en-US |
| Emulator | AVD `morrow`, `system-images;android-36;google_apis;x86_64`, 4 vCPU, KVM |
| Toolchain | JDK 21.0.13, Gradle 9.8.1, AGP 9.4.1, Kotlin built-in, SDK 37, NDK 29.0.14033849, CMake 3.31.6 |
| Model | FunctionGemma 270M IT, Q8_0, 291,557,792 bytes, sha256 `83940d4d…5270` |

## Measured

| Metric | Galaxy S21 FE (arm64) | Emulator (x86_64) |
|---|---|---|
| Model load (first use) | ≲ 1 s | ~0.6 s |
| Prefill, ~191 tokens | 565–868 ms | 1.6–2.5 s |
| Decode, ~21 tokens | 1.36 s (≈15.5 tok/s) | 3.5–4.8 s |
| Whole request, warm | 1.93 s | 5.2–7.3 s |
| Tap → reply, cold (incl. load) | 3.1 s | — |
| PSS with model resident | 477 MB (incl. 278 MB mapped) | ~440 MB |
| Rules-only request | ~1 ms | ~1 ms |
| 303 MB APK over USB | ~14 s | — |

Memory bound that made this viable: `n_outputs_max = 1` shrank the logits buffer from 523 MiB to
~40 MiB for a 262k vocabulary (`llm_bridge.cpp`).

## Functional checks performed on the phone

- Install, first launch, model unpack + SHA-256, "Model ready".
- Calendar: "My calendar" (`LOCAL`) and "Samsung Calendar" (`com.osp.app.signin`) listed; event written
  to `content://com.android.calendar/events`; read-back count updated live after a write; tapping an
  externally created event opened a calendar chooser → `EventInfoActivity` at the right event.
- Reminder with `window=0, exactAllowReason=permission` fired **with the screen off** and posted a
  notification with both actions.
- French via per-app locale: onboarding, Today, Ask, Settings, sheet, Undo label; plus the French
  utterances in `README.md` → "Where it breaks".
- Undo: Done → snackbar → Undo → state reverted (DB checked).
- Recurring series: 8 rows on creation, 14 after relaunches (cap), no duplicate starts; one occurrence
  completed and another deleted without touching the rest.
- Model off: engine reports `model-offline`, no model call, no item invented.
- 75 JVM tests, `lintDebug`, R8 release build.

All test items, turns, notifications and calendar events created during verification were deleted;
the app was reinstalled clean. The phone's own calendar events were never touched.

## Prompt / grammar validation against the real model

`app/src/test/resources/golden_prompt.txt`, `golden_grammar_forced_event.gbnf`,
`golden_grammar_free.gbnf` are the exact strings `FunctionGemma` produces. They were generated, then
run through `llama.cpp` (same pinned tag) against the real GGUF with greedy decoding and the same
GBNF, and the outputs parsed with `CallParser`. Results that shaped the design:

| Prompt shape | Outcome |
|---|---|
| 4 free tools | 9/24 correct intents, 13/24 with few-shot |
| 1 tool with `kind` enum | 11/19, never chose `event` |
| 1 tool, kind forced by host rules | 14/14 parseable, correct kind |
| Agenda context in the user turn | model answered with the first open task — harmful |

To re-run this validation after changing the prompt: regenerate the goldens, run the GGUF with
`llama-cli`/`llama-server` using those exact strings and grammar, confirm every output parses and the
kind is what the host decided, then update this table.

## Not verified

- A **Google-account** calendar (the test phone has a Samsung account). Same provider rows, but not
  exercised.
- **Reboot** resync (`BootReceiver`) — implemented, never tested on hardware.
- Android **8–14** (minSdk 26); only Android 16 was available.
- Long-term **One UI sleeping-apps** behaviour beyond the single screen-off alarm test.
- TalkBack / accessibility pass.
