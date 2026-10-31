package com.seema.assistant.customprovider.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Where a custom provider's credential lives once the user has saved it.
 *
 * The interface exists for the same reason as [com.seema.assistant.apikeys.data.ApiKeyVault]: the
 * settings screen, the router and the tests must not learn whether the bytes came from the
 * AndroidKeyStore or from a plain map. It is keyed by an opaque alias rather than by a provider id
 * because a custom provider's identity is a database row, and rows are deleted and recreated.
 */
interface CustomProviderKeyVault {

    /** The credential stored under [alias], or "" when none is stored or the store is unreadable. */
    fun read(alias: String): String

    /** Stores [apiKey] under [alias]. Callers pass an already-validated, non-blank key. */
    fun write(alias: String, apiKey: String)

    /** Forgets whatever is stored under [alias]. */
    fun clear(alias: String)
}

/**
 * Prefix of every custom provider's vault alias.
 *
 * The alias is derived from the user-typed name, and a user could name a provider "gemini" — the
 * same string the built-in vault uses as a slot. Prefixing keeps the two namespaces disjoint, so
 * saving a custom provider called "Gemini" can never overwrite the built-in Gemini key.
 */
internal const val CUSTOM_PROVIDER_KEY_ALIAS_PREFIX = "custom-provider:"

/**
 * Vault slot for the provider named [name].
 *
 * Derived from the name rather than the row id because the key is written before the row exists:
 * the alias has to be computable before the insert assigns an id, and `name` is unique, so it is
 * exactly as stable as the row it belongs to.
 */
internal fun customProviderKeyAlias(name: String): String =
    "$CUSTOM_PROVIDER_KEY_ALIAS_PREFIX${name.trim()}"

/**
 * [CustomProviderKeyVault] backed by its own [EncryptedSharedPreferences] file and master key.
 *
 * It deliberately does not share [com.seema.assistant.apikeys.data.EncryptedApiKeyVault]'s file: the
 * two stores have different lifetimes (a custom provider is deleted far more often than a built-in
 * key is rotated), and sharing one file would make the reset-on-corruption repair of either one wipe
 * the other.
 *
 * Every value this class touches is either the ciphertext it stores or the plaintext the caller
 * handed it, and neither may reach the log: no method here interpolates a key or a stored value into
 * a message. The tag is safe to grep.
 */
class EncryptedCustomProviderKeyVault(context: Context) : CustomProviderKeyVault {

    /** Only the application context is held: vault instances outlive the screen that built them. */
    private val appContext: Context = context.applicationContext

    /**
     * The encrypted store, or null when the AndroidKeyStore cannot be repaired at all.
     *
     * Lazy because opening it creates a keystore key, which touches hardware-backed storage and is
     * slow; doing that in a constructor would block whoever first creates the vault.
     */
    private val prefs: SharedPreferences? by lazy { openEncryptedPrefsOrNull() }

    /**
     * Returns the stored key, or "" when there is nothing to return.
     *
     * The catch is here rather than at each call site because [read] is called while the router is
     * assembling its targets, from paths that cannot fail a user's turn. A device restore or a
     * lock-screen change can leave ciphertext this key can no longer open, and that must surface as
     * "no key stored" — a provider the router then skips — rather than as an exception out of a
     * getter. Only the exception's type is logged; the exception is not, because a crypto message
     * could quote the material it refused to open.
     */
    override fun read(alias: String): String {
        val store = prefs ?: return ""
        return try {
            store.getString(alias, null).orEmpty()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Stored custom-provider key could not be read (${e::class.java.simpleName}); " +
                    "treating the provider as having no key",
            )
            ""
        }
    }

    /**
     * Stores [apiKey] under [alias].
     *
     * Throws when the encrypted store is unusable, and deliberately does not swallow that: a write
     * that silently did nothing would let the caller save a provider whose key exists nowhere, and
     * the user would only discover it when every call failed. The caller treats the throw as a
     * failed save.
     */
    override fun write(alias: String, apiKey: String) {
        val store = prefs
            ?: throw IllegalStateException("Encrypted custom-provider store is unavailable")
        // One trim point, so the value stored is the value compared against on the next read.
        store.edit().putString(alias, apiKey.trim()).apply()
    }

    /**
     * Forgets [alias].
     *
     * Best-effort on purpose: this runs as the tail of a delete the user already confirmed, and a
     * keystore problem must not turn "removed" into an exception after the row is already gone. A
     * failure is logged without the value.
     */
    override fun clear(alias: String) {
        val store = prefs ?: return
        try {
            store.edit().remove(alias).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Stored custom-provider key could not be cleared (${e::class.java.simpleName})")
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
     * Same reasoning as the built-in vault: a restored backup or an OEM lock-screen change can
     * invalidate the key that encrypts this file, at which point the existing ciphertext is
     * unreadable forever. Wiping both the file and the key and retrying once turns "crash on launch"
     * into "the custom provider list needs re-entering".
     */
    private fun openEncryptedPrefsOrNull(): SharedPreferences? =
        try {
            createEncryptedPrefs()
        } catch (first: Exception) {
            Log.w(
                TAG,
                "Encrypted preference file could not be opened (${first::class.java.simpleName}); resetting it",
            )
            resetEncryptedStorage()
            try {
                createEncryptedPrefs()
            } catch (second: Exception) {
                Log.e(
                    TAG,
                    "Encrypted custom-provider store still unusable (${second::class.java.simpleName}); " +
                        "keys will not be persisted",
                )
                null
            }
        }

    private fun resetEncryptedStorage() {
        runCatching { appContext.deleteSharedPreferences(PREFS_FILE) }
            .onFailure {
                Log.w(TAG, "Stale encrypted preference file could not be deleted (${it::class.java.simpleName})")
            }
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE)
                .apply { load(null as KeyStore.LoadStoreParameter?) }
                .deleteEntry(MASTER_KEY_ALIAS)
        }.onFailure {
            Log.w(TAG, "Stale keystore key could not be deleted (${it::class.java.simpleName})")
        }
    }

    private companion object {
        /** Never logged key material, so the tag stays safe to grep for. */
        const val TAG = "CustomProviderVault"

        /** Own file and own master key: see the class KDoc for why they are not shared. */
        const val PREFS_FILE = "seema_custom_provider_keys_encrypted"
        const val MASTER_KEY_ALIAS = "seema_custom_provider_keys_master"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
