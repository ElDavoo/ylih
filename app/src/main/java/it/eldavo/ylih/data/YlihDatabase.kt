package it.eldavo.ylih.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        DeviceEntity::class,
        PairEntity::class,
        SessionEntity::class,
        SettingEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class YlihDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao

    abstract fun pairDao(): PairDao

    abstract fun sessionDao(): SessionDao

    abstract fun settingsDao(): SettingsDao

    companion object {
        /**
         * Adds the settings table, which used to be a DataStore. Nothing is carried across: the
         * settings are five flags and a language tag, all of which the app can ask for again,
         * and no version of this app has shipped to anyone. The session history is a different
         * matter entirely, which is why this is a migration and not a destructive fallback.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `settings` (" +
                        "`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
                )
            }
        }

        /**
         * The [name] is a parameter only so `YlihDatabaseMigrationTest` can replay an old schema
         * through this exact function rather than through a builder of its own — a migration
         * registered somewhere else is a migration the app does not have. Nothing in the app ever
         * passes it.
         */
        fun open(context: Context, name: String = "ylih.db"): YlihDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                YlihDatabase::class.java,
                name,
            ).addMigrations(MIGRATION_1_2).build()
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
