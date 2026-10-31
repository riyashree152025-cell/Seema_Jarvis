package com.seema.assistant.customprovider.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The only SQL that touches `custom_providers`.
 *
 * ## Why every read is ordered by `createdAt, id`
 *
 * The router tries custom providers in the order the screen lists them, so the query's order is
 * part of the feature, not a detail of storage. `createdAt` alone is not enough: two providers saved
 * in the same millisecond (a bulk import, a script) would then come back in whatever order SQLite
 * felt like, and the HUD could show a different order after every restart. The `id` tiebreaker makes
 * the order total and therefore reproducible.
 *
 * ## Why there is no query that looks a row up by name
 *
 * `name` is unique, so the repository could ask for it directly, and deliberately does not: the
 * save path has to read the whole list anyway to build the router's targets, and the unique index
 * is the guarantee that "one row per name" is enforced by the database rather than by a query the
 * next caller might forget.
 */
@Dao
interface CustomProviderDao {

    /**
     * Every stored provider, re-emitted whenever the table changes.
     *
     * A [Flow] rather than a snapshot because the screen must react to a save or a delete without
     * being told to reload; Room invalidates this query by itself.
     */
    @Query("SELECT * FROM custom_providers ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<CustomProvider>>

    /** One-shot read of the same order, for non-UI callers such as the router's target load. */
    @Query("SELECT * FROM custom_providers ORDER BY createdAt ASC, id ASC")
    suspend fun getAll(): List<CustomProvider>

    /** One row by primary key, or null when it has been deleted since the caller last saw it. */
    @Query("SELECT * FROM custom_providers WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): CustomProvider?

    /**
     * Inserts [row], or updates the row whose primary key it already carries.
     *
     * Returns the primary key the row ended up with, so the caller can publish a [CustomProvider]
     * that names the row the database actually holds instead of guessing.
     */
    @Upsert
    suspend fun upsert(row: CustomProvider): Long

    /** Deletes by primary key, for a caller that only kept the id. */
    @Query("DELETE FROM custom_providers WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Deletes [row].
     *
     * Matching the whole entity rather than the id makes an accidental primary-key collision
     * impossible and reads as "remove exactly this row". Keys are matched by primary key, not by
     * every column.
     */
    @Delete
    suspend fun delete(row: CustomProvider)
}
