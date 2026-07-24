package it.eldavo.ylih.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.PairSummary
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.export.JsonBackup
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
            messageChannel.send(
                getApplication<Application>().getString(RES_DETAILED_NEEDS_BLUETOOTH),
            )
        }
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
            } ?: error("Could not open $uri")
        }.onSuccess { messageChannel.send(getApplication<Application>().getString(RES_EXPORT_OK)) }
            .onFailure { messageChannel.send(it.message ?: "Export failed") }
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        runCatching {
            val content = getApplication<Application>().contentResolver.openInputStream(uri)
                ?.use { it.readBytes().decodeToString() }
                ?: error("Could not open $uri")
            JsonBackup.import(container.database, content)
        }.onSuccess { count ->
            messageChannel.send(
                getApplication<Application>().resources.getQuantityString(RES_IMPORT_OK, count, count),
            )
            container.trackingController.syncWithSystem()
        }.onFailure { messageChannel.send(it.message ?: "Import failed") }
    }

    private companion object {
        val RES_EXPORT_OK = it.eldavo.ylih.R.string.export_done
        val RES_IMPORT_OK = it.eldavo.ylih.R.plurals.import_done
        val RES_DETAILED_NEEDS_BLUETOOTH = it.eldavo.ylih.R.string.detailed_needs_bluetooth
    }
}

fun SessionEntity.toSpan(): Span = Span(connectedAt, disconnectedAt, playingMs)
