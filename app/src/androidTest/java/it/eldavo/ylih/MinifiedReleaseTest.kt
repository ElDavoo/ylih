package it.eldavo.ylih

import android.content.Context
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import it.eldavo.ylih.data.DeviceIdentity
import it.eldavo.ylih.widget.ActivityWidget
import it.eldavo.ylih.widget.ChartWidget
import it.eldavo.ylih.widget.LifetimeWidget
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.SessionRepository
import it.eldavo.ylih.data.YlihDatabase
import it.eldavo.ylih.export.JsonBackup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The only tests in this project that run the code the user actually gets.
 *
 * `src/test` runs on the JVM against unshrunk classes, so R8 never executes: a class shrinking
 * removed or renamed still passes every unit test and then fails on a device. `testBuildType =
 * "release"` points this source set at the minified, resource-shrunk APK instead, earning it an
 * emulator in CI.
 *
 * The rule for what belongs here: not "an integration test" but a thing true only of the shipped
 * artifact — reached by a name rather than a symbol the compiler could check, which R8 is free
 * to rewrite:
 *
 * - Room loads `YlihDatabase_Impl` reflectively, from a name it derives from the @Database class.
 * - WorkManager instantiates workers from a class name it stored in its own database.
 * - kotlinx.serialization maps JSON keys through a generated descriptor.
 * - A launcher instantiates a home-screen widget's receiver from the name in the manifest.
 * - Compose and the activity are the largest thing R8 rewrites in this app.
 *
 * `.github/scripts/r8-keep-check.py` asserts the same classes survive by reading R8's mapping
 * file. That check is static and runs on every push; this one needs a device and proves the
 * stronger thing — that what survived still works.
 */
@RunWith(AndroidJUnit4::class)
class MinifiedReleaseTest {

    /**
     * Only the permissions that exist on the device this is running on.
     *
     * `BLUETOOTH_CONNECT` arrived in API 31 and `POST_NOTIFICATIONS` in API 33; asking for one
     * that doesn't exist fails the *grant* rather than being ignored, failing every test in the
     * class before a line runs, with "Failed to grant permissions, see logcat". Asking
     * unconditionally was fine while this only ran on API 34; the minSdk leg found this the
     * first time it ran.
     */
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add("android.permission.BLUETOOTH_CONNECT")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add("android.permission.POST_NOTIFICATIONS")
            }
        }.toTypedArray(),
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Room's generated implementation is found by name — `YlihDatabase` + `_Impl` — so shrinking
     * or renaming it fails at the first database access rather than at build time. This opens the
     * real database, not an in-memory one, because the reflective lookup is what's under test.
     */
    @Test
    fun roomOpensAndTheRepositoryRoundTripsASession() = runBlocking {
        val db = YlihDatabase.open(context)
        var now = 1_000_000L
        val repository = SessionRepository(db) { now }
        val identity = DeviceIdentity(
            key = "minified-release-test",
            kind = DeviceKind.BLUETOOTH,
            name = "R8 test pair",
        )

        val pairId = requireNotNull(repository.onConnected(identity)) {
            "onConnected opened no pair"
        }
        try {
            assertEquals(1, repository.openSessionsSnapshot().count { it.pairId == pairId })

            now += 60_000L
            repository.onDisconnected(identity.key, at = now)
            assertNull(repository.openSessionIdFor(identity.key))

            val session = db.sessionDao().getAll().single { it.pairId == pairId }
            assertEquals(60_000L, session.disconnectedAt!! - session.connectedAt)
        } finally {
            // Sessions cascade with the pair. The device row is left behind — the DAO has no
            // single-row delete — which is harmless on a throwaway emulator.
            repository.deletePair(pairId)
        }
    }

    /**
     * WorkManager stores a worker's class name as a string and instantiates it by that name after
     * the process — or app version — has changed. This walks the same path its default
     * `WorkerFactory` walks, so a rename between releases shows up here rather than as heartbeats
     * silently stopping for installs that already had one scheduled.
     */
    @Test
    fun workManagerCanStillReachItsWorkersByName() {
        // The heartbeat, whose loss is a session that runs forever, and the widget rollover,
        // whose loss is a home screen stuck on yesterday's figures.
        for (name in listOf(
            "it.eldavo.ylih.tracking.HeartbeatWorker",
            "it.eldavo.ylih.widget.WidgetRolloverWorker",
        )) {
            val clazz = Class.forName(name)

            assertTrue(
                "$name is no longer a ListenableWorker, so WorkManager could not run it",
                ListenableWorker::class.java.isAssignableFrom(clazz),
            )
            // WorkerFactory reflects for the two-argument constructor; R8 removing it as unused
            // would leave the class present and the worker unrunnable.
            val constructor =
                clazz.getDeclaredConstructor(Context::class.java, WorkerParameters::class.java)
            assertNotNull(constructor)
        }
    }

    /**
     * kotlinx.serialization bakes JSON key names into a generated descriptor at compile time, so
     * obfuscating the Kotlin properties should not move them — why a backup from one release
     * imports into the next. Worth executing "should not" once against the minified build.
     */
    @Test
    fun jsonBackupRoundTripsThroughTheMinifiedSerializers() = runBlocking {
        val db = YlihDatabase.open(context)

        val exported = JsonBackup.export(db, now = 1_700_000_000_000L)
        assertTrue(
            "export produced no formatVersion key: $exported",
            exported.contains("\"formatVersion\""),
        )
        assertTrue("export produced no sessions key", exported.contains("\"sessions\""))

        val before = db.sessionDao().getAll().size
        // import replaces the whole database with the file's contents, so feeding it what was
        // just exported is an identity — safe to run on a device.
        JsonBackup.import(db, exported)
        assertEquals(before, db.sessionDao().getAll().size)
    }

    /**
     * A home-screen widget is reached entirely by name: the launcher instantiates the receiver
     * from the string in the merged manifest, and Glance finds a widget's receiver by
     * instantiating every receiver the manifest names and asking each which `GlanceAppWidget` it
     * hosts. Neither route is a symbol the compiler can check.
     *
     * Only the classes are checked here. The widgets' resources are reached by name too —
     * `previewImage` and `description` are named only from `res/xml/widget_*_info.xml` — but a
     * test APK can't ask about them: `R$drawable` is inlined away in the app under test, so
     * naming a resource from here is a `ClassNotFoundException`, not an assertion.
     * `.github/scripts/r8-keep-check.py` reads the shrinker's verdict on them instead, and runs
     * on both flavors without an emulator.
     */
    @Test
    fun theWidgetReceiversAreStillReachableByName() {
        val hosted = mapOf(
            "it.eldavo.ylih.widget.LifetimeWidgetReceiver" to LifetimeWidget::class.java,
            "it.eldavo.ylih.widget.ActivityWidgetReceiver" to ActivityWidget::class.java,
            "it.eldavo.ylih.widget.ChartWidgetReceiver" to ChartWidget::class.java,
        )
        for ((name, widget) in hosted) {
            val receiver = Class.forName(name).getDeclaredConstructor().newInstance()
            assertTrue(
                "$name is no longer a GlanceAppWidgetReceiver, so the launcher could not run it",
                receiver is GlanceAppWidgetReceiver,
            )
            assertEquals(widget, (receiver as GlanceAppWidgetReceiver).glanceAppWidget.javaClass)
        }
    }

    /**
     * Compose is most of what R8 rewrites here, and a missing keep rule in that area typically
     * surfaces as a crash during the first composition rather than as anything a static check
     * could see.
     */
    @Test
    fun theAppStartsOnTheMinifiedBuild() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
