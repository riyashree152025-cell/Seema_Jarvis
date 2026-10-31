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