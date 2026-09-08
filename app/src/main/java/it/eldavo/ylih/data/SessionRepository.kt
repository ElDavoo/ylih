package it.eldavo.ylih.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single funnel every tracking source writes through: the manifest Bluetooth receiver, the
 * foreground service and the heartbeat worker. Methods are idempotent by construction: a pair
 * can never hold two open sessions, and closing an already-closed session is a no-op.
 */
class SessionRepository(
    private val db: YlihDatabase,
    private val clock: Clock = Clock.Wall,
) {
    private val devices = db.deviceDao()
    private val pairs = db.pairDao()
    private val sessions = db.sessionDao()
    private val batterySamples = db.batterySampleDao()

    /**
     * Serialises writes from the racing receivers, service and worker.
     *
     * Not reentrant. `setDetailedTracking` already takes it twice in a row, via
     * `closeSessionsForKinds` then `syncWithSystem`; merging those would deadlock silently.
     */
    private val mutex = Mutex()

    fun observeSummaries(): Flow<List<PairSummary>> = pairs.observeSummaries()

    fun observeSummary(pairId: Long): Flow<PairSummary?> =
        pairs.observeSummaries(pairId).map { it.firstOrNull() }

    fun observeSessionsFor(pairId: Long): Flow<List<SessionEntity>> = sessions.observeForPair(pairId)

    /**
     * Sessions inside a moving window, re-read on every table change.
     *
     * [from] is a lambda, not a value: the edge follows the clock, so a flow built once at
     * start-up would keep answering for the day it opened. Room re-queries `from()` on every
     * `sessions` invalidation.
     */
    fun observeRecentSessions(from: () -> Long): Flow<List<SessionEntity>> =
        db.invalidationTracker.createFlow("sessions").map { sessions.since(from()) }

    fun observeOpenSessions(): Flow<List<SessionEntity>> = sessions.observeOpen()

    fun observeDevices(): Flow<List<DeviceEntity>> = devices.observeAll()

    /**
     * Records that [identity] came up. Returns the id of the (possibly pre-existing) open
     * session, or null when the device is ignored.
     */
    suspend fun onConnected(
        identity: DeviceIdentity,
        at: Long = clock.now(),
        measurePlayback: Boolean = false,
    ): Long? = mutex.withLock {
        db.withTransaction { openSession(identity, at, measurePlayback) }
    }

    /** Records that the device behind [key] went away. No-op if we have no open session for it. */
    suspend fun onDisconnected(
        key: String,
        at: Long = clock.now(),
        reason: EndReason = EndReason.DISCONNECTED,
    ): Unit = mutex.withLock {
        db.withTransaction { closeSession(key, at, reason) }
    }

    /**
     * Marks every open session alive at [at]; recovery later closes here.
     *
     * Returns the updated sessions so a caller needing the list too — the service's tick, for
     * notification text — reads the table once per wakeup rather than twice.
     */
    suspend fun heartbeat(at: Long = clock.now()): List<SessionEntity> = mutex.withLock {
        db.withTransaction {
            sessions.allOpen().map {
                sessions.heartbeat(it.id, at)
                it.copy(heartbeatAt = at)
            }
        }
    }

    /**
     * Banks [deltaMs] of playback against whatever session [key] has open.
     *
     * Finding the session and writing to it are one locked step: as two calls, a disconnect
     * landing between them would credit a session that just closed — the same race
     * [SessionDao.addPlayback]'s guard catches. The guard stops the write; this stops the lookup
     * going stale.
     */
    suspend fun creditPlayback(key: String, deltaMs: Long) {
        if (deltaMs <= 0) return
        mutex.withLock {
            db.withTransaction {
                val device = devices.findByKey(key) ?: return@withTransaction
                val pair = pairs.activeFor(device.id) ?: return@withTransaction
                val open = sessions.openFor(pair.id) ?: return@withTransaction
                sessions.addPlayback(open.id, deltaMs)
            }
        }
    }

    /**
     * Files the headset's own battery level against whatever session [key] has open.
     *
     * Three refusals:
     *
     * - outside 0..100 isn't a reading: the stack sends -1 ("unknown") on *every* disconnect,
     *   which at face value is a 40-point drop into nothing, and -100 means Bluetooth is off;
     * - no open session means nothing to file against, and a reading outliving its session would
     *   let two subtract across an unwatched gap;
     * - unchanged isn't new: the stack re-announces the current level on reconnect and whenever
     *   another source appears, and storing it fakes a zero-drain segment mid-discharge.
     *
     * @return whether the pair had an open session — false means "ask again", a real, common
     *   case. See [it.eldavo.ylih.tracking.BtBatteryReceiver].
     */
    suspend fun recordBatteryLevel(key: String, level: Int, at: Long = clock.now()): Boolean =
        mutex.withLock {
            if (level !in 0..100) return@withLock false
            db.withTransaction {
                val device = devices.findByKey(key) ?: return@withTransaction false
                val pair = pairs.activeFor(device.id) ?: return@withTransaction false
                val open = sessions.openFor(pair.id) ?: return@withTransaction false
                if (batterySamples.lastFor(open.id)?.level != level) {
                    batterySamples.insert(
                        BatterySampleEntity(
                            sessionId = open.id,
                            pairId = pair.id,
                            at = at,
                            level = level,
                        ),
                    )
                }
                true
            }
        }

    /** Every reading a pair has produced, oldest first. A read, so no mutex. */
    fun observeBatterySamples(pairId: Long): Flow<List<BatterySampleEntity>> =
        batterySamples.observeForPair(pairId)

    suspend fun openSessionIdFor(key: String): Long? = mutex.withLock {
        val device = devices.findByKey(key) ?: return@withLock null
        val pair = pairs.activeFor(device.id) ?: return@withLock null
        sessions.openFor(pair.id)?.id
    }

    suspend fun hasOpenSessions(): Boolean = sessions.anyOpen()

    suspend fun openSessionsSnapshot(): List<SessionEntity> = sessions.allOpen()

    /** Sessions a window opening at [from] can still reach. A read, so no mutex. */
    suspend fun sessionsSince(from: Long): List<SessionEntity> = sessions.since(from)

    /**
     * Brings the database back in line with reality after process death, a reboot, or a
     * tracking-mode change.
     *
     * Sessions predating the current boot can't still be live, so they close at the last
     * evidence (their heartbeat), never later than [bootAt] — time the phone was off is never
     * counted. A device connected now gets a fresh session, not the old one stretched across
     * the reboot.
     */
    suspend fun reconcile(
        connected: List<DeviceIdentity>,
        now: Long = clock.now(),
        bootAt: Long,
        measurePlayback: Boolean = false,
    ): Unit = mutex.withLock {
        db.withTransaction {
            val connectedKeys = connected.map { it.key }.toSet()
            for (session in sessions.allOpen()) {
                val pair = pairs.byId(session.pairId) ?: continue
                val device = devices.byId(pair.deviceId) ?: continue
                val lastKnownAlive = maxOf(session.connectedAt, session.heartbeatAt)
                val stillConnected = device.deviceKey in connectedKeys
                when {
                    session.connectedAt < bootAt ->
                        // Started before this boot: the connection cannot have survived.
                        sessions.close(session.id, minOf(lastKnownAlive, bootAt), EndReason.RECOVERED)

                    // Still connected, and watched recently enough to believe it never stopped.
                    stillConnected && now - lastKnownAlive <= UNWATCHED_SESSION_MS ->
                        sessions.heartbeat(session.id, now)

                    // Still connected but unwatched far longer than the system would defer the
                    // heartbeat — a force-stop, or a process killed for memory. The headphones
                    // may have come off and back on; the gap goes uncredited. Closing at the last
                    // proof of life lets the pass below open a fresh session, the same split
                    // `openSession` does for a stale connect. This case used to fall into the
                    // heartbeat branch above, stretching the session by moving `heartbeatAt` to
                    // `now` before `openSession` could look.
                    else -> sessions.close(session.id, lastKnownAlive, EndReason.RECOVERED)
                }
            }
            connected.forEach { openSession(it, now, measurePlayback, graceMs = RECONNECT_GRACE_MS) }
        }
    }

    /**
     * Closes open sessions for kinds only the foreground service can observe.
     *
     * [stillLive] separates what the two callers mean by "close it". Turning detailed tracking
     * off happens with the service still running, so the wired session is alive at [at].
     * `TrackingController`'s permission-revoked fallback is the opposite: nothing has watched the
     * session since the process died, so [at] would credit the whole unwatched gap — that caller
     * ends it at the last heartbeat instead.
     */
    suspend fun closeSessionsForKinds(
        kinds: Set<DeviceKind>,
        at: Long = clock.now(),
        reason: EndReason = EndReason.TRACKING_DISABLED,
        stillLive: Boolean = true,
    ): Unit = mutex.withLock {
        db.withTransaction {
            for (session in sessions.allOpen()) {
                val pair = pairs.byId(session.pairId) ?: continue
                val device = devices.byId(pair.deviceId) ?: continue
                if (device.kind !in kinds) continue
                val endAt = if (stillLive) {
                    maxOf(session.connectedAt, at)
                } else {
                    maxOf(session.connectedAt, session.heartbeatAt)
                }
                sessions.close(session.id, endAt, reason)
            }
        }
    }

    /**
     * Freezes [pairId]'s totals. The next connection of the same device starts a new pair at
     * generation + 1 — this is how "my old pair died after 1,240 h" stays true.
     */
    suspend fun retirePair(
        pairId: Long,
        reason: String?,
        at: Long = clock.now(),
    ): Unit = mutex.withLock {
        db.withTransaction {
            val pair = pairs.byId(pairId) ?: return@withTransaction
            // Already retired: a double tap or app/widget race must not move the frozen date.
            if (pair.retiredAt != null) return@withTransaction
            sessions.openFor(pairId)?.let { open ->
                sessions.close(open.id, maxOf(open.connectedAt, at), EndReason.MANUAL)
            }
            pairs.update(pair.copy(retiredAt = at, retireReason = reason?.takeIf { it.isNotBlank() }))
        }
    }

    suspend fun renamePair(pairId: Long, label: String) = mutex.withLock {
        db.withTransaction {
            pairs.byId(pairId)?.let { pairs.update(it.copy(label = label.trim().ifEmpty { it.label })) }
        }
    }

    suspend fun setPurchaseInfo(pairId: Long, purchaseDate: Long?, priceCents: Long?) = mutex.withLock {
        db.withTransaction {
            pairs.byId(pairId)?.let { pairs.update(it.copy(purchaseDate = purchaseDate, priceCents = priceCents)) }
        }
    }

    /**
     * Runs [block] holding the write lock, for the one writer outside this class.
     *
     * `JsonBackup` replaces the whole database and must serialise with the four tracking
     * sources — a connect arriving mid-import once wrote a session the import's `deleteAll`
     * cascade then destroyed. It builds its own statements rather than calling methods here, so
     * it needs the lock, not the funnel. Export takes it too, so its file is a snapshot of
     * nothing mid-change.
     *
     * Not reentrant — see [mutex]. Nothing inside [block] may call back into this class.
     */
    suspend fun <T> withWriteLock(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun deletePair(pairId: Long) = mutex.withLock { pairs.delete(pairId) }

    suspend fun deleteSession(sessionId: Long) = mutex.withLock { sessions.delete(sessionId) }

    suspend fun setDeviceIgnored(deviceId: Long, ignored: Boolean): Unit = mutex.withLock {
        db.withTransaction {
            devices.setIgnored(deviceId, ignored)
            if (ignored) {
                val now = clock.now()
                pairs.activeFor(deviceId)?.let { pair ->
                    sessions.openFor(pair.id)?.let { open ->
                        sessions.close(open.id, maxOf(open.connectedAt, now), EndReason.MANUAL)
                    }
                }
            }
        }
    }

    // --- internals, always called inside the transaction + mutex ---------------------------

    private suspend fun openSession(
        identity: DeviceIdentity,
        at: Long,
        measurePlayback: Boolean,
        graceMs: Long = 0,
    ): Long? {
        val device = upsertDevice(identity, at)
        if (device.ignored) return null

        val pair = pairs.activeFor(device.id) ?: PairEntity(
            deviceId = device.id,
            label = device.defaultName,
            generation = pairs.maxGeneration(device.id) + 1,
            startedAt = at,
        ).let { it.copy(id = pairs.insert(it)) }

        if (graceMs > 0) {
            // The audio device list lags a disconnect briefly; without this a reconcile racing a
            // fresh ACL_DISCONNECTED would resurrect the session just closed.
            val lastEnd = sessions.lastDisconnectAt(pair.id)
            if (lastEnd != null && at - lastEnd in 0 until graceMs) return null
        }

        sessions.openFor(pair.id)?.let { existing ->
            val lastKnownAlive = maxOf(existing.connectedAt, existing.heartbeatAt)
            if (at - lastKnownAlive <= STALE_SESSION_MS) {
                // Same live connection seen twice: receiver and service both saw it, or the
                // system repeated the broadcast.
                sessions.heartbeat(existing.id, maxOf(existing.heartbeatAt, at))
                if (measurePlayback) sessions.startMeasuringPlayback(existing.id)
                return existing.id
            }
            // Nothing was running when that connection actually ended. Close at the last proof
            // of life instead of stretching over the gap, then open a fresh one below.
            sessions.close(existing.id, lastKnownAlive, EndReason.RECOVERED)
        }

        return sessions.insert(
            SessionEntity(
                pairId = pair.id,
                connectedAt = at,
                heartbeatAt = at,
                playingMs = if (measurePlayback) 0L else null,
            ),
        )
    }

    private suspend fun closeSession(key: String, at: Long, reason: EndReason) {
        val device = devices.findByKey(key) ?: return
        val pair = pairs.activeFor(device.id) ?: return
        val open = sessions.openFor(pair.id) ?: return
        sessions.close(open.id, maxOf(open.connectedAt, at), reason)
    }

    private suspend fun upsertDevice(identity: DeviceIdentity, at: Long): DeviceEntity {
        val existing = devices.findByKey(identity.key)
        if (existing == null) {
            val entity = DeviceEntity(
                deviceKey = identity.key,
                kind = identity.kind,
                defaultName = identity.name,
                firstSeenAt = at,
            )
            return entity.copy(id = devices.insert(entity))
        }
        // Names change (renamed headset, better name once BLUETOOTH_CONNECT granted), and so does
        // kind — the same headset reports A2DP by one platform view and BLE by another. Kind used
        // to update only alongside a name change, so a kind change under an unchanged name stuck
        // forever; `trackedKinds` and `closeSessionsForKinds` filter on kind, so a wrong one
        // strands a session neither can reach.
        val name = identity.name.takeIf { it.isNotBlank() } ?: existing.defaultName
        if (name != existing.defaultName || identity.kind != existing.kind) {
            val updated = existing.copy(defaultName = name, kind = identity.kind)
            devices.update(updated)
            return updated
        }
        return existing
    }

    private companion object {
        /** How long after a disconnect a reconcile refuses to re-open the same pair. */
        const val RECONNECT_GRACE_MS = 30_000L

        /**
         * An open session whose last heartbeat is older than this can't be the connection just
         * reported — three times the 15-minute heartbeat interval.
         *
         * Answers a question about a *connect event*: a device that never dropped fires no
         * second ACL_CONNECTED, so an arriving event is either an already-handled repeat or a
         * genuinely new connection, told apart by the gap since the last proof of life.
         * Deliberately not the ceiling [UNWATCHED_SESSION_MS] sets, which answers a different
         * question.
         */
        const val STALE_SESSION_MS = 45 * 60_000L

        /**
         * How long a still-connected session may go unwatched before the app distrusts it ran
         * unbroken.
         *
         * Far longer than [STALE_SESSION_MS]: this decides whether to credit an unobserved
         * stretch, usually caused by Doze deferring the periodic worker to its next maintenance
         * window — hours away when idle. A ceiling near the heartbeat interval would split every
         * pocketed night into two sessions and lose the listening.
         *
         * Six hours is the longest the platform legitimately defers the worker; past that the
         * cause is a force-stop or a memory-killed process, not battery management. Unobserved
         * time isn't listening time, so the session closes at its last proof of life and a fresh
         * one opens.
         */
        const val UNWATCHED_SESSION_MS = 6 * 3_600_000L
    }
}
