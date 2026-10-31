package com.seema.assistant.router

import com.seema.assistant.apikeys.data.ApiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the user-facing text derived from a failed turn.
 *
 * The voice service prints [ChatResult.AllFailed.failureSummary] under a failed reply, and chooses
 * between "the free tier is spent, keep the basics going" and "this is a real outage" from
 * [ChatResult.AllFailed.outOfQuota]. Both decisions are pure functions of the attempt trail, so they
 * are pinned here instead of being left to a device test.
 */
class ChatOutcomeTextTest {

    private fun attempt(provider: ApiProvider, failure: ProviderFailure?): ProviderAttempt =
        ProviderAttempt(provider, failure)

    @Test
    fun `the summary names every provider and its short reason in order`() {
        val result = ChatResult.AllFailed(
            listOf(
                attempt(
                    ApiProvider.GEMINI,
                    ProviderFailure.QuotaExceeded(ApiProvider.GEMINI, 429, "quota exhausted for today"),
                ),
                attempt(ApiProvider.GROQ, ProviderFailure.NoKey(ApiProvider.GROQ)),
                attempt(ApiProvider.CEREBRAS, ProviderFailure.Transport(ApiProvider.CEREBRAS, "socket closed")),
            ),
        )

        assertEquals(
            "Gemini: out of quota · Groq: no API key · Cerebras: no connection",
            result.failureSummary(),
        )
    }

    /** A blank prompt is "nothing was attempted", which is a different statement from "all failed". */
    @Test
    fun `an empty trail summarises to an empty line`() {
        assertEquals("", ChatResult.AllFailed(emptyList()).failureSummary())
    }

    @Test
    fun `a 402 is reported as out of credit rather than out of quota`() {
        val failure = ProviderFailure.QuotaExceeded(ApiProvider.OPENROUTER, 402, "insufficient credits")

        assertEquals("out of credit", failure.briefReason())
    }

    /** Only quota/credit refusals are "spend" problems; a dead socket is not. */
    @Test
    fun `outOfQuota is true only when a provider refused for quota or credit`() {
        val quota = ChatResult.AllFailed(
            listOf(
                attempt(
                    ApiProvider.GEMINI,
                    ProviderFailure.QuotaExceeded(ApiProvider.GEMINI, 429, "rate limited"),
                ),
            ),
        )
        val network = ChatResult.AllFailed(
            listOf(attempt(ApiProvider.GEMINI, ProviderFailure.Transport(ApiProvider.GEMINI, "unreachable"))),
        )
        val emptyReply = ChatResult.AllFailed(
            listOf(attempt(ApiProvider.GEMINI, ProviderFailure.EmptyReply(ApiProvider.GEMINI))),
        )

        assertTrue(quota.outOfQuota())
        assertFalse(network.outOfQuota())
        assertFalse(emptyReply.outOfQuota())
        assertFalse(ChatResult.AllFailed(emptyList()).outOfQuota())
    }

    @Test
    fun `brief reasons distinguish a missing key from an outage`() {
        assertEquals("no API key", ProviderFailure.NoKey(ApiProvider.GROQ).briefReason())
        assertEquals("empty reply", ProviderFailure.EmptyReply(ApiProvider.GEMINI).briefReason())
        assertEquals("no connection", ProviderFailure.Transport(ApiProvider.CEREBRAS, "timeout").briefReason())
        assertEquals(
            "key refused (HTTP 401)",
            ProviderFailure.Rejected(ApiProvider.GEMINI, 401, "invalid key").briefReason(),
        )
        assertEquals(
            "HTTP 503 error",
            ProviderFailure.HttpError(ApiProvider.CEREBRAS, 503, "unavailable").briefReason(),
        )
    }
}