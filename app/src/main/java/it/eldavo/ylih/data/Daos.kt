package it.eldavo.ylih.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Aggregated row backing the device list and the pair detail screen. */
data class PairSummary(
    val pairId: Long,
    val deviceId: Long,
    val label: String,
    val generation: Int,
    val startedAt: Long,
    val retiredAt: Long?,
    val retireReason: String?,
    val purchaseDate: Long?,
    val priceCents: Long?,
    val deviceKey: String,
    val deviceKind: DeviceKind,
    val deviceName: String,
    val deviceIgnored: Boolean,
    /** Sum of finished sessions only; add `now - openSince` for the live total. */
    val closedMs: Long,
    val playingMs: Long,
    val measuredPlaybackMs: Long,
    val sessionCount: Int,
    val openSince: Long?,
    val lastSeenAt: Long?,
    val longestMs: Long,
)

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices WHERE deviceKey = :key LIMIT 1")
    suspend fun findByKey(key: String): DeviceEntity?

    @Query("SELECT * FROM devices WHERE id = :deviceId")
    suspend fun byId(deviceId: Long): DeviceEntity?

    @Query("SELECT * FROM devices ORDER BY firstSeenAt")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Insert
    suspend fun insert(device: DeviceEntity): Long

    @Update
    suspend fun update(device: DeviceEntity)

    @Query("UPDATE devices SET ignored = :ignored WHERE id = :deviceId")
    suspend fun setIgnored(deviceId: Long, ignored: Boolean)

    @Query("SELECT * FROM devices")
    suspend fun getAll(): List<DeviceEntity>

    /** Cascades to pairs and sessions; used by backup import. */
    @Query("DELETE FROM devices")
    suspend fun deleteAll()
}

@Dao
interface PairDao {
    @Query(
        "SELECT * FROM pairs WHERE deviceId = :deviceId AND retiredAt IS NULL " +
            "ORDER BY generation DESC LIMIT 1",
    )
    suspend fun activeFor(deviceId: Long): PairEntity?

    @Query("SELECT IFNULL(MAX(generation), 0) FROM pairs WHERE deviceId = :deviceId")
    suspend fun maxGeneration(deviceId: Long): Int

    @Query("SELECT * FROM pairs WHERE id = :pairId")
    suspend fun byId(pairId: Long): PairEntity?

    @Insert
    suspend fun insert(pair: PairEntity): Long

    @Update
    suspend fun update(pair: PairEntity)

    @Query("DELETE FROM pairs WHERE id = :pairId")
    suspend fun delete(pairId: Long)

    @Query("SELECT * FROM pairs")
    suspend fun getAll(): List<PairEntity>

    /**
     * Every pair with its totals, or just one when [pairId] is given.
     *
     * One query with an optional filter rather than two near-identical ones. They were the same
     * twenty-two lines twice over, differing only by the `WHERE`, so any change to the arithmetic
     * had to be made in both by hand — and a projection this size is exactly where that goes
     * unnoticed. The `ORDER BY` is wasted work on a single row and cheaper than a third copy.
     */
    @Query(
        """
        SELECT p.id AS pairId, p.deviceId AS deviceId, p.label AS label, p.generation AS generation,
               p.startedAt AS startedAt, p.retiredAt AS retiredAt, p.retireReason AS retireReason,
               p.purchaseDate AS purchaseDate, p.priceCents AS priceCents,
               d.deviceKey AS deviceKey, d.kind AS deviceKind, d.defaultName AS deviceName,
               d.ignored AS deviceIgnored,
               IFNULL(SUM(CASE WHEN s.disconnectedAt IS NOT NULL
                               THEN s.disconnectedAt - s.connectedAt ELSE 0 END), 0) AS closedMs,
               IFNULL(SUM(s.playingMs), 0) AS playingMs,
               IFNULL(SUM(CASE WHEN s.playingMs IS NOT NULL AND s.disconnectedAt IS NOT NULL
                               THEN s.disconnectedAt - s.connectedAt ELSE 0 END), 0) AS measuredPlaybackMs,
               COUNT(s.id) AS sessionCount,
               MIN(CASE WHEN s.disconnectedAt IS NULL THEN s.connectedAt END) AS openSince,
               MAX(IFNULL(s.disconnectedAt, s.connectedAt)) AS lastSeenAt,
               IFNULL(MAX(CASE WHEN s.disconnectedAt IS NOT NULL
                               THEN s.disconnectedAt - s.connectedAt ELSE 0 END), 0) AS longestMs
        FROM pairs p
        JOIN devices d ON d.id = p.deviceId
        LEFT JOIN sessions s ON s.pairId = p.id
        WHERE (:pairId IS NULL OR p.id = :pairId)
        GROUP BY p.id
        ORDER BY (openSince IS NULL), retiredAt IS NOT NULL, lastSeenAt DESC
        """,
    )
    fun observeSummaries(pairId: Long? = null): Flow<List<PairSummary>>
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE pairId = :pairId AND disconnectedAt IS NULL LIMIT 1")
    suspend fun openFor(pairId: Long): SessionEntity?

    @Query("SELECT MAX(disconnectedAt) FROM sessions WHERE pairId = :pairId")
    suspend fun lastDisconnectAt(pairId: Long): Long?

    @Query("SELECT * FROM sessions WHERE disconnectedAt IS NULL")
    suspend fun allOpen(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE disconnectedAt IS NULL")
    fun observeOpen(): Flow<List<SessionEntity>>

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query(
        "UPDATE sessions SET disconnectedAt = :at, endReason = :reason, heartbeatAt = :at " +
            "WHERE id = :sessionId AND disconnectedAt IS NULL",
    )
    suspend fun close(sessionId: Long, at: Long, reason: EndReason)

    @Query("UPDATE sessions SET heartbeatAt = :at WHERE id = :sessionId AND disconnectedAt IS NULL")
    suspend fun heartbeat(sessionId: Long, at: Long)

    /**
     * `disconnectedAt IS NULL` for the same reason [close] and [heartbeat] carry it: playback is
     * credited from a coroutine launched behind the watcher's own edge, so a disconnect can land
     * first. Without the guard that slice is banked onto a session that had already ended, and the
     * stored `playingMs` then exceeds the span it was measured inside — invisible, because
     * `Stats.durationMs` clamps it at read time, and wrong on disk forever.
     */
    @Query(
        "UPDATE sessions SET playingMs = IFNULL(playingMs, 0) + :deltaMs " +
            "WHERE id = :sessionId AND disconnectedAt IS NULL",
    )
    suspend fun addPlayback(sessionId: Long, deltaMs: Long)

    @Query("UPDATE sessions SET playingMs = 0 WHERE id = :sessionId AND playingMs IS NULL")
    suspend fun startMeasuringPlayback(sessionId: Long)

    @Query("SELECT * FROM sessions WHERE pairId = :pairId ORDER BY connectedAt DESC")
    fun observeForPair(pairId: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY connectedAt")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY connectedAt")
    suspend fun getAll(): List<SessionEntity>

    /**
     * Everything that can contribute to a window opening at [from]: still running, or finished
     * inside it. A session that both started and ended before the window contributes nothing and
     * is dropped — which is the point, since the home-screen widgets ask this on every refresh and
     * the table grows forever.
     */
    @Query(
        "SELECT * FROM sessions WHERE disconnectedAt IS NULL OR disconnectedAt >= :from " +
            "ORDER BY connectedAt",
    )
    suspend fun since(from: Long): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun delete(sessionId: Long)
}

@Dao
interface SettingsDao {
    /**
     * The whole table at once, never one setting at a time, and that is a correctness requirement
     * rather than an optimisation for a table of five rows.
     *
     * Room's generated code prepares a statement per execution, but `androidx.sqlite` hands back a
     * statement **cached on the connection and keyed by the SQL text**, so every flow over
     * `WHERE key = ?` shares one statement. Collect several of them at once — which the view model
     * does, one `stateIn` per setting — and they bind their keys over each other: the observed
     * failure was `hibernation_asked` reading `onboarding_done`'s value, which suppressed the
     * hibernation prompt entirely. A query with no arguments has nothing to rebind.
     *
     * A missing row rather than a `null` value is what lets [SettingsStore] keep every default in
     * Kotlin instead of seeding rows at first run.
     */
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM settings")
    suspend fun getAll(): List<SettingEntity>

    @Upsert
    suspend fun put(setting: SettingEntity)
}
