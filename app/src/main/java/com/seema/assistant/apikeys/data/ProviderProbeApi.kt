package com.seema.assistant.apikeys.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.Url

/**
 * The one HTTP call the API Key Manager makes.
 *
 * [url] arrives as an argument because [ApiProvider] already owns the cheapest authenticated
 * endpoint each provider offers, so a Retrofit interface per provider would declare the same
 * call four times. The credential travels in [headers], never in the URL, so it cannot end up in
 * a URL log or a cache key.
 */
interface ProviderProbeApi {

    @GET
    suspend fun probe(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
    ): Response<ResponseBody>
}

/** Placeholder base URL: a reserved TLD, so a mis-routed call fails fast instead of hitting
 *  someone's real host. Every call overrides it with @Url. */
private const val PROBE_BASE_URL = "https://api.seema.invalid/"

/**
 * Lazily built, process-wide HTTP stack for probes.
 *
 * Both members are memoised: a fresh OkHttpClient per TEST press would strand a connection pool
 * and dispatcher each time, and Retrofit construction is not free either.
 */
object ApiKeyHttp {

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // A foreground TEST may wait 15s for headers, but not indefinitely: an honest
            // "Unreachable" beats a spinner the user cannot cancel.
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            // Off on purpose: a retried probe could report a success the user then trusts, while
            // the first attempt is the fact we are trying to show.
            .retryOnConnectionFailure(false)
            .build()
    }

    private val defaultProbeApi: ProviderProbeApi by lazy {
        Retrofit.Builder()
            .baseUrl(PROBE_BASE_URL)
            .client(defaultClient)
            .build()
            .create(ProviderProbeApi::class.java)
    }

    /**
     * The shared client.
     *
     * No logging interceptor is installed, and none may ever be: it would print the
     * `Authorization` / `x-goog-api-key` header, i.e. the user's credential, to Logcat.
     */
    fun client(): OkHttpClient = defaultClient

    /**
     * The shared probe interface.
     *
     * No converter factory is registered because `ResponseBody` is served by Retrofit's built-in
     * converter — the body is closed unread, never parsed.
     */
    fun probeApi(): ProviderProbeApi = defaultProbeApi
}

/**
 * Turns one live probe into a [ProviderStatus].
 *
 * Separate from [ProviderKeyRepository] so a test can hand it a fake [ProviderProbeApi] and
 * exercise the real status reduction with neither network nor Android context.
 */
class ProviderApiTester(private val probe: ProviderProbeApi) {

    /**
     * Probes [provider] with [apiKey].
     *
     * The key is trimmed first; a blank field is [ProviderStatus.Missing] and costs no call.
     * Only [IOException] is caught, because that is the transport itself failing (DNS, TLS,
     * timeout, connection refused). A 401 or a 500 still arrives as a [Response] and is the
     * provider's own verdict, so routing it through the catch would misreport it as unreachable.
     * Retrofit's suspend calls already run off the main thread, so no dispatcher hop is added.
     */
    suspend fun test(provider: ApiProvider, apiKey: String): ProviderStatus {
        val key = apiKey.trim()
        if (key.isEmpty()) return ProviderStatus.Missing

        return try {
            val response = probe.probe(provider.probeUrl, provider.authHeaders(key))
            val status = providerStatusFor(response.code())
            // The body is never read — the status code is the whole verdict — but it must still
            // be closed or the connection stays checked out and repeated TEST presses leak
            // sockets. Exactly one of body/errorBody is non-null for a given outcome.
            response.body()?.close()
            response.errorBody()?.close()
            status
        } catch (e: CancellationException) {
            // A cleared ViewModel must cancel rather than be reported as a dead network.
            throw e
        } catch (e: IOException) {
            ProviderStatus.Unreachable(e.message ?: "Network error")
        } catch (e: Exception) {
            // Anything else the stack can throw (a malformed @Url, a TLS failure that surfaces as a
            // RuntimeException) is still a failed probe, never a verdict about the key. Letting it
            // escape would crash the app and leave the row stuck on TESTING with its button dead.
            ProviderStatus.Unreachable(e::class.java.simpleName)
        }
    }
}
