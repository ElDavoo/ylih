package it.eldavo.ylih.data

import android.content.Context
import androidx.core.content.edit
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.agent.pushAgentAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Two-mode switch described in the README: Bluetooth tracking needs nothing running; wired and
 * playback tracking need the foreground service.
 *
 * Backed by a table in the app's own Room database rather than DataStore — not a storage
 * decision (either would do for six flags) but a dependency one. DataStore ships a prebuilt
 * `libdatastore_shared_counter.so` per ABI, the only file in this app whose bytes depend on
 * whether the build machine had an NDK to strip with; that's why `app/build.gradle.kts` had to
 * pin `keepDebugSymbols`. Room was already here.
 */
class SettingsStore(
    private val dao: SettingsDao,
    /** For the language mirror and the app-function push — see [setLanguage], [setAgentAccess]. */
    private val appContext: Context,
) {

    /**
     * For a caller holding only a context — `AppLocale.wrap`, and the tests.
     *
     * Goes through the container rather than opening its own database: Room's invalidation
     * tracker only notifies observers attached to the instance that did the write, so a second
     * instance over the same file would leave these flows deaf to every setting change.
     */
    constructor(context: Context) : this(
        (context.applicationContext as YlihApp).container.database.settingsDao(),
        context.applicationContext,
    )

    /**
     * Every setting, re-read whenever any of them changes. The table holds six rows, so reading
     * all of them costs nothing next to the reason it has to be one query — see
     * [SettingsDao.observeAll].
     */
    private val all: Flow<Map<String, String>> =
        dao.observeAll().map { rows -> rows.associate { it.key to it.value } }

    /** Wired headphones + playback measurement, at the cost of a persistent notification. */
    val detailedTracking: Flow<Boolean> = boolean(DETAILED_TRACKING)

    val onboardingDone: Flow<Boolean> = boolean(ONBOARDING_DONE)

    /**
     * Whether the one-off "let ylih keep its permissions" prompt has been answered. Asked once,
     * never again either way: the request stays in settings for anyone who said no, since an app
     * that nags about a system setting gets force-stopped.
     */
    val hibernationAsked: Flow<Boolean> = boolean(HIBERNATION_ASKED)

    /**
     * Report listening time rather than connected time. Purely a way of reading the same history
     * — nothing recorded changes — so it's never forced off; a mode that can't measure playback
     * just leaves it out of the settings screen.
     */
    val playbackOnly: Flow<Boolean> = boolean(PLAYBACK_ONLY)

    /**
     * Whether an on-device assistant may call this app's app functions — see
     * `agent/YlihAppFunctions.kt`.
     *
     * A new row in a key/value table needs no schema version or migration: `SettingEntity` is
     * what makes a new setting cost nothing.
     */
    val agentAccess: Flow<Boolean> = boolean(AGENT_ACCESS)

    /**
     * A BCP 47 tag, or `AppLocale.SYSTEM` for the system language. Only ever written below
     * Android 13, where the platform has no per-app language setting of its own — see `AppLocale`.
     */
    val language: Flow<String> = text(LANGUAGE).map { it ?: "" }

    /**
     * Where and how often ylih writes a backup by itself, and how the last attempt went — see
     * `export/AutoBackup.kt`. One flow rather than four: the settings screen draws all of it as
     * one block, and the worker reads it as one decision.
     */
    val autoBackup: Flow<AutoBackupState> = all.map(::autoBackupOf).distinctUntilChanged()

    suspend fun autoBackupNow(): AutoBackupState =
        autoBackupOf(dao.getAll().associate { it.key to it.value })

    /**
     * Points automatic backups at [uri], a tree the caller already holds a persisted grant on, or
     * turns them off with `null`.
     *
     * Clears the error either way: it described a folder that is no longer the one in use. The
     * last success is kept, since the backup it names still exists wherever it was written.
     */
    suspend fun setAutoBackupFolder(uri: String?) {
        put(AUTO_BACKUP_URI, uri.orEmpty())
        put(AUTO_BACKUP_ERROR, "")
    }

    suspend fun setAutoBackupEvery(days: Int) = put(AUTO_BACKUP_EVERY_DAYS, days.toString())

    /** The outcome of one run: [error] null for a backup written at [at]. */
    suspend fun recordAutoBackup(at: Long, error: AutoBackupError?) {
        if (error == null) put(AUTO_BACKUP_LAST_OK, at.toString())
        put(AUTO_BACKUP_ERROR, error?.name.orEmpty())
    }

    suspend fun detailedTrackingNow(): Boolean = booleanNow(DETAILED_TRACKING)

    suspend fun onboardingDoneNow(): Boolean = booleanNow(ONBOARDING_DONE)

    suspend fun playbackOnlyNow(): Boolean = booleanNow(PLAYBACK_ONLY)

    suspend fun agentAccessNow(): Boolean = booleanNow(AGENT_ACCESS)

    suspend fun languageNow(): String = textNow(LANGUAGE) ?: ""

    suspend fun setDetailedTracking(enabled: Boolean) = put(DETAILED_TRACKING, enabled.toString())

    suspend fun setPlaybackOnly(enabled: Boolean) = put(PLAYBACK_ONLY, enabled.toString())

    /**
     * Writes the row *and* the platform state it stands for.
     *
     * Both together, for the same reason [setLanguage] mirrors its value: the row is the source
     * of truth and the OS index is a projection, and a projection only some callers remember to
     * update is one that drifts. `false` here must reach the OS — an app function left enabled is
     * callable whatever this table says.
     */
    suspend fun setAgentAccess(enabled: Boolean) {
        put(AGENT_ACCESS, enabled.toString())
        pushAgentAccess(appContext, enabled)
    }

    suspend fun setOnboardingDone(done: Boolean) = put(ONBOARDING_DONE, done.toString())

    suspend fun setHibernationAsked(asked: Boolean) = put(HIBERNATION_ASKED, asked.toString())

    /**
     * Writes the row, and the copy of it that `AppLocale.wrap` reads.
     *
     * The mirror lives here, not at the call site, so it can't be forgotten: the row is the
     * source of truth, but `attachBaseContext` must settle the configuration before the process
     * may touch the database, and reading it there on a cold start would open Room — and run any
     * pending migration — on the main thread before the first frame.
     */
    suspend fun setLanguage(tag: String) {
        put(LANGUAGE, tag)
        appContext.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
            .edit { putString(LANGUAGE, tag) }
    }

    /**
     * Deduplicated because [all] re-emits on every write to the table: without it, changing the
     * tracking mode would announce a language change too, and the settings screen recreates the
     * activity when the language changes.
     */
    private fun text(key: String): Flow<String?> = all.map { it[key] }.distinctUntilChanged()

    private fun boolean(key: String): Flow<Boolean> = text(key).map { it.toBoolean() }

    private suspend fun booleanNow(key: String): Boolean = textNow(key).toBoolean()

    private suspend fun textNow(key: String): String? =
        dao.getAll().firstOrNull { it.key == key }?.value

    private suspend fun put(key: String, value: String) = dao.put(SettingEntity(key, value))

    // An empty value stands for an absent row: the table has no delete, and turning backups off
    // has to un-set a folder.
    private fun autoBackupOf(rows: Map<String, String>) = AutoBackupState(
        folder = rows[AUTO_BACKUP_URI]?.takeIf { it.isNotEmpty() },
        everyDays = rows[AUTO_BACKUP_EVERY_DAYS]?.toIntOrNull()
            ?.takeIf { it in AUTO_BACKUP_INTERVALS }
            ?: DEFAULT_AUTO_BACKUP_EVERY_DAYS,
        lastOkAt = rows[AUTO_BACKUP_LAST_OK]?.toLongOrNull(),
        error = AutoBackupError.entries.firstOrNull { it.name == rows[AUTO_BACKUP_ERROR] },
    )

    companion object {
        /** The mirror of [setLanguage], or null where this process has never seen one written. */
        fun cachedLanguage(context: Context): String? = context.applicationContext
            .getSharedPreferences(CACHE, Context.MODE_PRIVATE)
            .getString(LANGUAGE, null)

        /** Seeds the mirror from the row, for an install upgraded into having one. */
        fun cacheLanguage(context: Context, tag: String) {
            context.applicationContext.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
                .edit { putString(LANGUAGE, tag) }
        }

        private const val CACHE = "ylih-settings-cache"

        // The DataStore keys, kept to the letter so a debug install carried across the switch
        // reads as "never set" rather than as something else's value.
        const val DETAILED_TRACKING = "detailed_tracking"
        const val ONBOARDING_DONE = "onboarding_done"
        const val HIBERNATION_ASKED = "hibernation_asked"
        const val PLAYBACK_ONLY = "playback_only_stats"
        const val AGENT_ACCESS = "agent_access"
        const val LANGUAGE = "language"
        const val AUTO_BACKUP_URI = "auto_backup_uri"
        const val AUTO_BACKUP_EVERY_DAYS = "auto_backup_every_days"
        const val AUTO_BACKUP_LAST_OK = "auto_backup_last_ok"
        const val AUTO_BACKUP_ERROR = "auto_backup_error"

        /**
         * The rows describing this device's backup folder rather than the user's history or
         * preferences, and so left out of a JSON backup both ways — see `JsonBackup`.
         */
        val DEVICE_LOCAL_KEYS = setOf(
            AUTO_BACKUP_URI,
            AUTO_BACKUP_EVERY_DAYS,
            AUTO_BACKUP_LAST_OK,
            AUTO_BACKUP_ERROR,
        )

        /** Offered in settings; anything else read from the table is treated as the default. */
        val AUTO_BACKUP_INTERVALS = listOf(1, 7, 30)
        const val DEFAULT_AUTO_BACKUP_EVERY_DAYS = 7
    }
}

/** What went wrong with the last automatic backup — each is a different thing to tell the user. */
enum class AutoBackupError {
    /** The folder is gone or ylih's grant on it is: nothing but choosing a folder again fixes it. */
    ACCESS_LOST,

    /** The folder was there but the file couldn't be written, say a full disk. May pass by itself. */
    WRITE_FAILED,
}

data class AutoBackupState(
    /** The tree URI backups are written under, or null while automatic backups are off. */
    val folder: String?,
    val everyDays: Int,
    val lastOkAt: Long?,
    val error: AutoBackupError?,
)

/** Device kinds each mode is able to observe. */
fun trackedKinds(detailedTracking: Boolean): Set<DeviceKind> =
    if (detailedTracking) {
        setOf(DeviceKind.BLUETOOTH, DeviceKind.BLE, DeviceKind.WIRED, DeviceKind.USB)
    } else {
        setOf(DeviceKind.BLUETOOTH, DeviceKind.BLE)
    }
