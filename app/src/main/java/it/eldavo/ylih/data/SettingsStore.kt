package it.eldavo.ylih.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Not private: SettingsStore is a separate class, so a private top-level delegate would be
// reached through a synthetic accessor on every read (lint's SyntheticAccessor).
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Two-mode switch described in the README: Bluetooth tracking needs nothing running, while
 * wired and playback tracking require the foreground service.
 */
class SettingsStore(private val context: Context) {
    private val detailedKey = booleanPreferencesKey("detailed_tracking")
    private val onboardedKey = booleanPreferencesKey("onboarding_done")
    private val hibernationAskedKey = booleanPreferencesKey("hibernation_asked")
    private val playbackOnlyKey = booleanPreferencesKey("playback_only_stats")
    private val languageKey = stringPreferencesKey("language")

    /** Wired headphones + playback measurement, at the cost of a persistent notification. */
    val detailedTracking: Flow<Boolean> = context.dataStore.data.map { it[detailedKey] ?: false }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[onboardedKey] ?: false }

    /**
     * Whether the one-off "let ylih keep its permissions" prompt has been answered. Asked once and
     * never again either way: the same request lives in settings for anyone who said no, and an
     * app that nags about a system setting is an app people force-stop.
     */
    val hibernationAsked: Flow<Boolean> =
        context.dataStore.data.map { it[hibernationAskedKey] ?: false }

    /**
     * Report listening time rather than connected time. Purely a way of reading the same history
     * — nothing about what is recorded changes — so it is never forced off, and a mode that
     * cannot measure playback simply leaves it out of the settings screen.
     */
    val playbackOnly: Flow<Boolean> = context.dataStore.data.map { it[playbackOnlyKey] ?: false }

    /**
     * A BCP 47 tag, or `AppLocale.SYSTEM` for the system language. Only ever written below
     * Android 13, where the platform has no per-app language setting of its own — see `AppLocale`.
     */
    val language: Flow<String> = context.dataStore.data.map { it[languageKey] ?: "" }

    suspend fun detailedTrackingNow(): Boolean = detailedTracking.first()

    suspend fun onboardingDoneNow(): Boolean = onboardingDone.first()

    suspend fun playbackOnlyNow(): Boolean = playbackOnly.first()

    suspend fun languageNow(): String = language.first()

    suspend fun setDetailedTracking(enabled: Boolean) {
        context.dataStore.edit { it[detailedKey] = enabled }
    }

    suspend fun setPlaybackOnly(enabled: Boolean) {
        context.dataStore.edit { it[playbackOnlyKey] = enabled }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { it[onboardedKey] = done }
    }

    suspend fun setHibernationAsked(asked: Boolean) {
        context.dataStore.edit { it[hibernationAskedKey] = asked }
    }

    suspend fun setLanguage(tag: String) {
        context.dataStore.edit { it[languageKey] = tag }
    }
}

/** Device kinds each mode is able to observe. */
fun trackedKinds(detailedTracking: Boolean): Set<DeviceKind> =
    if (detailedTracking) {
        setOf(DeviceKind.BLUETOOTH, DeviceKind.BLE, DeviceKind.WIRED, DeviceKind.USB)
    } else {
        setOf(DeviceKind.BLUETOOTH, DeviceKind.BLE)
    }
