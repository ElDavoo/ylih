package it.eldavo.ylih.export

import androidx.room.withTransaction
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.EndReason
import it.eldavo.ylih.data.PairEntity
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.data.YlihDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Plain-JSON export/import. Years of listening history is only worth collecting if it can
 * leave the phone, so the format is deliberately readable and self-contained.
 */
object JsonBackup {
    const val FORMAT_VERSION = 1

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    data class Backup(
        val formatVersion: Int = FORMAT_VERSION,
        val exportedAt: Long,
        val devices: List<Device>,
        val pairs: List<Pair>,
        val sessions: List<Session>,
    )

    @Serializable
    data class Device(
        val id: Long,
        val key: String,
        val kind: String,
        val name: String,
        val firstSeenAt: Long,
        val ignored: Boolean,
    )

    @Serializable
    data class Pair(
        val id: Long,
        val deviceId: Long,
        val label: String,
        val generation: Int,
        val startedAt: Long,
        val retiredAt: Long? = null,
        val retireReason: String? = null,
        val purchaseDate: Long? = null,
        val priceCents: Long? = null,
    )

    @Serializable
    data class Session(
        val id: Long,
        val pairId: Long,
        val connectedAt: Long,
        val disconnectedAt: Long? = null,
        val playingMs: Long? = null,
        val heartbeatAt: Long,
        val endReason: String? = null,
    )

    suspend fun export(db: YlihDatabase, now: Long): String {
        val devices = db.deviceDao().getAll()
        val pairs = db.pairDao().getAll()
        val sessions = db.sessionDao().getAll()
        return json.encodeToString(
            Backup(
                exportedAt = now,
                devices = devices.map {
                    Device(it.id, it.deviceKey, it.kind.name, it.defaultName, it.firstSeenAt, it.ignored)
                },
                pairs = pairs.map {
                    Pair(
                        it.id, it.deviceId, it.label, it.generation, it.startedAt,
                        it.retiredAt, it.retireReason, it.purchaseDate, it.priceCents,
                    )
                },
                sessions = sessions.map {
                    Session(
                        it.id, it.pairId, it.connectedAt, it.disconnectedAt,
                        it.playingMs, it.heartbeatAt, it.endReason?.name,
                    )
                },
            ),
        )
    }

    /** Replaces the whole database with [content]. The UI confirms before calling this. */
    suspend fun import(db: YlihDatabase, content: String): Int {
        val backup = json.decodeFromString<Backup>(content)
        require(backup.formatVersion <= FORMAT_VERSION) {
            "Backup format ${backup.formatVersion} is newer than this app understands"
        }
        db.withTransaction {
            val devices = db.deviceDao()
            val pairs = db.pairDao()
            val sessions = db.sessionDao()
            // Foreign keys cascade, so this empties pairs and sessions too.
            devices.deleteAll()
            backup.devices.forEach {
                devices.insert(
                    DeviceEntity(
                        id = it.id,
                        deviceKey = it.key,
                        kind = DeviceKind.parse(it.kind),
                        defaultName = it.name,
                        firstSeenAt = it.firstSeenAt,
                        ignored = it.ignored,
                    ),
                )
            }
            backup.pairs.forEach {
                pairs.insert(
                    PairEntity(
                        id = it.id,
                        deviceId = it.deviceId,
                        label = it.label,
                        generation = it.generation,
                        startedAt = it.startedAt,
                        retiredAt = it.retiredAt,
                        retireReason = it.retireReason,
                        purchaseDate = it.purchaseDate,
                        priceCents = it.priceCents,
                    ),
                )
            }
            backup.sessions.forEach {
                sessions.insert(
                    SessionEntity(
                        id = it.id,
                        pairId = it.pairId,
                        connectedAt = it.connectedAt,
                        disconnectedAt = it.disconnectedAt,
                        playingMs = it.playingMs,
                        heartbeatAt = it.heartbeatAt,
                        endReason = EndReason.parse(it.endReason),
                    ),
                )
            }
        }
        return backup.sessions.size
    }
}
