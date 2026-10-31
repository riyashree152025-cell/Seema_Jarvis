package com.seema.assistant.customprovider.data

import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * The one HTTP call the Custom Provider save flow makes: an OpenAI-compatible `chat/completions`
 * probe.
 *
 * The URL is an argument because a custom provider's base URL is data the user typed at runtime, so
 * a static Retrofit base URL cannot express it. The credential travels in [headers] and never in the
 * URL, so a URL that reaches a log or a cache key never carries the key.
 *
 * The request body is a [RequestBody] rather than a data class because this module has no JSON
 * converter on its classpath (no Gson, Moshi or kotlinx-serialization), and adding one for a single
 * three-field ping would be a new dependency to keep patched. Retrofit's built-in converter passes a
 * pre-built body through untouched.
 */
interface CustomProviderProbeApi {

    @POST
    suspend fun probe(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
    ): Response<ResponseBody>
}

/**
 * Lazily built, process-wide HTTP stack for custom-provider probes.
 *
 * Both members are memoised for the same reason as the built-in probe stack: a fresh OkHttpClient
 * per SAVE press would strand a connection pool and a dispatcher each time.
 */
object CustomProviderHttp {

    /** Placeholder base URL: a reserved TLD, so a mis-routed call fails fast instead of hitting
     *  someone's real host. Every call overrides it with @Url. */
    private const val PLACEHOLDER_BASE_URL = "https://api.seema.invalid/"

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            // SAVE is a button press with the user waiting: ten seconds is the longest this can
            // hang before "the provider did not answer" is the honest result.
            .callTimeout(10, TimeUnit.SECONDS)
            // Off on purpose: the probe's verdict is what the user is about to trust, and a retried
            // request could report the second attempt's outcome as the first one's.
            .retryOnConnectionFailure(false)
            .build()
    }

    private val defaultProbeApi: CustomProviderProbeApi by lazy {
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(defaultClient)
            .build()
            .create(CustomProviderProbeApi::class.java)
    }

    /**
     * The shared probe interface.
     *
     * No logging interceptor is installed, and none may ever be: it would print the
     * `Authorization` header — i.e. the user's credential — to Logcat.
     *
     * No converter factory is registered either: [RequestBody] and [ResponseBody] are both served
     * by Retrofit's built-in converter, and the response body is closed unread.
     */
    fun probeApi(): CustomProviderProbeApi = defaultProbeApi
}

/** Media type of the OpenAI-compatible JSON body; Retrofit requires a body to declare one. */
internal val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
