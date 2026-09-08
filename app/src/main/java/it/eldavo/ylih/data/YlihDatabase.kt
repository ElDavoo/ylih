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
        BatterySampleEntity::class,
        SettingEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class YlihDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao

    abstract fun pairDao(): PairDao

    abstract fun sessionDao(): SessionDao

    abstract fun batterySampleDao(): BatterySampleDao

    abstract fun settingsDao(): SettingsDao

    companion object {
        /**
         * Adds the settings table, which used to be a DataStore. Nothing carries across: the
         * settings are five flags and a language tag, which the app can ask for again, and no
         * version has shipped to anyone. Session history is different, which is why this is a
         * migration rather than a destructive fallback.
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
         * Adds the `(pairId, disconnectedAt)` index — see [SessionEntity]. Index-only, so nothing
         * to move or lose; the name is Room's own, since a migration building an index Room
         * wouldn't have built fails the schema validator on every open afterwards.
         */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sessions_pairId_disconnectedAt` " +
                        "ON `sessions` (`pairId`, `disconnectedAt`)",
                )
            }
        }

        /**
         * Adds `battery_samples` — see [BatterySampleEntity]. A new table: no existing row is
         * touched, and every install that predates it simply has no readings, showing no charge
         * cycles until its headphones report one.
         *
         * The SQL is Room's own, down to the index name and the `ON UPDATE NO ACTION` the foreign
         * key carries: Room compares the migrated database against the identity hash compiled into
         * this class, and a table created even slightly differently fails every open afterwards,
         * not just when the migration runs.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `battery_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, `pairId` INTEGER NOT NULL, " +
                        "`at` INTEGER NOT NULL, `level` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_battery_samples_sessionId_at` " +
                        "ON `battery_samples` (`sessionId`, `at`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_battery_samples_pairId_at` " +
                        "ON `battery_samples` (`pairId`, `at`)",
                )
            }
        }

        /**
         * [name] is a parameter only so `YlihDatabaseMigrationTest` can replay an old schema
         * through this exact function rather than a builder of its own — a migration registered
         * elsewhere is one the app doesn't have. Nothing in the app ever passes it.
         */
        fun open(context: Context, name: String = "ylih.db"): YlihDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                YlihDatabase::class.java,
                name,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
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
