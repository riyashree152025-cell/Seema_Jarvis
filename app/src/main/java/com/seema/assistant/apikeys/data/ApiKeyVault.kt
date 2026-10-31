package com.seema.assistant.apikeys.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Where a model credential lives once the user has saved it.
 *
 * Kept as an interface so the storage decision is made in exactly one place: the screen and the
 * probe code ask for a key, they never learn whether it came from the encrypted store or from the
 * legacy plaintext file.
 */
interface ApiKeyVault {

    /** The credential for [provider], or "" when none is stored. */
    fun read(provider: ApiProvider): String

    /** Every provider's credential; providers with nothing stored map to "". */
    fun readAll(): Map<ApiProvider, String>

    /** Stores [apiKey] for [provider]. Callers pass an already-validated, non-blank key. */
    fun write(provider: ApiProvider, apiKey: String)

    /** Forgets the credential for [provider]. */
    fun clear(provider: ApiProvider)
}

/**
 * [ApiKeyVault] backed by [EncryptedSharedPreferences], encrypted with an AES-256-GCM key that
 * never leaves the AndroidKeyStore.
 *
 * This is the single writer of model credentials in the app: a rooted device or a pulled
 * `shared_prefs` file yields ciphertext, not a usable key.
 *
 * The plaintext `seema_ai` mirror that GEMINI and GROQ still get is debt, not design. It exists
 * only until the legacy readers (`AiSettings`, `GroqBrain`, `SeemaConfigBridge`) migrate to this
 * interface; both the mirror and this paragraph should be deleted on that day.
 */
class EncryptedApiKeyVault(context: Context) : ApiKeyVault {

    /** Only the application context is held: vault instances outlive the Activity that built them. */
    private val appContext: Context = context.applicationContext

    /**
     * The encrypted store, or null when the AndroidKeyStore cannot be repaired at all.
     *
     * Lazy because opening it builds a keystore key, which is slow and touches hardware-backed
     * storage; doing that in a constructor would block whatever thread first creates the vault.
     */
    private val prefs: SharedPreferences? by lazy { openEncryptedPrefsOrNull() }

    private val legacyPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE)
    }

    override fun read(provider: ApiProvider): String {
        val encrypted = prefs?.getString(provider.id, null)
        if (!encrypted.isNullOrBlank()) return encrypted
        // Read-through, never destructive: a user who never presses SAVE must not lose the key an
        // older build stored in plaintext, so the legacy value is surfaced but not rewritten here.
        return legacyKeyFor(provider)
            ?.let { legacyPrefs.getString(it, null) }
            ?.trim()
            .orEmpty()
    }

    override fun readAll(): Map<ApiProvider, String> = ApiProvider.entries.associateWith(::read)

    override fun write(provider: ApiProvider, apiKey: String) {
        // One trim point: the same canonical value is what gets stored and what gets mirrored, so
        // the two stores can never disagree over trailing whitespace.
        val trimmed = apiKey.trim()
        val legacyKey = legacyKeyFor(provider)
        val store = prefs

        if (store == null) {
            // Never claim a write that did not happen. With no encrypted store the two providers
            // that have a legacy slot still persist; the others do not persist at all.
            if (legacyKey == null) {
                Log.e(
                    TAG,
                    "Encrypted store unavailable and ${provider.id} has no legacy slot: " +
                        "the key was NOT persisted",
                )
            } else {
                Log.w(TAG, "Encrypted store unavailable; ${provider.id} key reached only the legacy store")
            }
        } else {
            store.edit().putString(provider.id, trimmed).apply()
        }

        // TEMPORARY COMPATIBILITY SHIM — TODO: remove once the legacy readers are migrated.
        // AiSettings (VoiceSettings.kt:86) and GroqBrain.kt / SeemaConfigBridge.kt still read the
        // plaintext "seema_ai" file directly. Without this mirror they would keep using the stale
        // key the user just replaced, and the assistant would look broken right after a save.
        if (legacyKey != null) {
            legacyPrefs.edit().putString(legacyKey, trimmed).apply()
        }
    }

    override fun clear(provider: ApiProvider) {
        prefs?.edit()?.remove(provider.id)?.apply()
        // The legacy copy has to go too: read() falls back to it, so leaving it behind would
        // resurrect the key the user just deleted.
        val legacyKey = legacyKeyFor(provider)
        if (legacyKey != null) {
            legacyPrefs.edit().remove(legacyKey).apply()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences =
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE,
            MasterKey.Builder(appContext, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    /**
     * Opens the encrypted store, repairing the keystore once if it has been invalidated.
     *
     * A device restore or an OEM lock-screen change can invalidate the keystore key that encrypts
     * this file, at which point the existing ciphertext is unreadable forever. Wiping both the file
     * and the key and retrying once turns "app crashes on launch" into "screen opens empty".
     */
    private fun openEncryptedPrefsOrNull(): SharedPreferences? =
        try {
            createEncryptedPrefs()
        } catch (first: Exception) {
            // Only the exception's type is logged: a keystore/prefs message is not expected to
            // contain key material, but logging the object is not proof of that, and this tag is
            // advertised as safe to grep.
            Log.w(TAG, "Encrypted preference file could not be opened (${first::class.java.simpleName}); resetting it")
            resetEncryptedStorage()
            try {
                createEncryptedPrefs()
            } catch (second: Exception) {
                // The keystore is unusable on this device. Degrade to "nothing stored" rather than
                // take the whole screen down; write() reports which providers still persisted.
                Log.e(
                    TAG,
                    "Encrypted key store still unusable (${second::class.java.simpleName}); " +
                        "keys will not be persisted",
                )
                null
            }
        }

    private fun resetEncryptedStorage() {
        runCatching { appContext.deleteSharedPreferences(PREFS_FILE) }
            .onFailure { Log.w(TAG, "Stale encrypted preference file could not be deleted (${it::class.java.simpleName})") }
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE)
                .apply { load(null as KeyStore.LoadStoreParameter?) }
                .deleteEntry(MASTER_KEY_ALIAS)
        }.onFailure { Log.w(TAG, "Stale keystore key could not be deleted (${it::class.java.simpleName})") }
    }

    /**
     * Plaintext slot this provider also has to be written to, or null if it never had one.
     *
     * Only the two providers the legacy readers know about appear here; the others exist solely in
     * the encrypted file.
     */
    private fun legacyKeyFor(provider: ApiProvider): String? = when (provider) {
        ApiProvider.GEMINI -> LEGACY_KEY_GEMINI
        ApiProvider.GROQ -> LEGACY_KEY_GROQ
        else -> null
    }

    private companion object {
        /** Never logged key material, so the tag stays safe to grep for. */
        const val TAG = "ApiKeyVault"

        const val PREFS_FILE = "seema_api_keys_encrypted"
        const val MASTER_KEY_ALIAS = "seema_api_keys_master"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        const val LEGACY_PREFS_FILE = "seema_ai"
        const val LEGACY_KEY_GEMINI = "api_key"
        const val LEGACY_KEY_GROQ = "groq_api_key"
    }
}
