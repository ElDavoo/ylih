package it.eldavo.ylih.tracking

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceIdentity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The only thing that sees a wired plug or measures playback, and the one component that keeps
 * running unwatched — audio callbacks must reach the database, and the minute tick must keep the
 * session alive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class TrackingServiceTest {

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val db get() = app.container.database
    private val audioManager = app.getSystemService(AudioManager::class.java)

    private lateinit var controller: ServiceController<TrackingService>
    private val service get() = controller.get()

    private var running = false

    @Before
    fun setUp() = runBlocking {
        // syncWithSystem() reaches WorkManager, whose androidx.startup initializer never runs here.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        // Without `specialUse` Play needs Bluetooth access to run the service; this test is
        // about the service, not that rule.
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        db.deviceDao().deleteAll()
        // The service only runs in detailed mode; off, its first sync would close as untracked
        // every session its own audio callback just opened.
        app.container.settings.setDetailedTracking(true)
        shadowOf(audioManager).setOutputDevices(emptyList())
        // `TrackingController.bootAt()` is the wall clock minus SystemClock.elapsedRealtime().
        // Robolectric's elapsed clock starts at zero and only advances when a test idles the
        // looper, so the fake phone reads as booted a moment ago and that moment creeps forward
        // in real time: a session opened 30 ms before a reconcile then looks pre-boot, gets
        // closed as RECOVERED, and the reconnect grace refuses to reopen it — on a busy machine,
        // most of a test. Booting an hour ago puts every session here comfortably after it.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(1))
        controller = Robolectric.buildService(TrackingService::class.java)
    }

    @After
    fun tearDown() = runBlocking {
        stop()
        // The preferences file is real and the DataStore behind it is a process singleton.
        app.container.settings.setDetailedTracking(false)
    }

    private fun stop() {
        if (!running) return
        running = false
        controller.destroy()
    }

    private fun start(awaitSync: Boolean = true) {
        running = true
        controller.create()
        if (awaitSync) awaitFirstSync()
    }

    /**
     * The first sync finishes on Room's threads, and only then does the tick loop reach its
     * `delay`. Idling the looper before that steps over the tick.
     */
    private fun awaitFirstSync() {
        settle("the first sync") {
            notificationText() != app.getString(R.string.notification_starting)
        }
    }

    private fun buds() =
        outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "XX:XX:XX:XX:5E:C2", "ACCENTUM Plus")

    private fun wired() =
        outputDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, productName = "Plugged in")

    /**
     * A Bluetooth output that isn't headphones, giving the ignore list something to be about.
     *
     * The audio stack's device view carries no class, so [AudioDevices.identityOf] can't tell it
     * from a headset the way the ACL broadcast's view can — it still reaches the service, and
     * unticking it in settings is the only way to keep it out.
     */
    private fun speaker() =
        outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "XX:XX:XX:XX:11:22", "Kitchen speaker")

    /** Untick a device in Settings › devices, before it has ever been seen. */
    private fun ignore(device: AudioDeviceInfo) = runBlocking {
        val identity = checkNotNull(AudioDevices.identityOf(device))
        db.deviceDao().insert(
            DeviceEntity(
                deviceKey = identity.key,
                kind = identity.kind,
                defaultName = identity.name,
                firstSeenAt = app.container.clock.now(),
                ignored = true,
            ),
        )
    }

    /** The service's work is launched on the main looper and finished on Room's own threads. */
    private fun settle(what: String = "the service to catch up", until: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun sessions(): List<SessionEntity> = runBlocking { db.sessionDao().getAll() }

    private fun heartbeatScheduled(): Boolean =
        WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(HeartbeatWorker.NAME)
            .get()
            .any { it.state != WorkInfo.State.CANCELLED }

    private fun notificationText(): String? = shadowOf(service).lastForegroundNotification
        ?.extras?.getCharSequence("android.text")?.toString()

    /**
     * The notification's duration runs on the wall clock, not the looper's, so this asserts
     * everything around it.
     */
    private fun assertNotificationReads(plural: Int, count: Int) {
        val blank = "\u0000"
        val (prefix, suffix) = app.resources.getQuantityString(plural, count, count, blank)
            .split(blank)
        val actual = notificationText()
        assertTrue(
            "\"$actual\" does not read like \"$prefix…$suffix\"",
            actual != null && actual.startsWith(prefix) && actual.endsWith(suffix),
        )
    }

    /**
     * Like [settle], but moving the clock a tick each round.
     *
     * The tick loop arms asynchronously once the open-session flow reports something to watch — a
     * Room invalidation behind the write that opened the session. A clock jump before the loop
     * reaches its `delay` would schedule the tick past the new moment, so nothing comes due.
     */
    private fun settleAcrossTicks(what: String, until: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TICK))
            if (until()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /**
     * Reads [value] once the service has stopped changing it.
     *
     * [settle] returns the first moment its condition holds, mid-story for anything done in
     * several steps — the notification, heartbeat and widgets land behind the write that
     * prompted them. Nothing here moves the clock, and the watcher only banks on a tick or an
     * edge, so an unchanged value across several drained rounds will not move again.
     */
    private fun <T> settled(what: String, value: () -> T): T {
        var last = value()
        var unchanged = 0
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            val current = value()
            unchanged = if (current == last) unchanged + 1 else 0
            last = current
            if (unchanged == 10) return last
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what to settle")
    }

    /**
     * [PlaybackWatcher] measures wall-clock ms, which idling the looper doesn't move, so real
     * time must pass before a tick has anything to credit.
     */
    private fun playFor(realMillis: Long) {
        Thread.sleep(realMillis)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TICK))
    }

    private fun connect(device: AudioDeviceInfo) {
        shadowOf(audioManager).addOutputDevice(device, /* notifyAudioDeviceCallbacks = */ true)
    }

    private fun disconnect(device: AudioDeviceInfo) {
        shadowOf(audioManager).removeOutputDevice(device, /* notifyAudioDeviceCallbacks = */ true)
    }

    @Test
    fun `starting the service adopts whatever is already plugged in`() {
        shadowOf(audioManager).setOutputDevices(listOf(wired()))

        start()
        controller.startCommand(0, 0)

        settle("the plugged-in pair to be adopted") { sessions().isNotEmpty() }
        assertNull("still plugged in", sessions().single().disconnectedAt)
        // Only the service can measure playback, so only it opens sessions that record it.
        assertEquals(0L, sessions().single().playingMs)
    }

    @Test
    fun `the notification says what is being tracked and for how long`() {
        start(awaitSync = false)

        // It says "starting…" until the first sync has told it what is actually connected.
        assertEquals(app.getString(R.string.notification_starting), notificationText())
        awaitFirstSync()
        assertEquals(app.getString(R.string.notification_idle), notificationText())

        connect(buds())

        settle("the notification to mention the connected pair") {
            notificationText() != app.getString(R.string.notification_idle)
        }
        assertNotificationReads(R.plurals.notification_active, count = 1)
    }

    @Test
    fun `plugging in and unplugging opens and closes exactly one session`() {
        start()

        connect(buds())
        settle("the session to open") { sessions().isNotEmpty() }

        disconnect(buds())
        settle("the session to close") { sessions().single().disconnectedAt != null }

        assertEquals(1, sessions().size)
    }

    @Test
    fun `outputs that are not headphones never reach the database`() {
        start()

        connect(outputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, productName = "Speaker"))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(sessions().isEmpty())
    }

    @Test
    fun `the minute tick keeps the open session's heartbeat moving`() {
        start()
        connect(buds())
        settle("the session to open") { sessions().isNotEmpty() }
        val before = sessions().single().heartbeatAt

        settleAcrossTicks("the heartbeat to advance") { sessions().single().heartbeatAt > before }

        assertNull("a tick must never close anything", sessions().single().disconnectedAt)
    }

    /**
     * The tick idles while nothing is connected; the open-session flow wakes it, not the audio
     * callback — so a session the manifest Bluetooth receiver opened, unseen by the service, is
     * still kept alive.
     */
    @Test
    fun `a session the service never saw open is heartbeaten all the same`() {
        start()
        runBlocking {
            app.container.repository.onConnected(
                DeviceIdentity("bt:5E:C2", DeviceKind.BLUETOOTH, "ACCENTUM Plus"),
            )
        }
        settle("the session to open") { sessions().isNotEmpty() }
        val before = sessions().single().heartbeatAt

        settleAcrossTicks("the heartbeat to advance") { sessions().single().heartbeatAt > before }
    }

    @Test
    fun `the tick stops with the last session and starts again with the next one`() {
        start()
        connect(buds())
        settle("the session to open") { sessions().isNotEmpty() }
        disconnect(buds())
        settle("the session to close") { sessions().single().disconnectedAt != null }

        // The close reaches the notification a Room round trip after the table, so reading the
        // text right after the session closed reads what the connect had posted.
        settle("the notification to go idle") {
            notificationText() == app.getString(R.string.notification_idle)
        }

        // Nothing connected: a tick would heartbeat an empty table and re-post an unchanged
        // notification — the wasted wakeup this loop avoids.
        val quiet = sessions().single().heartbeatAt
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5 * TICK))
        assertEquals(app.getString(R.string.notification_idle), notificationText())
        assertEquals("a tick ran with nothing connected", quiet, sessions().single().heartbeatAt)

        connect(wired())
        settle("the second session to open") { sessions().size == 2 }
        val before = sessions().last().heartbeatAt

        settleAcrossTicks("the tick to be running again") {
            sessions().last().heartbeatAt > before
        }
    }

    @Test
    fun `time spent playing is credited to the pair that was connected last`() {
        shadowOf(audioManager).setIsMusicActive(true)
        start()
        connect(buds())
        settle("the session to open") { sessions().isNotEmpty() }

        Thread.sleep(50)
        settleAcrossTicks("playback to be credited") { sessions().single().playingMs!! > 0 }

        // Measured playback can never exceed the span it was measured inside.
        val session = sessions().single()
        assertTrue(session.playingMs!! <= app.container.clock.now() - session.connectedAt)
        assertNotificationReads(R.plurals.notification_active_playing, count = 1)
    }

    @Test
    fun `unplugging the pair that was playing stops the playback clock`() {
        shadowOf(audioManager).setIsMusicActive(true)
        start()
        connect(buds())
        settle("the session to open") { sessions().isNotEmpty() }

        Thread.sleep(50)
        settleAcrossTicks("playback to be credited") { sessions().single().playingMs!! > 0 }
        disconnect(buds())
        settle("the session to close") { sessions().single().disconnectedAt != null }
        val credited = settled("what the unplugged pair was credited") {
            sessions().single().playingMs
        }

        // Music is still playing — out loud, now — and none of it belongs to the headphones.
        playFor(50)
        playFor(50)
        assertEquals(credited, sessions().single().playingMs)
    }

    /**
     * A wired plug event reaches only this service — no manifest receiver, no worker — so its own
     * sessions used to land unannounced: app and notification changed, but home-screen widgets
     * kept stale figures until something unrelated redrew them.
     *
     * The redraw is a Glance call into the launcher's process, which nothing here hosts, so this
     * asserts the other half instead — the heartbeat that exists only while a session is open,
     * which a plug event equally wasn't scheduling.
     */
    @Test
    fun `a session the service opened itself is announced like any other`() {
        start()
        assertFalse("nothing is connected yet", heartbeatScheduled())

        connect(wired())
        settle("the wired session to be announced") { heartbeatScheduled() }

        disconnect(wired())
        settle("the heartbeat to be dropped again") { !heartbeatScheduled() }
    }

    @Test
    fun `the service asks to be restarted if the system kills it`() {
        start()

        assertEquals(
            android.app.Service.START_STICKY,
            service.onStartCommand(null, 0, 1),
        )
    }

    /**
     * Plays for a slice only the *next* edge can bank.
     *
     * The watcher measures wall-clock ms, so real time must pass; idling the looper without
     * moving it forward stops a tick from banking the slice first — the point, since the three
     * tests below are each about an edge that used to drop it.
     */
    private fun playUntickedFor(realMillis: Long) {
        Thread.sleep(realMillis)
    }

    /** What the pair holds once the last tick's credit has landed and nothing else is coming. */
    private fun creditedAtLastTick(): Long {
        Thread.sleep(50)
        settleAcrossTicks("playback to be credited") { sessions().first().playingMs!! > 0 }
        return settled("the credited playback") { sessions().first().playingMs!! }
    }

    @Test
    fun `stopping the service banks the playback it had measured`() {
        shadowOf(audioManager).setIsMusicActive(true)
        start()
        connect(buds())
        settle("the session to open") { sessions().isNotEmpty() }
        val atLastTick = creditedAtLastTick()

        // Only onDestroy can bank this slice, and the write it launches must outlive the
        // lifecycle scope that same method cancels — every stop used to launch, suspend on the
        // database, then get cancelled, losing the part-minute.
        playUntickedFor(50)
        stop()

        settle("the last slice to land") { sessions().single().playingMs!! > atLastTick }
        // A destroyed service must not keep listening to the audio stack.
        connect(wired())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, sessions().size)
    }

    /**
     * The watcher's own callback edge — the only credit here nothing waits for. It fires whenever
     * any app changes a player, from a handler rather than a coroutine, banking on the
     * app-lifetime scope instead of the service's. Music stopping alone is enough: no tick, no
     * plug event.
     */
    @Test
    fun `music stopping banks what was played without waiting for a tick`() {
        shadowOf(audioManager).setIsMusicActive(true)
        start()
        connect(buds())
        // The *notification*, not the row. `playbackTargetKey` is assigned after `onConnected`
        // returns, but the row exists earlier — a callback landing in between finds no target and
        // drops its slice, most of the window on a loaded machine. The notification refreshes
        // after the whole loop, proving the assignment happened.
        settle("the connect to be finished with") {
            notificationText() != app.getString(R.string.notification_idle) && sessions().isNotEmpty()
        }

        playUntickedFor(50)
        // Nothing playing anywhere: the callback ends the span and hands back the whole slice,
        // floor or no floor — the branch `MIN_BANKED_SLICE_MS` must never swallow.
        shadowOf(audioManager).setIsMusicActive(false)
        shadowOf(audioManager).setActivePlaybackConfigurationsFor(emptyList(), true)

        settle("the callback's slice to reach the database") {
            sessions().single().playingMs!! > 0
        }
        assertNull("and it is still a live session", sessions().single().disconnectedAt)
    }

    @Test
    fun `unplugging banks the part-minute played since the last tick`() {
        shadowOf(audioManager).setIsMusicActive(true)
        start()
        connect(buds())
        settle("the session to open") { sessions().isNotEmpty() }
        val atLastTick = creditedAtLastTick()

        // Playback is credited to whatever session the pair has *open*, so this slice, banked
        // after the disconnect, used to find nothing to write it to and drop on the floor.
        playUntickedFor(50)
        disconnect(buds())
        settle("the session to close") { sessions().single().disconnectedAt != null }

        val total = settled("the final playback total") { sessions().single().playingMs!! }
        assertTrue("$total is no more than the $atLastTick banked at the last tick", total > atLastTick)
    }

    @Test
    fun `swapping headphones credits what was playing to the pair coming off`() {
        shadowOf(audioManager).setIsMusicActive(true)
        start()
        connect(buds())
        settle("the first session to open") { sessions().isNotEmpty() }
        val atLastTick = creditedAtLastTick()

        // A second pair plugged in without unplugging the first. The slice since the last tick
        // was played on the buds; moving the target used to discard rather than credit it.
        playUntickedFor(50)
        connect(wired())
        settle("the second session to open") { sessions().size == 2 }

        val onBuds = settled("what the pair coming off holds") { sessions().first().playingMs!! }
        assertTrue("$onBuds is no more than the $atLastTick it held at the last tick", onBuds > atLastTick)
        assertEquals("and none of it landed on the pair coming on", 0L, sessions().last().playingMs)
    }

    /**
     * The other half of the swap above, where the target used to be stranded.
     *
     * Unplugging the pair that held it left the target cleared with nothing to take over, so a
     * pair that went nowhere stopped being measured — silently, session still open, connected
     * time still counting. The audio callback reports only *changes* after its first delivery, so
     * the buds stayed unmeasured until disconnected and reconnected.
     */
    @Test
    fun `unplugging one of two pairs leaves the other still measured`() {
        shadowOf(audioManager).setIsMusicActive(true)
        start()
        connect(buds())
        settle("the first session to open") { sessions().isNotEmpty() }

        connect(wired())
        settle("the second session to open") { sessions().size == 2 }
        disconnect(wired())
        settle("the wired session to close") { sessions().count { it.disconnectedAt != null } == 1 }

        // The one still open is the buds', by state rather than index: the two connects can land
        // in the same millisecond, and `getAll` orders by `connectedAt`, making `first()` a coin
        // toss between them.
        val buds = { sessions().single { it.disconnectedAt == null } }
        val onBuds = settled("what the buds hold once the wired pair is gone") { buds().playingMs!! }
        playUntickedFor(50)
        settleAcrossTicks("the buds to go on being credited") { buds().playingMs!! > onBuds }
    }

    /**
     * A device unticked in Settings › devices opens no session, so it has nothing to be credited —
     * taking the playback target anyway left whatever *was* playing unmeasured for as long as it
     * stayed connected. A car stereo — the case the ignore list is for — holds it a whole drive.
     */
    @Test
    fun `a device the user ignores does not take playback off the pair that is playing`() {
        shadowOf(audioManager).setIsMusicActive(true)
        ignore(speaker())
        start()
        connect(buds())
        settle("the session to open") { sessions().isNotEmpty() }
        // Proves the buds are measured before the speaker arrives; without it, this could pass
        // on a service that never credited anything.
        creditedAtLastTick()

        connect(speaker())
        assertEquals(
            "the ignored device records nothing",
            1,
            settled("the session count") { sessions().size },
        )

        // Read *after* the speaker is on, not before: the connect banks the accrued slice to the
        // buds either way, so an earlier baseline is cleared by the edge this test is about, and
        // the assertion would pass without measuring anything.
        val onBuds = settled("what the buds hold once the speaker is on") {
            sessions().single().playingMs!!
        }
        playUntickedFor(50)
        settleAcrossTicks("the buds to go on being credited") {
            sessions().single().playingMs!! > onBuds
        }
    }

    @Test
    fun `a foreground start the platform refuses stops the service rather than waiting to be killed`() {
        // A service that never reaches startForeground is killed with an ANR-shaped crash a few
        // seconds later. Standing down loses detailed tracking until the next sync — the
        // recoverable half.
        shadowOf(service).setThrowInStartForeground(
            IllegalStateException("startForeground not allowed"),
        )

        start(awaitSync = false)

        assertTrue("the service took itself down", shadowOf(service).isStoppedBySelf)
        assertNull("and nothing was ever posted", notificationText())
    }

    private companion object {
        /** One service tick, plus enough to be sure the delayed coroutine has come due. */
        const val TICK = 61_000L
    }
}
