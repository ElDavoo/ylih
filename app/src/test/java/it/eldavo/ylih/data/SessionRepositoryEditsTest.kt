package it.eldavo.ylih.data

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The half of [SessionRepository] the UI drives — renaming, pricing, deleting and the observed
 * aggregate the two list screens are drawn from. `SessionRepositoryTest` covers the tracking
 * invariants; this covers what a person can do to the record afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class SessionRepositoryEditsTest {

    private lateinit var db: YlihDatabase
    private lateinit var repository: SessionRepository

    private val hour = 3_600_000L
    private var clockNow = 1_800_000_000_000L

    private val buds = DeviceIdentity("bt:5E:C2", DeviceKind.BLUETOOTH, "Buds")
    private val wired = DeviceIdentity("wired:headphones", DeviceKind.WIRED, "Wired headphones")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YlihDatabase::class.java,
        ).build()
        repository = SessionRepository(db) { clockNow }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun onlyPairId(): Long = db.pairDao().getAll().single().id

    @Test
    fun `renaming keeps the label the user typed, trimmed`() = runTest {
        repository.onConnected(buds, at = clockNow)

        repository.renamePair(onlyPairId(), "  WH-1000XM5  ")

        assertEquals("WH-1000XM5", db.pairDao().getAll().single().label)
    }

    @Test
    fun `renaming to nothing keeps the old label rather than blanking the card`() = runTest {
        repository.onConnected(buds, at = clockNow)
        val pairId = onlyPairId()

        repository.renamePair(pairId, "   ")

        assertEquals("Buds", db.pairDao().byId(pairId)!!.label)
    }

    @Test
    fun `renaming a pair that no longer exists is a no-op`() = runTest {
        repository.renamePair(pairId = 404, label = "Ghost")

        assertTrue(db.pairDao().getAll().isEmpty())
    }

    @Test
    fun `purchase info can be set and then cleared again`() = runTest {
        repository.onConnected(buds, at = clockNow)
        val pairId = onlyPairId()

        repository.setPurchaseInfo(pairId, purchaseDate = clockNow - 400 * hour, priceCents = 14_900)
        assertEquals(14_900L, db.pairDao().byId(pairId)!!.priceCents)
        assertEquals(clockNow - 400 * hour, db.pairDao().byId(pairId)!!.purchaseDate)

        repository.setPurchaseInfo(pairId, purchaseDate = null, priceCents = null)
        assertNull(db.pairDao().byId(pairId)!!.priceCents)
        assertNull(db.pairDao().byId(pairId)!!.purchaseDate)
    }

    @Test
    fun `setting purchase info on a missing pair is a no-op`() = runTest {
        repository.setPurchaseInfo(pairId = 404, purchaseDate = clockNow, priceCents = 1)

        assertTrue(db.pairDao().getAll().isEmpty())
    }

    @Test
    fun `deleting a pair takes its sessions with it but leaves the device known`() = runTest {
        repository.onConnected(buds, at = clockNow - hour)
        repository.onDisconnected(buds.key, at = clockNow)
        val pairId = onlyPairId()

        repository.deletePair(pairId)

        assertTrue(db.pairDao().getAll().isEmpty())
        assertTrue(db.sessionDao().getAll().isEmpty())
        assertNotNull(db.deviceDao().findByKey(buds.key))
    }

    @Test
    fun `deleting one session leaves the rest of the history alone`() = runTest {
        repository.onConnected(buds, at = clockNow - 3 * hour)
        repository.onDisconnected(buds.key, at = clockNow - 2 * hour)
        repository.onConnected(buds, at = clockNow - hour)
        repository.onDisconnected(buds.key, at = clockNow)
        val first = db.sessionDao().getAll().first()

        repository.deleteSession(first.id)

        assertEquals(listOf(clockNow - hour), db.sessionDao().getAll().map { it.connectedAt })
    }

    @Test
    fun `ignoring a device closes whatever it had open, un-ignoring does not reopen it`() =
        runTest {
            repository.onConnected(buds, at = clockNow - hour)
            val deviceId = db.deviceDao().findByKey(buds.key)!!.id

            repository.setDeviceIgnored(deviceId, ignored = true)
            assertEquals(clockNow, db.sessionDao().getAll().single().disconnectedAt)
            assertEquals(EndReason.MANUAL, db.sessionDao().getAll().single().endReason)

            repository.setDeviceIgnored(deviceId, ignored = false)
            assertFalse(db.deviceDao().byId(deviceId)!!.ignored)
            assertEquals(1, db.sessionDao().getAll().size)
        }

    @Test
    fun `the open session id is only found for a device that has one`() = runTest {
        assertNull("nothing has ever connected", repository.openSessionIdFor(buds.key))
        assertFalse(repository.hasOpenSessions())

        val sessionId = repository.onConnected(buds, at = clockNow)
        assertEquals(sessionId, repository.openSessionIdFor(buds.key))
        assertTrue(repository.hasOpenSessions())
        assertEquals(listOf(sessionId), repository.openSessionsSnapshot().map { it.id })

        repository.onDisconnected(buds.key, at = clockNow + hour)
        assertNull("the session is closed", repository.openSessionIdFor(buds.key))
        assertFalse(repository.hasOpenSessions())
        assertTrue(repository.openSessionsSnapshot().isEmpty())
    }

    @Test
    fun `the open session id ignores a device whose pair was retired`() = runTest {
        repository.onConnected(buds, at = clockNow - hour)
        repository.retirePair(onlyPairId(), reason = null, at = clockNow)

        assertNull(repository.openSessionIdFor(buds.key))
    }

    @Test
    fun `the summary adds up closed time, playback and the longest session`() = runTest {
        repository.onConnected(buds, at = clockNow - 5 * hour, measurePlayback = true)
        repository.addPlayback(repository.openSessionIdFor(buds.key)!!, 30 * 60_000)
        repository.onDisconnected(buds.key, at = clockNow - 2 * hour)
        repository.onConnected(buds, at = clockNow - hour)

        val summary = repository.observeSummaries().first().single()

        assertEquals("Buds", summary.label)
        assertEquals(DeviceKind.BLUETOOTH, summary.deviceKind)
        assertEquals(1, summary.generation)
        assertEquals(2, summary.sessionCount)
        // Only the finished session counts; the open one is `now - openSince` at read time.
        assertEquals(3 * hour, summary.closedMs)
        assertEquals(clockNow - hour, summary.openSince)
        assertEquals(3 * hour, summary.longestMs)
        assertEquals(30 * 60_000L, summary.playingMs)
        assertEquals(3 * hour, summary.measuredPlaybackMs)
        assertFalse(summary.deviceIgnored)
        assertEquals(summary, repository.observeSummary(summary.pairId).first())
    }

    @Test
    fun `a pair with no sessions still has a row, with zeroes`() = runTest {
        val deviceId = db.deviceDao().insert(
            DeviceEntity(
                deviceKey = "bt:00:01",
                kind = DeviceKind.BLE,
                defaultName = "Never used",
                firstSeenAt = clockNow,
            ),
        )
        val pairId = db.pairDao().insert(
            PairEntity(deviceId = deviceId, label = "Never used", generation = 1, startedAt = clockNow),
        )

        val summary = repository.observeSummary(pairId).first()!!

        assertEquals(0, summary.sessionCount)
        assertEquals(0L, summary.closedMs)
        assertNull(summary.openSince)
        assertNull(summary.lastSeenAt)
    }

    @Test
    fun `a pair id nobody has ever used observes nothing`() = runTest {
        assertNull(repository.observeSummary(404).first())
    }

    @Test
    fun `connected pairs sort ahead of idle ones and retired ones sort last`() = runTest {
        repository.onConnected(wired, at = clockNow - 10 * hour, measurePlayback = true)
        repository.onDisconnected(wired.key, at = clockNow - 9 * hour)
        val wiredPairId = db.pairDao().getAll().single().id
        repository.retirePair(wiredPairId, reason = "frayed", at = clockNow - 8 * hour)

        repository.onConnected(buds, at = clockNow - 7 * hour)
        repository.onDisconnected(buds.key, at = clockNow - 6 * hour)

        val stillOn = DeviceIdentity("bt:AA:BB", DeviceKind.BLUETOOTH, "On my head")
        repository.onConnected(stillOn, at = clockNow - hour)

        assertEquals(
            listOf("On my head", "Buds", "Wired headphones"),
            repository.observeSummaries().first().map { it.label },
        )
    }

    @Test
    fun `the observed session lists are ordered for their screens`() = runTest {
        repository.onConnected(buds, at = clockNow - 3 * hour)
        repository.onDisconnected(buds.key, at = clockNow - 2 * hour)
        repository.onConnected(buds, at = clockNow - hour)
        val pairId = onlyPairId()

        // The detail screen reads newest first; the stats screen reads oldest first.
        assertEquals(
            listOf(clockNow - hour, clockNow - 3 * hour),
            repository.observeSessionsFor(pairId).first().map { it.connectedAt },
        )
        assertEquals(
            listOf(clockNow - 3 * hour, clockNow - hour),
            repository.observeAllSessions().first().map { it.connectedAt },
        )
        assertEquals(
            listOf(clockNow - hour),
            repository.observeOpenSessions().first().map { it.connectedAt },
        )
    }

    @Test
    fun `devices are observed in the order they were first seen`() = runTest {
        repository.onConnected(wired, at = clockNow - 2 * hour)
        repository.onConnected(buds, at = clockNow - hour)

        assertEquals(
            listOf("Wired headphones", "Buds"),
            repository.observeDevices().first().map { it.defaultName },
        )
    }

    @Test
    fun `a heartbeat with nothing open changes nothing`() = runTest {
        repository.heartbeat(at = clockNow)

        assertTrue(db.sessionDao().getAll().isEmpty())
    }

    @Test
    fun `reconcile skips a pair whose device row has gone`() = runTest {
        // Deleting the device cascades the pair away while the session row survives only if
        // something upstream detached it; reconcile must walk past that rather than throw.
        repository.onConnected(buds, at = clockNow - hour)
        val session = db.sessionDao().getAll().single()
        db.pairDao().delete(session.pairId)

        repository.reconcile(connected = emptyList(), now = clockNow, bootAt = clockNow - 5 * hour)

        assertTrue(db.sessionDao().getAll().isEmpty())
    }
}
