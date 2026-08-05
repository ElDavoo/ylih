package it.eldavo.ylih.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The database is built with no fallback and the app's whole premise is that history is never
 * lost, so a migration that is merely *nearly* right is a crash on launch for everyone who already
 * has data — and a destructive fallback would be worse, because it would launch fine and lose
 * years of it.
 *
 * These go through `YlihDatabase.open`, the real opener, rather than through Room's
 * `MigrationTestHelper`: the helper reads the exported schemas from the APK's assets, and the only
 * way to put them there is to ship every schema this app has ever had inside the app. Opening for
 * real gets the check anyway and gets a better one. Room compares the migrated database against
 * the identity hash compiled into `YlihDatabase` and refuses a mismatch, so a `settings` table
 * created one way here and declared another way in `Entities.kt` fails these tests exactly as it
 * would fail on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class YlihDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun startFromNothing() {
        context.deleteDatabase(NAME)
    }

    /**
     * The reason the settings table arrived as a migration rather than as a destructive fallback.
     * Every column read back is one the app would show as a lifetime figure.
     */
    @Test
    fun `an install from version 1 keeps its history`() {
        writeVersion1Database {
            execSQL(
                "INSERT INTO devices (id, deviceKey, kind, defaultName, firstSeenAt, ignored) " +
                    "VALUES (1, '5E:C2', 'BLUETOOTH', 'WH-1000XM4', 1000, 0)",
            )
            execSQL(
                "INSERT INTO pairs (id, deviceId, label, generation, startedAt) " +
                    "VALUES (1, 1, 'the good ones', 2, 1000)",
            )
            execSQL(
                "INSERT INTO sessions " +
                    "(id, pairId, connectedAt, disconnectedAt, playingMs, heartbeatAt) " +
                    "VALUES (1, 1, 1000, 3601000, 900000, 3601000)",
            )
        }

        val db = openMigrated()
        try {
            runBlocking {
                val pair = db.pairDao().getAll().single()
                assertEquals("the good ones", pair.label)
                assertEquals(2, pair.generation)

                val session = db.sessionDao().getAll().single()
                // An hour connected, fifteen minutes of it playing — the two figures the app
                // exists to report, carried across intact.
                assertEquals(3_600_000L, session.disconnectedAt!! - session.connectedAt)
                assertEquals(900_000L, session.playingMs)

                assertEquals("5E:C2", db.deviceDao().getAll().single().deviceKey)
            }
        } finally {
            db.close()
        }
    }

    /**
     * The settings table has to arrive empty rather than seeded: every default lives in Kotlin, so
     * a row that exists means the user chose it — see [SettingsStore].
     */
    @Test
    fun `an upgraded install starts with every setting at its default`() {
        writeVersion1Database {}

        val db = openMigrated()
        try {
            val settings = SettingsStore(db.settingsDao())
            runBlocking {
                assertEquals(emptyList<SettingEntity>(), db.settingsDao().getAll())
                assertEquals(false, settings.onboardingDoneNow())
                assertEquals(false, settings.detailedTrackingNow())
                assertEquals("", settings.languageNow())
            }
        } finally {
            db.close()
        }
    }

    /** And is writable afterwards, which the schema check on its own does not show. */
    @Test
    fun `an upgraded install can store a setting`() {
        writeVersion1Database {}

        val db = openMigrated()
        try {
            val settings = SettingsStore(db.settingsDao())
            runBlocking {
                settings.setLanguage("pt-BR")
                settings.setDetailedTracking(true)
                assertEquals("pt-BR", settings.languageNow())
                assertEquals(true, settings.detailedTrackingNow())
            }
        } finally {
            db.close()
        }
    }

    /**
     * The guard on the guard: the fixture below is written by hand, and a hand-written fixture is
     * a thing that can quietly stop resembling what version 1 shipped. Checked against the
     * committed `1.json` rather than against itself, because a fixture that agrees only with its
     * own constants would let every test above pass while migrating a schema no install ever had.
     */
    @Test
    fun `the fixture matches the committed version 1 schema`() {
        val exported = JSONObject(
            File("schemas/it.eldavo.ylih.data.YlihDatabase/1.json").readText(),
        ).getJSONObject("database")

        assertEquals(VERSION_1_IDENTITY_HASH, exported.getString("identityHash"))

        val entities = exported.getJSONArray("entities")
        val expected = mutableListOf<String>()
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            // The exported SQL writes the table name as a placeholder; nothing else about it is
            // allowed to differ, so everything past this substitution is compared verbatim.
            fun resolve(sql: String) = sql.replace("\${TABLE_NAME}", entity.getString("tableName"))
            expected += resolve(entity.getString("createSql"))
            val indices = entity.optJSONArray("indices")
            for (j in 0 until (indices?.length() ?: 0)) {
                expected += resolve(indices!!.getJSONObject(j).getString("createSql"))
            }
        }

        // room_master_table is Room's own bookkeeping and is not an entity, so it is not exported.
        assertEquals(expected.sorted(), VERSION_1_SCHEMA.drop(1).sorted())
    }

    /** And that the fixture really is a version 1 — no settings table, and Room's own marker. */
    @Test
    fun `the fixture is written as a version 1 database`() {
        writeVersion1Database {}

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(NAME).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            assertEquals(1, db.version)
            db.rawQuery("SELECT identity_hash FROM room_master_table", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(VERSION_1_IDENTITY_HASH, it.getString(0))
            }
            // The table the migration adds must not be there yet, or nothing above is a migration.
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'settings'",
                null,
            ).use { assertEquals(0, it.count) }
        }
    }

    /**
     * Opens the way the app does. `addMigrations(MIGRATION_1_2)` lives in `YlihDatabase.open`, so
     * a migration written but never registered fails here rather than on someone's phone.
     */
    private fun openMigrated(): YlihDatabase =
        YlihDatabase.open(context, NAME)

    /**
     * Writes the schema `app/schemas/it.eldavo.ylih.data.YlihDatabase/1.json` describes, by hand
     * and including `room_master_table`: without the identity hash Room treats the file as one it
     * has never seen and rebuilds it, which would make every test here pass without migrating
     * anything.
     */
    private fun writeVersion1Database(fill: SQLiteDatabase.() -> Unit) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(NAME), null).use { db ->
            VERSION_1_SCHEMA.forEach(db::execSQL)
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(VERSION_1_IDENTITY_HASH),
            )
            db.version = 1
            db.fill()
        }
    }

    private companion object {
        // Not "ylih.db": the app container opens that one, and these tests must not race it.
        const val NAME = "migration-test.db"

        // Straight out of 1.json. Room recomputes it from the compiled entities and compares.
        const val VERSION_1_IDENTITY_HASH = "0395aeec2cbcb8f61e2efc2c75615d4a"

        val VERSION_1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `room_master_table` " +
                "(`id` INTEGER PRIMARY KEY, `identity_hash` TEXT)",
            "CREATE TABLE IF NOT EXISTS `devices` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `deviceKey` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `defaultName` TEXT NOT NULL, " +
                "`firstSeenAt` INTEGER NOT NULL, `ignored` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_devices_deviceKey` " +
                "ON `devices` (`deviceKey`)",
            "CREATE TABLE IF NOT EXISTS `pairs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `deviceId` INTEGER NOT NULL, " +
                "`label` TEXT NOT NULL, `generation` INTEGER NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `retiredAt` INTEGER, `retireReason` TEXT, " +
                "`purchaseDate` INTEGER, `priceCents` INTEGER, " +
                "FOREIGN KEY(`deviceId`) REFERENCES `devices`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_pairs_deviceId` ON `pairs` (`deviceId`)",
            "CREATE TABLE IF NOT EXISTS `sessions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pairId` INTEGER NOT NULL, " +
                "`connectedAt` INTEGER NOT NULL, `disconnectedAt` INTEGER, " +
                "`playingMs` INTEGER, `heartbeatAt` INTEGER NOT NULL, `endReason` TEXT, " +
                "FOREIGN KEY(`pairId`) REFERENCES `pairs`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_sessions_pairId_connectedAt` " +
                "ON `sessions` (`pairId`, `connectedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_sessions_disconnectedAt` " +
                "ON `sessions` (`disconnectedAt`)",
        )
    }
}
