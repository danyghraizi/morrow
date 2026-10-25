# Known limitations & backlog

Everything here is deliberate or measured, not imagined. Each row says what the app does *today*,
where the code is, what the next concrete step is, and how we would know it is fixed. Ordered roughly
by user value. Public-facing summary lives in `README.md` → "Where it breaks".

| # | Gap | Today (measured) | Start here | Next step | Done when |
|---|---|---|---|---|---|
| 1 | **Three or more actions in one sentence** | At most two are split; the rest stays in the remaining title | `QuickParse.splitActions` | Generalise to N parts, still requiring a verb per part; cap at 4 | `pay rent, call the dentist tomorrow and book a haircut` → 3 items; "buy milk and eggs" still unsplit |
| 2 | **Conversational reference** | "move it to tomorrow" needs the item's name | `Assistant.findItem`, `Repo` | Remember the last created/matched id; accept "it/that/le/la" when the name misses | After "dentist friday at 3pm", "move it to monday" moves that event |
| 3 | **Time idioms** | "half past five", "quarter to six", "end of month", "next weekend", "the 3rd" are not parsed (model saves the phrase or nothing) | `When` (regex tables) | Add phrase tables + tests; keep the 1:1 span rule | Each idiom resolves to an instant, and its span leaves the title |
| 4 | **Recurrence variants** | "every 3rd tuesday" is saved once with an explicit notice; no end dates | `Recurrence` | `BYSETPOS`, `COUNT`/`UNTIL`, and `nextAfter` support; drop the notice for supported rules | `every 3rd tuesday at 10 count 6` produces 6 correct rows |
| 5 | **Recurring items are not mirrored to the calendar** | Local rows only (deleting one occurrence in a calendar is ambiguous) | `CalendarWriter`, `Assistant.addSeries` | One `RRULE` event for the series; delete the event only when the last occurrence goes; dedupe by eventId | A weekly series appears once in the system calendar and survives per-occurrence completion |
| 6 | **Provider is not reconciled after external edits** | Deleting/moving an event in Google/Samsung Calendar leaves the mirrored row (or a stale local row) until the app is reinstalled | `CalendarFeed.refresh` | On refresh, drop mirrors whose event vanished; for linked local items, flag or re-sync start | Deleting the event in the calendar app removes it from Today within one refresh |
| 7 | **The model is English-trained** | French titles are fine (it copies words); free-form French replies are terse and may be English | `FunctionGemma`, `Assistant.think` | Prefer local templates for French replies, or swap in a bilingual 300–600 M model behind `LlamaEngine` | A French question gets a French answer, or is answered by a template |
| 8 | **Agenda is single-day** | "what is on my plate tomorrow" works; "this week" does not | `Insights.agendaText` | Accept a day range and aggregate | "what's this week look like" lists the week |
| 9 | **No widget / share target / quick tile** | Quick capture is the Ask tab only | `ui/Root.kt` | `AppWidgetProvider` for the next item; `ACTION_SEND` into Ask | Sharing text from another app opens Ask prefilled |
| 10 | **Size: 303 MB APK + 278 MB unpacked** | One-time unpack is what makes "install and use" true | `model/ModelPack`, `app/build.gradle.kts` | Play Asset Delivery variant, or a verified Q4 build with the same checksum discipline | A build variant ships under 200 MB and still works offline on first launch |
| 11 | **No CI** | Tests/lint are run by hand | `.github/workflows` | Workflow: `testDebugUnitTest lintDebug` with Gradle cache (skip the native build if it is not a dependency) | Green check on every push |
| 12 | **No instrumentation tests** | `Assistant`, `Repo` and the UI are verified by hand on a device | `app/src/test` | Robolectric for `Store` (v1→v3 migration) and `Repo` (undo); Compose test for the sheet | Two new tests run in CI |
| 13 | **Reminder resync after reboot is unverified** | `BootReceiver` exists and is wired; no reboot test was performed on hardware | `notify/Receivers.kt` | Reboot test: schedule, reboot, confirm the alarm set is rebuilt | Documented pass on a real device |
| 14 | **x86_64 inference is slow (~5 tok/s)** | Baseline SIMD; only matters for emulators | `cpp/CMakeLists.txt` | `GGML_CPU_ALL_VARIANTS` or accept | Emulator response under ~2 s |
| 15 | **Samsung/One UI battery policy** | Exact alarms need the user to tap Allow; sleeping apps may defer reminders | `notify/Reminders`, Settings screen | Detect and explain `isIgnoringBatteryOptimizations` | A first-run hint appears only when needed |
| 16 | **Events carry title + time only** | No attendees, location, notes or attachments | `Item`, `CalendarWriter` | Add fields with a migration, map to `Events.EVENT_LOCATION`/`DESCRIPTION` | One field round-trips through the provider |
| 17 | **Accessibility semantics** | Icons have `contentDescription = null`; TalkBack reads labels only | `ui/Components.kt`, screens | Descriptions + a TalkBack pass | Manual TalkBack pass documented |
| 18 | **One device, one language pair** | Verified on Galaxy S21 FE (Android 16) and an API 36 x86_64 emulator | `docs/VERIFICATION.md` | Smoke on a second vendor and on Android 8–14 | Matrix filled in |

## Explicit non-goals

- Cloud models, accounts, sync, analytics, telemetry — the app declares no `INTERNET` permission and
  that is a feature, not an oversight.
- Free-form conversation quality. The model is a 270 M function-caller; the product is the rules.
- Replacing the phone's calendar app: Morrow writes through `CalendarProvider` and mirrors read-only.

## How to pick up an item

1. Read the "Start here" file and its test; run `./gradlew testDebugUnitTest` first.
2. Reproduce the "Today" behaviour on the emulator (`morrow`) or a phone, using the DB as the oracle
   (recipe in `AGENTS.md`).
3. Add the test that encodes the "Done when" line before changing behaviour.
4. Update this table, `README.md` and `CHANGELOG.md`.
