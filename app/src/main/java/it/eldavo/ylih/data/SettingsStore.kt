package it.eldavo.ylih.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Two-mode switch described in the README: Bluetooth tracking needs nothing running, while
 * wired and playback tracking require the foreground service.
 */
class SettingsStore(private val context: Context) {
    private val detailedKey = booleanPreferencesKey("detailed_tracking")
    private val onboardedKey = booleanPreferencesKey("onboarding_done")

    /** Wired headphones + playback measurement, at the cost of a persistent notification. */
    val detailedTracking: Flow<Boolean> = context.dataStore.data.map { it[detailedKey] ?: false }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[onboardedKey] ?: false }

    suspend fun detailedTrackingNow(): Boolean = detailedTracking.first()

    suspend fun onboardingDoneNow(): Boolean = onboardingDone.first()

    suspend fun setDetailedTracking(enabled: Boolean) {
        context.dataStore.edit { it[detailedKey] = enabled }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { it[onboardedKey] = done }
    }
}

/** Device kinds each mode is able to observe. */
fun trackedKinds(detailedTracking: Boolean): Set<DeviceKind> =
    if (detailedTracking) {
        setOf(DeviceKind.BLUETOOTH, DeviceKind.BLE, DeviceKind.WIRED, DeviceKind.USB)
    } else {
        setOf(DeviceKind.BLUETOOTH, DeviceKind.BLE)
    }
