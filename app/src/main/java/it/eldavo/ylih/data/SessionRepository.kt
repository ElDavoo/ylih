package it.eldavo.ylih.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single funnel every tracking source writes through — the manifest Bluetooth receiver,
 * the foreground service and the heartbeat worker all call these methods, so they are
 * idempotent by construction: a pair can never hold two open sessions, and closing a session
 * that is already closed is a no-op.
 */
class SessionRepository(
    private val db: YlihDatabase,
    private val clock: Clock = Clock.Wall,
) {
    private val devices = db.deviceDao()
    private val pairs = db.pairDao()
    private val sessions = db.sessionDao()

    /**
     * Serialises writes from receivers, the service and the worker, which race freely.
     *
     * Not reentrant, and nothing here nests two public calls — but `setDetailedTracking` already
     * takes it twice in a row, via `closeSessionsForKinds` and then `syncWithSystem`, so a
     * refactor that merged those into one would deadlock with no diagnostic at all.
     */
    private val mutex = Mutex()

    fun observeSummaries(): Flow<List<PairSummary>> = pairs.observeSummaries()

    fun observeSummary(pairId: Long): Flow<PairSummary?> =
        pairs.observeSummaries(pairId).map { it.firstOrNull() }

    fun observeSessionsFor(pairId: Long): Flow<List<SessionEntity>> = sessions.observeForPair(pairId)

    fun observeAllSessions(): Flow<List<SessionEntity>> = sessions.observeAll()

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
     * Marks every open session as still alive at [at]; recovery later closes here.
     *
     * Returns the sessions as they now stand, so a caller that also needs the list — the
     * service's tick, which turns it into notification text — reads the table once rather than
     * twice on every wakeup.
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
     * Finding the session and writing to it are one locked step on purpose. Done as two calls —
     * `openSessionIdFor` then a write — a disconnect landing between them credits a session that
     * has just closed, which is the race [SessionDao.addPlayback]'s own guard exists to catch.
     * Both are needed: the guard stops the write, this stops the lookup from going stale.
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

    suspend fun openSessionIdFor(key: String): Long? = mutex.withLock {
        val device = devices.findByKey(key) ?: return@withLock null
        val pair = pairs.activeFor(device.id) ?: return@withLock null
        sessions.openFor(pair.id)?.id
    }

    suspend fun hasOpenSessions(): Boolean = sessions.allOpen().isNotEmpty()

    suspend fun openSessionsSnapshot(): List<SessionEntity> = sessions.allOpen()

    /** Sessions a window opening at [from] can still reach. A read, so no mutex. */
    suspend fun sessionsSince(from: Long): List<SessionEntity> = sessions.since(from)

    /**
     * Brings the database back in line with reality after process death, a reboot, or a
     * tracking-mode change.
     *
     * Sessions that predate the current boot cannot still be live, so they are closed at the
     * last moment we have evidence for (their heartbeat) and never later than [bootAt] — the
     * time the phone was off is never counted. If such a device is connected right now, a
     * fresh session is opened instead of stretching the old one across the reboot.
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

                    // Still connected, but nothing of ours has looked in far longer than the
                    // system would ever defer the heartbeat — a force-stop, or a process gone
                    // since something else needed the memory. The headphones may well have been
                    // taken off and put back on in between; nothing knows, so the gap is not
                    // credited. Closing at the last proof of life lets the pass below open a
                    // fresh session, which is the same split `openSession` does for a connect
                    // arriving on a stale one. This case used to fall into the heartbeat above
                    // and stretch the session over the whole gap — and it defeated that split
                    // too, by moving `heartbeatAt` up to `now` before `openSession` could look.
                    else -> sessions.close(session.id, lastKnownAlive, EndReason.RECOVERED)
                }
            }
            connected.forEach { openSession(it, now, measurePlayback, graceMs = RECONNECT_GRACE_MS) }
        }
    }

    /**
     * Closes open sessions for kinds that only the foreground service can observe.
     *
     * The two callers mean different things by "close it", which is what [stillLive] separates.
     * Turning detailed tracking off happens with the app in front of the user and the service
     * still running, so the wired session genuinely is alive at [at]. The permission-revoked
     * fallback in `TrackingController` is the opposite: nothing of ours has watched that session
     * since the process last died, so [at] is not evidence of anything and closing there would
     * credit the whole unwatched gap. That caller ends the session at the last heartbeat instead,
     * which is the rule the rest of this file follows — never later than the last proof of life.
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
            // Already retired: the totals are frozen and the date is part of the record. A second
            // call — a double tap, or the app and a widget both landing — must not move either.
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
     * Runs [block] holding the write lock, for the one writer that lives outside this class.
     *
     * `JsonBackup` replaces the whole database, which is a write like any other and has to be
     * serialised with the four tracking sources — a connect arriving mid-import wrote a session
     * that the import's `deleteAll` cascade then destroyed, which made it a fifth writer beside
     * the ones this class exists to funnel. It builds its own statements rather than calling the
     * methods here, so what it needs is the lock rather than the funnel. Export takes it too, so
     * that the file is a snapshot of a database nothing is halfway through changing.
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
            // The audio device list lags a disconnect by a moment; without this a reconcile
            // racing a fresh ACL_DISCONNECTED would resurrect the session we just closed.
            val lastEnd = sessions.lastDisconnectAt(pair.id)
            if (lastEnd != null && at - lastEnd in 0 until graceMs) return null
        }

        sessions.openFor(pair.id)?.let { existing ->
            val lastKnownAlive = maxOf(existing.connectedAt, existing.heartbeatAt)
            if (at - lastKnownAlive <= STALE_SESSION_MS) {
                // The same live connection seen twice: the receiver and the service both saw
                // it, or the system repeated the broadcast.
                sessions.heartbeat(existing.id, maxOf(existing.heartbeatAt, at))
                if (measurePlayback) sessions.startMeasuringPlayback(existing.id)
                return existing.id
            }
            // Nothing of ours was running when that connection actually ended. Close it where
            // we last had proof it was alive instead of stretching it over the gap, then open
            // a fresh session below.
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
        // Names change (renamed headset, better name once BLUETOOTH_CONNECT is granted), and so
        // does the kind — the same headset is reported as A2DP by one platform view and BLE by
        // another. The kind was previously only ever written alongside a name change, so a headset
        // that changed kind under an unchanged name kept the stale one forever; `trackedKinds` and
        // `closeSessionsForKinds` both filter on it, so a wrong kind leaves a session neither can
        // reach.
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
         * An open session whose last heartbeat is older than this cannot be the connection we
         * are being told about now — three times the 15-minute heartbeat interval.
         *
         * This is a question about a *connect event*: a device that never dropped fires no second
         * ACL_CONNECTED, so an event arriving at all means either a repeat of one already handled
         * or a genuinely new connection, and the gap since the last proof of life is what tells
         * the two apart. Deliberately not the ceiling [UNWATCHED_SESSION_MS] sets, which answers
         * something else entirely.
         */
        const val STALE_SESSION_MS = 45 * 60_000L

        /**
         * How long a still-connected session may go unwatched before the app stops believing it
         * ran without a break.
         *
         * Much longer than [STALE_SESSION_MS] on purpose. This one decides whether to credit a
         * stretch of time nothing observed at all, and the ordinary reason for one is Doze, which
         * defers a periodic worker to its next maintenance window — hours away on an idle phone.
         * A ceiling anywhere near the heartbeat interval would turn every night in a pocket into
         * a split session and lose the listening along with it.
         *
         * Six hours is about the longest the platform will legitimately keep the worker from
         * running, so past it the explanation is no longer battery management: the app was
         * force-stopped, or its process has been gone since something else needed the memory.
         * Nothing knows what the headphones did in between, and unobserved time is not listening
         * time — so the session is closed at its last proof of life and a fresh one opened.
         */
        const val UNWATCHED_SESSION_MS = 6 * 3_600_000L
    }
}
