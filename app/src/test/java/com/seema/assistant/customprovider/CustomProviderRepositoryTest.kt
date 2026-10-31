package com.seema.assistant.customprovider

import com.seema.assistant.customprovider.data.CustomProvider
import com.seema.assistant.customprovider.data.CustomProviderDao
import com.seema.assistant.customprovider.data.CustomProviderKeyVault
import com.seema.assistant.customprovider.data.CustomProviderProbeApi
import com.seema.assistant.customprovider.data.CustomProviderRepository
import com.seema.assistant.customprovider.data.CustomProviderSaveResult
import com.seema.assistant.customprovider.data.customProviderKeyAlias
import com.seema.assistant.customprovider.data.escapeForJsonString
import com.seema.assistant.customprovider.data.message
import com.seema.assistant.customprovider.data.normalizeBaseUrl
import com.seema.assistant.router.customProviderBrainId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

/**
 * Contract test for [CustomProviderRepository].
 *
 * The repository is the one place where a custom provider exists as two things at once — a row in
 * Room and a credential in the encrypted vault — so this suite drives the real repository with three
 * hand-written fakes ([FakeCustomProviderDao], [FakeCustomProviderKeyVault], [RecordingProbeApi])
 * and asserts the state that results, with no Android, no device and no network.
 *
 * The non-obvious cases carry the most weight:
 * - A save must put the key **only** in the vault: a database row holding the credential would leak
 *   it to any backup or root read, which is the entire reason the alias column exists.
 * - A blank field must never reach the probe. The probe counter is an assertion surface, not
 *   bookkeeping: "a typo costs no quota" is only provable if the test can show zero calls.
 * - A non-2xx response must store nothing, so a failed TEST cannot replace a working provider with a
 *   broken one.
 * - A thrown [IOException] is a failure with no HTTP code, never a crash; a
 *   [CancellationException] is not a failure at all and must escape.
 * - Re-saving the same name must update that one row in place (the unique index on `name`), keeping
 *   the id and `createdAt` so the provider does not jump around the list.
 *
 * The assertions are deliberately exact, including the request URL, the Authorization header and the
 * literal JSON body, because "some request was sent" is not the contract.
 */
class CustomProviderRepositoryTest {

    // -----------------------------------------------------------------------------------------
    // A. A successful save: the vault/row split and the exact wire format
    // -----------------------------------------------------------------------------------------

    /**
     * Requirement 1: a 200 stores the provider, the credential lands in the vault under the row's
     * alias, and the database row (and its `toString()`) never contains the secret. The request is
     * pinned down exactly: trailing slash trimmed from the base URL, one `/chat/completions` path,
     * `Authorization: Bearer <key>`, and the OpenAI-compatible body with `max_tokens: 1`.
     */
    @Test
    fun `a 200 stores the row and keeps the key in the vault, not the database`() {
        val dao = FakeCustomProviderDao()
        val vault = FakeCustomProviderKeyVault()
        val probe = RecordingProbeApi()
        val repository = repository(dao, vault, probe)

        val saved = assertSaved(
            runBlocking { repository.testAndSave(NAME, BASE_URL_WITH_SLASH, API_KEY, MODEL_ID) },
        )

        assertEquals(1L, saved.row.id)
        assertEquals(NAME, saved.row.name)
        assertEquals(NORMALIZED_BASE_URL, saved.row.baseUrl)
        assertEquals(MODEL_ID, saved.row.modelId)
        assertEquals(customProviderKeyAlias(NAME), saved.row.apiKeyAlias)

        // The vault holds the credential under exactly the alias the row names...
        assertEquals(API_KEY, vault.read(saved.row.apiKeyAlias))
        // ...and the persisted row holds neither the key nor a copy of it.
        assertEquals(listOf(saved.row), dao.stored)
        assertNotEquals(API_KEY, dao.stored.single().apiKeyAlias)
        assertFalse(dao.stored.single().toString().contains(API_KEY))

        assertEquals(1, probe.callCount)
        val call = probe.calls.single()
        assertEquals("$NORMALIZED_BASE_URL/chat/completions", call.url)
        assertEquals(mapOf("Authorization" to "Bearer $API_KEY"), call.headers)
        assertEquals(
            """{"model":"$MODEL_ID","messages":[{"role":"user","content":"ping"}],"max_tokens":1}""",
            call.body,
        )

        // The row is immediately routable: one target, its key decrypted, not a built-in provider.
        val target = runBlocking { repository.loadRouterTargets() }.single()
        assertEquals(API_KEY, target.apiKey)
    }

    // -----------------------------------------------------------------------------------------
    // B. Refusals: nothing may be stored
    // -----------------------------------------------------------------------------------------

    /**
     * Requirement 2: a 401 is the provider's verdict on the credential. Nothing may be saved and the
     * failure must name the code and say what to do about it, because "TEST failed" without the
     * reason leaves the user editing the wrong field.
     */
    @Test
    fun `a 401 saves nothing and reports the code`() {
        val dao = FakeCustomProviderDao()
        val vault = FakeCustomProviderKeyVault()
        val probe = RecordingProbeApi(ProbeScript.Answer(401, """{"error":"invalid api key"}"""))
        val repository = repository(dao, vault, probe)

        val result = runBlocking { repository.testAndSave(NAME, BASE_URL_WITH_SLASH, API_KEY, MODEL_ID) }

        assertEquals(CustomProviderSaveResult.Failed(401, "Check the API key"), result)
        assertEquals("Check the API key (HTTP 401)", result.message)
        assertEquals(0, dao.stored.size)
        assertTrue(vault.written.isEmpty())
        assertEquals(1, probe.callCount)
    }

    /**
     * Requirement 3: a 429 means the credential was accepted and the account is out of capacity,
     * which is a different instruction from "fix your key" — so it must not be stored either, but it
     * must not be worded as an auth failure.
     */
    @Test
    fun `a 429 saves nothing and reports rate limiting`() {
        val dao = FakeCustomProviderDao()
        val vault = FakeCustomProviderKeyVault()
        val probe = RecordingProbeApi(ProbeScript.Answer(429, "slow down"))
        val repository = repository(dao, vault, probe)

        val result = runBlocking { repository.testAndSave(NAME, BASE_URL_WITH_SLASH, API_KEY, MODEL_ID) }

        assertEquals(CustomProviderSaveResult.Failed(429, "Rate limited; try again shortly"), result)
        assertEquals("Rate limited; try again shortly (HTTP 429)", result.message)
        assertEquals(0, dao.stored.size)
        assertTrue(vault.written.isEmpty())
    }

    /**
     * Requirement 4: a blank field is caught before the network is touched. This is the important
     * one: the probe counter must be exactly zero for every field, because a single accidental
     * request would spend the user's quota on input that could not have been valid.
     */
    @Test
    fun `a blank or invalid field fails without spending a request`() {
        val dao = FakeCustomProviderDao()
        val vault = FakeCustomProviderKeyVault()
        val probe = RecordingProbeApi()
        val repository = repository(dao, vault, probe)

        val results = runBlocking {
            listOf(
                "Name is required" to repository.testAndSave("   ", BASE_URL_WITH_SLASH, API_KEY, MODEL_ID),
                "Base URL is required" to repository.testAndSave(NAME, "   ", API_KEY, MODEL_ID),
                "API key is required" to repository.testAndSave(NAME, BASE_URL_WITH_SLASH, "  ", MODEL_ID),
                "Model ID is required" to repository.testAndSave(NAME, BASE_URL_WITH_SLASH, API_KEY, "\t"),
                INVALID_BASE_URL_REASON to repository.testAndSave(NAME, "ftp://api.example.com", API_KEY, MODEL_ID),
                INVALID_BASE_URL_REASON to repository.testAndSave(NAME, "api.example.com/v1", API_KEY, MODEL_ID),
            )
        }

        for ((reason, result) in results) {
            assertEquals(CustomProviderSaveResult.Failed(null, reason), result)
        }
        assertEquals(0, probe.callCount)
        assertEquals(0, vault.writeCount)
        assertEquals(0, dao.stored.size)
    }

    /**
     * Requirement 5: a dead socket is a failure with no HTTP code and stores nothing. The catch must
     * also not swallow cancellation — which is why the cancellation case below is separate.
     */
    @Test
    fun `an IOException is a failure with no code and saves nothing`() {
        val dao = FakeCustomProviderDao()
        val vault = FakeCustomProviderKeyVault()
        val probe = RecordingProbeApi(ProbeScript.Crash(IOException("connection reset by peer")))
        val repository = repository(dao, vault, probe)

        val result = runBlocking { repository.testAndSave(NAME, BASE_URL_WITH_SLASH, API_KEY, MODEL_ID) }

        assertEquals(CustomProviderSaveResult.Failed(null, "connection reset by peer"), result)
        assertEquals("connection reset by peer", result.message)
        assertEquals(0, dao.stored.size)
        assertTrue(vault.written.isEmpty())
    }

    /**
     * Requirement 6: cancellation is not a provider verdict. A cleared screen cancels the request,
     * and the repository must rethrow rather than turn that into "the provider is unreachable" and
     * store nothing on the way out.
     */
    @Test
    fun `cancellation from the probe is rethrown and stores nothing`() {
        val dao = FakeCustomProviderDao()
        val vault = FakeCustomProviderKeyVault()
        val probe = RecordingProbeApi(ProbeScript.Crash(CancellationException("screen closed")))
        val repository = repository(dao, vault, probe)

        try {
            runBlocking { repository.testAndSave(NAME, BASE_URL_WITH_SLASH, API_KEY, MODEL_ID) }
            fail("expected the CancellationException to escape the repository")
        } catch (expected: CancellationException) {
            assertEquals("screen closed", expected.message)
        }

        assertEquals(0, dao.stored.size)
        assertTrue(vault.written.isEmpty())
    }

    /**
     * Requirement 7: an unusable encrypted store must not produce a stored provider whose key lives
     * nowhere. The save fails; the database keeps whatever it had.
     */
    @Test
    fun `an unusable secure store fails the save and stores nothing`() {
        val dao = FakeCustomProviderDao()
        val vault = FakeCustomProviderKeyVault(failWrites = true)
        val probe = RecordingProbeApi()
        val repository = repository(dao, vault, probe)

        val result = runBlocking { repository.testAndSave(NAME, BASE_URL_WITH_SLASH, API_KEY, MODEL_ID) }

        assertEquals(CustomProviderSaveResult.Failed(null, SAVE_FAILURE_REASON), result)
        assertEquals(0, dao.stored.size)
    }

    // -----------------------------------------------------------------------------------------
    // C. Upsert by unique name
    // -----------------------------------------------------------------------------------------

    /**
     * Requirement 8: `name` is unique, so a second save for the same name must update that row
     * instead of inserting a duplicate or hitting the index. The id and `createdAt` are kept so the
     * provider does not move in the list (which is the routing order) and the vault slot is reused.
     */
    @Test
    fun `re-saving a name updates the same row and keeps its creation time`() {
        val dao = FakeCustomProviderDao()
        val vault = FakeCustomProviderKeyVault()
        val probe = RecordingProbeApi()
        var now = CREATED_AT
        val repository = repository(dao, vault, probe, clock = { now })

        val first = assertSaved(
            runBlocking { repository.testAndSave(NAME, BASE_URL_WITH_SLASH, FIRST_KEY, MODEL_ID) },
        )
        now = CREATED_AT + 60_000L
        val second = assertSaved(
            runBlocking { repository.testAndSave(NAME, OTHER_BASE_URL, SECOND_KEY, OTHER_MODEL_ID) },
        )

        assertEquals(first.row.id, second.row.id)
        assertEquals(1, dao.stored.size)
        assertEquals(OTHER_BASE_URL, dao.stored.single().baseUrl)
        assertEquals(OTHER_MODEL_ID, dao.stored.single().modelId)
        assertEquals(CREATED_AT, second.row.createdAt)
        assertEquals(customProviderKeyAlias(NAME), second.row.apiKeyAlias)
        assertEquals(SECOND_KEY, vault.read(second.row.apiKeyAlias))
    }

    // -----------------------------------------------------------------------------------------
    // D. The router's load path and delete
    // -----------------------------------------------------------------------------------------

    /**
     * Requirement 9: one target per row, in `createdAt` order, each carrying its decrypted key and
     * reporting itself as not built-in. The router joins on `id`, so the id must be the prefixed
     * `custom:` brain id rather than the bare primary key.
     */
    @Test
    fun `loadRouterTargets joins every row to its decrypted key`() {
        val dao = FakeCustomProviderDao()
        val vault = FakeCustomProviderKeyVault()
        val repository = repository(dao, vault, RecordingProbeApi())

        dao.seed(
            CustomProvider(
                id = 3L,
                name = "First",
                baseUrl = "https://first.example.com/v1",
                modelId = "first-model",
                apiKeyAlias = customProviderKeyAlias("First"),
                createdAt = 10L,
            ),
        )
        dao.seed(
            CustomProvider(
                id = 7L,
                name = "Second",
                baseUrl = "https://second.example.com/v1",
                modelId = "second-model",
                apiKeyAlias = customProviderKeyAlias("Second"),
                createdAt = 20L,
            ),
        )
        vault.seed(customProviderKeyAlias("First"), "first-key")
        vault.seed(customProviderKeyAlias("Second"), "second-key")

        val targets = runBlocking { repository.loadRouterTargets() }

        assertEquals(listOf(customProviderBrainId(3L), customProviderBrainId(7L)), targets.map { it.id })
        val first = targets[0]
        assertEquals("First", first.displayName)
        assertEquals("https://first.example.com/v1", first.baseUrl)
        assertEquals("first-key", first.apiKey)
        assertEquals("first-model", first.modelId)
        assertFalse(first.isBuiltIn)
        assertNull(first.builtIn)
        assertEquals(3L, first.origin.rowId)
        assertEquals(customProviderKeyAlias("First"), first.origin.apiKeyAlias)
        assertEquals(10L, first.origin.createdAt)
        assertEquals("second-key", targets[1].apiKey)
    }

    /**
     * Requirement 10: deleting removes the row **and** the credential. Leaving the vault entry would
     * keep a deleted provider's key on disk with nothing left to delete it by.
     */
    @Test
    fun `deleting a row clears its vault entry`() {
        val dao = FakeCustomProviderDao()
        val vault = FakeCustomProviderKeyVault()
        val repository = repository(dao, vault, RecordingProbeApi())

        val saved = assertSaved(
            runBlocking { repository.testAndSave(NAME, BASE_URL_WITH_SLASH, API_KEY, MODEL_ID) },
        )
        assertEquals(API_KEY, vault.read(saved.row.apiKeyAlias))

        runBlocking { repository.delete(saved.row) }

        assertEquals(0, dao.stored.size)
        assertEquals(listOf(saved.row), dao.deleted)
        assertEquals(listOf(saved.row.apiKeyAlias), vault.cleared)
        assertEquals("", vault.read(saved.row.apiKeyAlias))
    }

    // -----------------------------------------------------------------------------------------
    // E. The pure helpers, which is where the URL and JSON rules can be pinned down exactly
    // -----------------------------------------------------------------------------------------

    /**
     * Requirement 11: the base URL rule is pure Kotlin so it can be proven without a socket. Only
     * http(s) with a host survives; the trailing slash is removed because the caller appends a path.
     */
    @Test
    fun `normalizeBaseUrl accepts http and https with a host and rejects everything else`() {
        assertEquals("https://api.example.com/v1", normalizeBaseUrl("https://api.example.com/v1/"))
        assertEquals("https://api.example.com/v1", normalizeBaseUrl("  https://api.example.com/v1//  "))
        assertEquals("http://localhost:8080", normalizeBaseUrl("http://localhost:8080/"))
        // The user's own casing is preserved; only the scheme comparison is case-insensitive.
        assertEquals("HTTPS://api.example.com", normalizeBaseUrl("HTTPS://api.example.com/"))

        assertNull(normalizeBaseUrl(""))
        assertNull(normalizeBaseUrl("   "))
        assertNull(normalizeBaseUrl("ftp://api.example.com"))
        assertNull(normalizeBaseUrl("api.example.com/v1"))
        assertNull(normalizeBaseUrl("https://"))
        assertNull(normalizeBaseUrl("https:///v1"))
        assertNull(normalizeBaseUrl("https://api example.com"))
        assertNull(normalizeBaseUrl("javascript:alert(1)"))
    }

    /**
     * Requirement 12: the only user input that reaches the request body is the model id, and a model
     * id containing a quote must not be able to forge a second JSON field.
     */
    @Test
    fun `a model id with a quote cannot forge a second JSON field`() {
        assertEquals("m\\\"x", escapeForJsonString("m\"x"))
        assertEquals("a\\\\b", escapeForJsonString("a\\b"))
        assertEquals("line\\nbreak", escapeForJsonString("line\nbreak"))
        assertEquals("tab\\there", escapeForJsonString("tab\there"))
        assertEquals("\\u0001", escapeForJsonString("\u0001"))

        val dao = FakeCustomProviderDao()
        val probe = RecordingProbeApi()
        val repository = repository(dao, FakeCustomProviderKeyVault(), probe)
        val hostileModelId = "m\",\"evil\":\"1"

        assertSaved(
            runBlocking { repository.testAndSave(NAME, BASE_URL_WITH_SLASH, API_KEY, hostileModelId) },
        )

        assertEquals(
            """{"model":"m\",\"evil\":\"1","messages":[{"role":"user","content":"ping"}],"max_tokens":1}""",
            probe.calls.single().body,
        )
    }

    // -----------------------------------------------------------------------------------------
    // F. Test helpers
    // -----------------------------------------------------------------------------------------

    private fun repository(
        dao: FakeCustomProviderDao,
        vault: FakeCustomProviderKeyVault,
        probe: RecordingProbeApi,
        clock: () -> Long = { CREATED_AT },
    ): CustomProviderRepository = CustomProviderRepository(dao, vault, probe, clock)

    private fun assertSaved(result: CustomProviderSaveResult): CustomProviderSaveResult.Saved {
        assertTrue("expected CustomProviderSaveResult.Saved but was $result", result is CustomProviderSaveResult.Saved)
        return result as CustomProviderSaveResult.Saved
    }

    private companion object {
        const val NAME = "My Provider"
        const val BASE_URL_WITH_SLASH = "https://api.example.com/v1/"
        const val NORMALIZED_BASE_URL = "https://api.example.com/v1"
        const val OTHER_BASE_URL = "https://other.example.com/v2"
        const val API_KEY = "sk-secret-key-value"
        const val FIRST_KEY = "sk-first"
        const val SECOND_KEY = "sk-second"
        const val MODEL_ID = "gpt-4o-mini"
        const val OTHER_MODEL_ID = "gpt-4o"
        const val CREATED_AT = 1_700_000_000_000L
        const val INVALID_BASE_URL_REASON = "Base URL must start with http:// or https://"
        const val SAVE_FAILURE_REASON = "The provider could not be saved"
    }
}

// ---------------------------------------------------------------------------------------------
// Fakes. Kept at the bottom of the file on purpose: the tests above are the contract, and these
// only exist so the real repository can be driven with no Android, no network and no mocking
// framework.
// ---------------------------------------------------------------------------------------------

/**
 * [CustomProviderDao] over an in-memory list.
 *
 * It records what it was asked to do ([upserts], [deleted]) so the tests can prove that a failed
 * save never reached the write path, and it assigns ids the way Room's `autoGenerate` does, so a
 * `Saved` row names the row the database actually holds.
 */
private class FakeCustomProviderDao : CustomProviderDao {

    private val rows = mutableListOf<CustomProvider>()
    private val changes = MutableStateFlow<List<CustomProvider>>(emptyList())
    private var nextId = 1L

    /** Everything currently stored, newest insert last. */
    val stored: List<CustomProvider> get() = rows.toList()

    /** Every row handed to [upsert], including failed attempts. */
    val upserts: MutableList<CustomProvider> = mutableListOf()

    /** Every row handed to [delete]. */
    val deleted: MutableList<CustomProvider> = mutableListOf()

    /** Adds a row that was already in the database, for tests that need a preset id. */
    fun seed(row: CustomProvider) {
        rows += row
        changes.value = rows.toList()
    }

    override fun observeAll(): Flow<List<CustomProvider>> = changes

    override suspend fun getAll(): List<CustomProvider> = rows.sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun findById(id: Long): CustomProvider? = rows.firstOrNull { it.id == id }

    override suspend fun upsert(row: CustomProvider): Long {
        upserts += row
        if (row.id != 0L) {
            val index = rows.indexOfFirst { it.id == row.id }
            if (index >= 0) {
                rows[index] = row
                changes.value = rows.toList()
                return row.id
            }
        }
        val id = nextId++
        rows += row.copy(id = id)
        changes.value = rows.toList()
        return id
    }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
        changes.value = rows.toList()
    }

    override suspend fun delete(row: CustomProvider) {
        deleted += row
        rows.removeAll { it.id == row.id }
        changes.value = rows.toList()
    }
}

/**
 * [CustomProviderKeyVault] over a map.
 *
 * [writeCount] and [cleared] are assertion surfaces: "a validation failure never wrote a key" and
 * "deleting the row cleared exactly one slot" are only provable if the fake can report them.
 * [failWrites] models the encrypted store being unusable, which must fail the save rather than
 * produce a stored provider with no credential.
 */
private class FakeCustomProviderKeyVault(
    private val failWrites: Boolean = false,
) : CustomProviderKeyVault {

    private val entries = mutableMapOf<String, String>()

    /** Every stored alias and the credential under it; the key material either way. */
    val written: Map<String, String> get() = entries.toMap()

    /** Aliases passed to [clear], in order. */
    val cleared: MutableList<String> = mutableListOf()

    /** How many times a credential was stored, including attempts that threw. */
    var writeCount: Int = 0
        private set

    /** Puts a credential in the fake store directly, for tests with a preset row. */
    fun seed(alias: String, apiKey: String) {
        entries[alias] = apiKey
    }

    override fun read(alias: String): String = entries[alias].orEmpty()

    override fun write(alias: String, apiKey: String) {
        writeCount++
        if (failWrites) throw IllegalStateException("keystore unavailable")
        entries[alias] = apiKey
    }

    override fun clear(alias: String) {
        cleared += alias
        entries.remove(alias)
    }
}

/**
 * [CustomProviderProbeApi] that records each call and answers from a script.
 *
 * The recorded body is read out of the [RequestBody] with an [okio.Buffer], which is how the tests
 * assert the exact JSON that would have gone on the wire.
 */
private class RecordingProbeApi(
    private var script: ProbeScript = ProbeScript.Answer(200),
) : CustomProviderProbeApi {

    private val recorded = mutableListOf<RecordedProbe>()

    /** Every call in order, with the exact URL, headers and body the repository sent. */
    val calls: List<RecordedProbe> get() = recorded.toList()

    /** Call count, so "no request was made" is directly assertable. */
    val callCount: Int get() = recorded.size

    override suspend fun probe(
        url: String,
        headers: Map<String, String>,
        body: RequestBody,
    ): Response<ResponseBody> {
        recorded += RecordedProbe(
            url = url,
            headers = headers.toMap(),
            body = Buffer().also { body.writeTo(it) }.readUtf8(),
        )
        return when (val answer = script) {
            is ProbeScript.Answer ->
                if (answer.code in 200..299) {
                    Response.success(answer.body.toResponseBody(TEXT_PLAIN))
                } else {
                    Response.error(answer.code, answer.body.toResponseBody(TEXT_PLAIN))
                }
            is ProbeScript.Crash -> throw answer.error
        }
    }

    /** One probe as the fake saw it. */
    data class RecordedProbe(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )
}

/** What [RecordingProbeApi] should do on its next call. */
private sealed interface ProbeScript {

    /** Answer with [code] and [body], as a real HTTP response would. */
    data class Answer(val code: Int, val body: String = "") : ProbeScript

    /** Throw [error] from the call, as a dead socket or a cancelled coroutine does. */
    data class Crash(val error: Throwable) : ProbeScript
}

/** Media type the fake response bodies are declared with; the repository never reads them. */
private val TEXT_PLAIN = "text/plain".toMediaType()
