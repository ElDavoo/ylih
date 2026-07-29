package it.eldavo.ylih.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
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

    /** Serialises writes from receivers, the service and the worker, which race freely. */
    private val mutex = Mutex()

    fun observeSummaries(): Flow<List<PairSummary>> = pairs.observeSummaries()

    fun observeSummary(pairId: Long): Flow<PairSummary?> = pairs.observeSummary(pairId)

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

    suspend fun addPlayback(sessionId: Long, deltaMs: Long) {
        if (deltaMs <= 0) return
        mutex.withLock { sessions.addPlayback(sessionId, deltaMs) }
    }

    suspend fun openSessionIdFor(key: String): Long? = mutex.withLock {
        val device = devices.findByKey(key) ?: return@withLock null
        val pair = pairs.activeFor(device.id) ?: return@withLock null
        sessions.openFor(pair.id)?.id
    }

    suspend fun hasOpenSessions(): Boolean = sessions.allOpen().isNotEmpty()

    suspend fun openSessionsSnapshot(): List<SessionEntity> = sessions.allOpen()

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

                    stillConnected -> sessions.heartbeat(session.id, now)

                    else -> sessions.close(session.id, lastKnownAlive, EndReason.RECOVERED)
                }
            }
            connected.forEach { openSession(it, now, measurePlayback, graceMs = RECONNECT_GRACE_MS) }
        }
    }

    /** Closes open sessions for kinds that only the foreground service can observe. */
    suspend fun closeSessionsForKinds(
        kinds: Set<DeviceKind>,
        at: Long = clock.now(),
        reason: EndReason = EndReason.TRACKING_DISABLED,
    ): Unit = mutex.withLock {
        db.withTransaction {
            for (session in sessions.allOpen()) {
                val pair = pairs.byId(session.pairId) ?: continue
                val device = devices.byId(pair.deviceId) ?: continue
                if (device.kind in kinds) {
                    sessions.close(session.id, maxOf(session.connectedAt, at), reason)
                }
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
        // Names change (renamed headset, better name once BLUETOOTH_CONNECT is granted).
        if (identity.name.isNotBlank() && identity.name != existing.defaultName) {
            val updated = existing.copy(defaultName = identity.name, kind = identity.kind)
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
         */
        const val STALE_SESSION_MS = 45 * 60_000L
    }
}
