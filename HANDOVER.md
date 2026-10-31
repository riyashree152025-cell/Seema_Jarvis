# HANDOVER — SEEMA / JARVIS rebuild, phase 1

तारीख: 2026-10-31
Build: `assembleDebug` — BUILD SUCCESSFUL, APK 85.79 MB

---

## 1. क्या डिलीवर हुआ

### नया UI (पूरा बदला)
पुराना Reactor/Tactical HUD (गोला, छोटा arc reactor, घूमता globe) हटा दिया गया है
main screen से। नया interface `ui/jarvis/` में है:

| फ़ाइल | क्या करती है |
|---|---|
| `JarvisScreen.kt` | मुख्य screen: header, core, transcript, chips, dock, boot sequence, typed-command dialog |
| `JarvisCore.kt` | केंद्रीय core: 120-point rotating constellation + 3 orbital rings + 72-tick collar + radar sweep + audio-reactive deformation |
| `JarvisTokens.kt` | रंग/टोकन + `AgentState` → colour/label mapping |

Design language:
- near-black void + radial vignette + 44dp engineering grid + scanlines
- monospaced telemetry (दिन का असली clock, battery, network link)
- hairlines, corner-free चrome — कोई card, कोई shadow, कोई emoji नहीं
- 32-bar equalizer जो **असली microphone level** से चलता है
- state के हिसाब से core का रंग/रफ़्तार/rings बदलते हैं: STANDBY(dim cyan) → LISTENING(cyan)
  → CAPTURING(ice) → PROCESSING(amber) → RESPONDING(mint) → FAULT(red)
- 2.6s का power-on boot sequence (line → ring → "S E E M A" → "SYSTEM ONLINE")

### Bug fixes
1. **Wake word झूठ बोलना बंद** — notification अब असली model से phrase derive करता है।
   `seema.onnx` नहीं है, इसलिए अब "Hey Jarvis" दिखता है, "Seema" नहीं।
   (`SeemaVoiceService.kt`: `wakeWordPhrase` + `WAKE_WORD_PHRASES` map)
2. **Reboot survival** — नया `SeemaBootReceiver.kt` + manifest entry
   (BOOT_COMPLETED / MY_PACKAGE_REPLACED / QUICKBOOT_POWERON), SharedPreferences flag
   `service_enabled` के साथ।
3. **Stop control** — notification में "Stop" action, `SeemaCommand.STOP`, और
   `stopConversation()` जो TTS playback तुरंत काट देता है (fake barge-in)।
4. **Typed commands** — `SeemaState.sendText(...)`; UI के keyboard button से text से
   command भेज सकते हैं, पूरा agent + tools + TTS path इस्तेमाल होता है।
5. **Permission onboarding** — पहले 10 permissions एक साथ माँगे जाते थे। अब:
   wave-1 runtime permissions, फिर background location, फिर
   **overlay permission** और **notification-listener access** (ये दोनों पहले कभी
   request नहीं होते थे — इसलिए `openApp`, `playMusic`, `setAlarm`, `readNotifications`
   install के बाद चुपचाप fail होते थे)।

### Documentation (नए)
`ARCHITECTURE.md` · `ROADMAP.md` · `COST_MODEL.md` · `PROMPT.md`
— असली architecture, phase-wise plan, ₹ cost model (2/3/5 घंटे/दिन), और
system-prompt का 10-block design।

---

## 2. Install कैसे करें

APK: repo root में `SEEMA-debug.apk`

1. फ़ोन में "unknown sources" allow करो, APK install करो
2. App खोलो → mic permission दो
3. App खुद overlay permission और notification access के लिए settings खोलेगा — दोनों ON करो
4. Battery optimisation से exclude करो (dialog खुद आएगा)
5. Settings (⚙) → AI → Gemini API key डालो
6. "Hey Jarvis" बोलो — notification में यही phrase दिखेगा

---

## 3. जो अभी नहीं बना (ईमानदारी से)

| कमी | क्यों |
|---|---|
| **Live/duplex voice** (बोलते-बोलते रोकना, साथ-साथ सुनना) | यह phase 2 है। `com.google.genai:google-genai 1.44.0` में `AsyncLive`, `ActivityStart/End`, `SessionResumption` मौजूद है — infrastructure ready है, voice loop rewrite बाकी है |
| **Instant command router** | ₹0 cost fast-path (torch/volume/time/alarm बिना LLM के)। Phase 1 में है, अभी नहीं बना |
| **Semantic memory** | अभी भी 300 facts, keyword search, FIFO delete। `ROADMAP.md` P1 |
| **Android 14+ boot restart** | Platform limit: mic-typed foreground service BOOT_COMPLETED से start नहीं हो सकती। try/catch + log किया है, crash नहीं होगा; user को app खोलना पड़ेगा |
| **Web search / calendar / vision** | Phase 3 |

---

## 4. Test status (ज़रूरी)

- ✅ **Build verified**: `assembleDebug` clean success, APK produced
- ❌ **Device पर नहीं चला**: इस session में कोई फ़ोन/emulator connect नहीं था
- इसलिए UI की असली rendering, wake word false-positives, battery drain — सब
  **unverified** हैं। पहली बार install करते वक़्त इन्हें देखना।

---

## 5. Rollback

सब कुछ git में है। पुराना version वापस चाहिए:

```bash
git log --oneline          # 85ef7a2 = original
git checkout 85ef7a2 -- .  # या नया branch
```

---

## 6. आगे क्या (सुझाया क्रम)

1. **फ़ोन पर install करके UI देखो** — रंग/गति/लेआउट tune करने के लिए
2. Instant command router (₹0, instant feel)
3. Memory v2 (semantic + persistent)
4. Live API conversation mode (असली JARVIS feel)
5. Senses (accessibility screen reading, calendar, web search)

विस्तार `ROADMAP.md` में।
---

## UPDATE — phase 2: capabilities wired, readability pass

### Readability (user feedback)
Every font in the HUD raised one step (8→11, 9→12, 10→14, 11→15, 12→16, 13→17sp), the
palette lifted out of near-black, muted text brightened from #8A6F73 to #D2AEB5, glass
edges and hairlines strengthened, and the code stream made ~2x more visible. Default glow
raised to 0.85.

### Now genuinely functional (not just UI)
| Feature | Status |
|---|---|
| Wake word ON/OFF + sensitivity | Real. OFF tears down the ONNX engine and its AudioRecord. Measured on a Galaxy A14: **266% -> 100% CPU foreground, 0% in background**, memory 228MB -> 171MB, GC events 24/10s -> **0** |
| Local Brain toggle | Real. Bypasses the cloud entirely |
| AI engine dropdown | Real. Groq LPU routes conversation to Groq; Gemini Flash keeps the 54 device tools |
| Groq fallback | Real. When Gemini returns an error (e.g. the 20/day free-tier quota), Groq answers instead of dropping to canned offline replies |
| Groq API key field | Real, masked, stored in the AI preference file |
| Colour profile | Real. Changes the reactor's resting glow (Deep Red / Crimson / Obsidian) |
| Glow, Transparency, Smooth reactor | Real, live |
| Screen Context | Real. Accessibility service reads on-screen text; readScreen and findOnScreen tools registered and enabled |
| Web search | Real. Keyless DuckDuckGo, gated by the Autonomous Web Search toggle |
| Permission onboarding | Overlay, notification access, background location and accessibility all requested in waves |

### Still not implemented
Autonomous code generation and website deployment are configuration only (no generator or
deploy backend exists). Automation Scripts and Gesture Activation are stored but not acted
on. The Groq path has no tool access by design, so device actions stay on Gemini.

---

## UPDATE — phase 3: layout, stream gating, service resilience, Groq status

### Interface
- The reactor and the MARK-II globe are clamped against the canvas: the collar and brackets reach
  ~1.5x the core radius, so on a narrow or short screen the whole instrument scales down instead
  of being cropped, and the core is offset inside its box so the caption never sits on it.
- Palette lifted again (base 0x241016, pure white primary text, 24% white glass, ~90% edge light).

### Code stream
- Strictly hidden by default. It renders only while `SeemaWorkStream` reports an active coding,
  web-scraping or file-generation task, is reference counted across tools, and has a 3-minute
  stall guard so a dead tool cannot leave it running.

### Foreground service
- `onTaskRemoved` keeps the listener alive when the app is swiped away, and a 30-second watchdog
  rebuilds the wake word engine if an OEM build silently kills its AudioRecord.
- Offline recognition: the on-device recogniser is constructed defensively, a busy recogniser is
  treated as no-speech rather than an error, `startListening` failures are caught, and any offline
  failure is retried once online so voice never dead-ends.

### Groq
- Live status indicator on the configuration page: a real request to the Groq chat endpoint on
  save, green when it answers, red when the key is missing or rejected.
- **The shipped model was dead.** `llama-3.3-70b-versatile` returns 404 from Groq now. The brain
  now uses a verified chain (`qwen/qwen3.8-27b`, `openai/gpt-oss-120b`, `openai/gpt-oss-20b`),
  advances to the next model on a retired model or an empty answer, and the token budget was
  raised to 1024 because reasoning models otherwise spend the whole budget thinking and return
  no visible text.
- When a fallback key exists the Gemini timeout drops from 25 s to 8 s with a single retry, so a
  dead primary costs seconds instead of half a minute. Measured fallback: ~3 s to a spoken answer.

---

## UPDATE — phase 4: API Key Manager, Multi-Provider Router

Contributed in a parallel session and verified here before pushing.

### New
- `apikeys/` — `ApiKeyVault` (encrypted storage), `ApiProvider`, `ProviderKeyRepository`,
  `ProviderProbeApi` (live health probes), `ProviderStatus`, plus the manager screen and its
  ViewModel. Keys are stored with `androidx.security:security-crypto 1.1.0-alpha06`; only obvious
  placeholder strings exist in source, no real key is committed.
- `router/` — `MultiProviderRouter`, `ChatTransport`, `ChatModels`. Routing is now a typed
  contract: a failure is a sealed `ProviderFailure` carrying the HTTP status, instead of the old
  "ERROR:" string convention, so the UI can tell an invalid key from an exhausted quota.
- `app/src/test/` — 37 JVM unit tests (router ordering/fall-through, outcome text, status mapping).
- `tools/` — offline verification scripts for the router and a smoke-test harness.

### This session's fix
- `HudTheme.brainHudLabel()` used trailing commas inside `when` entry conditions, which this
  toolchain rejects with "Expecting a when-condition"; the build failed. Commas removed.
- `gradle.properties` gained `android.overridePathCheck=true`, which removes the need to copy the
  project to an ASCII path before building (the checkout lives under "ドキュメント").

### Verification
- `assembleDebug`: BUILD SUCCESSFUL
- `testDebugUnitTest`: 37 tests, 0 failures, 0 skipped
- Device install not re-tested: the phone was disconnected at the time of this commit.
