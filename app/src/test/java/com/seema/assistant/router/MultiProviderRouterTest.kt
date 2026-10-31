package com.seema.assistant.router

import com.seema.assistant.apikeys.data.ApiKeyVault
import com.seema.assistant.apikeys.data.ApiProvider
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for [MultiProviderRouter]'s routing policy.
 *
 * The router is the one place that decides what the user gets when a provider is down, out of quota,
 * has no key, or throws on the wire: it must fall through the providers in a defined order, stop on
 * the first usable answer, and keep an honest picture in [RouterState] for the HUD. This suite drives
 * the real router with two hand-written fakes — [FakeVault] for credentials and [RecordingTransport]
 * for the wire — so it runs as a plain JVM test with no Android, no network and no device.
 *
 * The non-obvious cases carry the most weight:
 * - A `ChatCall.Failure` returned as data and an exception thrown by the transport are different
 *   failure kinds, and a 429 must be labelled "out of quota", not "broken key": the HUD acts on that
 *   difference.
 * - A provider with no stored key must skip the transport entirely instead of sending a request with
 *   an empty credential.
 * - A blank prompt must not read a single vault entry or call a single provider.
 * - Cancellation is not a failure: it must leave `executeChat` instead of being turned into a
 *   fallback, otherwise a cleared screen keeps spending the user's quota behind their back.
 * - A failed sweep must leave the last good `activeProvider` in place, because blanking the HUD badge
 *   would misreport a transient outage as a lost configuration.
 *
 * The assertions are deliberately exact about order (the [ProviderAttempt] sequence), because "some
 * provider answered" is not the contract — "the next provider in priority order answered" is.
 */
class MultiProviderRouterTest {

    // -----------------------------------------------------------------------------------------
    // A. Automatic routing: priority order and the reasons to fall through
    // -----------------------------------------------------------------------------------------

    /**
     * Requirement 1: with no provider id, the default order starts at Gemini, a healthy reply ends
     * the sweep after exactly one request, and the outcome is visible as READY plus the HUD label.
     * The whole [ChatResult.Success] is compared as a value so the text, model and attempt trail
     * cannot drift from what the HUD is told.
     */
    @Test
    fun `auto mode sends the turn to Gemini and records it as the active brain`() {
        val transport = RecordingTransport(
            mapOf(BuiltIn(ApiProvider.GEMINI) to reply("Namaste! How can I help?", "gemini-flash")),
        )
        val router = newRouter(transport)

        val result = runBlocking { router.executeChat("hello") }

        assertEquals(
            ChatResult.Success(
                ChatCompletion(BuiltIn(ApiProvider.GEMINI), "Namaste! How can I help?", "gemini-flash"),
                listOf(ProviderAttempt(BuiltIn(ApiProvider.GEMINI), null)),
            ),
            result,
        )
        assertEquals(listOf(BuiltIn(ApiProvider.GEMINI)), transport.calledProviders)
        assertEquals(BuiltIn(ApiProvider.GEMINI), router.activeBrain.value)
        assertEquals(BuiltIn(ApiProvider.GEMINI), router.state.value.activeProvider)
        assertEquals(RouterState.Status.READY, router.state.value.status)
        assertEquals(listOf(ProviderAttempt(BuiltIn(ApiProvider.GEMINI), null)), router.state.value.lastAttempts)
        assertEquals("Brain: Gemini", router.state.value.brainLabel)
    }

    /**
     * Requirement 2: a 429 is data, not an exception. Gemini must be recorded as QuotaExceeded, Groq
     * must be tried next, and the attempt log must contain both in order — the failed attempt is part
     * of the success story, not discarded.
     */
    @Test
    fun `a quota failure on Gemini falls over to Groq in priority order`() {
        val transport = RecordingTransport(
            mapOf(
                BuiltIn(ApiProvider.GEMINI) to failure(429, "quota exhausted for today"),
                BuiltIn(ApiProvider.GROQ) to reply("Groq answering", "llama-3.3-70b"),
            ),
        )
        val router = newRouter(transport)

        val result = assertSuccess(runBlocking { router.executeChat("hello") })

        assertEquals(listOf(BuiltIn(ApiProvider.GEMINI), BuiltIn(ApiProvider.GROQ)), transport.calledProviders)
        assertEquals(ChatCompletion(BuiltIn(ApiProvider.GROQ), "Groq answering", "llama-3.3-70b"), result.completion)
        assertEquals(listOf(BuiltIn(ApiProvider.GEMINI), BuiltIn(ApiProvider.GROQ)), result.attempts.map { it.provider })
        assertEquals(listOf(BuiltIn(ApiProvider.GEMINI), BuiltIn(ApiProvider.GROQ)), router.state.value.lastAttempts.map { it.provider })

        val geminiFailure = result.attempts[0].failure
        assertTrue("expected QuotaExceeded but was $geminiFailure", geminiFailure is ProviderFailure.QuotaExceeded)
        val quota = geminiFailure as ProviderFailure.QuotaExceeded
        assertEquals(BuiltIn(ApiProvider.GEMINI), quota.provider)
        assertEquals(429, quota.httpCode)
        assertNull(result.attempts[1].failure)

        assertEquals(BuiltIn(ApiProvider.GROQ), router.activeBrain.value)
        assertEquals(RouterState.Status.READY, router.state.value.status)
        assertEquals("Brain: Groq", router.state.value.brainLabel)
    }

    /**
     * Requirement 3: a transport exception must be caught and classified as [ProviderFailure.Transport]
     * with the exception's message, not escape and not stop the sweep. This is the branch that keeps a
     * dead socket from looking like a broken credential.
     */
    @Test
    fun `a thrown transport exception is recorded as Transport and falls over`() {
        val transport = RecordingTransport(
            mapOf(
                BuiltIn(ApiProvider.GEMINI) to crash(IOException("gemini socket closed")),
                BuiltIn(ApiProvider.GROQ) to reply("Groq answering", "llama-3.3-70b"),
            ),
        )
        val router = newRouter(transport)

        val result = assertSuccess(runBlocking { router.executeChat("hello") })

        assertEquals(listOf(BuiltIn(ApiProvider.GEMINI), BuiltIn(ApiProvider.GROQ)), result.attempts.map { it.provider })
        assertEquals(ChatCompletion(BuiltIn(ApiProvider.GROQ), "Groq answering", "llama-3.3-70b"), result.completion)

        val geminiFailure = result.attempts[0].failure
        assertTrue("expected Transport but was $geminiFailure", geminiFailure is ProviderFailure.Transport)
        val transportFailure = geminiFailure as ProviderFailure.Transport
        assertEquals(BuiltIn(ApiProvider.GEMINI), transportFailure.provider)
        assertEquals("gemini socket closed", transportFailure.detail)
        assertNull(result.attempts[1].failure)

        assertEquals(BuiltIn(ApiProvider.GROQ), router.activeBrain.value)
    }

    /**
     * Requirement 4: a mixed sweep keeps going — a refusal (429) and an exception (IOException) on the
     * first two providers must still reach Cerebras, with three attempts logged in exactly the tried
     * order.
     */
    @Test
    fun `the sweep survives a quota failure and an exception to reach Cerebras`() {
        val transport = RecordingTransport(
            mapOf(
                BuiltIn(ApiProvider.GEMINI) to failure(429, "quota exhausted for today"),
                BuiltIn(ApiProvider.GROQ) to crash(IOException("groq timed out")),
                BuiltIn(ApiProvider.CEREBRAS) to reply("Cerebras answering", "llama-3.3-70b"),
            ),
        )
        val router = newRouter(transport)

        val result = assertSuccess(runBlocking { router.executeChat("hello") })

        assertEquals(
            listOf(BuiltIn(ApiProvider.GEMINI), BuiltIn(ApiProvider.GROQ), BuiltIn(ApiProvider.CEREBRAS)),
            result.attempts.map { it.provider },
        )
        assertTrue(result.attempts[0].failure is ProviderFailure.QuotaExceeded)
        assertTrue(result.attempts[1].failure is ProviderFailure.Transport)
        assertNull(result.attempts[2].failure)
        assertEquals(BuiltIn(ApiProvider.CEREBRAS), result.completion.provider)
        assertEquals(BuiltIn(ApiProvider.CEREBRAS), router.activeBrain.value)
        assertEquals("Brain: Cerebras", router.state.value.brainLabel)
    }

    /**
     * Requirement 15: HTTP 200 with no text is [ProviderFailure.EmptyReply] and must fall through —
     * a reasoning model that spent its whole budget thinking is not a quota or key problem, but it is
     * also not an answer the user can hear.
     */
    @Test
    fun `an empty reply is an EmptyReply and falls through to the next provider`() {
        val transport = RecordingTransport(
            mapOf(
                BuiltIn(ApiProvider.GEMINI) to reply("", "gemini-flash"),
                BuiltIn(ApiProvider.GROQ) to reply("Groq answering", "llama-3.3-70b"),
            ),
        )
        val router = newRouter(transport)

        val result = assertSuccess(runBlocking { router.executeChat("hello") })

        assertEquals(listOf(BuiltIn(ApiProvider.GEMINI), BuiltIn(ApiProvider.GROQ)), result.attempts.map { it.provider })
        val geminiFailure = result.attempts[0].failure
        assertTrue("expected EmptyReply but was $geminiFailure", geminiFailure is ProviderFailure.EmptyReply)
        assertEquals(BuiltIn(ApiProvider.GEMINI), (geminiFailure as ProviderFailure.EmptyReply).provider)
        assertNull(result.attempts[1].failure)
        assertEquals(BuiltIn(ApiProvider.GROQ), router.activeBrain.value)
        assertEquals("Brain: Groq", router.state.value.brainLabel)
    }

    // -----------------------------------------------------------------------------------------
    // B. Exhaustion, and the last good brain surviving it
    // -----------------------------------------------------------------------------------------

    /**
     * Requirement 5: when every provider fails the result is [ChatResult.AllFailed] with one attempt
     * per provider, `isSuccess` is false, and the state says EXHAUSTED. The attempts are mirrored into
     * `state.lastAttempts` so the HUD can explain the outage.
     */
    @Test
    fun `three failures produce AllFailed and an EXHAUSTED state`() {
        val transport = RecordingTransport(
            mapOf(
                BuiltIn(ApiProvider.GEMINI) to failure(500, "server error"),
                BuiltIn(ApiProvider.GROQ) to failure(500, "server error"),
                BuiltIn(ApiProvider.CEREBRAS) to failure(500, "server error"),
            ),
        )
        val router = newRouter(transport)

        val result = assertAllFailed(runBlocking { router.executeChat("hello") })

        assertFalse(result.isSuccess)
        assertEquals(
            listOf(BuiltIn(ApiProvider.GEMINI), BuiltIn(ApiProvider.GROQ), BuiltIn(ApiProvider.CEREBRAS)),
            result.attempts.map { it.provider },
        )
        result.attempts.forEach { attempt ->
            assertTrue("expected HttpError but was ${attempt.failure}", attempt.failure is ProviderFailure.HttpError)
        }
        assertEquals(result.attempts, router.state.value.lastAttempts)
        assertEquals(RouterState.Status.EXHAUSTED, router.state.value.status)
        // No provider ever answered, so the HUD still shows the em-dash placeholder.
        assertNull(router.activeBrain.value)
        assertEquals("Brain: —", router.state.value.brainLabel)
    }

    /**
     * Requirement 6: the failed sweep must not blank out the last good brain. Gemini served the first
     * turn; the second sweep fails everywhere, so [RouterState.activeProvider] must still be Gemini
     * while [RouterState.status] reports EXHAUSTED.
     */
    @Test
    fun `a failed sweep keeps the last successful brain`() {
        val transport = RecordingTransport(
            mapOf(BuiltIn(ApiProvider.GEMINI) to reply("first answer", "gemini-flash")),
        )
        val router = newRouter(transport)

        assertSuccess(runBlocking { router.executeChat("hello") })
        assertEquals(BuiltIn(ApiProvider.GEMINI), router.activeBrain.value)
        assertEquals(RouterState.Status.READY, router.state.value.status)

        transport.script(BuiltIn(ApiProvider.GEMINI), failure(429, "quota exhausted for today"))
        transport.script(BuiltIn(ApiProvider.GROQ), failure(429, "quota exhausted for today"))
        transport.script(BuiltIn(ApiProvider.CEREBRAS), failure(503, "service unavailable"))

        val result = assertAllFailed(runBlocking { router.executeChat("hello again") })

        assertEquals(
            listOf(BuiltIn(ApiProvider.GEMINI), BuiltIn(ApiProvider.GROQ), BuiltIn(ApiProvider.CEREBRAS)),
            result.attempts.map { it.provider },
        )
        assertEquals(RouterState.Status.EXHAUSTED, router.state.value.status)
        assertEquals(BuiltIn(ApiProvider.GEMINI), router.state.value.activeProvider)
        assertEquals(BuiltIn(ApiProvider.GEMINI), router.activeBrain.value)
        assertEquals("Brain: Gemini", router.state.value.brainLabel)
    }

    // -----------------------------------------------------------------------------------------
    // C. Manual provider selection and priority configuration
    // -----------------------------------------------------------------------------------------

    /**
     * Requirement 7: a provider id resolved with [ApiProvider.fromId] is tried first, and a healthy
     * one ends the sweep there — exactly one attempt, no speculative calls to the rest.
     */
    @Test
    fun `a manual provider id is tried first and a healthy one is the only attempt`() {
        val transport = RecordingTransport(
            mapOf(BuiltIn(ApiProvider.CEREBRAS) to reply("Cerebras answering", "llama-3.3-70b")),
        )
        val router = newRouter(transport)

        val result = assertSuccess(runBlocking { router.executeChat("hello", "cerebras") })

        assertEquals(listOf(BuiltIn(ApiProvider.CEREBRAS)), transport.calledProviders)
        assertEquals(1, result.attempts.size)
        assertEquals(BuiltIn(ApiProvider.CEREBRAS), result.attempts.single().provider)
        assertEquals(ChatCompletion(BuiltIn(ApiProvider.CEREBRAS), "Cerebras answering", "llama-3.3-70b"), result.completion)
        assertEquals(BuiltIn(ApiProvider.CEREBRAS), router.activeBrain.value)
        assertEquals(RouterState.Status.READY, router.state.value.status)
        assertEquals("Brain: Cerebras", router.state.value.brainLabel)
    }

    /**
     * Requirement 8: after the manual provider fails, the fallback order is the priority order with
     * the selected provider removed — not the enum declaration order and not "Gemini always". With
     * Cerebras selected and throwing, Gemini is the next attempt.
     */
    @Test
    fun `a manual provider that throws is followed by the priority order`() {
        val transport = RecordingTransport(
            mapOf(
                BuiltIn(ApiProvider.CEREBRAS) to crash(IOException("cerebras unreachable")),
                BuiltIn(ApiProvider.GEMINI) to reply("Gemini answering", "gemini-flash"),
            ),
        )
        val router = newRouter(transport)

        val result = assertSuccess(runBlocking { router.executeChat("hello", "cerebras") })

        assertEquals(listOf(BuiltIn(ApiProvider.CEREBRAS), BuiltIn(ApiProvider.GEMINI)), transport.calledProviders)
        assertEquals(listOf(BuiltIn(ApiProvider.CEREBRAS), BuiltIn(ApiProvider.GEMINI)), result.attempts.map { it.provider })
        assertTrue(result.attempts[0].failure is ProviderFailure.Transport)
        assertNull(result.attempts[1].failure)
        assertEquals(ChatCompletion(BuiltIn(ApiProvider.GEMINI), "Gemini answering", "gemini-flash"), result.completion)
        assertEquals(BuiltIn(ApiProvider.GEMINI), router.activeBrain.value)
    }

    /**
     * Requirement 9: an id [ApiProvider.fromId] cannot resolve is not an error — it means "no manual
     * choice", so the router falls back to auto mode and Gemini leads.
     */
    @Test
    fun `an unknown provider id behaves as auto mode`() {
        val transport = RecordingTransport(
            mapOf(BuiltIn(ApiProvider.GEMINI) to reply("Gemini answering", "gemini-flash")),
        )
        val router = newRouter(transport)

        val result = assertSuccess(runBlocking { router.executeChat("hello", "nope") })

        assertEquals(listOf(BuiltIn(ApiProvider.GEMINI)), transport.calledProviders)
        assertEquals(ChatCompletion(BuiltIn(ApiProvider.GEMINI), "Gemini answering", "gemini-flash"), result.completion)
    }

    /** A blank id is the same "no manual choice" case as an unknown id: auto mode, Gemini first. */
    @Test
    fun `a blank provider id behaves as auto mode`() {
        val transport = RecordingTransport(
            mapOf(BuiltIn(ApiProvider.GEMINI) to reply("Gemini answering", "gemini-flash")),
        )
        val router = newRouter(transport)

        assertSuccess(runBlocking { router.executeChat("hello", "   ") })

        assertEquals(listOf(BuiltIn(ApiProvider.GEMINI)), transport.calledProviders)
    }

    /**
     * The default rotation is exactly Gemini, Groq, Cerebras. OpenRouter exists in [ApiProvider] and
     * is routable by id, but must not silently join the automatic rotation.
     */
    @Test
    fun `the default priority is exactly Gemini then Groq then Cerebras`() {
        assertEquals(
            listOf(BuiltIn(ApiProvider.GEMINI), BuiltIn(ApiProvider.GROQ), BuiltIn(ApiProvider.CEREBRAS)),
            MultiProviderRouter.DEFAULT_PRIORITY,
        )
        assertEquals(
            MultiProviderRouter.DEFAULT_PRIORITY,
            newRouter(RecordingTransport()).priority,
        )
        assertTrue(
            "OpenRouter must stay out of the automatic rotation",
            BuiltIn(ApiProvider.OPENROUTER) !in MultiProviderRouter.DEFAULT_PRIORITY,
        )
    }

    /**
     * OpenRouter is not in the default rotation but is routable when passed by id; after it fails the
     * sweep continues in default priority order (Gemini next). This pins the "selected id first, then
     * the remaining priority order" rule for a provider the defaults do not mention.
     */
    @Test
    fun `OpenRouter is routable by id even though it is not in the default rotation`() {
        val transport = RecordingTransport(
            mapOf(BuiltIn(ApiProvider.OPENROUTER) to reply("OpenRouter answering", "google/gemini-2.0-flash-exp:free")),
        )
        val router = newRouter(transport)

        val first = assertSuccess(runBlocking { router.executeChat("hello", "openrouter") })
        assertEquals(listOf(BuiltIn(ApiProvider.OPENROUTER)), transport.calledProviders)
        assertEquals(BuiltIn(ApiProvider.OPENROUTER), first.completion.provider)
        assertEquals("Brain: OpenRouter", router.state.value.brainLabel)

        transport.script(BuiltIn(ApiProvider.OPENROUTER), failure(429, "quota exhausted for today"))
        transport.script(BuiltIn(ApiProvider.GEMINI), reply("Gemini answering", "gemini-flash"))

        val second = assertSuccess(runBlocking { router.executeChat("hello", "openrouter") })

        assertEquals(listOf(BuiltIn(ApiProvider.OPENROUTER), BuiltIn(ApiProvider.OPENROUTER), BuiltIn(ApiProvider.GEMINI)), transport.calledProviders)
        assertEquals(listOf(BuiltIn(ApiProvider.OPENROUTER), BuiltIn(ApiProvider.GEMINI)), second.attempts.map { it.provider })
        assertEquals(BuiltIn(ApiProvider.GEMINI), router.activeBrain.value)
    }

    /**
     * Requirement 13: [MultiProviderRouter.setPriority] reorders routing and mirrors the order into
     * [RouterState.priority]; an empty list is ignored rather than leaving the router with nothing to
     * try. The second sweep proves the new order survives a real fallback decision.
     */
    @Test
    fun `setPriority reorders routing and is mirrored in state while an empty list is ignored`() {
        val transport = RecordingTransport(
            mapOf(BuiltIn(ApiProvider.CEREBRAS) to reply("Cerebras answering", "llama-3.3-70b")),
        )
        val router = newRouter(transport)

        router.setPriority(listOf(BuiltIn(ApiProvider.CEREBRAS), BuiltIn(ApiProvider.GROQ), BuiltIn(ApiProvider.GEMINI)))
        assertEquals(
            listOf(BuiltIn(ApiProvider.CEREBRAS), BuiltIn(ApiProvider.GROQ), BuiltIn(ApiProvider.GEMINI)),
            router.priority,
        )
        assertEquals(
            listOf(BuiltIn(ApiProvider.CEREBRAS), BuiltIn(ApiProvider.GROQ), BuiltIn(ApiProvider.GEMINI)),
            router.state.value.priority,
        )

        val first = assertSuccess(runBlocking { router.executeChat("hello") })
        assertEquals(listOf(BuiltIn(ApiProvider.CEREBRAS)), transport.calledProviders)
        assertEquals(BuiltIn(ApiProvider.CEREBRAS), first.completion.provider)

        // An empty order is ignored: the router keeps the last usable order instead of clearing it.
        router.setPriority(emptyList())
        assertEquals(
            listOf(BuiltIn(ApiProvider.CEREBRAS), BuiltIn(ApiProvider.GROQ), BuiltIn(ApiProvider.GEMINI)),
            router.priority,
        )
        assertEquals(
            listOf(BuiltIn(ApiProvider.CEREBRAS), BuiltIn(ApiProvider.GROQ), BuiltIn(ApiProvider.GEMINI)),
            router.state.value.priority,
        )

        // The reordered fallback still holds: Cerebras throws, Groq is out of quota, Gemini answers.
        transport.script(BuiltIn(ApiProvider.CEREBRAS), crash(IOException("cerebras unreachable")))
        transport.script(BuiltIn(ApiProvider.GROQ), failure(429, "quota exhausted for today"))
        transport.script(BuiltIn(ApiProvider.GEMINI), reply("Gemini answering", "gemini-flash"))

        val second = assertSuccess(runBlocking { router.executeChat("hello") })

        assertEquals(
            listOf(BuiltIn(ApiProvider.CEREBRAS), BuiltIn(ApiProvider.CEREBRAS), BuiltIn(ApiProvider.GROQ), BuiltIn(ApiProvider.GEMINI)),
            transport.calledProviders,
        )
        assertEquals(
            listOf(BuiltIn(ApiProvider.CEREBRAS), BuiltIn(ApiProvider.GROQ), BuiltIn(ApiProvider.GEMINI)),
            second.attempts.map { it.provider },
        )
        assertEquals(BuiltIn(ApiProvider.GEMINI), router.activeBrain.value)
    }

    // -----------------------------------------------------------------------------------------
    // D. Request hygiene: keys, blank prompts, cancellation and the exact request on the wire
    // -----------------------------------------------------------------------------------------

    /**
     * Requirement 10: a missing key is a [ProviderFailure.NoKey] verdict, not a request with an empty
     * credential — the transport must never be called for Gemini or Groq. The call log is the proof,
     * not the result alone.
     */
    @Test
    fun `a provider with no stored key is skipped without calling the transport`() {
        val vault = FakeVault(mapOf(ApiProvider.CEREBRAS to CEREBRAS_KEY))
        val transport = RecordingTransport(
            mapOf(BuiltIn(ApiProvider.CEREBRAS) to reply("Cerebras answering", "llama-3.3-70b")),
        )
        val router = MultiProviderRouter(vault, transport)

        val result = assertSuccess(runBlocking { router.executeChat("hello") })

        assertEquals(
            listOf(BuiltIn(ApiProvider.GEMINI), BuiltIn(ApiProvider.GROQ), BuiltIn(ApiProvider.CEREBRAS)),
            result.attempts.map { it.provider },
        )
        val geminiFailure = result.attempts[0].failure
        assertTrue("expected NoKey but was $geminiFailure", geminiFailure is ProviderFailure.NoKey)
        assertEquals(BuiltIn(ApiProvider.GEMINI), (geminiFailure as ProviderFailure.NoKey).provider)
        val groqFailure = result.attempts[1].failure
        assertTrue("expected NoKey but was $groqFailure", groqFailure is ProviderFailure.NoKey)
        assertEquals(BuiltIn(ApiProvider.GROQ), (groqFailure as ProviderFailure.NoKey).provider)
        assertNull(result.attempts[2].failure)

        assertEquals(listOf(BuiltIn(ApiProvider.CEREBRAS)), transport.calledProviders)
        assertEquals(1, vault.readCount(BuiltIn(ApiProvider.GEMINI)))
        assertEquals(1, vault.readCount(BuiltIn(ApiProvider.GROQ)))
        assertEquals(1, vault.readCount(BuiltIn(ApiProvider.CEREBRAS)))
    }

    /**
     * Requirement 11: a blank prompt is rejected before the router spends anything — no vault read and
     * no transport call — and the state is left exactly as it was (IDLE, no attempts, no brain).
     */
    @Test
    fun `a blank prompt attempts nothing at all`() {
        val vault = FakeVault(allKeys())
        val transport = RecordingTransport(
            mapOf(BuiltIn(ApiProvider.GEMINI) to reply("must never be reached", "gemini-flash")),
        )
        val router = MultiProviderRouter(vault, transport)

        assertEquals(ChatResult.AllFailed(emptyList()), runBlocking { router.executeChat("") })
        assertEquals(ChatResult.AllFailed(emptyList()), runBlocking { router.executeChat("   ") })

        assertEquals(0, vault.totalReads)
        assertEquals(emptyList<ApiProvider>(), transport.calledProviders)
        assertEquals(RouterState.Status.IDLE, router.state.value.status)
        assertEquals(emptyList<ProviderAttempt>(), router.state.value.lastAttempts)
        assertNull(router.activeBrain.value)
    }

    /**
     * Requirement 12: cancellation is not a provider failure. A [CancellationException] from the
     * transport must propagate out of `executeChat` unchanged and must not trigger a fallback — a
     * cancelled screen must stop spending quota, not silently move on to Groq.
     */
    @Test
    fun `a CancellationException from the transport propagates and triggers no fallback`() {
        val cancellation = CancellationException("route cancelled")
        val vault = FakeVault(allKeys())
        val transport = RecordingTransport(
            mapOf(
                BuiltIn(ApiProvider.GEMINI) to crash(cancellation),
                BuiltIn(ApiProvider.GROQ) to reply("must never be reached", "llama-3.3-70b"),
            ),
        )
        val router = MultiProviderRouter(vault, transport)

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { router.executeChat("hello") }
        }

        assertEquals("route cancelled", thrown.message)
        assertEquals(listOf(BuiltIn(ApiProvider.GEMINI)), transport.calledProviders)
        assertEquals(0, vault.readCount(BuiltIn(ApiProvider.GROQ)))
        assertEquals(0, vault.readCount(BuiltIn(ApiProvider.CEREBRAS)))
    }

    /**
     * Requirement 14: the request is assembled from the prompt verbatim, the vault key is trimmed
     * exactly once before it reaches the wire, the history starts empty, and the provider receives a
     * usable default system prompt. The fake records the whole [ChatRequest] so the assertion is made
     * on what the transport really saw.
     */
    @Test
    fun `the transport receives the exact prompt, the trimmed vault key and no history`() {
        // Deliberately padded: the router, not the transport, must be the trim point for vault values.
        val vault = FakeVault(mapOf(ApiProvider.GEMINI to "  gemini-key-42 \n"))
        val transport = RecordingTransport(
            mapOf(BuiltIn(ApiProvider.GEMINI) to reply("ok", "gemini-flash")),
        )
        val router = MultiProviderRouter(vault, transport)

        assertSuccess(runBlocking { router.executeChat("what is the weather in Pune?") })

        val call = transport.calls.single()
        assertEquals(BuiltIn(ApiProvider.GEMINI), call.provider)
        assertEquals("gemini-key-42", call.apiKey)
        assertEquals("what is the weather in Pune?", call.request.prompt)
        assertEquals(emptyList<ChatMessage>(), call.request.history)
        assertTrue(
            "a non-empty default system prompt must reach the transport",
            call.request.systemPrompt.isNotBlank(),
        )
    }

    /**
     * The [ChatRequest] overload is how the voice service keeps the old Groq fallback's persona: a
     * custom system prompt and the earlier turns must reach the transport exactly, and the prompt
     * itself must not be rewritten by the router.
     */
    @Test
    fun `the request overload passes a custom system prompt and history verbatim`() {
        val transport = RecordingTransport(
            mapOf(BuiltIn(ApiProvider.GROQ) to reply("Groq answering", "llama-3.3-70b")),
        )
        val router = newRouter(transport)
        val request = ChatRequest(
            prompt = "torch on",
            systemPrompt = "You cannot touch the phone. This is a test persona.",
            history = listOf(
                ChatMessage(ChatRole.USER, "hello"),
                ChatMessage(ChatRole.ASSISTANT, "hi"),
            ),
        )

        val result = assertSuccess(runBlocking { router.executeChat(request, "groq") })

        val call = transport.calls.single()
        assertEquals(request, call.request)
        assertEquals(BuiltIn(ApiProvider.GROQ), call.provider)
        assertEquals("You cannot touch the phone. This is a test persona.", call.request.systemPrompt)
        assertEquals(2, call.request.history.size)
        assertEquals("torch on", call.request.prompt)
        assertEquals("Groq answering", result.completion.text)
    }

    /**
     * The [ChatRequest] overload must reject a blank prompt on the same terms as the string form:
     * nothing is read from the vault, nothing is sent, and the state is left alone.
     */
    @Test
    fun `a blank prompt inside a request attempts nothing`() {
        val vault = FakeVault(allKeys())
        val transport = RecordingTransport()
        val router = MultiProviderRouter(vault, transport)

        assertEquals(
            ChatResult.AllFailed(emptyList()),
            runBlocking { router.executeChat(ChatRequest(prompt = "   ")) },
        )
        assertEquals(0, vault.totalReads)
        assertEquals(emptyList<ApiProvider>(), transport.calledProviders)
        assertEquals(RouterState.Status.IDLE, router.state.value.status)
    }

    /**
     * A tool-carrying turn bypasses the router, so the voice service reports the answering provider
     * itself. The badge and the state must both move to it, and no attempt may be invented: nothing
     * was routed, so the previous trail stays exactly as it was.
     */
    @Test
    fun `recordExternalReply moves the badge without inventing an attempt`() {
        val router = newRouter(RecordingTransport())

        router.recordExternalReply(BuiltIn(ApiProvider.GEMINI))

        assertEquals(BuiltIn(ApiProvider.GEMINI), router.activeBrain.value)
        assertEquals(BuiltIn(ApiProvider.GEMINI), router.state.value.activeProvider)
        assertEquals(RouterState.Status.READY, router.state.value.status)
        assertEquals("Brain: Gemini", router.state.value.brainLabel)
        assertEquals(emptyList<ProviderAttempt>(), router.state.value.lastAttempts)
        assertEquals(MultiProviderRouter.DEFAULT_PRIORITY, router.priority)
    }

    // -----------------------------------------------------------------------------------------
    // E. Failure taxonomy
    // -----------------------------------------------------------------------------------------

    /**
     * The routing-relevant classification of [ChatCall.Failure]: 429 and 402 are quota (valid key,
     * nothing left to spend), 400/401/403 are a refusal (fix the key/request), and every other status
     * — a 5xx, a 404, an unassigned 4xx — is a generic HTTP error. Each case must fall through to Groq
     * so a misclassification can never stall the sweep.
     */
    @Test
    fun `refusal codes are classified as quota, rejected or http errors`() {
        val transport = RecordingTransport(mapOf(BuiltIn(ApiProvider.GROQ) to reply("Groq answering", "llama-3.3-70b")))
        val router = newRouter(transport)

        fun failureFor(code: Int): ProviderFailure? {
            transport.script(BuiltIn(ApiProvider.GEMINI), failure(code, "refused with $code"))
            val result = assertSuccess(runBlocking { router.executeChat("hello") })
            assertEquals(listOf(BuiltIn(ApiProvider.GEMINI), BuiltIn(ApiProvider.GROQ)), result.attempts.map { it.provider })
            return result.attempts[0].failure
        }

        for (code in listOf(429, 402)) {
            val failure = failureFor(code)
            assertTrue("$code should be QuotaExceeded but was $failure", failure is ProviderFailure.QuotaExceeded)
            assertEquals(code, (failure as ProviderFailure.QuotaExceeded).httpCode)
        }
        for (code in listOf(400, 401, 403)) {
            val failure = failureFor(code)
            assertTrue("$code should be Rejected but was $failure", failure is ProviderFailure.Rejected)
            assertEquals(code, (failure as ProviderFailure.Rejected).httpCode)
        }
        for (code in listOf(404, 500, 502)) {
            val failure = failureFor(code)
            assertTrue("$code should be HttpError but was $failure", failure is ProviderFailure.HttpError)
            assertEquals(code, (failure as ProviderFailure.HttpError).httpCode)
        }
    }

    // -----------------------------------------------------------------------------------------
    // F. Test helpers
    // -----------------------------------------------------------------------------------------

    private fun allKeys(): Map<ApiProvider, String> = mapOf(
        ApiProvider.GEMINI to GEMINI_KEY,
        ApiProvider.GROQ to GROQ_KEY,
        ApiProvider.CEREBRAS to CEREBRAS_KEY,
        ApiProvider.OPENROUTER to OPENROUTER_KEY,
    )

    private fun newRouter(transport: RecordingTransport): MultiProviderRouter =
        MultiProviderRouter(FakeVault(allKeys()), transport)

    private fun assertSuccess(result: ChatResult): ChatResult.Success {
        assertTrue("expected ChatResult.Success but was $result", result is ChatResult.Success)
        return result as ChatResult.Success
    }

    private fun assertAllFailed(result: ChatResult): ChatResult.AllFailed {
        assertTrue("expected ChatResult.AllFailed but was $result", result is ChatResult.AllFailed)
        return result as ChatResult.AllFailed
    }

    private companion object {
        const val GEMINI_KEY = "gemini-key"
        const val GROQ_KEY = "groq-key"
        const val CEREBRAS_KEY = "cerebras-key"
        const val OPENROUTER_KEY = "openrouter-key"
    }
}

// ---------------------------------------------------------------------------------------------
// Fakes. Kept at the bottom of the file on purpose: the tests above are the contract, and these
// only exist so the real router can be driven with no Android, no network and no mocking framework.
// ---------------------------------------------------------------------------------------------

/**
 * [ApiKeyVault] without Android in it: a map of stored credentials plus a read counter.
 *
 * The counter is an assertion surface, not bookkeeping: "a missing key means no request" and "a blank
 * prompt reads nothing" are only provable if the test can show whether — and how often — the vault was
 * consulted.
 */
private class FakeVault(initial: Map<ApiProvider, String> = emptyMap()) : ApiKeyVault {

    private val stored = initial.toMutableMap()
    private val reads = mutableMapOf<ApiProvider, Int>()

    /** Total `read(provider)` calls across all providers, so "before any read" is observable. */
    var totalReads: Int = 0
        private set

    /** How many times the router asked for [provider]'s credential. */
    fun readCount(provider: ApiProvider): Int = reads[provider] ?: 0

    override fun read(provider: ApiProvider): String {
        reads[provider] = readCount(provider) + 1
        totalReads++
        return stored[provider].orEmpty()
    }

    /** Uncounted: the routing contract is one read per provider, and the router never calls this. */
    override fun readAll(): Map<ApiProvider, String> =
        ApiProvider.entries.associateWith { stored[it].orEmpty() }

    override fun write(provider: ApiProvider, apiKey: String) {
        stored[provider] = apiKey
    }

    override fun clear(provider: ApiProvider) {
        stored.remove(provider)
    }
}

/**
 * [ChatTransport] that answers from a per-provider script and records every call it receives.
 *
 * Recording is the point: several contracts in this suite are about calls that must **not** happen —
 * a later provider after a success, any provider when the key is missing, any provider after a
 * cancellation — and a fake that only returned values could not prove them.
 */
private class RecordingTransport(
    initial: Map<ApiProvider, Script> = emptyMap(),
) : ChatTransport {

    private val scripts = initial.toMutableMap()
    private val recorded = mutableListOf<RecordedCall>()

    /** Providers in call order, including repeats, so a multi-call test can assert the whole trail. */
    val calledProviders: List<ApiProvider> get() = recorded.map { it.provider }

    /** Every call in order, with the exact credential and request the transport received. */
    val calls: List<RecordedCall> get() = recorded.toList()

    /** Re-scripts one provider, so a test can make the same router go from healthy to broken. */
    fun script(provider: ApiProvider, script: Script) {
        scripts[provider] = script
    }

    override suspend fun complete(
        provider: ApiProvider,
        apiKey: String,
        request: ChatRequest,
    ): ChatCall {
        recorded += RecordedCall(provider, apiKey, request)
        val script = scripts[provider]
            ?: throw AssertionError("$provider was called but the test scripted no answer for it")
        return when (script) {
            is Script.Answer -> script.call
            is Script.Crash -> throw script.error
        }
    }

    /** One call as the transport saw it: who, with which credential, and with which request. */
    data class RecordedCall(
        val provider: ApiProvider,
        val apiKey: String,
        val request: ChatRequest,
    )
}

/** What [RecordingTransport] should do for one provider on its next call. */
private sealed interface Script {

    /** Return [call]: either a [ChatCall.Reply] or a [ChatCall.Failure]. */
    data class Answer(val call: ChatCall) : Script

    /** Throw [error] from `complete`, as a real socket, TLS or JSON stack could. */
    data class Crash(val error: Throwable) : Script
}

private fun reply(text: String, model: String = "test-model"): Script =
    Script.Answer(ChatCall.Reply(text, model))

private fun failure(code: Int, reason: String): Script =
    Script.Answer(ChatCall.Failure(code, reason))

private fun crash(error: Throwable): Script = Script.Crash(error)
