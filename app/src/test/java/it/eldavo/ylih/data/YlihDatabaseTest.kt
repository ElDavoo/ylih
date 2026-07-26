package it.eldavo.ylih.data

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The database is built with no fallback and history is never allowed to be lost, so the schema
 * Room validates an existing file against has to match the entities exactly. That check only
 * runs when an *existing* file is opened, which is the second open below — the first one merely
 * creates the tables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class YlihDatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

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
}
