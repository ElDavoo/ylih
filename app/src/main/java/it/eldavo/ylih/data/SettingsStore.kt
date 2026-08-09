package it.eldavo.ylih.data

import android.content.Context
import androidx.core.content.edit
import it.eldavo.ylih.YlihApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Two-mode switch described in the README: Bluetooth tracking needs nothing running, while
 * wired and playback tracking require the foreground service.
 *
 * Backed by a table in the app's own Room database rather than by DataStore, which is not a
 * storage decision — either would do for five flags — but a dependency one. DataStore ships a
 * prebuilt `libdatastore_shared_counter.so` per ABI, and that file is the only thing in this app
 * whose bytes depend on whether the machine that built it had an NDK to strip with; it is why
 * `app/build.gradle.kts` had to pin `keepDebugSymbols`. Room was already here.
 */
class SettingsStore(
    private val dao: SettingsDao,
    /** Only for the language mirror — see [setLanguage] and [cachedLanguage]. */
    private val appContext: Context,
) {

    /**
     * For a caller holding only a context — `AppLocale.wrap`, and the tests.
     *
     * It goes through the container rather than opening its own database on purpose. Room's
     * invalidation tracker only notifies observers attached to the instance that did the write,
     * so a second instance over the same file would leave these flows deaf to every setting the
     * app changes.
     */
    constructor(context: Context) : this(
        (context.applicationContext as YlihApp).container.database.settingsDao(),
        context.applicationContext,
    )

    /**
     * Every setting, re-read whenever any of them changes. The table holds five rows, so reading
     * all of them costs nothing next to the reason it has to be one query — see [SettingsDao.observeAll].
     */
    private val all: Flow<Map<String, String>> =
        dao.observeAll().map { rows -> rows.associate { it.key to it.value } }

    /** Wired headphones + playback measurement, at the cost of a persistent notification. */
    val detailedTracking: Flow<Boolean> = boolean(DETAILED_TRACKING)

    val onboardingDone: Flow<Boolean> = boolean(ONBOARDING_DONE)

    /**
     * Whether the one-off "let ylih keep its permissions" prompt has been answered. Asked once and
     * never again either way: the same request lives in settings for anyone who said no, and an
     * app that nags about a system setting is an app people force-stop.
     */
    val hibernationAsked: Flow<Boolean> = boolean(HIBERNATION_ASKED)

    /**
     * Report listening time rather than connected time. Purely a way of reading the same history
     * — nothing about what is recorded changes — so it is never forced off, and a mode that
     * cannot measure playback simply leaves it out of the settings screen.
     */
    val playbackOnly: Flow<Boolean> = boolean(PLAYBACK_ONLY)

    /**
     * A BCP 47 tag, or `AppLocale.SYSTEM` for the system language. Only ever written below
     * Android 13, where the platform has no per-app language setting of its own — see `AppLocale`.
     */
    val language: Flow<String> = text(LANGUAGE).map { it ?: "" }

    suspend fun detailedTrackingNow(): Boolean = booleanNow(DETAILED_TRACKING)

    suspend fun onboardingDoneNow(): Boolean = booleanNow(ONBOARDING_DONE)

    suspend fun playbackOnlyNow(): Boolean = booleanNow(PLAYBACK_ONLY)

    suspend fun languageNow(): String = textNow(LANGUAGE) ?: ""

    suspend fun setDetailedTracking(enabled: Boolean) = put(DETAILED_TRACKING, enabled.toString())

    suspend fun setPlaybackOnly(enabled: Boolean) = put(PLAYBACK_ONLY, enabled.toString())

    suspend fun setOnboardingDone(done: Boolean) = put(ONBOARDING_DONE, done.toString())

    suspend fun setHibernationAsked(asked: Boolean) = put(HIBERNATION_ASKED, asked.toString())

    /**
     * Writes the row, and the copy of it that `AppLocale.wrap` reads.
     *
     * The mirror lives here rather than at the call site so that it cannot be forgotten: the row
     * is the source of truth, but `attachBaseContext` has to settle the configuration before the
     * process may touch the database, and on a cold start reading it there would open Room — and
     * run any pending migration — on the main thread before the first frame.
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
        const val LANGUAGE = "language"
    }
}

/** Device kinds each mode is able to observe. */
fun trackedKinds(detailedTracking: Boolean): Set<DeviceKind> =
    if (detailedTracking) {
        setOf(DeviceKind.BLUETOOTH, DeviceKind.BLE, DeviceKind.WIRED, DeviceKind.USB)
    } else {
        setOf(DeviceKind.BLUETOOTH, DeviceKind.BLE)
    }
