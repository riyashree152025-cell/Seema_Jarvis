package com.seema.assistant.customprovider.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One OpenAI-compatible provider the user added, as stored in the `custom_providers` table.
 *
 * ## Why the credential is not a column
 *
 * [apiKeyAlias] is a pointer, never the secret itself. A Room table is an ordinary SQLite file: it
 * is copied by `adb backup`, read by any root process, and it lands in every database dump a
 * debugger prints. Keeping the key out of it means the worst case for a leaked database is the
 * provider's name and URL, not a working credential. The bytes live in
 * [EncryptedCustomProviderKeyVault], under the alias recorded here, so the row and the key can be
 * deleted independently without one implying the other exists.
 *
 * ## Why the name is unique
 *
 * [name] is how the user refers to the provider (it is the HUD's label and the alias it stores its
 * key under), so two rows with the same name would be two indistinguishable entries with two
 * possible keys. The unique index makes "save" mean "create or replace this name" instead of
 * silently accumulating duplicates.
 *
 * ## Why [createdAt] is stored rather than derived
 *
 * It is the routing order: the router tries custom providers oldest-first so the list the user sees
 * is the list it follows. A timestamp taken from the row id would survive neither a database
 * rebuild nor an import, and there is no other ordering column that a human could read.
 */
@Entity(
    tableName = "custom_providers",
    indices = [Index(value = ["name"], unique = true)],
)
data class CustomProvider(
    /**
     * SQLite row id, generated on insert.
     *
     * Defaults to 0 because Room treats 0 on an `autoGenerate` key as "not assigned yet"; that is
     * how a freshly built row and a row read back from the database are told apart before the
     * insert.
     */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The name the user typed; unique, and the vault alias is derived from it. */
    val name: String,

    /** The endpoint root, stored without a trailing slash so requests can append a path. */
    val baseUrl: String,

    /** The model id sent in the request body, e.g. `gpt-4o-mini`. */
    val modelId: String,

    /**
     * Vault slot holding the credential, as produced by [customProviderKeyAlias].
     *
     * Deliberately not [id]-derived: the alias has to exist before the first insert, because the key
     * is written to the vault before the row so a failed insert cannot leave a row pointing at a
     * key that was never stored.
     */
    val apiKeyAlias: String,

    /** Insert time in epoch milliseconds; also the routing order. Preserved across an update. */
    val createdAt: Long,
)
