package it.eldavo.ylih.tracking

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.DeviceIdentity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The service is the only thing in the app that ever sees a wired plug or measures playback, and
 * it is the one component that keeps running while nobody is looking — so what matters is that
 * the audio callbacks reach the database and that the minute tick keeps the session alive.
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
        // Without `specialUse` the Play build needs Bluetooth access before it will run the
        // service at all, and this test is about what the service does, not about that rule.
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        db.deviceDao().deleteAll()
        // The service only ever runs in detailed mode; with the setting off its first sync would
        // close, as untracked, every session its own audio callback had just opened.
        app.container.settings.setDetailedTracking(true)
        shadowOf(audioManager).setOutputDevices(emptyList())
        // `TrackingController.bootAt()` is the wall clock minus SystemClock.elapsedRealtime(), and
        // only the first of those two moves on its own here: Robolectric's elapsed clock starts at
        // zero and advances only when a test idles the looper forward. So the fake phone reads as
        // having booted a moment ago, and that moment creeps forward in real time — a session
        // opened 30 ms before a reconcile looks pre-boot to it, is closed as RECOVERED, and the
        // reconnect grace then refuses to reopen it. On a busy machine that is most of a test.
        // Booting an hour ago puts every session this class opens comfortably after it.
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
     * `delay`. Idling the looper forward before that steps straight over the tick, which is
     * scheduled against the clock as it is when the loop finally gets there.
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

    private fun notificationText(): String? = shadowOf(service).lastForegroundNotification
        ?.extras?.getCharSequence("android.text")?.toString()

    /**
     * The notification carries how long the pair has been connected, and that runs on the wall
     * clock rather than the looper's, so the assertion is on everything around the duration.
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
     * Like [settle], but moving the clock a tick on every round.
     *
     * The tick loop is armed asynchronously: it starts once the open-session flow reports there
     * is something to watch, which is a Room invalidation behind the write that opened the
     * session. A single clock jump that lands before the loop reaches its `delay` would schedule
     * the tick past the moment we had just advanced to, and nothing would ever come due.
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
     * A [settle] returns on the first moment its condition holds, which for anything the service
     * does in more than one step is the middle of the story: unplugging credits the last slice of
     * playback from a coroutine launched behind the one that closed the session, so the session
     * reads as closed a moment before the number this file asserts on is final. Nothing here moves
     * the clock and the watcher only credits on a tick or a callback edge, so a value that has
     * held across several drained rounds is not going to move again.
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
     * [PlaybackWatcher] measures wall-clock milliseconds, which idling the looper does not move —
     * so a slice of real time has to pass before a tick has anything to credit.
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
     * The tick idles while nothing is connected, and what wakes it is the open-session flow
     * rather than this service's own audio callback — so a session the manifest Bluetooth
     * receiver opened, which the service never saw arrive, is still kept alive by it.
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

        // The close reaches the notification a Room round trip after it reaches the table, so
        // reading the text straight after the session closed reads what the connect had posted.
        settle("the notification to go idle") {
            notificationText() == app.getString(R.string.notification_idle)
        }

        // Nothing is connected: a tick here would heartbeat an empty table and re-post an
        // unchanged notification, which is the wakeup a minute this loop exists to avoid.
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

    @Test
    fun `the service asks to be restarted if the system kills it`() {
        start()

        assertEquals(
            android.app.Service.START_STICKY,
            service.onStartCommand(null, 0, 1),
        )
    }

    @Test
    fun `stopping the service banks the playback it had measured`() {
        shadowOf(audioManager).setIsMusicActive(true)
        start()
        connect(buds())
        settle("the session to open") { sessions().isNotEmpty() }
        Thread.sleep(50)
        settleAcrossTicks("playback to be credited") { sessions().single().playingMs!! > 0 }

        stop()
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(sessions().single().playingMs)
        // A destroyed service must not keep listening to the audio stack.
        connect(wired())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, sessions().size)
    }

    @Test
    fun `a foreground start the platform refuses stops the service rather than waiting to be killed`() {
        // A service that never reaches startForeground is killed with an ANR-shaped crash a few
        // seconds later. Standing down deliberately loses detailed tracking until the next sync,
        // which is the recoverable half of a bad situation.
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
