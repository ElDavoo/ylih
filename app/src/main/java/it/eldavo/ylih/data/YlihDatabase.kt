package it.eldavo.ylih.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [DeviceEntity::class, PairEntity::class, SessionEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class YlihDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao

    abstract fun pairDao(): PairDao

    abstract fun sessionDao(): SessionDao

    companion object {
        fun open(context: Context): YlihDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                YlihDatabase::class.java,
                "ylih.db",
            ).build()
    }
}

/** Wall-clock source, injectable so the time-sensitive logic can be tested. */
fun interface Clock {
    fun now(): Long

    companion object {
        val Wall = Clock { java.lang.System.currentTimeMillis() }
    }
}

/** How Android identifies a connected audio output, normalised for storage. */
data class DeviceIdentity(
    val key: String,
    val kind: DeviceKind,
    val name: String,
)
