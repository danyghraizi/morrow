# Changelog

## 1.2.0 — 2026-10-25

Found by a deliberate limits battery on a Galaxy S21 FE.

**Understanding more**
- Two actions in one sentence are split when both halves contain a verb ("pay the rent and call the
  dentist tomorrow" → two items). Noun lists and appointments are left alone.
- A relative offset keeps an explicit clock: "in 3 days at 9am" is now Wednesday 09:00 instead of
  "now + 3 days" with "at 9am" left in the title.
- A time with no subject asks for a name instead of saving a task called "At 9am".
- Agenda questions respect a named day: "what is on my plate tomorrow", "mon programme demain".
- "already done" phrasing completes instead of adding: "i already paid the rent", "j'ai déjà payé …".
- Cancelling is refused with guidance ("cancel my gym session") instead of silently creating a task.
- An unsupported repetition pattern says so ("every 3rd tuesday") instead of pretending.

**Correctness**
- Model output is validated against the user's words; a refusal or drift is discarded and the user's
  own phrasing is used. Short verb phrases ("pay the rent") skip the model entirely.
- Item matching ranks exact > prefix > substring > token overlap, so "done with the rent" picks the
  closest item rather than the longest one.
- Repeating-series top-up is serialised and never re-adds an occurrence whose start already exists —
  a race between app start and the boot receiver was duplicating rows on hardware.
- The Undo snackbar action is localised (it was hardcoded English).
- Per-app language support (`android:localeConfig`, en/fr), so French can be chosen without changing
  the phone's language.

## 1.1.0 — 2026-10-25

**French**
- Full French interface (`values-fr/`) plus a bilingual `Copy` layer for every sentence the app
  composes: replies, agenda, insights, row labels, notification actions.
- The rules layer parses French: `demain`, `après-demain`, `ce soir`, `la semaine prochaine`,
  `vendredi`/`lundi prochain`, `12 novembre`, `à 15h`, `15h30`, `midi`, `minuit`, `dans 2 heures`,
  `pendant 45 minutes`, `avant 18h`, `tous les lundis`, `tous les jours`, `en semaine`.
- Accent-insensitive matching with a one-to-one index map, so titles are still cleaned correctly
  ("dentiste vendredi à 15h" → `Dentiste`).
- French verbs for add / complete / move / remember / agenda / suggest / help / small talk.

**Repeating items**
- "every monday", "every weekday", "every 2 weeks", "every month" and their French equivalents
  become iCalendar rules, materialised as 8 real occurrences (topped up to a 21-day horizon).
- First occurrence is aligned to the rule; each occurrence has its own alarm, completion and delete.
- Database v3 adds `rrule` and `series_id`, with an in-place migration.

**Suggestions**
- New insight: a stacked day (4+ items) with a clear tomorrow offers to move the latest task there.
- Free-slot suggestions now skip days that are already crowded.

**Verified on hardware (Galaxy S21 FE, Android 16, arm64)**
- Install, model unpack + checksum, first launch.
- One model request: 565 ms prefill + 1.36 s decode = **1.93 s** total; PSS 477 MB.
- "My calendar" (LOCAL) and "Samsung Calendar" listed; event written to the provider; read-back count
  updated live after a write; deep link opened a calendar app at the event.
- Reminder with an exact alarm fired with the screen off and posted its two-action notification.

**Fixes found while verifying**
- Overlapping parse spans ("every monday" contains "monday") corrupted cleaned titles.
- 3-letter French day names collided with ordinary words and names ("Sam" → samedi).
- App-specific locale filtering stripped `values-fr` from the APK.
- Calendar feed stayed stale after creating an event (refresh was only on resume).
- "1 events read from the phone" — the count is now pluralised.

## 1.0.0 — 2026-10-25

First working version: install and use, no download step, no account.

**Assistant**
- Deterministic time parser for relative days, weekdays, explicit dates, clock times, deadlines
  ("before six"), durations and offsets (`When`), with the matched span removed from the title.
- Verb-pattern router (`QuickParse`) for add / complete / move / remember / agenda / suggestions /
  small talk. Anything it cannot place goes to the on-device model.
- On-device FunctionGemma 270M (Q8_0, 278 MiB) bundled in the APK, driven through llama.cpp b11879
  with a GBNF grammar. When the rules know the kind, the grammar is narrowed to that one choice.
- Tolerant parser for the model's native call syntax, including truncated generations.
- Local insight engine: overdue items, a free hour today, an empty day.
- Undo for adds, completions and moves.

**App**
- Three screens (Today, Ask, Settings) plus an item action sheet, in a warm-paper Material 3 design.
- "Anytime" tasks, calendar events with durations, one-tap conversion between the two.
- Reminders via exact-`AlarmManager` alarms with Done / In-an-hour notification actions that work
  without opening the app; rescheduled after reboot, timezone or clock changes.
- System calendar integration both ways through `CalendarProvider` (Google, Samsung, Etar — any
  account type): events are written into the chosen calendar, and existing occurrences are read
  back into Today as read-only rows used by the agenda and free-slot suggestions. "Local only"
  remains the default for writes.
- Tapping a mirrored row opens the owning calendar app (`<queries>` added, otherwise Android 11+
  package visibility blocks the launch).
- Fixed the calendar read query, which asked `Instances` for an `status` column it does not have and
  therefore returned nothing at all.
- Model lifecycle: loads on demand, unloads after 90 s idle / on background / on low memory.
- Offline by construction: no `INTERNET` permission, backups disabled, model in `noBackupFilesDir`.

**Engineering**
- Database schema v2 with an in-place migration that keeps existing items.
- 33 JVM tests covering the parser, router, prompt/grammar goldens and model metadata consistency.
- llama.cpp pinned by tag and tarball SHA-256 through CMake `FetchContent`.
- Model fetch/verify script + Gradle task; checksum enforced again at runtime.
