# SEEMA — Cost Model

**Everything numeric here is an ESTIMATE, not a quote.** It was written without network access, from
source-code facts at commit `85ef7a2` plus stated assumptions. Provider prices change without notice
and vary by region, tier and model alias. Treat §2 as *parameters to be replaced*, then re-run §3.
Verify every figure on the provider's pricing page before trusting a total.

Second caveat: the default model is a `-latest` alias (`build.gradle.kts:35-39`), so the model behind
it — and its price — can change without a code change. Pin a version once the numbers matter.

---

## 1. The one distinction that decides the bill

"SEEMA is on for 3 hours" and "SEEMA is talking for 3 hours" are different by an order of magnitude.

- **App open / service alive**: the wake-word engine is listening on-device. This costs ₹0 in API
  terms. Today it is also ₹0 in practice: at idle only the ONNX models run, and after two minutes of
  silence the service sleeps with the engine still armed (`SeemaVoiceService.kt:288-325,649`).
- **Actually speaking**: a user utterance plus a reply. This is where cloud tokens are spent, and it
  is bounded by the conversation loop — 6 s follow-up window, at most 10 wake-word-free turns per
  conversation (`SeemaVoiceService.kt:643,647`).

Assumption used throughout: **speech duty cycle is 10–20 % of app-open time**, i.e. of a 3-hour
window, 18–36 minutes contain actual exchanges. This is an assumption, not a measurement. The app
should measure it (see §5) and this file should be rewritten with real numbers.

Turn-count assumption: one exchange averages **30 s** wall clock (listen + think + speak), i.e. 120
exchanges per speaking-hour — deliberately conservative for a phone assistant that is not being
talked to continuously.

| App open | 10 % duty | 15 % duty | 20 % duty |
|---|---|---|---|
| 2 h/day | 24 turns/day | 36 | 48 |
| 3 h/day | 36 turns/day | 54 | 72 |
| 5 h/day | 60 turns/day | 90 | 120 |

---

## 2. Assumed unit prices (REPLACE THESE)

FX assumption: **₹88 = $1**. Assumed prices per 1M tokens:

| Symbol | Assumed input | Assumed output | Notes |
|---|---|---|---|
| Flash-class text LLM | $0.30 | $2.50 | the tier `gemini-flash-latest` typically resolves to |
| Flash-Lite-class text LLM | $0.10 | $0.40 | cheaper conversational tier |
| Live API audio in | $1.00 / 1M audio tokens | — | audio ≈ 25 tokens/s ⇒ 90,000 tokens/hour |
| Live API audio out | — | $2.00 / 1M audio tokens | same 25 tokens/s ⇒ 90,000 tokens/hour |
| Free tier | $0 | $0 | daily request caps; quota-exhaustion already degrades to basics (`SeemaVoiceService.kt:472-479`) |

### Tokens per turn, from the actual code

| Component | Estimate | Basis |
|---|---|---|
| Static system prompt | ~1,500 tokens | `SYSTEM_PROMPT` is 5,923 characters (`SeemaAgent.kt:36-119`), ~4 chars/token |
| Tool declarations (54 tools, all resent every turn) | ~6,000 tokens | 54 `@Tool` functions wired into one agent (`SeemaAgent.kt:132-145`); must be measured, not guessed |
| Dynamic context | ~700 tokens average | time + up to 20 reminders + places + routines + automations + last 100 memories (`SeemaAgent.kt:299-341`) |
| Conversation history | ~400 tokens average | accumulates within a session; session is process-local (`SeemaAgent.kt:153-164`) |
| User utterance | ~15 tokens | one or two short sentences |
| Assistant reply | ~80 tokens | prompt enforces 1–3 spoken sentences (`SeemaAgent.kt:39`) |
| **Input total (no router)** | **~8,600 tokens** | |
| **Input total (router active, routed tool subset)** | **~4,100 tokens** | router handles ~80 % of tool-only turns locally, so cloud turns are the harder ones and get a smaller tool set |

Derived per-turn costs (input × price-in + output × price-out):

| Configuration | ₹ per cloud turn |
|---|---|
| Flash text, no router (~8,600 in / 80 out) | ~₹0.25 |
| Flash text, router active (~4,100 in / 80 out) | ~₹0.13 |
| Flash-Lite, router active | ~₹0.04 |

Unmodelled surcharges, stated so they are not mistaken for precision:

- Multi-step/tool turns usually need a second model round trip (tool call, then answer): assume
  **+40 % to +150 %** on those turns. Multi-tool routined turns are the expensive ones.
- Proactive jobs spend without the user present: the daily briefing is a full agent turn and is
  **on by default** (`SeemaProactive.kt:33-34,114-143`), and each scheduled/triggered routine is
  another (`SeemaProactive.kt:237-265`), retried up to 3 attempts. Budget ~3 extra turns/day
  ≈ +₹22/month on Flash. Rain alerts cost nothing: they call the weather API, not the model
  (`SeemaProactive.kt:158`).
- On-device TTS, wake word, Open-Meteo weather and the offline router are all ₹0.

---

## 3. Three stacks, per month

### (a) Fully on-device + free tier

Router + `OfflineBrain` + on-device TTS + on-device STT, with free-tier Gemini for genuine
conversation.

| App open | Cost |
|---|---|
| 2 / 3 / 5 h per day | **₹0** |

Caveats, not footnotes: free-tier daily caps will be hit on heavy days, the app degrades to basics
when they are (`SeemaVoiceService.kt:472-479`), and free-tier traffic is typically used by the
provider to improve its products. This stack also has the highest latency and the lowest
conversational quality.

### (b) On-device gating + paid Gemini text LLM

| Variant | 2 h/day | 3 h/day | 5 h/day |
|---|---|---|---|
| No router, Flash text | ₹180 – ₹360 | ₹270 – ₹540 | ₹450 – ₹900 |
| No router, Flash-Lite text | ₹55 – ₹115 | ₹85 – ₹170 | ₹140 – ₹285 |
| Router (20 % cloud), Flash text | ₹25 – ₹95 | ₹40 – ₹130 | ₹60 – ₹225 |
| Router (20 % cloud), Flash-Lite text | ₹8 – ₹30 | ₹10 – ₹40 | ₹20 – ₹70 |
| Any variant, + proactive jobs | +₹22 | +₹22 | +₹22 |

Arithmetic for the first row: 24 turns/day × ₹0.25 × 30 days = ₹180; 48 × ₹0.25 × 30 = ₹360; the 3 h
row spans 36–72 turns (₹270–₹540). The router rows are 20 % of those turns at ₹0.13 (Flash) or ₹0.04
(Flash-Lite) per cloud turn, and the top of each range carries the +40 %/+150 % multi-step surcharge
from §2. All ranges also carry the ±40 % token-estimate uncertainty.

Consequence worth stating plainly: once the router is in, **turn count stops being the dominant
term** — at low volume the fixed ~3 proactive turns/day (₹22/month on Flash) can cost more than the
conversations. Making the default-on briefing opt-in matters as much as the router does.

**The router is not a cost optimisation, it is the cost control.** It moves the deterministic 80 %
(torch, volume, time, alarms, apps, media, settings) off the cloud and shrinks what remains.

### (c) Gemini Live API conversation mode

Audio flows both ways while a session is open, so the unit is **₹ per session-hour**, not per turn:
90,000 input tokens/hour ≈ $0.09/h, and 90,000 output tokens/hour if SEEMA talks continuously ≈
$0.18/h — but SEEMA speaks well under half the time, so assume ~$0.054/h of output audio. Total
**≈ $0.144 per continuous session-hour ≈ ₹12.7**.

| Session policy | 2 h/day | 3 h/day | 5 h/day |
|---|---|---|---|
| Session open for the whole app-open window (naive) | ₹760 | ₹1,140 | ₹1,900 |
| Session open only while speech is detected (10–20 % duty) | ₹75 – ₹150 | ₹115 – ₹230 | ₹190 – ₹380 |

Arithmetic for the naive row: 2 h × ₹12.7 × 30 = ₹762. Gated row: 2 h × 0.15 × ₹12.7 × 30 = ₹114.

This is the whole argument for on-device gating: **without local VAD/wake-word gating, Live audio
alone exceeds the ₹1000–1500 budget at 3 h/day.** With gating, the same feature fits with room for
the text path. Add ±40 % for token-rate uncertainty and for audio the model emits while "thinking".

### Budget verdict

- 2 h/day: (a) ₹0, (b) ₹8–₹360, (c) ₹75–₹760 — all viable; naive Live is the top of the range.
- 3 h/day: (b) with router ₹10–₹130 fits comfortably; (c) gated ₹115–₹230 fits; (c) naive ₹1,140 does not.
- 5 h/day: (b) with router ₹20–₹225 fits; (c) gated ₹190–₹380 fits; (c) naive ₹1,900 breaks it.

Recommended shape for ₹1000–1500/month: **(b) with the router as the default, Live conversation mode
as an opt-in capped session**. That lands around ₹20–₹250/month, so a heavy month with Live enabled
(₹115–₹380) still leaves most of the budget unused. The ₹1000–1500 figure should be read as a ceiling
that is very hard to hit by accident once the router and gating exist — and easy to hit without them.

---

## 4. How to keep it under ₹1000 — checklist

1. **Prompt slimming.** ~1,500 static prompt tokens and ~6,000 tool-schema tokens dominate every
   turn. Move per-tool guidance into tool descriptions, or split tools into two agents (device-control
   vs conversation) so no turn carries all 54 schemas.
2. **Context caching.** Keep the stable prefix byte-identical across turns so provider-side prefix
   caching can apply: static blocks first, volatile content last. Today the clock is appended after
   the prompt (`SeemaAgent.kt:299-303`) — that order is correct, keep it.
3. **Instant command router.** ≥80 % of tool-only turns at ₹0 and <100 ms. The single largest lever,
   and the only one that also improves latency.
4. **TTS on-device.** Already true (`SeemaTextToSpeech.kt`). Never add cloud TTS: a spoken reply would
   be billed twice (text out, audio out).
5. **Session gating.** Open a Live session only after wake word + VAD confirm speech, and close it on
   inactivity instead of leaving audio streaming.
6. **Per-day budget guard.** Track tokens per turn (P1.6 in `ROADMAP.md`), convert with §2, and
   hard-stop cloud calls for the rest of the day when the cap is hit — falling back to the router,
   not to silence.
7. **Model tiering.** Short conversational turns to the Lite tier; Flash only for multi-step tool
   orchestration and the briefing. Make the default-on briefing opt-in and cap its retries.
8. **Cap the loop, not the user.** `MAX_CONVERSATION_TURNS = 10` and the 6 s follow-up window
   (`SeemaVoiceService.kt:643,647`) already bound a conversation; shrink the follow-up window before
   reducing capability.

---

## 5. Moving from estimates to facts

Record per turn: model id, input/output tokens, tool-call count, router vs LLM, latency. Then replace
§2's assumed prices with invoiced ones and §1's duty cycle with the measured one. Until that exists
every ₹ figure here is a planning number with a ±40 % band, and the only defensible claim is the
ordering: **router-first and gated Live keep this assistant inside ₹1000–1500/month; a naive
always-streaming Live session does not.**
