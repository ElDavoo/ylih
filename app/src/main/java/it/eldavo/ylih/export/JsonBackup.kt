package it.eldavo.ylih.export

import androidx.room.withTransaction
import it.eldavo.ylih.data.BatterySampleEntity
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.EndReason
import it.eldavo.ylih.data.PairEntity
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.data.SettingEntity
import it.eldavo.ylih.data.YlihDatabase
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
        // The default is what lets a file written before this annotation existed still import,
        // so it cannot go away — but a default is exactly what kotlinx.serialization omits when
        // encoding, and omitting this one left every exported backup with no version in it at
        // all. import()'s `formatVersion <= FORMAT_VERSION` guard then read every file as
        // version 1 whatever wrote it, which is the one thing the field is for. Encode it
        // always; the remaining defaults below are optional fields where absent and null mean
        // the same thing.
        @OptIn(ExperimentalSerializationApi::class)
        @EncodeDefault
        val formatVersion: Int = FORMAT_VERSION,
        val exportedAt: Long,
        val devices: List<Device>,
        val pairs: List<Pair>,
        val sessions: List<Session>,
        /**
         * Absent in a file written before this field existed, which is why it has a default: an
         * older backup restores its history and leaves the current settings alone, which is the
         * behaviour every backup had until now.
         */
        val settings: List<Setting> = emptyList(),
        /** Defaulted for the same reason [settings] is: an older file simply has no readings. */
        val batterySamples: List<BatterySample> = emptyList(),
    )

    @Serializable
    data class Setting(val key: String, val value: String)

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
    data class BatterySample(
        val id: Long,
        val sessionId: Long,
        val at: Long,
        val level: Int,
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

    /**
     * The whole database as JSON.
     *
     * One transaction, because three separate reads are three different instants: a connect
     * landing between the second and the third writes a session whose pair is not in the snapshot,
     * and the file that produces fails to import on the foreign key it dangles.
     */
    suspend fun export(db: YlihDatabase, now: Long): String = db.withTransaction {
        val devices = db.deviceDao().getAll()
        val pairs = db.pairDao().getAll()
        val sessions = db.sessionDao().getAll()
        val batterySamples = db.batterySampleDao().getAll()
        val settings = db.settingsDao().getAll()
        json.encodeToString(
            Backup(
                exportedAt = now,
                settings = settings.map { Setting(it.key, it.value) },
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
                batterySamples = batterySamples.map {
                    BatterySample(it.id, it.sessionId, it.at, it.level)
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
            // After the sessions, and not before: every reading names the session it was taken in
            // and the foreign key is checked as each row lands.
            val batterySamples = db.batterySampleDao()
            backup.batterySamples.forEach {
                batterySamples.insert(
                    BatterySampleEntity(
                        id = it.id,
                        sessionId = it.sessionId,
                        at = it.at,
                        level = it.level,
                    ),
                )
            }
            // Not cleared first, unlike the tables above: a file written before settings were
            // carried has none, and wiping them would silently reset the tracking mode and the
            // language of anyone restoring an older backup.
            val settingsDao = db.settingsDao()
            backup.settings.forEach { settingsDao.put(SettingEntity(it.key, it.value)) }
        }
        return backup.sessions.size
    }
}
