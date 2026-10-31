package com.seema.assistant.apikeys

import com.seema.assistant.apikeys.data.ProviderStatus
import com.seema.assistant.apikeys.data.chipLabel
import com.seema.assistant.apikeys.data.httpCode
import com.seema.assistant.apikeys.data.providerStatusFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract test for the API Key Manager's verdict reduction.
 *
 * It calls the real [providerStatusFor] and the real [chipLabel] — no fake, no double — because the
 * mapping is the one place where a wrong branch tells the user the wrong thing: "your key is wrong",
 * "your key is fine but out of quota", or "the provider is broken". Everything asserted here lives in
 * `com.seema.assistant.apikeys.data` and imports no Android, OkHttp or Retrofit type, so this runs as
 * a plain JVM test with no Robolectric, no device and no network.
 *
 * The package is the parent of `...apikeys.data` on purpose: the function under test is
 * `internal`, and the unit-test source set of this module is the friend of the main source set.
 */
class ProviderStatusMappingTest {

    // -----------------------------------------------------------------------------------------
    // A. Status mapping
    // -----------------------------------------------------------------------------------------

    /**
     * 2xx means the credential authenticated. Asserted with the concrete code, not just the class,
     * so a mapping that dropped the status (`Active()` with a default code) would fail here rather
     * than silently report HTTP 200 for a 204.
     */
    @Test
    fun `every code in the 2xx class is Active and keeps its own code`() {
        assertEquals(ProviderStatus.Active(200), providerStatusFor(200))
        assertEquals(ProviderStatus.Active(201), providerStatusFor(201))
        assertEquals(ProviderStatus.Active(204), providerStatusFor(204))
        assertEquals(ProviderStatus.Active(299), providerStatusFor(299))
    }

    /** The class boundary, not an arbitrary 200: 199 is not a success, and 300 is not either. */
    @Test
    fun `the edges of the 2xx class are exclusive`() {
        assertEquals(ProviderStatus.ProviderError(199), providerStatusFor(199))
        assertEquals(ProviderStatus.ProviderError(300), providerStatusFor(300))
    }

    /**
     * 400 is grouped with 401/403 deliberately: Google answers an *invalid* Gemini key with
     * `400 INVALID_ARGUMENT`, so mapping 400 to a generic error would tell a user with a typo'd key
     * to retry later instead of fixing the key.
     */
    @Test
    fun `400 401 and 403 are Invalid`() {
        assertEquals(ProviderStatus.Invalid(400), providerStatusFor(400))
        assertEquals(ProviderStatus.Invalid(401), providerStatusFor(401))
        assertEquals(ProviderStatus.Invalid(403), providerStatusFor(403))
    }

    /** The 300/400 boundary: 399 must not fall into the Invalid branch that starts at 400. */
    @Test
    fun `399 is a ProviderError and 400 starts the Invalid band`() {
        assertEquals(ProviderStatus.ProviderError(399), providerStatusFor(399))
        assertEquals(ProviderStatus.Invalid(400), providerStatusFor(400))
    }

    /**
     * 402 and 429 both mean "the credential is valid but cannot spend right now". They must not be
     * collapsed into Invalid: the fix for the user is to top up or wait, not to re-enter the key.
     */
    @Test
    fun `402 and 429 are QuotaLow`() {
        assertEquals(ProviderStatus.QuotaLow(402), providerStatusFor(402))
        assertEquals(ProviderStatus.QuotaLow(429), providerStatusFor(429))
    }

    /**
     * The 429/430 boundary: 429 is the last QuotaLow code and 430 is one step past it. 428 sits
     * directly below the rate limit and must not be swept into the QuotaLow band either.
     */
    @Test
    fun `428 is a ProviderError while 429 is QuotaLow and 430 is a ProviderError`() {
        assertEquals(ProviderStatus.ProviderError(428), providerStatusFor(428))
        assertEquals(ProviderStatus.QuotaLow(429), providerStatusFor(429))
        assertEquals(ProviderStatus.ProviderError(430), providerStatusFor(430))
    }

    /**
     * Everything the branches above did not claim — a 5xx, an unassigned 4xx, or the sentinel 0 a
     * cancelled/failed call can carry — is reported as a provider error, never as a key verdict.
     */
    @Test
    fun `500 503 418 and 0 are ProviderError`() {
        assertEquals(ProviderStatus.ProviderError(500), providerStatusFor(500))
        assertEquals(ProviderStatus.ProviderError(503), providerStatusFor(503))
        assertEquals(ProviderStatus.ProviderError(418), providerStatusFor(418))
        assertEquals(ProviderStatus.ProviderError(0), providerStatusFor(0))
    }

    // -----------------------------------------------------------------------------------------
    // B. Chip captions
    // -----------------------------------------------------------------------------------------

    /**
     * The three captions are a verbatim UI contract: the screen was specified with these exact
     * strings, including the emoji and the single spaces. Widening the message, dropping the emoji
     * or "fixing" the spelling of Quota Low must fail this test.
     */
    @Test
    fun `the three specified chip captions are exact`() {
        assertEquals("✅ Active", ProviderStatus.Active(200).chipLabel)
        assertEquals("❌ Invalid", ProviderStatus.Invalid(401).chipLabel)
        assertEquals("⚠️ Quota Low", ProviderStatus.QuotaLow(429).chipLabel)
    }

    /**
     * End-to-end through both halves of the contract: the HTTP code the probe received, reduced by
     * the real mapping and rendered by the real caption, must produce the specified chip. This is the
     * assertion that actually matches the brief's 200/401/429 row, rather than the mapping alone.
     */
    @Test
    fun `200 401 and 429 map to the specified chips`() {
        assertEquals("✅ Active", providerStatusFor(200).chipLabel)
        assertEquals("❌ Invalid", providerStatusFor(401).chipLabel)
        assertEquals("⚠️ Quota Low", providerStatusFor(429).chipLabel)
    }

    /**
     * The screen also prints the raw code under the chip (`entry.status.httpCode`), so the mapping
     * must carry the code through instead of discarding it.
     */
    @Test
    fun `the mapped status keeps the http code for the telemetry line`() {
        assertEquals(201, providerStatusFor(201).httpCode)
        assertEquals(401, providerStatusFor(401).httpCode)
        assertEquals(429, providerStatusFor(429).httpCode)
        assertEquals(500, providerStatusFor(500).httpCode)
    }
}
