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
 * The database has no fallback and history must never be lost, so a migration that is merely
 * *nearly* right crashes on launch for everyone with data — a destructive fallback would be worse,
 * launching fine while losing years of it.
 *
 * These go through `YlihDatabase.open`, the real opener, rather than Room's
 * `MigrationTestHelper`: the helper reads exported schemas from the APK's assets, which requires
 * shipping every schema the app has ever had. Opening for real gets the same check and a better
 * one — Room compares the migrated database against the identity hash compiled into
 * `YlihDatabase` and refuses a mismatch, so a `settings` table created one way here and declared
 * another in `Entities.kt` fails exactly as it would on a phone.
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
     * Why the settings table is a migration, not a destructive fallback: every column read back
     * is a lifetime figure the app shows.
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
                // An hour connected, fifteen minutes playing — the two figures the app reports,
                // carried across intact.
                assertEquals(3_600_000L, session.disconnectedAt!! - session.connectedAt)
                assertEquals(900_000L, session.playingMs)

                assertEquals("5E:C2", db.deviceDao().getAll().single().deviceKey)
            }
        } finally {
            db.close()
        }
    }

    /**
     * 2 to 3 adds an index and nothing else, so "the history survived" doesn't show it landed.
     *
     * Room validates the schema on open, so a *missing* or misnamed index already fails every
     * test here. What it can't catch is an index that exists but is never chosen, so this asks
     * SQLite directly. `openFor` is the repository's most frequent query — every connect,
     * disconnect and playback credit — and without this index it walks every session the pair had.
     */
    @Test
    fun `after migrating, the open-session lookup is an index search rather than a walk`() {
        writeVersion1Database {
            execSQL(
                "INSERT INTO devices (id, deviceKey, kind, defaultName, firstSeenAt, ignored) " +
                    "VALUES (1, '5E:C2', 'BLUETOOTH', 'WH-1000XM4', 1000, 0)",
            )
            execSQL(
                "INSERT INTO pairs (id, deviceId, label, generation, startedAt) " +
                    "VALUES (1, 1, 'the good ones', 1, 1000)",
            )
        }

        val db = openMigrated()
        try {
            val plan = db.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN SELECT * FROM sessions " +
                    "WHERE pairId = 1 AND disconnectedAt IS NULL LIMIT 1",
            ).use { cursor ->
                buildString {
                    while (cursor.moveToNext()) append(cursor.getString(cursor.columnCount - 1))
                }
            }

            assertTrue(
                "openFor is not using the index the migration added: $plan",
                plan.contains("index_sessions_pairId_disconnectedAt"),
            )
        } finally {
            db.close()
        }
    }

    /**
     * The settings table must arrive empty, not seeded: every default lives in Kotlin, so a row
     * that exists means the user chose it — see [SettingsStore].
     */
    @Test
    fun `an upgraded install starts with every setting at its default`() {
        writeVersion1Database {}

        val db = openMigrated()
        try {
            val settings = SettingsStore(db.settingsDao(), ApplicationProvider.getApplicationContext())
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

    /** And is writable afterwards, which the schema check alone does not show. */
    @Test
    fun `an upgraded install can store a setting`() {
        writeVersion1Database {}

        val db = openMigrated()
        try {
            val settings = SettingsStore(db.settingsDao(), ApplicationProvider.getApplicationContext())
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
     * The newest migration's table, written to rather than merely validated.
     *
     * Room's identity check compares columns and indices, not whether the foreign key really
     * cascades — and the cascade keeps a deleted session from leaving readings the next pair's
     * charge cycles would subtract across.
     */
    @Test
    fun `an upgraded install can record a battery level`() {
        writeVersion1Database {
            execSQL(
                "INSERT INTO devices (id, deviceKey, kind, defaultName, firstSeenAt, ignored) " +
                    "VALUES (1, '5E:C2', 'BLUETOOTH', 'WH-1000XM4', 1000, 0)",
            )
            execSQL(
                "INSERT INTO pairs (id, deviceId, label, generation, startedAt) " +
                    "VALUES (1, 1, 'the good ones', 1, 1000)",
            )
            execSQL(
                "INSERT INTO sessions (id, pairId, connectedAt, heartbeatAt) " +
                    "VALUES (1, 1, 1000, 1000)",
            )
        }

        val db = openMigrated()
        try {
            runBlocking {
                SessionRepository(db) { 4000L }.recordBatteryLevel("5E:C2", level = 80)

                assertEquals(80, db.batterySampleDao().getAll().single().level)

                db.sessionDao().delete(1)
                assertTrue(
                    "readings do not outlive the session they were taken in",
                    db.batterySampleDao().getAll().isEmpty(),
                )
            }
        } finally {
            db.close()
        }
    }

    /**
     * The guard on the guard: the fixture below is hand-written and can quietly stop resembling
     * what version 1 shipped. Checked against the committed `1.json` rather than against itself —
     * a fixture agreeing only with its own constants would let every test above pass while
     * migrating a schema no install ever had.
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
            // The exported SQL writes the table name as a placeholder; everything past this
            // substitution is compared verbatim.
            fun resolve(sql: String) = sql.replace("\${TABLE_NAME}", entity.getString("tableName"))
            expected += resolve(entity.getString("createSql"))
            val indices = entity.optJSONArray("indices")
            for (j in 0 until (indices?.length() ?: 0)) {
                expected += resolve(indices!!.getJSONObject(j).getString("createSql"))
            }
        }

        // room_master_table is Room's own bookkeeping, not an entity, so it is not exported.
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
            // Tables the migrations add must not be there yet, or nothing above is a migration.
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name IN ('settings', 'battery_samples')",
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
     * Writes the schema `app/schemas/it.eldavo.ylih.data.YlihDatabase/1.json` describes, by hand,
     * including `room_master_table`: without the identity hash Room treats the file as unseen and
     * rebuilds it, letting every test here pass without migrating anything.
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
