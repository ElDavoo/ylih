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
import it.eldavo.ylih.runCatchingCancellable
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.stats.Span
import it.eldavo.ylih.stats.Summary
import it.eldavo.ylih.widget.refreshWidgets
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
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

    /**
     * [now] rounded down to the minute, for every figure derived from the whole history.
     *
     * Nothing that reads this can show a per-second change: `formatHours` rounds to a tenth of an
     * hour, which is six minutes, and the day buckets move by the hour. But keying them on [now]
     * meant re-summarising and re-bucketing every session ever recorded sixty times a minute, on
     * the main thread, on three screens — a cost that grows for as long as the app is used. The
     * live "connected for …" lines still read [now]; they are the only thing on screen that is
     * meant to move every second.
     */
    val nowMinute: StateFlow<Long> = now
        .map { it - it.mod(MINUTE_MS) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(2_000),
            container.clock.now().let { it - it.mod(MINUTE_MS) },
        )

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

    /**
     * The sessions the charts can actually draw: the last [WINDOW_DAYS] days, plus whatever is
     * still open.
     *
     * This used to be the whole table, twice — an `observeAllSessions()` per shape. Room's
     * invalidation is table-granular and the service writes a heartbeat to `sessions` once a
     * minute, so every one of those minutes re-ran `SELECT * FROM sessions ORDER BY connectedAt`
     * over a table that grows for as long as the app is used: a scan and a sort of the lot,
     * measured at 26 ms against 22,000 rows, to redraw a thirty-day chart and produce lifetime
     * figures SQL had already grouped. The lifetime figures come off the aggregate now
     * (`summarizeLifetime`) and this covers the windows, which is all a chart can show.
     *
     * A day of slack past the window so that a bucket on the boundary is whole, and re-read
     * whenever the table changes because the window's own edge moves with the clock.
     */
    private val recentSessions: SharedFlow<List<SessionEntity>> = container.repository
        .observeRecentSessions { container.clock.now() - (WINDOW_DAYS + 1) * DAY_MS }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val recentSpans: StateFlow<List<Span>> = recentSessions
        .map { sessions -> sessions.map { it.toSpan() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The same window grouped per pair, so a card's figures need no query of its own. */
    val spansByPair: StateFlow<Map<Long, List<Span>>> = recentSessions
        .map { sessions -> sessions.groupBy({ it.pairId }, { it.toSpan() }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Snackbar text, over a buffered channel rather than a `SharedFlow`.
     *
     * The UI collects this under `repeatOnLifecycle`, so there are stretches with no collector at
     * all — and a `MutableSharedFlow` with `replay = 0` drops what it emits when nobody is
     * listening, which is precisely those stretches. A channel buffers instead, so a message
     * raised while the activity is stopped is waiting when it comes back. Raising the replay to 1
     * would be the other way to survive that, and it would re-show the last message on every
     * return to STARTED, rotation included.
     *
     * Consume-once, and nothing enforces a single collector: a second one would take messages from
     * the first. There is one, in `YlihNavHost`.
     */
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    fun summary(pairId: Long): Flow<PairSummary?> = container.repository.observeSummary(pairId)

    fun sessions(pairId: Long): Flow<List<SessionEntity>> =
        container.repository.observeSessionsFor(pairId)

    /**
     * False on the Play build until Bluetooth access is granted — see `Distribution`.
     *
     * State rather than a function call, for two reasons. It reaches `checkSelfPermission`, a
     * binder round-trip, and the settings screen was making it on every recomposition. And the
     * answer *changes*: granting Bluetooth from the prompt that screen raises flips it, and a
     * function read during composition gives nothing to recompose on, so the row stayed disabled
     * until something unrelated redrew it. [refreshCapabilities] is what re-asks.
     */
    private val _detailedTrackingSupported =
        MutableStateFlow(container.trackingController.detailedTrackingSupported())
    val detailedTrackingSupported: StateFlow<Boolean> = _detailedTrackingSupported.asStateFlow()

    /** Re-reads what the platform will currently allow; called after a permission result. */
    fun refreshCapabilities() {
        _detailedTrackingSupported.value =
            container.trackingController.detailedTrackingSupported()
    }

    fun setDetailedTracking(enabled: Boolean) = viewModelScope.launch {
        if (!container.trackingController.setDetailedTracking(enabled)) {
            messageChannel.send(string(RES_DETAILED_NEEDS_BLUETOOTH))
        }
        refreshCapabilities()
    }

    /**
     * Runs a foreground edit and tells the home screen about it afterwards.
     *
     * The background sources are covered by `TrackingController.onDataChanged`; these are the
     * writes that never reach it. [setPlaybackOnly] is in here as well even though it touches no
     * session — it changes what every widget *counts*, and it is a settings write, so nothing
     * watching the database would ever notice.
     *
     * Every caller is a database write, and an exception escaping `viewModelScope.launch` reaches
     * the default handler and takes the process with it — so a failed delete crashed the app
     * rather than saying so. It reports through the same channel `exportTo` and `importFrom`
     * already use; the redraw still runs, because whatever did land needs showing.
     */
    private fun mutate(block: suspend () -> Unit) = viewModelScope.launch {
        runCatchingCancellable { block() }
            .onFailure { messageChannel.send(it.message ?: string(RES_EDIT_FAILED)) }
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
        // A sync is exactly when a permission granted since the last one takes effect.
        refreshCapabilities()
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
        // Ignoring closes the open session itself. Un-ignoring has nothing to reopen from — the
        // device is connected right now but no event will say so again — so without this, tracking
        // resumed only whenever something else happened to sync, up to fifteen minutes later.
        if (!ignored) container.trackingController.syncWithSystem()
    }

    fun exportTo(uri: Uri) = viewModelScope.launch {
        runCatchingCancellable {
            val payload = container.repository.withWriteLock {
                JsonBackup.export(container.database, container.clock.now())
            }
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                it.write(payload.toByteArray())
            } ?: error(string(RES_COULD_NOT_OPEN, uri))
        }.onSuccess { messageChannel.send(string(RES_EXPORT_OK)) }
            .onFailure { messageChannel.send(it.message ?: string(RES_EXPORT_FAILED)) }
    }

    fun importFrom(uri: Uri) = mutate {
        runCatchingCancellable {
            val content = getApplication<Application>().contentResolver.openInputStream(uri)
                ?.use { it.readBytes().decodeToString() }
                ?: error(string(RES_COULD_NOT_OPEN, uri))
            container.repository.withWriteLock { JsonBackup.import(container.database, content) }
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
        private val RES_EDIT_FAILED = R.string.edit_failed
        private val RES_COULD_NOT_OPEN = R.string.error_could_not_open
        private val RES_DETAILED_NEEDS_BLUETOOTH = R.string.detailed_needs_bluetooth

        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 24 * 60 * MINUTE_MS
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

/**
 * The stats screen's headline block, out of the per-pair aggregates rather than out of every
 * session ever recorded.
 *
 * `Stats.summarize` answers the same question from a `List<Span>`, and did until this existed —
 * which meant `SELECT * FROM sessions` on every invalidation of a table the heartbeat writes to
 * once a minute, growing for as long as the app is used, to produce figures SQL had already
 * grouped. The two must agree exactly, and `SummarizeLifetimeTest` is what says they do;
 * treat that test as the definition and this as an implementation of it.
 *
 * Each pair contributes its finished sessions from SQL and its open one from here, because
 * clamping an open session's playback needs `now`. A pair holds at most one open session, which is
 * what makes that a single term rather than a scan.
 */
fun List<PairSummary>.summarizeLifetime(now: Long, counting: Counting): Summary {
    val openConnected = { it: PairSummary -> it.openSince?.let { at -> (now - at).coerceAtLeast(0) } }
    // The open session counts here only if it can answer the question being asked: under playback
    // that means it is measuring, which is exactly what a non-null `openPlayingMs` says.
    val openCounted = { it: PairSummary ->
        when (counting) {
            Counting.CONNECTED -> openConnected(it)
            Counting.PLAYBACK ->
                it.openPlayingMs?.let { played -> played.coerceIn(0L, openConnected(it) ?: 0L) }
        }
    }
    val sessions = sumOf {
        when (counting) {
            Counting.CONNECTED -> it.sessionCount
            Counting.PLAYBACK -> it.measuredSessionCount
        }
    }
    if (sessions == 0) return Summary(0, 0, 0, 0, 0, 0, null, null, null)

    val total = sumOf {
        val closed = when (counting) {
            Counting.CONNECTED -> it.closedMs
            Counting.PLAYBACK -> it.closedPlaybackMs
        }
        closed + (openCounted(it) ?: 0L)
    }
    return Summary(
        sessionCount = sessions,
        totalMs = total,
        longestMs = maxOf(
            maxOfOrNull {
                when (counting) {
                    Counting.CONNECTED -> it.longestMs
                    Counting.PLAYBACK -> it.longestClosedPlaybackMs
                }
            } ?: 0L,
            mapNotNull(openCounted).maxOrNull() ?: 0L,
        ),
        averageMs = total / sessions,
        // Unclamped, like `Stats.summarize`: this is what the watcher banked, and the row it
        // appears in reads it against `measuredMs` as a share.
        playingMs = sumOf { it.playingMs },
        measuredMs = sumOf {
            it.measuredPlaybackMs + (if (it.openPlayingMs != null) openConnected(it) ?: 0L else 0L)
        },
        firstAt = mapNotNull {
            when (counting) {
                Counting.CONNECTED -> it.firstAt
                Counting.PLAYBACK -> it.firstMeasuredAt
            }
        }.minOrNull(),
        // `now` only where the open session is one this counting mode can see: an unmeasured
        // session still running says nothing about when playback was last heard.
        lastAt = mapNotNull { summary ->
            if (openCounted(summary) != null) {
                now
            } else {
                when (counting) {
                    Counting.CONNECTED -> summary.lastSeenAt
                    Counting.PLAYBACK -> summary.lastMeasuredAt
                }
            }
        }.maxOrNull(),
        openSince = mapNotNull { if (openCounted(it) != null) it.openSince else null }.minOrNull(),
    )
}
