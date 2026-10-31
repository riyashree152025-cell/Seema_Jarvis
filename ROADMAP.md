# SEEMA — Roadmap

Phases are ordered by dependency, not ambition. P0 protects what already works; P1 is what makes the
running cost predictable; P2 and P3 buy capability only after P1 has capped the bill. Baseline for
every "status today" claim: commit `85ef7a2`.

Status vocabulary: **done** = shipped in this tree; **partial** = a mechanism exists but misses the
acceptance criterion; **not built** = nothing in the tree. Effort is engineering days for one
developer familiar with the codebase, excluding OEM device testing; ranges reflect Android
background-execution differences.

---

## P0 — Make today's build honest, survivable and stoppable

| # | Item | Status today | Acceptance criteria | Effort | Depends on |
|---|---|---|---|---|---|
| P0.1 | Wake word honesty | **partial** | The notification, the HUD status label and the sleep label all name the model actually loaded. If `seema.onnx` is absent, every surface says "Hey Jarvis". Nothing says "Seema" while `hey_jarvis_v0.1.onnx` is the loaded model. | 0.5–1 d | — |
| P0.2 | Ship or train `seema.onnx` | **not built** | Either a trained `seema.onnx` in `app/src/main/assets/` with a documented training recipe and a false-accept measurement, or the decision recorded to ship "Hey Jarvis" permanently. | 2–5 d (training/measurement) | P0.1 |
| P0.3 | Reboot survival | **partial** | After a reboot: reminders and geofences are re-registered, scheduled briefings/routines still run, and the user has a one-tap path back to always-on listening from a notification. Document explicitly which part cannot be automatic and why. | 2–3 d | P0.4 |
| P0.4 | Permissions onboarding | **partial** | One guided screen showing every access SEEMA needs, each with state (granted / denied / not asked) and a working deep link: mic, contacts, phone, SMS, notification-listener access, DND access, modify-system-settings, display-over-other-apps, battery-optimization exemption, background location, exact alarms, notifications. Every capability the app claims is reachable without reading source. | 2–3 d | — |
| P0.5 | Stop control | **partial** | A hard stop that (a) cancels the current conversation, (b) flushes TTS mid-utterance, (c) ends follow-up mode, and (d) is reachable from the app UI, the foreground notification and the Quick Settings tile. Spoken exit phrases keep working but stop being the only way out. | 1.5–2 d | — |
| P0.6 | New UI | **partial** | A control surface that shows, at minimum: live state, service-running/sleeping, active wake word, today's turn count and estimated spend, router enabled/disabled, memory count with a browse/delete screen, and the permission board from P0.4. Two existing HUD skins are kept. | 4–6 d | P0.4 |

P0 phase acceptance: a fresh install reaches fully-granted, always-on, stoppable, cost-visible
operation by tapping through one screen, with no ambiguity about which wake word to say.

Constraints P0.3 must respect, from the current code:

- `ReminderBootReceiver` handles only `BOOT_COMPLETED` → reminders + geofences
  (`SeemaReminders.kt:218-227`). The voice service is never restarted; it returns only when the user
  opens the app (`MainActivity.kt:131,199-202`).
- A microphone-type foreground service cannot generally be started from a boot receiver on
  Android 14+, and the code already refuses when it is illegal (`SeemaVoiceService.kt:546-575`,
  comment at `:571`). So P0.3 is "notification with one tap", plus an attempt via the
  draw-over-apps exemption that is verified per device — never a promised auto-start.
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

| Phase | Effort | Ship condition |
|---|---|---|
| P0 | 12–20 d | Honest, survivable, stoppable, cost-visible |
| P1 | 16–24 d | ≥80 % of actions off-cloud, daily budget enforced |
| P2 | 12–17 d | Streaming conversation, capped, never a regression |
| P3 | 18–28 d | New senses with privacy and rate limits in place |

Sequencing rule: finish P0.1 and P0.5 before P1.1 — the router is only useful if the user trusts that
SEEMA hears them and can be stopped.
