package it.eldavo.ylih.tracking

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.data.SessionRepository
import it.eldavo.ylih.data.SettingsStore
import it.eldavo.ylih.data.YlihDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What happens when the platform refuses to start the service at all. Separate from
 * [TrackingControllerTest] because it runs on a different SDK level, and mixing the two inside one
 * class puts two Robolectric sandboxes — and so two WorkManager singletons — behind test methods
 * that share a class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class TrackingControllerForegroundStartTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private lateinit var db: YlihDatabase
    private lateinit var repository: SessionRepository
    private lateinit var settings: SettingsStore

    private val clockNow = 1_800_000_000_000L

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, YlihDatabase::class.java).build()
        repository = SessionRepository(db) { clockNow }
        settings = SettingsStore(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A context whose foreground starts always fail, the way the platform's do. */
    private fun controllerThatCannotStart(failure: Exception) = TrackingController(
        object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName = throw failure
        },
        repository,
        settings,
    ) { clockNow }

    @Test
    fun `a foreground start the system refuses is deferred rather than crashed`() {
        // Android 12+ refuses background foreground-service starts outside a handful of allowed
        // windows, and a manifest receiver waking on a connect is exactly that case. Losing the
        // service until the next sync is recoverable; taking the receiver down with it is not.
        val controller = controllerThatCannotStart(
            ForegroundServiceStartNotAllowedException("not allowed"),
        )

        controller.startService()

        assertNull("nothing started, and nothing was thrown either", shadowOf(context).nextStartedService)
    }

    @Test
    fun `a foreground start that fails for any other reason is not swallowed`() {
        // Only the documented refusal is survivable; anything else is a real bug, and hiding it
        // would leave detailed tracking quietly dead.
        val controller = controllerThatCannotStart(SecurityException("denied"))

        assertThrows(SecurityException::class.java) { controller.startService() }
    }

    @Test
    fun `a start the system allows reaches the tracking service`() = runBlocking {
        val controller = TrackingController(context, repository, settings) { clockNow }

        controller.startService()

        assertNull(
            "the intent names the service and nothing else",
            shadowOf(context).nextStartedService.component?.className
                ?.takeIf { it != TrackingService::class.java.name },
        )
    }
}
