package it.eldavo.ylih.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bookkeeping rules that decide whether lifetime totals can be trusted:
 * idempotency, reboot handling and pair generations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRepositoryTest {

    private lateinit var db: YlihDatabase
    private lateinit var repository: SessionRepository

    private val hour = 3_600_000L
    private val day = 24 * hour
    private var clockNow = 1_800_000_000_000L

    /** Far enough back that nothing this class opens reads as predating the boot. */
    private val bootAt get() = clockNow - 30 * day

    private val buds = DeviceIdentity("bt:AA:BB:CC:DD:EE:FF", DeviceKind.BLUETOOTH, "Buds")
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

    private suspend fun sessions() = db.sessionDao().getAll()

    @Test
    fun `a caller that names no time gets the clock the repository was built with`() = runTest {
        // The receivers pass a timestamp captured before `goAsync`, because the work runs later
        // than the event; everything else lets these default, and both routes have to agree.
        repository.onConnected(buds)
        assertEquals(clockNow, sessions().single().connectedAt)

        clockNow += hour
        repository.heartbeat()
        assertEquals(clockNow, sessions().single().heartbeatAt)

        clockNow += hour
        repository.reconcile(connected = listOf(buds), bootAt = bootAt)
        assertEquals(
            "a pair that is still connected is heartbeaten, not reopened",
            clockNow,
            sessions().single().heartbeatAt,
        )

        clockNow += hour
        repository.onDisconnected(buds.key)
        assertEquals(clockNow, sessions().single().disconnectedAt)
    }

    @Test
    fun `built without one, the clock is the wall clock`() = runTest {
        // How AppContainer builds it in production; only the tests ever inject time.
        val before = System.currentTimeMillis()

        SessionRepository(db).onConnected(buds)

        val at = sessions().single().connectedAt
        assertTrue(
            "$at is not a wall-clock instant",
            at >= before && at <= System.currentTimeMillis(),
        )
    }

    @Test
    fun `connect then disconnect records one closed session`() = runTest {
        repository.onConnected(buds, at = clockNow)
        repository.onDisconnected(buds.key, at = clockNow + 2 * hour)

        val all = sessions()
        assertEquals(1, all.size)
        assertEquals(clockNow, all[0].connectedAt)
        assertEquals(clockNow + 2 * hour, all[0].disconnectedAt)
        assertEquals(EndReason.DISCONNECTED, all[0].endReason)
        assertNull("Bluetooth-only mode must not claim to have measured playback", all[0].playingMs)
    }

    @Test
    fun `a second connect for the same device does not open a second session`() = runTest {
        val first = repository.onConnected(buds, at = clockNow)
        val second = repository.onConnected(buds, at = clockNow + 60_000)

        assertEquals(first, second)
        assertEquals(1, sessions().size)
    }

    @Test
    fun `a connect on top of a stale open session splits instead of stretching it`() = runTest {
        // Connected, then nothing of ours ran for hours — no disconnect was ever recorded.
        repository.onConnected(buds, at = clockNow - 5 * hour)
        repository.heartbeat(at = clockNow - 4 * hour)

        repository.onConnected(buds, at = clockNow)

        val all = sessions().sortedBy { it.connectedAt }
        assertEquals(2, all.size)
        assertEquals(clockNow - 4 * hour, all[0].disconnectedAt)
        assertEquals(EndReason.RECOVERED, all[0].endReason)
        assertEquals(clockNow, all[1].connectedAt)
        assertNull(all[1].disconnectedAt)
    }

    @Test
    fun `disconnect for an unknown device is a no-op`() = runTest {
        repository.onDisconnected("bt:00:00:00:00:00:00", at = clockNow)
        assertTrue(sessions().isEmpty())
    }

    @Test
    fun `disconnect before connect cannot produce a negative session`() = runTest {
        repository.onConnected(buds, at = clockNow)
        repository.onDisconnected(buds.key, at = clockNow - 5_000)

        val session = sessions().single()
        assertEquals(session.connectedAt, session.disconnectedAt)
    }

    @Test
    fun `ignored devices record nothing`() = runTest {
        repository.onConnected(buds, at = clockNow)
        repository.onDisconnected(buds.key, at = clockNow + hour)
        val deviceId = db.deviceDao().findByKey(buds.key)!!.id
        repository.setDeviceIgnored(deviceId, ignored = true)

        repository.onConnected(buds, at = clockNow + 2 * hour)

        assertEquals(1, sessions().size)
    }

    @Test
    fun `a session left open by a reboot is closed at its heartbeat, never across the downtime`() =
        runTest {
            // Connected 14 h ago, last heartbeat 13 h ago; the phone booted 12 h ago.
            repository.onConnected(buds, at = clockNow - 14 * hour)
            repository.heartbeat(at = clockNow - 13 * hour)

            repository.reconcile(connected = emptyList(), now = clockNow, bootAt = bootAt)

            val session = sessions().single()
            assertEquals(clockNow - 13 * hour, session.disconnectedAt)
            assertEquals(EndReason.RECOVERED, session.endReason)
        }

    @Test
    fun `a device still connected across a reboot starts a fresh session`() = runTest {
        repository.onConnected(buds, at = clockNow - 14 * hour)
        repository.heartbeat(at = clockNow - 13 * hour)

        repository.reconcile(connected = listOf(buds), now = clockNow, bootAt = bootAt)

        val all = sessions().sortedBy { it.connectedAt }
        assertEquals(2, all.size)
        assertEquals(clockNow - 13 * hour, all[0].disconnectedAt)
        assertEquals(EndReason.RECOVERED, all[0].endReason)
        assertEquals(clockNow, all[1].connectedAt)
        assertNull(all[1].disconnectedAt)
    }

    @Test
    fun `process death without a reboot keeps a live session open`() = runTest {
        repository.onConnected(buds, at = clockNow - 2 * hour)
        // Inside the unwatched ceiling: a gap this short is Doze holding the heartbeat back, or
        // the process dying and coming back, and the headphones really were on throughout.
        repository.heartbeat(at = clockNow - hour)

        repository.reconcile(connected = listOf(buds), now = clockNow, bootAt = bootAt)

        val session = sessions().single()
        assertNull(session.disconnectedAt)
        assertEquals(clockNow, session.heartbeatAt)
    }

    /**
     * The other side of the rule above, and the one that stops a lifetime total from quietly
     * gaining a day.
     *
     * A force-stop, or a battery manager that starves the heartbeat worker, leaves a session open
     * with nothing watching it. Reconnecting the app later found the same headphones connected and
     * stretched the session across the whole gap — and defeated the split `openSession` would
     * otherwise have done, because heartbeating to `now` first made the session look fresh.
     */
    @Test
    fun `a still-connected session nobody watched for too long is split, not stretched`() = runTest {
        repository.onConnected(buds, at = clockNow - 3 * day)
        repository.heartbeat(at = clockNow - 2 * day)

        repository.reconcile(connected = listOf(buds), now = clockNow, bootAt = bootAt)

        val all = sessions()
        assertEquals("the unwatched gap is a second session, not part of the first", 2, all.size)
        assertEquals(clockNow - 2 * day, all[0].disconnectedAt)
        assertEquals(EndReason.RECOVERED, all[0].endReason)
        assertEquals("and the live one starts now", clockNow, all[1].connectedAt)
        assertNull(all[1].disconnectedAt)
    }

    @Test
    fun `a missed disconnect is closed at the last heartbeat`() = runTest {
        repository.onConnected(buds, at = clockNow - 3 * hour)
        repository.heartbeat(at = clockNow - 2 * hour)

        repository.reconcile(connected = emptyList(), now = clockNow, bootAt = bootAt)

        val session = sessions().single()
        assertEquals(clockNow - 2 * hour, session.disconnectedAt)
        assertEquals(EndReason.RECOVERED, session.endReason)
    }

    @Test
    fun `reconcile racing a fresh disconnect does not resurrect the session`() = runTest {
        repository.onConnected(buds, at = clockNow - hour)
        repository.onDisconnected(buds.key, at = clockNow)

        // The audio device list still lists the headset for a moment after the ACL broadcast.
        repository.reconcile(connected = listOf(buds), now = clockNow + 1_000, bootAt = bootAt)

        assertEquals(1, sessions().size)
        assertNotNull(sessions().single().disconnectedAt)
    }

    @Test
    fun `the grace window only looks forward, never back over a clock correction`() = runTest {
        // The window exists to absorb a device list that lags a disconnect by a moment. A
        // connect dated *before* that disconnect is the clock having been put back instead, and
        // swallowing it would drop a real session on the floor.
        repository.onConnected(buds, at = clockNow - hour)
        repository.onDisconnected(buds.key, at = clockNow)

        repository.reconcile(connected = listOf(buds), now = clockNow - 1_000, bootAt = bootAt)

        assertEquals(2, sessions().size)
        assertNull("the second one is live", sessions().last().disconnectedAt)
    }

    @Test
    fun `retiring a pair freezes it and the next connection starts generation two`() = runTest {
        repository.onConnected(buds, at = clockNow - 3 * hour)
        repository.onDisconnected(buds.key, at = clockNow - 2 * hour)
        val firstPair = db.pairDao().getAll().single()

        repository.retirePair(firstPair.id, reason = "died", at = clockNow - hour)
        repository.onConnected(buds, at = clockNow)

        val pairs = db.pairDao().getAll().sortedBy { it.generation }
        assertEquals(2, pairs.size)
        assertEquals(1, pairs[0].generation)
        assertEquals("died", pairs[0].retireReason)
        assertEquals(2, pairs[1].generation)
        assertNull(pairs[1].retiredAt)
        assertNotEquals(pairs[0].id, sessions().last().pairId)
    }

    @Test
    fun `retiring closes the open session so the frozen total is final`() = runTest {
        repository.onConnected(buds, at = clockNow - hour)
        val pairId = db.pairDao().getAll().single().id

        repository.retirePair(pairId, reason = null, at = clockNow)

        val session = sessions().single()
        assertEquals(clockNow, session.disconnectedAt)
        assertEquals(EndReason.MANUAL, session.endReason)
    }

    @Test
    fun `turning detailed tracking off closes wired sessions only`() = runTest {
        repository.onConnected(buds, at = clockNow - hour, measurePlayback = true)
        repository.onConnected(wired, at = clockNow - hour, measurePlayback = true)

        repository.closeSessionsForKinds(setOf(DeviceKind.WIRED, DeviceKind.USB), at = clockNow)

        val byKind = sessions().associateBy { session ->
            val pair = db.pairDao().byId(session.pairId)!!
            db.deviceDao().byId(pair.deviceId)!!.kind
        }
        assertEquals(clockNow, byKind[DeviceKind.WIRED]!!.disconnectedAt)
        assertEquals(EndReason.TRACKING_DISABLED, byKind[DeviceKind.WIRED]!!.endReason)
        assertNull(byKind[DeviceKind.BLUETOOTH]!!.disconnectedAt)
    }

    @Test
    fun `a fallback that has not been watching closes at the proof, not at now`() = runTest {
        // The other caller: bluetooth access was revoked, so the service died — most likely with
        // the whole process, hours ago. "Now" is when we noticed, not when the plug came out, and
        // counting the difference would invent listening time out of a permission change.
        repository.onConnected(wired, at = clockNow - 5 * hour, measurePlayback = true)
        repository.onConnected(buds, at = clockNow - 5 * hour, measurePlayback = true)
        repository.heartbeat(at = clockNow - 4 * hour)

        repository.closeSessionsForKinds(
            setOf(DeviceKind.WIRED, DeviceKind.USB),
            at = clockNow,
            reason = EndReason.RECOVERED,
            stillLive = false,
        )

        val byKind = sessions().associateBy { session ->
            val pair = db.pairDao().byId(session.pairId)!!
            db.deviceDao().byId(pair.deviceId)!!.kind
        }
        assertEquals(clockNow - 4 * hour, byKind[DeviceKind.WIRED]!!.disconnectedAt)
        assertEquals(EndReason.RECOVERED, byKind[DeviceKind.WIRED]!!.endReason)
        assertNull("bluetooth is still observable, so it stays open", byKind[DeviceKind.BLUETOOTH]!!.disconnectedAt)
    }

    @Test
    fun `playback time accumulates on the open session`() = runTest {
        repository.onConnected(wired, at = clockNow, measurePlayback = true)

        repository.creditPlayback(wired.key, 20 * 60_000)
        repository.creditPlayback(wired.key, 10 * 60_000)
        repository.creditPlayback(wired.key, -5) // ignored

        assertEquals(30 * 60_000L, sessions().single().playingMs)
    }

    /**
     * The watcher banks a slice from a coroutine launched behind the one that closed the session,
     * so this is the ordering the service is written to avoid and the database has to refuse
     * anyway: credited here, the stored playback would exceed the span it was measured inside.
     */
    @Test
    fun `playback credited after the disconnect is refused rather than backdated`() = runTest {
        repository.onConnected(wired, at = clockNow, measurePlayback = true)
        repository.creditPlayback(wired.key, 10 * 60_000)
        repository.onDisconnected(wired.key, at = clockNow + hour)

        repository.creditPlayback(wired.key, 20 * 60_000)

        assertEquals(10 * 60_000L, sessions().single().playingMs)
    }

    /**
     * `kind` decides which tracking mode can see a session at all — `trackedKinds` filters on it,
     * and so does `closeSessionsForKinds` — so a stale one leaves a session neither can reach. It
     * used to be written only alongside a name change, which the same headset reported by two
     * platform views does not necessarily bring.
     */
    @Test
    fun `a device that changes kind under the same name is corrected`() = runTest {
        repository.onConnected(buds, at = clockNow - hour)
        repository.onDisconnected(buds.key, at = clockNow)

        repository.onConnected(buds.copy(kind = DeviceKind.BLE), at = clockNow + hour)

        val device = db.deviceDao().findByKey(buds.key)!!
        assertEquals(DeviceKind.BLE, device.kind)
        assertEquals("without splitting the identity", "Buds", device.defaultName)
        assertEquals(1, db.deviceDao().getAll().size)
    }

    @Test
    fun `retiring a pair twice does not move the date it was retired on`() = runTest {
        repository.onConnected(buds, at = clockNow - hour)
        val pairId = db.pairDao().getAll().single().id

        repository.retirePair(pairId, reason = "died", at = clockNow)
        repository.retirePair(pairId, reason = "tapped again", at = clockNow + hour)

        val pair = db.pairDao().byId(pairId)!!
        assertEquals("the frozen total is dated once and stays dated", clockNow, pair.retiredAt)
        assertEquals("died", pair.retireReason)
    }

    @Test
    fun `a device renamed by the user updates without splitting the identity`() = runTest {
        repository.onConnected(buds, at = clockNow - hour)
        repository.onDisconnected(buds.key, at = clockNow)
        repository.onConnected(buds.copy(name = "WH-1000XM5"), at = clockNow + hour)

        assertEquals(1, db.deviceDao().getAll().size)
        assertEquals("WH-1000XM5", db.deviceDao().findByKey(buds.key)!!.defaultName)
        assertEquals(1, db.pairDao().getAll().size)
    }
}
