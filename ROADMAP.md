# SEEMA — Roadmap

Phases are ordered by dependency, not ambition. P0 protects what already works; P1 is what makes the
running cost predictable; P2 and P3 buy capability only after P1 has capped the bill.

Status vocabulary: **done** = shipped at the baseline commit; **partial** = a mechanism exists but
misses the acceptance criterion; **not built** = nothing in the tree. Baseline: commit `130d43b`.
Effort is engineering days for one developer familiar with the codebase, excluding OEM device
testing. A later commit can invalidate a status row — re-check against the tree before planning
sprints from it.

---

## P0 — Make today's build honest, survivable and stoppable

| # | Item | Status today | Acceptance criteria | Effort | Depends on |
|---|---|---|---|---|---|
| P0.1 | Wake word honesty | **done, one residual** | The notification, HUD and sleep label all name the model actually loaded. Notifications now derive the phrase from the resolved asset (`SeemaVoiceService.kt:274,332`) and `WAKE_WORD_NAME` is gone. Residual: `AgentState.SLEEPING` still hard-codes "SAY HEY JARVIS" (`SeemaState.kt:15`), so the HUD will misname the phrase once `seema.onnx` ships. Fix that label, then this row is fully closed. | 0.5 d | — |
| P0.2 | Ship or train `seema.onnx` | **not built** | Either a trained `seema.onnx` in `app/src/main/assets/` with a documented training recipe and a false-accept measurement, or the decision recorded to ship "Hey Jarvis" permanently. | 2–5 d (training/measurement) | P0.1 |
| P0.3 | Reboot survival | **done, unverified on device** | Listening comes back after reboot, reinstall and OEM quick-boot, and only when the user had it on. `SeemaBootReceiver` + the `service_enabled` flag implement this (`SeemaBootReceiver.kt:24-46`, `SeemaVoiceService.kt:719`). Remaining: confirm on a real device that the mic FGS start is not refused on Android 14+; the receiver catches and logs that failure rather than retrying (`SeemaBootReceiver.kt:42-45`). | 1–2 d (device verification) | — |
| P0.4 | Permissions onboarding | **partial** | One guided screen showing every access SEEMA needs, each with state and a working deep link: mic, contacts, phone, SMS, notification-listener access, DND access, modify-system-settings, display-over-other-apps, battery-optimization exemption, background location, exact alarms, notifications. Today only the overlay and notification-access prompts exist (`MainActivity.kt:174-192`) on top of the bulk runtime-permission request (`:125`); DND, write-settings, exact alarms and background location are still discovered only when a tool fails. | 2–3 d | — |
| P0.5 | Stop control | **done** | A hard stop that cancels the conversation, flushes TTS mid-utterance, ends follow-up mode, and is reachable without speaking. Implemented as `SeemaCommand.STOP`, the notification "Stop" action (`ACTION_STOP`, `SeemaVoiceService.kt:168,713`) and in-app control; cancellation reaches `TextToSpeech.stop()` through `SeemaTextToSpeech`'s `finally` (`SeemaTextToSpeech.kt:82-87`). Remaining: confirm the Quick Settings tile also exposes stop. | 0.5 d (verify tile path) | — |
| P0.6 | New UI | **done** | A control surface with state, telemetry, typed commands and onboarding. The Reactor/Tactical HUDs were replaced by the JARVIS instrument interface (`ui/jarvis/`, `MainActivity.kt:78`). Still missing from the acceptance list: today's turn count, estimated spend, router toggle and a memory browser — those depend on P1.6. | 2–4 d (cost panels only) | P1.6 |

P0 phase acceptance: a fresh install reaches fully-granted, always-on, stoppable, cost-visible
operation by tapping through one screen, with no ambiguity about which wake word to say.

Notes from the current code that P0.2 and P0.3 still depend on:

- `seema.onnx` is preferred but absent; only `hey_jarvis_v0.1.onnx` ships
  (`SeemaVoiceService.kt:778`), so the honest phrase today is "Hey Jarvis".
- A microphone foreground service cannot reliably start from a boot receiver on Android 14+; the
  receiver attempts it, catches `ForegroundServiceStartNotAllowedException` and leaves the
  `service_enabled` flag set so the next app launch restores listening (`SeemaBootReceiver.kt:16-20,42-45`).
- WorkManager-persisted work (daily briefing, scheduled routines) is re-scheduled by WorkManager
  itself after reboot. That part is **done**; do not rebuild it.

---

## P1 — Instant command router and memory v2

| # | Item | Status today | Acceptance criteria | Effort | Depends on |
|---|---|---|---|---|---|
| P1.1 | Instant command router | **partial seed** (`OfflineBrain`, `OfflineBrain.kt:36-62`) | ≥40 action intents matched deterministically; ≥80 % of measured tool-only utterances handled with zero cloud tokens; p95 local latency <100 ms from final transcript to first TTS sample; unmatched input falls through to the LLM, never guesses; `needs_user` and `error` wording identical to the LLM path. | 5–7 d | P0.1 |
| P1.2 | Router safety parity | **not built** | Every risky intent (call, SMS, delete routine, forget-everything) either goes to the LLM or passes a code-enforced confirmation, matching the gate list in `PROMPT.md`. A router unit-test suite covers each intent and each refusal. | 2–3 d | P1.1 |
| P1.3 | Persistent conversation session | **not built** (`InMemoryRunner` is process-local, `SeemaAgent.kt:153-164`) | Conversation context survives process death and reboot, with a bounded on-disk window and an explicit "clear conversation" action. | 2–3 d | — |
| P1.4 | Semantic memory | **partial** (`SeemaMemory.search` is AND-substring, `SeemaMemory.kt:54-63`) | Recall by meaning, not literal words, for at least a Hinglish/English mix; per-entry delete and export kept; corrupt-file behaviour preserved (`SeemaMemory.kt:91-96`). | 3–5 d | P1.3 |
| P1.5 | Memory summarisation | **not built** (prompt takes the last 100 of 300 facts, `SeemaAgent.kt:334-341,347`) | Older facts collapse into dated summaries so the injected memory block has a hard token ceiling regardless of how much has been remembered. | 2–3 d | P1.4 |
| P1.6 | Cost instrumentation | **not built** | Per-turn token counts and a daily spend estimate are recorded locally and shown in the UI; a configurable per-day hard stop switches SEEMA to router-only mode for the rest of the day. | 2–3 d | P0.6 |

P1 is the phase that turns the ₹1000–1500 budget from a hope into a control loop: P1.1 removes most
billable turns, P1.6 proves it.

---

## P2 — Streaming conversation (Gemini Live)

Dependency: `com.google.genai:google-genai:1.44.0` exists **only as a runtime-scope transitive** of
`google-adk-kotlin-core-android:0.1.0` and is not declared in `gradle/libs.versions.toml`. P2 must
declare it. No Live API class is referenced anywhere in the current sources.

| # | Item | Status today | Acceptance criteria | Effort | Depends on |
|---|---|---|---|---|---|
| P2.1 | Live session skeleton | **not built** | A `LiveSession` wrapper over `AsyncLive` that opens a session, streams mic audio, plays returned audio, and closes cleanly on stop; text-path fallback (P1) always available. | 4–6 d | P1.6 |
| P2.2 | Turn-taking | **not built** | `ActivityStart`/`ActivityEnd` used so SEEMA speaks only on user turn end; barge-in stops playback within ~300 ms. | 3–4 d | P2.1, P0.5 |
| P2.3 | Session resumption | **not built** | `SessionResumption` handles a dropped connection without losing context or double-executing a tool. | 2–3 d | P2.1, P1.3 |
| P2.4 | Budget cap | **not built** | Conversation mode is opt-in per session, bounded by a per-day audio-minute ceiling, and degrades to the router/text path when the ceiling is hit — with a spoken reason. | 2–3 d | P1.6, P2.1 |
| P2.5 | Tool calling in Live mode | **not built** | Open design question: whether ADK 0.1.0 can drive Live function calls, or whether the app dispatches the existing 54 tools itself. Decide before P2.1 starts. | 1 d spike | — |

P2 is deliberately *additive*: if the Live path is unavailable, over budget, or unstable, the P1
path is the product and nothing regresses.

---

## P3 — Senses and proactivity

| # | Item | Status today | Acceptance criteria | Effort | Depends on |
|---|---|---|---|---|---|
| P3.1 | Accessibility screen reading | **not built** (no accessibility service in `AndroidManifest.xml`) | SEEMA can read the current screen's text on request, with an explicit user-enabled switch, a visible indicator while reading, and a redaction rule for password fields. | 4–6 d | P0.4 |
| P3.2 | Calendar | **not built** (no calendar permission in the manifest) | Read today/tomorrow's events by voice, create an event with confirmation, and include the next event in the briefing. | 2–3 d | P0.4 |
| P3.3 | Camera / OCR | **not built** (no camera permission; no ML Kit dependency) | "Read this / translate this / what does this say" works on a photo, on-device preferred, with the image never uploaded unless the user opts in per request. | 4–6 d | — |
| P3.4 | Web-search grounding | **not built** | Answers to factual questions cite retrieved sources when grounding is on, with grounding calls counted in the P1.6 budget and switched off automatically when the budget guard trips. | 3–5 d | P1.6 |
| P3.5 | Proactivity v2 | **partial** (scheduled briefing + rain alerts + place/power automations, `SeemaProactive.kt:59-111`, `SeemaTriggers.kt:202-255`) | Unprompted suggestions about upcoming context (traffic before a calendar event, a reminder that a routine is due) with a strict rate limit (≤N per day), a do-not-disturb window, and a mute control that survives reboot. | 5–8 d | P3.2, P1.6 |

Proactivity is the highest-risk phase for both cost and annoyance: every proactive path needs a hard
daily cap and should route through the deterministic layer wherever possible — the rain-alert design
(`SeemaProactive.kt:158`, no model call) is the model to copy.

---

## Effort summary

| Phase | Effort still open | Ship condition |
|---|---|---|
| P0 | 6–12 d | Honest, survivable, stoppable, cost-visible |
| P1 | 16–24 d | ≥80 % of actions off-cloud, daily budget enforced |
| P2 | 12–17 d | Streaming conversation, capped, never a regression |
| P3 | 18–28 d | New senses with privacy and rate limits in place |

Sequencing rule: P0.2 (decide the wake word for good) and P1.1 (router) come first — the router is
only useful if the user trusts that SEEMA hears them and can be stopped, and both are already
partly true.
