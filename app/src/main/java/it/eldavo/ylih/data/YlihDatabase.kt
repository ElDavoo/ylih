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
         * Adds the `(pairId, disconnectedAt)` index — see [SessionEntity]. Index-only, so there is
         * no data to move and nothing that can be lost; the name is Room's own, because a
         * migration that builds an index Room would not have built leaves the schema validator
         * failing on every open afterwards.
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
         * Adds `battery_samples` — see [BatterySampleEntity]. A new table, so no existing row is
         * touched and nothing can be lost; every install that predates it simply has no readings
         * and so shows no charge cycles until its headphones report one.
         *
         * The SQL is Room's own, down to the index name and the `ON UPDATE NO ACTION` the foreign
         * key carries: Room compares the migrated database against the identity hash compiled into
         * this class, and a table created even slightly differently here fails every open
         * afterwards rather than at the moment the migration ran.
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
