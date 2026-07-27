package it.eldavo.ylih.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The database is built with no fallback and history is never allowed to be lost, so the schema
 * Room validates an existing file against has to match the entities exactly. Which check runs
 * depends on where the file came from: one Room wrote carries an identity hash it compares, while
 * one restored from a backup has no such bookkeeping and is validated column by column instead.
 * Both openings happen for real here, because a fresh create exercises neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class YlihDatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** These tests open the real `ylih.db` by name, so each one has to start from no file. */
    @Before
    fun removeAnyExistingDatabase() {
        val file = context.getDatabasePath("ylih.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.deleteDatabase(file)
    }

    @Test
    fun `reopening an existing database validates its schema and keeps the rows`() = runTest {
        val created = YlihDatabase.open(context)
        val deviceId = created.deviceDao().insert(
            DeviceEntity(
                deviceKey = "bt:5E:C2",
                kind = DeviceKind.BLE,
                defaultName = "ACCENTUM Plus",
                firstSeenAt = 1_800_000_000_000L,
            ),
        )
        val pairId = created.pairDao().insert(
            PairEntity(
                deviceId = deviceId,
                label = "ACCENTUM Plus",
                generation = 1,
                startedAt = 1_800_000_000_000L,
            ),
        )
        created.sessionDao().insert(
            SessionEntity(
                pairId = pairId,
                connectedAt = 1_800_000_000_000L,
                disconnectedAt = 1_800_003_600_000L,
                heartbeatAt = 1_800_003_600_000L,
                endReason = EndReason.DISCONNECTED,
            ),
        )
        created.close()

        // A mismatch between the committed schema and the entities throws here rather than
        // silently migrating, which is the behaviour the "history is never lost" premise needs.
        val reopened = YlihDatabase.open(context)
        try {
            assertEquals(DeviceKind.BLE, reopened.deviceDao().getAll().single().kind)
            assertEquals(
                EndReason.DISCONNECTED,
                reopened.sessionDao().getAll().single().endReason,
            )
        } finally {
            reopened.close()
        }
    }

    /**
     * A `ylih.db` copied back from a file-manager backup has the tables but none of Room's own
     * bookkeeping, so Room validates it column by column instead of trusting an identity hash.
     * That path is the one thing standing between a restored file and years of silently wrong
     * totals, and the DDL below is what the committed schema says version 1 looks like — if an
     * entity changes without a version bump and a migration, this is where it surfaces.
     */
    @Test
    fun `a database Room did not create is checked against the entities before it is trusted`() =
        runTest {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("ylih.db"), null).use { restored ->
                SCHEMA_V1.forEach(restored::execSQL)
                restored.execSQL(
                    "INSERT INTO devices (deviceKey, kind, defaultName, firstSeenAt, ignored) " +
                        "VALUES ('bt:5E:C2', 'BLE', 'ACCENTUM Plus', 0, 0)",
                )
                restored.version = 1
            }

            val db = YlihDatabase.open(context)
            try {
                assertEquals(
                    "the restored history is readable, not discarded",
                    "bt:5E:C2",
                    db.deviceDao().getAll().single().deviceKey,
                )
            } finally {
                db.close()
            }
        }

    /**
     * Room refuses a database whose columns do not match the entities rather than migrating it
     * destructively, because the alternative is losing the history the app exists to keep.
     */
    @Test
    fun `a database whose columns have drifted is refused rather than silently repaired`() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("ylih.db"), null).use { drifted ->
            SCHEMA_V1.forEach(drifted::execSQL)
            drifted.execSQL("ALTER TABLE sessions DROP COLUMN playingMs")
            drifted.version = 1
        }

        val db = YlihDatabase.open(context)
        try {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { db.sessionDao().getAll() }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `clearing the tables leaves the schema in place`() = runTest {
        val db = YlihDatabase.open(context)
        try {
            db.deviceDao().insert(
                DeviceEntity(
                    deviceKey = "usb:Dock",
                    kind = DeviceKind.USB,
                    defaultName = "Dock",
                    firstSeenAt = 0,
                ),
            )

            withContext(Dispatchers.IO) { db.clearAllTables() }

            assertEquals(emptyList<DeviceEntity>(), db.deviceDao().getAll())
        } finally {
            db.close()
        }
    }

    @Test
    fun `deleting a device cascades to its pairs and their sessions`() = runTest {
        val db = YlihDatabase.open(context)
        try {
            val deviceId = db.deviceDao().insert(
                DeviceEntity(
                    deviceKey = "wired:headphones",
                    kind = DeviceKind.WIRED,
                    defaultName = "Wired headphones",
                    firstSeenAt = 0,
                ),
            )
            val pairId = db.pairDao().insert(
                PairEntity(deviceId = deviceId, label = "Wired", generation = 1, startedAt = 0),
            )
            db.sessionDao().insert(SessionEntity(pairId = pairId, connectedAt = 0, heartbeatAt = 0))

            db.deviceDao().deleteAll()

            assertEquals(emptyList<PairEntity>(), db.pairDao().getAll())
            assertEquals(emptyList<SessionEntity>(), db.sessionDao().getAll())
        } finally {
            db.close()
        }
    }

    private companion object {
        /** Verbatim from `app/schemas/it.eldavo.ylih.data.YlihDatabase/1.json`. */
        val SCHEMA_V1 = listOf(
            "CREATE TABLE IF NOT EXISTS `devices` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`deviceKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `defaultName` TEXT NOT NULL, " +
                "`firstSeenAt` INTEGER NOT NULL, `ignored` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_devices_deviceKey` ON `devices` (`deviceKey`)",
            "CREATE TABLE IF NOT EXISTS `pairs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`deviceId` INTEGER NOT NULL, `label` TEXT NOT NULL, `generation` INTEGER NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `retiredAt` INTEGER, `retireReason` TEXT, " +
                "`purchaseDate` INTEGER, `priceCents` INTEGER, FOREIGN KEY(`deviceId`) " +
                "REFERENCES `devices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_pairs_deviceId` ON `pairs` (`deviceId`)",
            "CREATE TABLE IF NOT EXISTS `sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`pairId` INTEGER NOT NULL, `connectedAt` INTEGER NOT NULL, `disconnectedAt` INTEGER, " +
                "`playingMs` INTEGER, `heartbeatAt` INTEGER NOT NULL, `endReason` TEXT, " +
                "FOREIGN KEY(`pairId`) REFERENCES `pairs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_sessions_pairId_connectedAt` ON `sessions` " +
                "(`pairId`, `connectedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_sessions_disconnectedAt` ON `sessions` (`disconnectedAt`)",
        )
    }
}
