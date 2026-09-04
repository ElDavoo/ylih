package it.eldavo.ylih.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** Physical transport a headphone set is attached through. */
enum class DeviceKind {
    BLUETOOTH,
    BLE,
    WIRED,
    USB,
    UNKNOWN,
    ;

    companion object {
        fun parse(raw: String?): DeviceKind = entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

/** Why a session stopped. Recorded so recovered sessions can be told apart from clean ones. */
enum class EndReason {
    /** The system told us the device went away. */
    DISCONNECTED,

    /** The session was left open by process death or a reboot and was closed at its last heartbeat. */
    RECOVERED,

    /** Detailed tracking was switched off while a wired device was connected. */
    TRACKING_DISABLED,

    /** Closed by the user from the UI. */
    MANUAL,
    ;

    companion object {
        fun parse(raw: String?): EndReason? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * A device *identity* as Android reports it. Wired headsets all collapse into a single
 * identity because the platform cannot tell two wired pairs apart — that is what [PairEntity]
 * generations are for.
 */
@Entity(
    tableName = "devices",
    indices = [Index(value = ["deviceKey"], unique = true)],
)
data class DeviceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceKey: String,
    val kind: DeviceKind,
    val defaultName: String,
    val firstSeenAt: Long,
    /** User excluded this device (car stereos, speakers, …) — no sessions are recorded. */
    val ignored: Boolean = false,
)

/**
 * One physical pair of headphones: the unit whose lifetime hours we care about.
 * Retiring a pair freezes its totals and the next connection starts generation + 1.
 */
@Entity(
    tableName = "pairs",
    foreignKeys = [
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("deviceId")],
)
data class PairEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val label: String,
    val generation: Int,
    val startedAt: Long,
    val retiredAt: Long? = null,
    val retireReason: String? = null,
    val purchaseDate: Long? = null,
    val priceCents: Long? = null,
)

/**
 * A single connect → disconnect span. Kept forever; [disconnectedAt] is null while the
 * connection is live.
 */
@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = PairEntity::class,
            parentColumns = ["id"],
            childColumns = ["pairId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["pairId", "connectedAt"]),
        Index("disconnectedAt"),
        /**
         * "Has this pair got a session open?" — `openFor`, and `lastDisconnectAt` beside it.
         *
         * Without `disconnectedAt` in an index alongside `pairId`, SQLite finds the pair from the
         * composite above and then walks every session that pair has ever had, checking each one.
         * That is O(the pair's whole history) on the query the repository asks most: every connect
         * and disconnect, and every playback credit — which is the minute tick plus every callback
         * edge, and those fire whenever any app on the phone starts or stops a player.
         *
         * Measured over 22,000 sessions with 80% of them on one pair, which is what a person
         * actually accumulates: 2.5 ms to 0.003 ms, with the connect/disconnect write going from
         * 0.006 ms to 0.007 ms. The point is the shape rather than the milliseconds — O(log n)
         * instead of O(n), on a table this app intends to keep for decades.
         */
        Index(value = ["pairId", "disconnectedAt"]),
    ],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pairId: Long,
    val connectedAt: Long,
    val disconnectedAt: Long? = null,
    /** Milliseconds of actual playback. `null` means "not measured" (detailed tracking was off). */
    val playingMs: Long? = null,
    /** Last moment we know for sure the connection was still up; recovery closes here. */
    val heartbeatAt: Long,
    @ColumnInfo(name = "endReason") val endReason: EndReason? = null,
)

/**
 * One battery level, as the headset reported it partway through a session.
 *
 * Keyed on the *session* rather than on the pair, which is what makes "only drain we actually
 * watched" a property of the schema instead of a rule someone has to remember: two readings can
 * only be subtracted when they belong to the same row here, and a gap in which the headphones were
 * charged is indistinguishable from one in which they were not. The cascade is the other half —
 * deleting a session, retiring a pair or importing over the lot takes the readings with it.
 *
 * How often a row lands is entirely up to the headphones, and the answer is usually "often". Most
 * report through HFP 1.7's battery-level HF indicator, `AT+BIEV=2,<0-100>`, which carries a real
 * percentage — observed on an ACCENTUM Plus as `EVENT_TYPE_BIEV valInt=2, valInt2=50`. BLE's
 * battery service is the same resolution and Apple's `AT+IPHONEACCEV` moves in tens.
 *
 * The five-step `+CIND` indicator the stack widens to 0 / 13 / 38 / 63 / 88 / 100 is *not* this
 * path: `batteryChargeIndicatorToPercentage` is reached only from `onAgBatteryLevelChanged`, the
 * HFP **client** role, where the phone is the headset rather than the audio gateway. No pair of
 * headphones takes it.
 */
@Entity(
    tableName = "battery_samples",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "at"]), Index(value = ["pairId", "at"])],
)
data class BatterySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /**
     * The pair the session belongs to, carried here as well.
     *
     * Redundant — it is `sessions.pairId` for [sessionId], and a session never changes pair — and
     * worth it for one reason: reading a pair's history through a join makes Room's flow observe
     * `sessions` too, and the heartbeat writes to that table once a minute. Measured over 400,000
     * readings, that re-ran a 3.5-second query every minute for as long as the pair page was open.
     * Off this column the same read is an index scan of `(pairId, at)` with no join and no sort,
     * and it re-runs only when a reading actually lands.
     */
    val pairId: Long,
    val at: Long,
    /** Percent remaining, 0..100, exactly as the platform reported it. */
    val level: Int,
)

/**
 * One user setting, stored as text and parsed by [SettingsStore].
 *
 * A key/value table rather than a one-row table with a column per setting: adding a setting is
 * then a line of Kotlin instead of a schema version, and settings are the one part of this
 * database with no history to keep and no queries to answer beyond "what is it now".
 */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

class Converters {
    @TypeConverter
    fun deviceKindToString(value: DeviceKind): String = value.name

    @TypeConverter
    fun stringToDeviceKind(value: String?): DeviceKind = DeviceKind.parse(value)

    @TypeConverter
    fun endReasonToString(value: EndReason?): String? = value?.name

    @TypeConverter
    fun stringToEndReason(value: String?): EndReason? = EndReason.parse(value)
}
