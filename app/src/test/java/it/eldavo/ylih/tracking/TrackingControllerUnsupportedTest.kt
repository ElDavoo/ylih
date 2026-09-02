package it.eldavo.ylih.tracking

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.Worker
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.Distribution
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.SessionRepository
import it.eldavo.ylih.data.SettingsStore
import it.eldavo.ylih.data.YlihDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A build where detailed tracking cannot run at all, which since the floor moved to Android 8 is
 * one route only: the Play flavor on Android 14+ with `BLUETOOTH_CONNECT` denied. The
 * `connectedDevice` service type requires a Bluetooth permission there and the flavor declares no
 * `specialUse` type to fall back on, so the install gets Bluetooth-only tracking no matter what
 * the setting says. The classic flavor declares both and is never blocked, which is why the tests
 * that need the unavailable state assume their way past it there.
 *
 * Separate from [TrackingControllerTest] for the reason [TrackingControllerForegroundStartTest]
 * gives: one SDK level per class, or two Robolectric sandboxes end up behind methods that share
 * a WorkManager singleton.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class TrackingControllerUnsupportedTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private lateinit var db: YlihDatabase
    private lateinit var repository: SessionRepository
    private lateinit var settings: SettingsStore
    private lateinit var controller: TrackingController

    private val hour = 3_600_000L
    private var clockNow = 1_800_000_000_000L

    @Before
    fun setUp() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(InertWorkers).build(),
            WorkManagerTestInitHelper.ExecutorsMode.LEGACY_OVERRIDE_WITH_SYNCHRONOUS_EXECUTORS,
        )
        db = Room.inMemoryDatabaseBuilder(context, YlihDatabase::class.java).build()
        repository = SessionRepository(db) { clockNow }
        settings = SettingsStore(context)
        controller = TrackingController(context, repository, settings) { clockNow }
        // The DataStore behind this is a process singleton, so a setting written by another
        // class in the same JVM is still there.
        settings.setDetailedTracking(false)
        // Denied, which is the one route left to the unavailable state: on Android 14+ the
        // connectedDevice service type requires a Bluetooth permission, and the play flavor
        // declares no specialUse type to fall back on. The classic build is never blocked,
        // which is what the assumption in each test is about.
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun connect(vararg devices: AudioDeviceInfo) {
        shadowOf(audioManager).setOutputDevices(devices.toList())
    }

    private fun wired() =
        outputDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, productName = "Plugged in")

    @Test
    fun `without bluetooth access there is no service to offer`() {
        assumeFalse("only the play build can be blocked here", Distribution.HAS_SPECIAL_USE_FGS)

        assertFalse(controller.detailedTrackingSupported())
    }

    @Test
    fun `asking for detailed tracking here is refused rather than half-applied`() = runTest {
        assumeFalse("only the play build can be blocked here", Distribution.HAS_SPECIAL_USE_FGS)

        assertFalse(controller.setDetailedTracking(enabled = true))

        assertFalse("nothing was written", settings.detailedTrackingNow())
        assertNull("and nothing was started", shadowOf(context).nextStartedService)
    }

    @Test
    fun `a setting that arrived from a newer phone falls back instead of counting forever`() =
        runTest {
            assumeFalse("only the play build can be blocked here", Distribution.HAS_SPECIAL_USE_FGS)
            // Preferences are restored across devices by Android's own backup, so the flag can
            // outlive the install that could act on it. Nothing here can observe a wired plug,
            // and an open wired session nobody is watching would count until the end of time.
            connect(wired())
            repository.onConnected(
                AudioDevices.identityOf(wired())!!,
                at = clockNow,
                measurePlayback = true,
            )
            settings.setDetailedTracking(true)

            // Nothing runs between these two lines: no service, no heartbeat, no process even.
            // The hour is exactly the gap this session was unwatched for.
            val lastProof = clockNow
            clockNow += hour
            controller.syncWithSystem()

            assertEquals(
                "closed where we last had proof, not stretched over the gap",
                listOf(lastProof),
                db.sessionDao().getAll().map { it.disconnectedAt },
            )
            assertNull("the service was never asked for", shadowOf(context).nextStartedService)
            settings.setDetailedTracking(false)
        }

    @Test
    fun `bluetooth-only tracking still works without the permission`() = runTest {
        connect(outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, productName = "ACCENTUM Plus"))

        controller.syncWithSystem()

        val session = db.sessionDao().getAll().single()
        assertEquals(clockNow, session.connectedAt)
        assertNull("still connected", session.disconnectedAt)
        assertNull("only the service measures playback", session.playingMs)
        assertEquals(
            listOf(DeviceKind.BLUETOOTH),
            db.deviceDao().getAll().map { it.kind },
        )
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
