package com.seema.assistant.customprovider.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's own database, holding the user's custom providers.
 *
 * Kept separate from the app's settings and from the encrypted key store on purpose: it is the only
 * thing in the app that needs SQL, and giving it its own file means a Room version bump can recreate
 * it destructively without touching a single stored preference.
 *
 * ## Why version 1 has no migration
 *
 * There is no shipped schema to migrate from ([exportSchema] is false and no previous release of
 * this feature exists), so a hypothetical future version bump would have nothing to carry over.
 * [RoomDatabase.Builder.fallbackToDestructiveMigration] therefore turns the failure mode on an
 * unknown schema into "the table is empty" rather than "the app crashes on launch" — the user
 * re-adds a provider, which is the only acceptable outcome while the data cannot be recovered.
 */
@Database(
    entities = [CustomProvider::class],
    version = 1,
    exportSchema = false,
)
abstract class CustomProviderDatabase : RoomDatabase() {

    /** The table's DAO. Room generates the implementation at build time. */
    abstract fun customProviders(): CustomProviderDao

    companion object {

        /** File name inside the app's private `databases/` directory. */
        private const val DATABASE_NAME = "seema_custom_providers.db"

        /**
         * The process-wide instance, or null before the first call to [of].
         *
         * `@Volatile` because [of] may be called from whichever thread first needs a provider — the
         * settings screen, or the router resolving targets after a restart — and a non-volatile
         * field could hand a second thread the half-published instance.
         */
        @Volatile
        private var instance: CustomProviderDatabase? = null

        /**
         * The single database for [context]'s application.
         *
         * Double-checked locking rather than `synchronized` on every call: after the first call this
         * is a plain volatile read. Only the application context is used, so a screen holding the
         * database can never leak the Activity that happened to open it first.
         */
        fun of(context: Context): CustomProviderDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CustomProviderDatabase::class.java,
                    DATABASE_NAME,
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
