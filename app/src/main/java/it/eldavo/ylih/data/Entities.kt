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
    indices = [Index(value = ["pairId", "connectedAt"]), Index("disconnectedAt")],
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
