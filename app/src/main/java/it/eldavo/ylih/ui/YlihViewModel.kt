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
import it.eldavo.ylih.data.BatterySampleEntity
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.PairSummary
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.export.JsonBackup
import it.eldavo.ylih.runCatchingCancellable
import it.eldavo.ylih.stats.Charge
import it.eldavo.ylih.stats.ChargeSummary
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.stats.Reading
import it.eldavo.ylih.stats.Span
import it.eldavo.ylih.stats.Summary
import it.eldavo.ylih.widget.refreshWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
     * Nothing here shows a per-second change: `formatHours` rounds to a tenth of an hour (six
     * minutes), and day buckets move by the hour. Keying on [now] instead meant re-summarising
     * and re-bucketing every session sixty times a minute, on the main thread, on three screens,
     * growing with app use. Live "connected for …" lines still read [now]; they're the only thing
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

    /** Whether an on-device assistant may read these figures — see `agent/YlihAppFunctions.kt`. */
    val agentAccess: StateFlow<Boolean> = container.settings.agentAccess
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Null until the stored answer arrives, so the welcome does not flash on every later launch. */
    val onboardingDone: StateFlow<Boolean?> = container.settings.onboardingDone
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Null until the stored answer arrives, for the same reason as [onboardingDone]. */
    val hibernationAsked: StateFlow<Boolean?> = container.settings.hibernationAsked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Null until the stored tag arrives — the settings screen restarts the activity when this
     * changes, so it must not see the default first.
     */
    val language: StateFlow<String?> = container.settings.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The sessions the charts can draw: the last [WINDOW_DAYS] days, plus whatever is still open.
     *
     * Used to be the whole table, twice — an `observeAllSessions()` per shape. Room's invalidation
     * is table-granular and the service writes a heartbeat to `sessions` once a minute, so every
     * minute re-ran `SELECT * FROM sessions ORDER BY connectedAt` over a growing table: a scan and
     * sort of the lot, measured at 26 ms against 22,000 rows, just to redraw a thirty-day chart and
     * produce figures SQL had already grouped. Lifetime figures now come off the aggregate
     * (`summarizeLifetime`); this covers the windows, all a chart can show.
     *
     * A day of slack past the window keeps a boundary bucket whole, and it re-reads on every table
     * change since the window's edge moves with the clock.
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
     * The UI collects this under `repeatOnLifecycle`, so there are stretches with no collector,
     * and a `MutableSharedFlow` with `replay = 0` drops what it emits with nobody listening. A
     * channel buffers instead, so a message raised while the activity is stopped is waiting when
     * it returns. Raising the replay to 1 would also survive that, but would re-show the last
     * message on every return to STARTED, rotation included.
     *
     * Consume-once, and nothing enforces a single collector — a second would steal messages from
     * the first. There is one, in `YlihNavHost`.
     */
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    fun summary(pairId: Long): Flow<PairSummary?> = container.repository.observeSummary(pairId)

    fun sessions(pairId: Long): Flow<List<SessionEntity>> =
        container.repository.observeSessionsFor(pairId)

    /**
     * This pair's charge cycles, worked out off the main thread.
     *
     * The readings are unwindowed, unlike [recentSessions]: charge cycles are a lifetime figure —
     * what a charge bought when the pair was new against what it buys now, which a thirty-day
     * window can't ask. That makes the walk over them the one calculation here whose cost grows
     * without bound (measured at 173 ms for a million readings), so it lives here rather than in
     * a `remember` on the pair page, where it used to sit keyed on the minute clock and re-run on
     * the main thread every sixty seconds.
     *
     * Sessions are reduced to spans *before* [distinctUntilChanged], keeping the heartbeat out of
     * this: it writes to `sessions` once a minute but moves nothing this summary reads, so the
     * spans compare equal and nothing recomputes.
     */
    fun chargeSummary(pairId: Long): Flow<ChargeSummary> {
        val spans = container.repository.observeSessionsFor(pairId)
            .map { sessions -> sessions.associate { it.id to it.toSpan() } }
            .distinctUntilChanged()
        val readings = container.repository.observeBatterySamples(pairId)
            .map { samples -> samples.map { it.toReading() } }
        return combine(readings, spans, counting) { byTime, bySession, mode ->
            Charge.summarize(byTime, bySession, container.clock.now(), mode)
        }.flowOn(Dispatchers.Default)
    }

    /**
     * False on the Play build until Bluetooth access is granted — see `Distribution`.
     *
     * State, not a function call, for two reasons: it reaches `checkSelfPermission`, a binder
     * round-trip the settings screen made on every recomposition; and the answer *changes* —
     * granting Bluetooth from the prompt that screen raises flips it, and a function read during
     * composition gives nothing to recompose on, so the row stayed disabled until something
     * unrelated redrew it. [refreshCapabilities] re-asks.
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
     * `TrackingController.onDataChanged` covers background sources; these are the writes that
     * never reach it. [setPlaybackOnly] is here too even though it touches no session — it changes
     * what every widget *counts*, and as a settings write nothing watching the database would
     * otherwise notice.
     *
     * Every caller is a database write, and an exception escaping `viewModelScope.launch` reaches
     * the default handler and takes the process with it — a failed delete used to crash the app
     * instead of reporting it. It now reports through the same channel `exportTo`/`importFrom`
     * use; the redraw still runs, since whatever did land needs showing.
     */
    private fun mutate(block: suspend () -> Unit) = viewModelScope.launch {
        runCatchingCancellable { block() }
            .onFailure { messageChannel.send(it.message ?: string(RES_EDIT_FAILED)) }
        refreshWidgets(getApplication<Application>())
    }

    fun setPlaybackOnly(enabled: Boolean) = mutate {
        container.settings.setPlaybackOnly(enabled)
    }

    /**
     * Not a [mutate]: this moves no figure any widget shows. Reported the same way regardless,
     * since the write reaches the platform as well as the table (see
     * `SettingsStore.setAgentAccess`), and a refusal there must say so rather than leave the
     * switch looking like it took.
     */
    fun setAgentAccess(enabled: Boolean) = viewModelScope.launch {
        runCatchingCancellable { container.settings.setAgentAccess(enabled) }
            .onFailure { messageChannel.send(it.message ?: string(RES_EDIT_FAILED)) }
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
        // device is connected but no event will say so again — so without this, tracking resumed
        // only when something else happened to sync, up to fifteen minutes later.
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
         * Builds the view model from the *owner's* application, not the one
         * `AndroidViewModelFactory` happens to hold.
         *
         * That factory is a process-wide singleton pinned to the first `Application` it ever saw —
         * invisible in an app, where there's only ever one, but wrong under Robolectric, where
         * every test builds a fresh one. The view model then reads a container, and so a database,
         * belonging to a previous test: welcome and hibernation prompts read an earlier test's
         * answers and never appeared. Nothing caught it until settings moved out of DataStore,
         * whose own per-property-singleton store handed the stale application the same data
         * anyway.
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

fun BatterySampleEntity.toReading(): Reading = Reading(sessionId, at, level)

/**
 * The lifetime figure a pair's card and the ranking show, off the aggregate, not its sessions.
 * `closedMs` is finished sessions only, so connected time adds the open one's live tail; the
 * playback sum already includes it, since the watcher banks playback into the open session as it
 * goes.
 */
fun PairSummary.countedMs(now: Long, counting: Counting): Long = when (counting) {
    Counting.CONNECTED -> closedMs + (openSince?.let { now - it } ?: 0L)
    Counting.PLAYBACK -> playingMs
}

/**
 * The stats screen's headline block, off the per-pair aggregates, not every session ever
 * recorded.
 *
 * `Stats.summarize` answers the same question from a `List<Span>`, and did until this existed —
 * meaning `SELECT * FROM sessions` on every invalidation of a table the heartbeat writes to once
 * a minute, growing with app use, to produce figures SQL had already grouped. The two must agree
 * exactly; `SummarizeLifetimeTest` says they do — treat that test as the definition and this as
 * an implementation of it.
 *
 * Each pair contributes its finished sessions from SQL and its open one from here, since clamping
 * an open session's playback needs `now`. A pair holds at most one open session, so that's a
 * single term, not a scan.
 */
fun List<PairSummary>.summarizeLifetime(now: Long, counting: Counting): Summary {
    val openConnected = { it: PairSummary -> it.openSince?.let { at -> (now - at).coerceAtLeast(0) } }
    // The open session counts here only if it can answer the question asked: under playback that
    // means it is measuring, which is what a non-null `openPlayingMs` says.
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
        // Unclamped, like `Stats.summarize`: what the watcher banked, read against `measuredMs`
        // as a share in the row it appears in.
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
