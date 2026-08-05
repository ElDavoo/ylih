package it.eldavo.ylih.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.PairSummary
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.export.JsonBackup
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.stats.Span
import it.eldavo.ylih.widget.refreshWidgets
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

    /** Null until the stored answer arrives, so the welcome does not flash on every later launch. */
    val onboardingDone: StateFlow<Boolean?> = container.settings.onboardingDone
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Null until the stored answer arrives, for the same reason as [onboardingDone]. */
    val hibernationAsked: StateFlow<Boolean?> = container.settings.hibernationAsked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Null until the stored tag arrives. The settings screen restarts the activity when this
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

    /**
     * Runs a foreground edit and tells the home screen about it afterwards.
     *
     * The background sources are covered by `TrackingController.onDataChanged`; these are the
     * writes that never reach it. [setPlaybackOnly] is in here as well even though it touches no
     * session — it changes what every widget *counts*, and it is a settings write, so nothing
     * watching the database would ever notice.
     */
    private fun mutate(block: suspend () -> Unit) = viewModelScope.launch {
        block()
        refreshWidgets(getApplication<Application>())
    }

    fun setPlaybackOnly(enabled: Boolean) = mutate {
        container.settings.setPlaybackOnly(enabled)
    }

    fun setLanguage(tag: String) = viewModelScope.launch {
        container.settings.setLanguage(tag)
    }

    fun completeOnboarding() = viewModelScope.launch {
        container.settings.setOnboardingDone(true)
    }

    /** Answered either way: opening the settings screen is no promise that anything changed. */
    fun dismissHibernationPrompt() = viewModelScope.launch {
        container.settings.setHibernationAsked(true)
    }

    fun syncWithSystem() = viewModelScope.launch {
        container.trackingController.syncWithSystem()
    }

    fun retirePair(pairId: Long, reason: String?) = mutate {
        container.repository.retirePair(pairId, reason)
    }

    fun renamePair(pairId: Long, label: String) = mutate {
        container.repository.renamePair(pairId, label)
    }

    fun setPurchaseInfo(pairId: Long, purchaseDate: Long?, priceCents: Long?) = viewModelScope.launch {
        // Not a mutate: a price changes no figure any widget shows.
        container.repository.setPurchaseInfo(pairId, purchaseDate, priceCents)
    }

    fun deletePair(pairId: Long) = mutate {
        container.repository.deletePair(pairId)
    }

    fun deleteSession(sessionId: Long) = mutate {
        container.repository.deleteSession(sessionId)
    }

    fun setDeviceIgnored(deviceId: Long, ignored: Boolean) = mutate {
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

    fun importFrom(uri: Uri) = mutate {
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

    companion object {
        /**
         * Builds the view model from the *owner's* application rather than from the one
         * `AndroidViewModelFactory` happens to hold.
         *
         * That factory is a process-wide singleton pinned to the first `Application` it ever saw,
         * which is invisible in an app — there is only ever one — and wrong under Robolectric,
         * where every test builds a fresh one. The view model then reads a container, and so a
         * database, belonging to a previous test: the welcome and hibernation prompts read the
         * answers given to an earlier test's dialog and never appeared. Nothing caught it until
         * the settings moved out of DataStore, whose own store is a per-property singleton and
         * so handed the stale application the same data anyway.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { YlihViewModel(checkNotNull(this[APPLICATION_KEY])) }
        }

        private val RES_EXPORT_OK = R.string.export_done
        private val RES_EXPORT_FAILED = R.string.export_failed
        private val RES_IMPORT_OK = R.plurals.import_done
        private val RES_IMPORT_FAILED = R.string.import_failed
        private val RES_COULD_NOT_OPEN = R.string.error_could_not_open
        private val RES_DETAILED_NEEDS_BLUETOOTH = R.string.detailed_needs_bluetooth
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
