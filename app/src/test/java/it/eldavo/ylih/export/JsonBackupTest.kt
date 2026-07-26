package it.eldavo.ylih.export

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.EndReason
import it.eldavo.ylih.data.PairEntity
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.data.YlihDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A decade of listening history is only worth collecting if it can leave the phone and come
 * back intact, so the round trip is asserted row by row rather than by counting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class JsonBackupTest {

    private lateinit var db: YlihDatabase
    private val json = Json { ignoreUnknownKeys = true }

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        db = newDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newDatabase(): YlihDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        YlihDatabase::class.java,
    ).build()

    private suspend fun seed(target: YlihDatabase) {
        val deviceId = target.deviceDao().insert(
            DeviceEntity(
                deviceKey = "bt:5E:C2",
                kind = DeviceKind.BLUETOOTH,
                defaultName = "ACCENTUM Plus",
                firstSeenAt = now - 400 * hour,
                ignored = false,
            ),
        )
        val ignoredId = target.deviceDao().insert(
            DeviceEntity(
                deviceKey = "bt:11:22",
                kind = DeviceKind.UNKNOWN,
                defaultName = "Car stereo",
                firstSeenAt = now - 300 * hour,
                ignored = true,
            ),
        )
        val pairId = target.pairDao().insert(
            PairEntity(
                deviceId = deviceId,
                label = "ACCENTUM Plus",
                generation = 2,
                startedAt = now - 400 * hour,
                retiredAt = now - 10 * hour,
                retireReason = "left earcup died",
                purchaseDate = now - 500 * hour,
                priceCents = 14_900,
            ),
        )
        // A retired device with no pair of its own: the import must not invent one.
        assertTrue(ignoredId > 0)
        target.sessionDao().insert(
            SessionEntity(
                pairId = pairId,
                connectedAt = now - 30 * hour,
                disconnectedAt = now - 28 * hour,
                playingMs = 90 * 60_000L,
                heartbeatAt = now - 28 * hour,
                endReason = EndReason.DISCONNECTED,
            ),
        )
        // Still open, never measured — the two nullable columns that a lossy format would drop.
        target.sessionDao().insert(
            SessionEntity(
                pairId = pairId,
                connectedAt = now - hour,
                disconnectedAt = null,
                playingMs = null,
                heartbeatAt = now,
                endReason = null,
            ),
        )
    }

    @Test
    fun `the exported document names every row exactly once`() = runTest {
        seed(db)

        val backup = json.decodeFromString<JsonBackup.Backup>(JsonBackup.export(db, now))

        assertEquals(JsonBackup.FORMAT_VERSION, backup.formatVersion)
        assertEquals(now, backup.exportedAt)
        assertEquals(
            listOf("ACCENTUM Plus", "Car stereo"),
            backup.devices.map { it.name },
        )
        assertEquals(listOf("BLUETOOTH", "UNKNOWN"), backup.devices.map { it.kind })
        assertEquals(listOf(false, true), backup.devices.map { it.ignored })
        assertEquals(
            JsonBackup.Pair(
                id = backup.pairs.single().id,
                deviceId = backup.devices.first().id,
                label = "ACCENTUM Plus",
                generation = 2,
                startedAt = now - 400 * hour,
                retiredAt = now - 10 * hour,
                retireReason = "left earcup died",
                purchaseDate = now - 500 * hour,
                priceCents = 14_900,
            ),
            backup.pairs.single(),
        )
        assertEquals(listOf("DISCONNECTED", null), backup.sessions.map { it.endReason })
        assertEquals(listOf(90 * 60_000L, null), backup.sessions.map { it.playingMs })
        assertEquals(listOf(now - 28 * hour, null), backup.sessions.map { it.disconnectedAt })
    }

    @Test
    fun `a backup restores into an empty database unchanged`() = runTest {
        seed(db)
        val payload = JsonBackup.export(db, now)

        val restored = newDatabase()
        try {
            assertEquals(2, JsonBackup.import(restored, payload))

            assertEquals(db.deviceDao().getAll(), restored.deviceDao().getAll())
            assertEquals(db.pairDao().getAll(), restored.pairDao().getAll())
            assertEquals(db.sessionDao().getAll(), restored.sessionDao().getAll())
        } finally {
            restored.close()
        }
    }

    @Test
    fun `importing replaces whatever was there rather than merging`() = runTest {
        seed(db)
        val payload = JsonBackup.export(db, now)

        val other = newDatabase()
        try {
            other.deviceDao().insert(
                DeviceEntity(
                    deviceKey = "wired:headphones",
                    kind = DeviceKind.WIRED,
                    defaultName = "Wired headphones",
                    firstSeenAt = now,
                ),
            )
            other.pairDao().insert(
                PairEntity(deviceId = 1, label = "Wired", generation = 1, startedAt = now),
            )
            other.sessionDao().insert(
                SessionEntity(pairId = 1, connectedAt = now, heartbeatAt = now),
            )

            JsonBackup.import(other, payload)

            assertEquals(db.deviceDao().getAll(), other.deviceDao().getAll())
            // Foreign keys cascade off `devices`, so the stale pair and session go with it.
            assertEquals(db.pairDao().getAll(), other.pairDao().getAll())
            assertEquals(db.sessionDao().getAll(), other.sessionDao().getAll())
        } finally {
            other.close()
        }
    }

    @Test
    fun `a backup from a newer build is refused instead of half-imported`() = runTest {
        db.deviceDao().insert(
            DeviceEntity(
                deviceKey = "bt:5E:C2",
                kind = DeviceKind.BLUETOOTH,
                defaultName = "Kept",
                firstSeenAt = now,
            ),
        )
        val fromTheFuture = """
            {"formatVersion":${JsonBackup.FORMAT_VERSION + 1},"exportedAt":$now,
             "devices":[],"pairs":[],"sessions":[]}
        """.trimIndent()

        val failure = runCatching { JsonBackup.import(db, fromTheFuture) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("Kept", db.deviceDao().getAll().single().defaultName)
    }

    @Test
    fun `enum names this build does not know degrade instead of failing the import`() = runTest {
        // A future release that adds a device kind or an end reason must not make its backups
        // unreadable by an older install.
        val payload = """
            {"formatVersion":1,"exportedAt":$now,
             "devices":[{"id":1,"key":"x:1","kind":"SATELLITE","name":"Implant",
                         "firstSeenAt":$now,"ignored":false}],
             "pairs":[{"id":1,"deviceId":1,"label":"Implant","generation":1,"startedAt":$now}],
             "sessions":[{"id":1,"pairId":1,"connectedAt":$now,"heartbeatAt":$now,
                          "endReason":"EXPLODED"}]}
        """.trimIndent()

        assertEquals(1, JsonBackup.import(db, payload))

        assertEquals(DeviceKind.UNKNOWN, db.deviceDao().getAll().single().kind)
        assertNull(db.sessionDao().getAll().single().endReason)
    }

    @Test
    fun `an empty database exports a document that imports back as empty`() = runTest {
        val payload = JsonBackup.export(db, now)

        assertEquals(0, JsonBackup.import(db, payload))
        assertTrue(db.deviceDao().getAll().isEmpty())
    }
}
