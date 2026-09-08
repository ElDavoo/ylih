package it.eldavo.ylih.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.tracking.BtBatteryReceiver
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        // Receivers pass a timestamp captured before `goAsync`, since the work runs late; both
        // routes must agree.
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
        // What AppContainer uses in production; only tests inject time.
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
        // Connected, then nothing ran for hours — no disconnect was recorded.
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
            // Connected 14 h ago, last heartbeat 13 h ago; phone booted 12 h ago.
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
        // Inside the unwatched ceiling: a gap this short is Doze or a process restart, not a real
        // disconnect.
        repository.heartbeat(at = clockNow - hour)

        repository.reconcile(connected = listOf(buds), now = clockNow, bootAt = bootAt)

        val session = sessions().single()
        assertNull(session.disconnectedAt)
        assertEquals(clockNow, session.heartbeatAt)
    }

    /**
     * Stops a lifetime total from quietly gaining a day.
     *
     * A force-stop or a starved heartbeat worker leaves a session open, unwatched. Reconnecting
     * later found the same headphones connected and stretched the session across the whole gap:
     * heartbeating to `now` first defeated the split `openSession` otherwise does.
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

        // The audio device list still shows the headset briefly after the ACL broadcast.
        repository.reconcile(connected = listOf(buds), now = clockNow + 1_000, bootAt = bootAt)

        assertEquals(1, sessions().size)
        assertNotNull(sessions().single().disconnectedAt)
    }

    @Test
    fun `the grace window only looks forward, never back over a clock correction`() = runTest {
        // The window absorbs a device list lagging a disconnect by a moment. A connect dated
        // *before* it means the clock went back instead — swallowing it would drop a real
        // session.
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
        // Bluetooth access was revoked, so the service died — likely with the whole process,
        // hours ago. "Now" is when this was noticed, not when the plug came out; counting the
        // gap would invent listening time from a permission change.
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
     * The watcher credits whatever `playbackTargetKey` last named, and the service can outlive
     * it: the pair retired, the device forgotten by an import, or the session closed some other
     * way.
     */
    @Test
    fun `playback with no open session to credit is dropped rather than resurrecting one`() = runTest {
        repository.creditPlayback("bt:never:seen", 10 * 60_000)
        assertTrue("an unknown device records nothing at all", sessions().isEmpty())

        repository.onConnected(buds, at = clockNow, measurePlayback = true)
        repository.retirePair(db.pairDao().getAll().single().id, reason = null, at = clockNow)

        repository.creditPlayback(buds.key, 10 * 60_000)

        assertEquals("a retired pair is not an open one", 0L, sessions().single().playingMs)
    }

    /**
     * A slice from a coroutine launched behind the one that closed the session — an ordering the
     * service avoids, and the database refuses anyway: crediting it would push playback past the
     * span it measured.
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
     * `kind` gates `trackedKinds` and `closeSessionsForKinds`, so a stale value leaves a session
     * unreachable by either. It used to be written only alongside a name change, which the same
     * headset does not always bring across its two platform views.
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

    private suspend fun batterySamples() = db.batterySampleDao().getAll()

    @Test
    fun `a battery reading is filed against the session that was open when it arrived`() = runTest {
        repository.onConnected(buds, at = clockNow)
        repository.recordBatteryLevel(buds.key, level = 80, at = clockNow + hour)

        val sample = batterySamples().single()
        assertEquals(sessions().single().id, sample.sessionId)
        assertEquals(80, sample.level)
        assertEquals(clockNow + hour, sample.at)
    }

    /**
     * A reading outliving its session would subtract two readings across an unwatched gap.
     * `false` is the contract the receiver's retry relies on — "ask again" — since a session's
     * first reading often arrives before it does.
     */
    @Test
    fun `a battery reading with no session open is refused and says so`() = runTest {
        assertEquals(
            "no device at all",
            false,
            repository.recordBatteryLevel(buds.key, level = 80, at = clockNow),
        )
        assertTrue("nothing to attach it to", batterySamples().isEmpty())

        repository.onConnected(buds, at = clockNow)
        repository.onDisconnected(buds.key, at = clockNow + hour)
        assertEquals(
            "a closed session is not one to file against either",
            false,
            repository.recordBatteryLevel(buds.key, level = 60, at = clockNow + 2 * hour),
        )

        assertTrue(batterySamples().isEmpty())
    }

    /**
     * A level is re-announced when another source appears; a second row would insert a no-drain
     * segment mid-discharge.
     */
    @Test
    fun `the same level reported twice is recorded once`() = runTest {
        repository.onConnected(buds, at = clockNow)
        repository.recordBatteryLevel(buds.key, level = 80, at = clockNow + hour)
        assertEquals(
            "a duplicate is still an answer, so the caller must not ask again",
            true,
            repository.recordBatteryLevel(buds.key, level = 80, at = clockNow + 2 * hour),
        )

        assertEquals(1, batterySamples().size)

        repository.recordBatteryLevel(buds.key, level = 70, at = clockNow + 3 * hour)
        repository.recordBatteryLevel(buds.key, level = 80, at = clockNow + 4 * hour)

        assertEquals(
            "but a level that comes back after changing is a real reading",
            listOf(80, 70, 80),
            batterySamples().map { it.level },
        )
    }

    /**
     * -1 is "unknown", broadcast on every disconnect; -100 is "Bluetooth is off". At face value
     * that reads as the whole battery dropping to nothing.
     */
    @Test
    fun `a level outside a percentage is not a reading`() = runTest {
        repository.onConnected(buds, at = clockNow)
        repository.recordBatteryLevel(buds.key, level = -1, at = clockNow + hour)
        repository.recordBatteryLevel(buds.key, level = -100, at = clockNow + 2 * hour)
        repository.recordBatteryLevel(buds.key, level = 101, at = clockNow + 3 * hour)

        assertTrue(batterySamples().isEmpty())

        repository.recordBatteryLevel(buds.key, level = 0, at = clockNow + 4 * hour)
        repository.recordBatteryLevel(buds.key, level = 100, at = clockNow + 5 * hour)

        assertEquals("but both ends of the range are", listOf(0, 100), batterySamples().map { it.level })
    }

    @Test
    fun `a reading for a device the user ignores is dropped with its session`() = runTest {
        repository.onConnected(buds, at = clockNow)
        val deviceId = db.deviceDao().findByKey(buds.key)!!.id
        repository.recordBatteryLevel(buds.key, level = 80, at = clockNow + hour)

        repository.setDeviceIgnored(deviceId, ignored = true)
        repository.recordBatteryLevel(buds.key, level = 70, at = clockNow + 2 * hour)

        assertEquals("ignoring closes the session, so nothing takes the next reading", 1, batterySamples().size)
    }

    @Test
    fun `deleting a session takes its readings with it`() = runTest {
        repository.onConnected(buds, at = clockNow)
        repository.recordBatteryLevel(buds.key, level = 80, at = clockNow + hour)
        val sessionId = sessions().single().id

        repository.deleteSession(sessionId)

        assertTrue(batterySamples().isEmpty())
    }

    @Test
    fun `deleting a pair takes its readings with it`() = runTest {
        repository.onConnected(buds, at = clockNow)
        repository.recordBatteryLevel(buds.key, level = 80, at = clockNow + hour)

        repository.deletePair(db.pairDao().getAll().single().id)

        assertTrue(batterySamples().isEmpty())
    }

    /**
     * The retry [BtBatteryReceiver] wraps every reading in, ordered by the repository's own mutex.
     *
     * Not rare on a phone: measured, the battery broadcast arrived ~400 ms after ACL_CONNECTED,
     * beating the app's own session row by 68 ms. Many headsets report battery only at connect,
     * so a dropped reading here loses that pair's charge cycles entirely.
     *
     * Driving the retry by the clock — advance a second, write the connect, advance past the
     * settle — looks deterministic and isn't: `runTest`'s runner advances virtual time to whatever
     * is next scheduled whenever the test body parks on a non-test dispatcher, and every Room call
     * parks on Room's transaction executor. A `delay` landing in that window lets the runner fire
     * the retry before the connect is written, so both attempts find no session and nothing is
     * recorded — `expected:<[70]> but was:<[]>`, which failed #42's build after hundreds of green
     * runs.
     *
     * `UNDISPATCHED` fixes it: the first attempt takes the mutex before `launch` returns
     * (`Mutex.lock` doesn't suspend when free), so the connect queues behind it and the retry,
     * locking again only after its delay, queues behind the connect. The clock then runs free.
     * **Nothing may suspend between the launch and the connect**: a yield there lets the retry
     * fire against a mutex the connect hasn't reached yet.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a reading that arrives before its session is retried and lands`() = runTest {
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            BtBatteryReceiver.recordWithRetry(repository, buds.key, level = 70, at = clockNow)
        }
        repository.onConnected(buds, at = clockNow)
        job.join()

        assertEquals(listOf(70), batterySamples().map { it.level })
        assertEquals(sessions().single().id, batterySamples().single().sessionId)
        // The settle is the only thing scheduled on this clock, so spending it proves the first
        // attempt was refused.
        assertEquals(
            "the reading landed on the retry, not on a first attempt that found the session",
            BtBatteryReceiver.SESSION_SETTLE_MS,
            testScheduler.currentTime,
        )
    }

    @Test
    fun `a reading with no session even after the retry is dropped`() = runTest {
        BtBatteryReceiver.recordWithRetry(repository, buds.key, level = 70, at = clockNow)

        assertTrue("nothing ever connected, so there is nothing to file it against", batterySamples().isEmpty())
    }

    @Test
    fun `a pair's readings are observed across every session it has had`() = runTest {
        repository.onConnected(buds, at = clockNow)
        repository.recordBatteryLevel(buds.key, level = 90, at = clockNow + hour)
        repository.onDisconnected(buds.key, at = clockNow + 2 * hour)
        repository.onConnected(buds, at = clockNow + 3 * hour)
        repository.recordBatteryLevel(buds.key, level = 60, at = clockNow + 4 * hour)

        // A second pair's readings must not leak into the first one's charge cycles.
        repository.onConnected(wired, at = clockNow)
        repository.recordBatteryLevel(wired.key, level = 50, at = clockNow + hour)

        val pairId = db.pairDao().activeFor(db.deviceDao().findByKey(buds.key)!!.id)!!.id
        assertEquals(
            listOf(90, 60),
            repository.observeBatterySamples(pairId).first().map { it.level },
        )
        // `pairId` is carried on the reading rather than reached only via its session, so the
        // read above never joins `sessions` — see BatterySampleEntity. The two must agree.
        for (sample in batterySamples()) {
            assertEquals(
                "a reading's pair must be its session's pair",
                db.sessionDao().getAll().single { it.id == sample.sessionId }.pairId,
                sample.pairId,
            )
        }
    }
}
