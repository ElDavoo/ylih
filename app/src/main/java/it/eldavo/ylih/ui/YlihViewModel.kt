package it.eldavo.ylih.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.PairSummary
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.export.JsonBackup
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.stats.Span
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class YlihViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as YlihApp).container

    /** 1 Hz tick so live "connected for …" timers move; stops when nothing is watching. */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(container.clock.now())
            delay(1_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), container.clock.now())

    val summaries: StateFlow<List<PairSummary>> = container.repository.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val devices: StateFlow<List<DeviceEntity>> = container.repository.observeDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val detailedTracking: StateFlow<Boolean> = container.settings.detailedTracking
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** What every figure on the three screens counts; connected time until told otherwise. */
    val counting: StateFlow<Counting> = container.settings.playbackOnly
        .map { if (it) Counting.PLAYBACK else Counting.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Counting.CONNECTED)

    /** Null until DataStore has answered, so the welcome does not flash on every later launch. */
    val onboardingDone: StateFlow<Boolean?> = container.settings.onboardingDone
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Null until DataStore has answered. The settings screen restarts the activity when this
     * changes, so it must not see the default before the stored tag arrives.
     */
    val language: StateFlow<String?> = container.settings.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val allSpans: StateFlow<List<Span>> = container.repository.observeAllSessions()
        .map { sessions -> sessions.map { it.toSpan() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Sessions grouped per pair, so time-window stats can be computed without extra queries. */
    val spansByPair: StateFlow<Map<Long, List<Span>>> = container.repository.observeAllSessions()
        .map { sessions -> sessions.groupBy({ it.pairId }, { it.toSpan() }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    fun summary(pairId: Long): Flow<PairSummary?> = container.repository.observeSummary(pairId)

    fun sessions(pairId: Long): Flow<List<SessionEntity>> =
        container.repository.observeSessionsFor(pairId)

    /** False on the Play build until Bluetooth access is granted — see `Distribution`. */
    fun detailedTrackingSupported(): Boolean =
        container.trackingController.detailedTrackingSupported()

    fun setDetailedTracking(enabled: Boolean) = viewModelScope.launch {
        if (!container.trackingController.setDetailedTracking(enabled)) {
            messageChannel.send(string(RES_DETAILED_NEEDS_BLUETOOTH))
        }
    }

    fun setPlaybackOnly(enabled: Boolean) = viewModelScope.launch {
        container.settings.setPlaybackOnly(enabled)
    }

    fun setLanguage(tag: String) = viewModelScope.launch {
        container.settings.setLanguage(tag)
    }

    /** Also what releases MainActivity's permission request — see the comment there. */
    fun completeOnboarding() = viewModelScope.launch {
        container.settings.setOnboardingDone(true)
    }

    fun syncWithSystem() = viewModelScope.launch {
        container.trackingController.syncWithSystem()
    }

    fun retirePair(pairId: Long, reason: String?) = viewModelScope.launch {
        container.repository.retirePair(pairId, reason)
    }

    fun renamePair(pairId: Long, label: String) = viewModelScope.launch {
        container.repository.renamePair(pairId, label)
    }

    fun setPurchaseInfo(pairId: Long, purchaseDate: Long?, priceCents: Long?) = viewModelScope.launch {
        container.repository.setPurchaseInfo(pairId, purchaseDate, priceCents)
    }

    fun deletePair(pairId: Long) = viewModelScope.launch {
        container.repository.deletePair(pairId)
    }

    fun deleteSession(sessionId: Long) = viewModelScope.launch {
        container.repository.deleteSession(sessionId)
    }

    fun setDeviceIgnored(deviceId: Long, ignored: Boolean) = viewModelScope.launch {
        container.repository.setDeviceIgnored(deviceId, ignored)
    }

    fun exportTo(uri: Uri) = viewModelScope.launch {
        runCatching {
            val payload = JsonBackup.export(container.database, container.clock.now())
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                it.write(payload.toByteArray())
            } ?: error(string(RES_COULD_NOT_OPEN, uri))
        }.onSuccess { messageChannel.send(string(RES_EXPORT_OK)) }
            .onFailure { messageChannel.send(it.message ?: string(RES_EXPORT_FAILED)) }
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        runCatching {
            val content = getApplication<Application>().contentResolver.openInputStream(uri)
                ?.use { it.readBytes().decodeToString() }
                ?: error(string(RES_COULD_NOT_OPEN, uri))
            JsonBackup.import(container.database, content)
        }.onSuccess { count ->
            messageChannel.send(
                getApplication<Application>().resources.getQuantityString(RES_IMPORT_OK, count, count),
            )
            container.trackingController.syncWithSystem()
        }.onFailure { messageChannel.send(it.message ?: string(RES_IMPORT_FAILED)) }
    }

    private fun string(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private companion object {
        val RES_EXPORT_OK = R.string.export_done
        val RES_EXPORT_FAILED = R.string.export_failed
        val RES_IMPORT_OK = R.plurals.import_done
        val RES_IMPORT_FAILED = R.string.import_failed
        val RES_COULD_NOT_OPEN = R.string.error_could_not_open
        val RES_DETAILED_NEEDS_BLUETOOTH = R.string.detailed_needs_bluetooth
    }
}

fun SessionEntity.toSpan(): Span = Span(connectedAt, disconnectedAt, playingMs)

/**
 * The lifetime figure a pair's card and the ranking show, straight off the aggregate rather than
 * off its sessions. `closedMs` is finished sessions only, so connected time has to add the open
 * one's live tail; the playback sum already includes it, because the watcher banks playback into
 * the open session as it goes.
 */
fun PairSummary.countedMs(now: Long, counting: Counting): Long = when (counting) {
    Counting.CONNECTED -> closedMs + (openSince?.let { now - it } ?: 0L)
    Counting.PLAYBACK -> playingMs
}
