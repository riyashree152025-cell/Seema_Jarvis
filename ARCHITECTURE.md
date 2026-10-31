# SEEMA — Architecture

Status: current-state description plus target model, written against commit `85ef7a2` and
`versionCode 1` (`app/build.gradle.kts:23`). All line references are to that commit; a working tree
that is ahead of it may have shifted lines. No builds were run and no network was used; every claim
about *today* is anchored to a source path and line. Scope is a personal, single-user, sideloaded
app (`README.md:14`), so Play policy is a constraint to work around, not a requirement to satisfy.

---

## 1. What exists today

One process. `SeemaVoiceService` is the runtime core: a `START_STICKY` foreground service of type
`microphone|location` (`SeemaVoiceService.kt:134-144`, `AndroidManifest.xml:152-156`) holding a
partial wake lock (`SeemaVoiceService.kt:577-586`). The UI (`MainActivity`) and the tile/widget are
thin clients that push commands into `SeemaState` flows (`SeemaState.kt:61-89`); the service collects
them (`SeemaVoiceService.kt:113-123`).

| Layer | Today | Anchor |
|---|---|---|
| Ears (wake) | openWakeWord 0.1.5, one model, library detection disabled, app applies its own threshold to live scores | `SeemaVoiceService.kt:170-201,631` |
| Ears (STT) | Android `SpeechRecognizer`, one utterance, on-device recognizer used on API 31+ when prefer-offline (default off) | `SeemaSpeechRecognizer.kt:40-67,143-152` |
| Brain | Google ADK 0.1.0 `LlmAgent` + Gemini; instruction rebuilt every turn; in-memory session only | `SeemaAgent.kt:123-164,299-342` |
| Mouth | Android `TextToSpeech`, on-device; no cloud TTS anywhere | `SeemaTextToSpeech.kt:35-107` |
| Memory | Flat list of short fact strings in one `AtomicFile` JSON, cap 300 | `SeemaMemory.kt:21-52` |
| Senses | Notifications, SMS/call log, battery, weather (Open-Meteo, no key), location/geofence, power events | `SeemaNotificationListener.kt`, `SeemaBriefingTools.kt:72-114`, `SeemaWeatherTools.kt:30-33`, `SeemaTriggers.kt:198-255` |
| Agency | 54 `@Tool` functions across 14 groups, executed in-process against Android APIs | `SeemaAgent.kt:132-145`; 54 `@Tool` declarations verified by search |

Two facts that shape everything downstream:

- The wake word in the shipped build is **"Hey Jarvis", not "Seema"**. Only
  `hey_jarvis_v0.1.onnx` is in `app/src/main/assets/`; `seema.onnx` is the preferred name
  (`SeemaVoiceService.kt:677`) and is absent. The service nevertheless announces "say \"Seema\""
  because `WAKE_WORD_NAME = "Seema"` (`:629`, used at `:220,223,322`), while the state label says
  "SAY HEY JARVIS" (`SeemaState.kt:15`) and the UI derives the real word from assets
  (`MainActivity.kt:239-241`). The three disagree.
- Conversation state is process-local. `InMemoryRunner` is cached per model+key and reset on
  process death (`SeemaAgent.kt:153-164,255-258`), so multi-turn context is lost whenever Android
  reclaims the app. Only *facts* survive (`SeemaMemory`).

### Current data flow (turn-based)

```
 mic
  │
  ▼
[openWakeWord] score ≥ VoiceSettings.wakeThreshold (default 0.05)   SeemaVoiceService.kt:194-197
  │  dispatchWakeWord()  ── rearm guard 2 s, cooldown 2 s
  ▼
launchConversation()  ── releaseMicrophone(): engine.stop() + 400 ms   :245-248
  │
  ▼
captureCommand()  ── SeemaSpeechRecognizer.listenOnce()               :400-459
  │                  15 s cap, 6 s follow-up start window, 1.5 s end silence
  ▼
handleCommand()                                                        :462-497
  │
  ├─ offline or quota exhausted ──► OfflineBrain.handle()   regex router, ~16 actions, ₹0
  │
  └─ online ──► SeemaAgent.runAgent()
                  │  Mutex: one turn at a time                          SeemaAgent.kt:193
                  │  instruction = SYSTEM_PROMPT (5,923 chars) + time + reminders +
                  │                places + routines + automations + last 100 memories
                  │  all 54 tool declarations resent every turn
                  ▼
                Gemini (gemini-flash-latest by default)                 build.gradle.kts:35-39
                  │  tool calls run in-process; 3 retries/25 s attempt, 60 s deadline,
                  │  and no retry once a tool has already executed        SeemaAgent.kt:200-247
                  ▼
               reply text
  │
  ▼
speak() ── SeemaTextToSpeech.speak()  (audio focus, music ducks)      :512-517
  │
  ▼
follow-up loop, no wake word, ≤ 10 turns ──► back to wake-word listening  :377-394
```

Idle cost today is already near zero: at idle only the wake-word ONNX sessions run, and after
2 minutes without voice input the service sleeps with the engine still armed
(`SeemaVoiceService.kt:288-325,649`). Cloud cost is incurred only per conversational turn — but
*every* such turn carries the whole prompt and all 54 tool schemas (see `COST_MODEL.md`).

### Background paths that spend tokens without a user present

- Daily briefing: on by default (`SeemaProactive.kt:33-34`); a WorkManager job that re-schedules
  itself and calls `SeemaAgent.runAgent` with a fixed instruction (`SeemaProactive.kt:114-143`).
- Scheduled and triggered routines: same pattern (`SeemaProactive.kt:237-265`).
- Rain alerts do **not** use the model — they call `SeemaWeatherTools.rainAlert()` directly
  (`SeemaProactive.kt:158`), so they are ₹0.

---

## 2. The six-layer target model

The layers are the same runtime components, re-cut so each can be replaced independently.

| Layer | Job | Today | Target | Reuse vs replace |
|---|---|---|---|---|
| **Ears** | decide *when* to listen and turn speech into text | wake word only; no VAD; recognizer's own silence detection | wake word + explicit on-device VAD gate before STT | keep `WakeWordEngine` wiring; add VAD; keep `SeemaSpeechRecognizer` |
| **Brain** | decide *what* to do | one cloud LLM for every utterance | deterministic `InstantCommandRouter` first, cloud LLM only on miss | new router class; keep `SeemaAgent` for the conversational path |
| **Mouth** | turn text into speech | on-device Android TTS | unchanged; add a barge-in stop | keep `SeemaTextToSpeech`; add `stop()` |
| **Memory** | remember the user | flat fact list, no semantics, no conversation persistence | facts + embeddings + rolling summaries + persistent session | extend `SeemaMemory`; replace `InMemoryRunner` session store |
| **Senses** | read the world | notifications, SMS/call log, battery, weather, location, power | + accessibility screen text, calendar, camera/OCR, web grounding | keep existing; add `SeemaSenses*` providers |
| **Agency** | change the world | 54 tools, in-process, prompt-level confirmation for calls/SMS | same tools, called by router *or* LLM; code-enforced gates for irreversible actions | keep tool classes verbatim; add a gate wrapper |

### Target pipeline

```
 mic
  │
  ▼
[EARS] openWakeWord (on-device, always on, ₹0 of API spend; battery cost is real and unmeasured)
  │ wake
  ▼
[EARS] on-device VAD  ── no speech? abort, back to wake word, ₹0
  │ speech detected
  ▼
[EARS] STT (Android on-device recognizer when available)
  │ text
  ▼
[BRAIN] InstantCommandRouter   deterministic match, ₹0, target <100 ms
  ├── HIT  (≈80 % of tool actions) ──► [AGENCY] tool call ──► [MOUTH] TTS ──► wake word
  └── MISS (conversation / multi-step / unknown)
            ▼
      [BRAIN] Cloud LLM (ADK + Gemini text) with a *routed tool subset*
            │   gates: confirm before call/SMS/delete/send
            ▼
      [AGENCY] tools ──► [MOUTH] on-device TTS
            (Phase 2 only: [BRAIN] Gemini Live audio in / audio out, capped)

 [MEMORY]  facts + embeddings + summaries ── injected into the cloud path only
 [SENSES]  notifications · location · battery · weather · power   (P3: screen, calendar, camera, web)
```

### The instant command router fast path

The router is the highest-leverage change: it converts most spoken commands from a ~1.5–3 s,
token-billed cloud round trip into a local function call.

- It plugs in where `handleCommand` branches today (`SeemaVoiceService.kt:462-497`), before the
  online/offline branch, so it serves both.
- Matching: normalise the transcript as `SeemaMemory.normalize` does (`SeemaMemory.kt:81-83`) and run
  an ordered rule list — the shape of `OfflineBrain.handle` (`OfflineBrain.kt:36-62`), which already
  covers torch, DND, Wi-Fi, Bluetooth, battery, volume, timer, reminder, alarm, media transport,
  time, date, call, open-app and notifications. The work is **widening** those rules and pointing the
  online path at them too, then keeping the LLM as fallback.
- `OfflineBrain` already returns spoken text, never `ERROR:` (`OfflineBrain.kt:27`), and already
  enforces one confirmation gate in code (pending call, `OfflineBrain.kt:31-34`) — keep both
  properties.
- Router scope: torch, volume/mute, time/date, alarms/timers, open app, media transport,
  ringer/DND/Wi-Fi/Bluetooth toggles, brightness, battery, "run <routine>", exit phrases. Everything
  with a free-text argument, all memory reads/writes, notification reads, call/SMS composition,
  routine *creation* and anything multi-step stays with the LLM.
- Honesty rule: when unsure, return *no match* rather than guess. A wrong local torch or volume
  action is worse than a billed cloud turn.

### Layer change list with concrete reuse

| Change | Reuses | Replaces / adds |
|---|---|---|
| Wake word honesty | `resolveWakeWordModel()` (`SeemaVoiceService.kt:251-276`) | `WAKE_WORD_NAME` becomes derived, not constant (`:629`) |
| VAD gate | recognizer level callback (`SeemaSpeechRecognizer.kt:123-125`) | new on-device VAD in front of `listenOnce` |
| Instant router | `OfflineBrain` rules + all 54 tool classes | new `InstantCommandRouter`; `handleCommand` ordering |
| Memory v2 | `SeemaMemory` JSON file + `add/search/remove` API | embeddings + summariser; facts stay the source of truth |
| Persistent session | `SeemaAgent` session id plumbing (`:256-258,350`) | replace the `InMemoryRunner` session store with a file-backed one |
| Tool subsetting | ADK `LlmAgent(tools = …)` (`SeemaAgent.kt:132-145`) | send only the schemas a turn plausibly needs |
| Live mode (P2) | ADK + `google-genai` 1.44.0 | new streaming session; text path stays as fallback |
| Barge-in / hard stop | `SeemaState.conversationEndRequest` (`SeemaState.kt:103-111`) | real `stop()` on TTS + wake engine arm/disarm |

Note on the Live API dependency: `com.google.genai:google-genai:1.44.0` reaches the app **only as a
runtime-scope transitive** of `google-adk-kotlin-core-android:0.1.0` (its POM declares
`<scope>runtime</scope>`); it is not in `gradle/libs.versions.toml`. Phase 2 must declare it
explicitly or code referencing `com.google.genai.live.*` will not compile.

---

## 3. Offline and degraded behaviour

The rule: every degradation removes *capability*, never *usability* or *honesty*.

| Condition | Behaviour today | Target behaviour |
|---|---|---|
| No internet at turn time | `OfflineBrain.handle()` answers basics; response tagged `⚠ OFFLINE` (`SeemaVoiceService.kt:468-485`) | identical, but the router handles most turns before the network is even consulted |
| Gemini quota exhausted | detected via `isQuotaError` (`SeemaAgent.kt:249-253`) → offline brain, `⚠ GEMINI QUOTA USED UP — BASIC MODE` (`:474-479`) | same signal drives a per-day budget guard and a spoken "conversation mode is off for today" |
| Connection drops mid-request | reply starting `ERROR:` triggers offline retry (`:480-485`) | unchanged |
| No `seema.onnx` | falls back to `hey_jarvis_v0.1.onnx`; if neither exists, wake word is marked unavailable and TALK/tile/widget still work (`:56-57,204-214,264-271`) | advertise the model actually loaded, in the notification, the HUD label and the sleep label |
| No speech recognition service | spoken error, conversation aborts (`:401-407`) | unchanged |
| TTS engine or Hindi voice missing | falls back to English, offers the voice-data screen once (`SeemaTextToSpeech.kt:110-141`) | unchanged |
| Microphone stolen by another app | `CoroutineExceptionHandler` → `scheduleRestart()` after 3 s (`:47-51,233-242`) | unchanged, plus a visible "mic busy" state |
| Android 14+ forbids a mic FGS start | `enterForeground()` fails, service stops, user must open the app (`:546-575`) | guided onboarding + a non-mic foreground path that survives reboot (see `ROADMAP.md` P0) |
| `needs_user` from a settings tool | tool returns `status="needs_user"`, model is instructed to name the exact switch (`SeemaSettingsTools.kt:213`, `SeemaAgent.kt:77-79`) | unchanged; the router reuses the same contract and wording |
