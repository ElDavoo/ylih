package it.eldavo.ylih.tracking

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.Distribution
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.EndReason
import it.eldavo.ylih.data.SessionRepository
import it.eldavo.ylih.data.SettingsStore
import it.eldavo.ylih.data.YlihDatabase
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The policy layer: which of the two tracking modes is actually running, and whether the
 * database still agrees with what is plugged in. `syncWithSystem()` is called from five places
 * and is the app's only repair mechanism, so what it does to a database that has drifted is
 * the interesting part.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class TrackingControllerTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private lateinit var db: YlihDatabase
    private lateinit var repository: SessionRepository
    private lateinit var settings: SettingsStore
    private lateinit var controller: TrackingController

    private val hour = 3_600_000L
    private var clockNow = 1_800_000_000_000L

    /** Stands in for the Glance `updateAll` the app wires in here. */
    private var dataChanges = 0

    private val budsMac = "XX:XX:XX:XX:5E:C2"
    private val budsName = "ACCENTUM Plus"

    @Before
    fun setUp() = runBlocking {
        // The heartbeat is enqueued and cancelled on WorkManager's own threads, so reading the
        // unique work's state straight after asking for a change otherwise races the worker that
        // the enqueue starts — which showed up as this class passing or failing on what ran
        // before it in the same JVM.
        //
        // Synchronous executors are not enough on their own: the test scheduler starts the
        // heartbeat the moment it is enqueued, and the real HeartbeatWorker is a CoroutineWorker,
        // so its body runs on Dispatchers.Default whatever executor is installed — against the
        // real container rather than this test's database. CI caught that work reaching a
        // terminal state before cancelUniqueWork got to it, which made the cancel a no-op and
        // left the work reading as still scheduled. What this class asserts is which work the
        // controller schedules, never what the worker does (ReceiversTest covers that), so the
        // worker is replaced by a plain one that finishes inline on the synchronous executor.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(InertWorkers).build(),
            WorkManagerTestInitHelper.ExecutorsMode.LEGACY_OVERRIDE_WITH_SYNCHRONOUS_EXECUTORS,
        )
        db = Room.inMemoryDatabaseBuilder(context, YlihDatabase::class.java).build()
        repository = SessionRepository(db) { clockNow }
        settings = SettingsStore(context)
        dataChanges = 0
        controller = TrackingController(
            context,
            repository,
            settings,
            onDataChanged = { dataChanges++ },
        ) { clockNow }
        // The preferences file is a real one and the DataStore behind it is a process
        // singleton, so a setting written by one test method is still there in the next.
        settings.setDetailedTracking(false)
    }

    /**
     * Moves both clocks. Advancing wall time on its own looks exactly like a reboot to
     * [TrackingController.bootAt], which would make every open session pre-date the boot.
     */
    private fun advance(millis: Long) {
        clockNow += millis
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun connect(vararg devices: AudioDeviceInfo) {
        shadowOf(audioManager).setOutputDevices(devices.toList())
    }

    private fun buds() = outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, budsMac, budsName)

    private fun wired() =
        outputDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, productName = "Plugged in")

    private fun heartbeatScheduled(): Boolean =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(HeartbeatWorker.NAME)
            .get()
            .any { it.state != WorkInfo.State.CANCELLED }

    private fun startedService() = shadowOf(context).nextStartedService

    /** Detailed tracking needs Bluetooth access on Android 14+ unless the flavor has specialUse. */
    private fun enableDetailedTracking() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    }

    @Test
    fun `the boot instant is where the elapsed-realtime clock started`() {
        // Sessions are never counted across a boot, so this is what decides whether an open
        // session predates the phone being switched on.
        assertEquals(clockNow - SystemClock.elapsedRealtime(), controller.bootAt())
    }

    @Test
    fun `detailed tracking needs bluetooth access only where the flavor gave up specialUse`() {
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals(Distribution.HAS_SPECIAL_USE_FGS, controller.detailedTrackingSupported())

        enableDetailedTracking()
        assertTrue(controller.detailedTrackingSupported())
    }

    @Test
    fun `syncing opens a session for headphones that were connected while we were not looking`() =
        runTest {
            connect(buds())

            controller.syncWithSystem()

            val session = db.sessionDao().getAll().single()
            assertEquals(clockNow, session.connectedAt)
            assertNull("still connected", session.disconnectedAt)
            assertNull("bluetooth-only mode runs no service", startedService())
            assertTrue("something is open, so the safety net is armed", heartbeatScheduled())
        }

    @Test
    fun `syncing closes a session where we last had proof it was alive`() = runTest {
        connect(buds())
        controller.syncWithSystem()

        advance(hour)
        controller.syncWithSystem()
        val lastProof = clockNow

        // Whatever happened in the following hour, we were not watching, so it is not listening
        // time — the session is closed back at the last heartbeat rather than stretched to now.
        advance(hour)
        connect()
        controller.syncWithSystem()

        assertEquals(lastProof, db.sessionDao().getAll().single().disconnectedAt)
        assertFalse("nothing left to watch", heartbeatScheduled())
    }

    @Test
    fun `bluetooth-only mode does not see wired headphones at all`() = runTest {
        connect(wired(), buds())

        controller.syncWithSystem()

        assertEquals(
            listOf("bt:5E:C2"),
            db.deviceDao().getAll().map { it.deviceKey },
        )
    }

    @Test
    fun `detailed tracking sees the wired pair and keeps the safety net behind the service`() =
        runTest {
            enableDetailedTracking()
            connect(wired(), buds())

            assertTrue(controller.setDetailedTracking(enabled = true))

            assertEquals(
                setOf("wired:headphones", "bt:5E:C2"),
                db.deviceDao().getAll().map { it.deviceKey }.toSet(),
            )
            assertNotNull("the foreground service does the watching now", startedService())
            // The service is the better watcher, not a guaranteed one: an OEM battery manager
            // that kills it must not leave this mode with nothing at all behind it.
            assertTrue("the heartbeat backs up the service too", heartbeatScheduled())
            // Only the service can measure playback, so only it opts sessions into it.
            assertTrue(db.sessionDao().getAll().all { it.playingMs != null })
        }

    @Test
    fun `turning detailed tracking off closes what only the service could see`() = runTest {
        enableDetailedTracking()
        connect(wired(), buds())
        controller.setDetailedTracking(enabled = true)

        advance(hour)
        controller.setDetailedTracking(enabled = false)

        val byKey = db.pairDao().getAll().associateBy { it.id }
        val open = db.sessionDao().getAll().filter { it.disconnectedAt == null }
        assertEquals(
            "the wired pair cannot be observed any more, so it must not stay open",
            listOf(DeviceKind.BLUETOOTH),
            open.map { session ->
                val pair = byKey.getValue(session.pairId)
                db.deviceDao().byId(pair.deviceId)!!.kind
            },
        )
        assertFalse(settings.detailedTrackingNow())
    }

    @Test
    fun `a build that cannot run the service refuses to pretend otherwise`() = runTest {
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        val accepted = controller.setDetailedTracking(enabled = true)

        assertEquals(Distribution.HAS_SPECIAL_USE_FGS, accepted)
        assertEquals(Distribution.HAS_SPECIAL_USE_FGS, settings.detailedTrackingNow())
    }

    @Test
    fun `losing bluetooth access mid-flight retires the wired sessions rather than stretching them`() =
        runTest {
            // Only reachable on a build without specialUse: the setting stays on but the
            // service can no longer legally start, so wired sessions would run forever.
            if (Distribution.HAS_SPECIAL_USE_FGS) return@runTest

            enableDetailedTracking()
            connect(wired())
            controller.setDetailedTracking(enabled = true)
            val lastProof = clockNow

            // The permission went, and the service went with it — probably along with the whole
            // process, and long before we were woken up to notice. The hour in between is not
            // listening time, so the session ends back where we last had evidence of it.
            advance(hour)
            shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            controller.syncWithSystem()

            val session = db.sessionDao().getAll().single()
            assertEquals(lastProof, session.disconnectedAt)
            assertEquals(EndReason.RECOVERED, session.endReason)
        }

    @Test
    fun `an opened session arms the safety net in either mode`() = runTest {
        connect(buds())
        repository.onConnected(AudioDevices.identityOf(buds())!!, at = clockNow)

        controller.onSessionOpened(detailedTracking = false)
        assertTrue("bluetooth-only mode arms the heartbeat", heartbeatScheduled())
        assertNull("bluetooth-only mode starts nothing", startedService())

        controller.onSessionOpened(detailedTracking = true)
        assertTrue("the service adds to the heartbeat, it does not replace it", heartbeatScheduled())
        assertNotNull("detailed mode starts the service", startedService())
    }

    @Test
    fun `closing the last session disarms the safety net`() = runTest {
        val identity = AudioDevices.identityOf(buds())!!
        repository.onConnected(identity, at = clockNow)
        controller.onSessionOpened(detailedTracking = false)

        repository.onDisconnected(identity.key, at = clockNow + hour)
        controller.onSessionClosed()

        assertFalse(heartbeatScheduled())
    }

    @Test
    fun `stopping the service asks the system to stop exactly that service`() {
        controller.stopService()

        assertEquals(
            TrackingService::class.java.name,
            shadowOf(context).nextStoppedService.component?.className,
        )
    }

    @Test
    fun `every entry point tells the home screen something changed`() = runTest {
        // All four background write sources converge here, so this is the app's only chance to
        // notice. Miss one and a widget sits on a stale figure until something else happens to
        // redraw it — which, with updatePeriodMillis at 0, may be never.
        controller.syncWithSystem()
        assertEquals("the repair path, called from boot, onStart, the worker and the service", 1, dataChanges)

        controller.onSessionOpened(detailedTracking = false)
        assertEquals(2, dataChanges)

        controller.onSessionClosed()
        assertEquals(3, dataChanges)
    }

    /** Stands in for whatever the controller schedules, so only the scheduling is under test. */
    private object InertWorkers : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker =
            object : Worker(appContext, workerParameters) {
                override fun doWork(): Result = Result.success()
            }
    }
}
