package it.eldavo.ylih

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.ContextCompat
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
 * Everything under `src/test` runs on the JVM against unshrunk classes, so R8 never executes for
 * it: a class that shrinking removed or renamed still passes every unit test in the build and
 * then fails on a device. `testBuildType = "release"` points this source set at the minified,
 * resource-shrunk APK instead, which is why these tests earn an emulator in CI.
 *
 * So the rule for what belongs here is narrow. Not "an integration test" — a thing that is
 * *only* true of the shipped artifact. Everything below is reached by a name rather than by a
 * symbol the compiler could check, which is exactly what R8 is free to rewrite:
 *
 * - Room loads `YlihDatabase_Impl` reflectively, from a name it derives from the @Database class.
 * - WorkManager instantiates workers from a class name it stored in its own database.
 * - kotlinx.serialization maps JSON keys through a generated descriptor.
 * - Compose and the activity are simply the largest thing R8 rewrites in this app.
 *
 * `.github/scripts/r8-keep-check.py` asserts the same classes survive by reading R8's mapping
 * file. That check is static and runs on every push; this one needs a device and proves the
 * stronger thing — that what survived still works.
 */
@RunWith(AndroidJUnit4::class)
class MinifiedReleaseTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.POST_NOTIFICATIONS",
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Room's generated implementation is found by name — `YlihDatabase` + `_Impl` — so shrinking
     * it away or renaming it fails at the first database access rather than at build time. This
     * opens the real database the app opens, not an in-memory one, because the reflective lookup
     * is the thing under test.
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
     * WorkManager stores a worker's class name as a string in its own database and instantiates
     * it by that name after the process — or the app version — has changed. This walks the same
     * path its default `WorkerFactory` walks, so a rename between releases shows up here instead
     * of as heartbeats that silently stop for installs that already had one scheduled.
     */
    @Test
    fun workManagerCanStillReachTheHeartbeatWorkerByName() {
        val name = "it.eldavo.ylih.tracking.HeartbeatWorker"
        val clazz = Class.forName(name)

        assertTrue(
            "$name is no longer a ListenableWorker, so WorkManager could not run it",
            ListenableWorker::class.java.isAssignableFrom(clazz),
        )
        // The two-argument constructor is what WorkerFactory reflects for; R8 removing it as
        // unused would leave the class present and the worker still unrunnable.
        val constructor = clazz.getDeclaredConstructor(Context::class.java, WorkerParameters::class.java)
        assertNotNull(constructor)
    }

    /**
     * kotlinx.serialization bakes the JSON key names into a generated descriptor at compile time,
     * so obfuscating the Kotlin properties should not move them — which is the whole reason a
     * backup taken from one release imports into the next. "Should not" is worth executing once
     * against the minified build.
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
        // import replaces the whole database with the file's contents; feeding it what was just
        // exported is therefore an identity, which is what makes this safe to run on a device.
        JsonBackup.import(db, exported)
        assertEquals(before, db.sessionDao().getAll().size)
    }

    /**
     * A home-screen widget is reached entirely by name: the launcher instantiates the receiver
     * from the string in the merged manifest, and Glance finds a widget's receiver by
     * instantiating every receiver the manifest names and asking each which `GlanceAppWidget` it
     * hosts. Neither route is a symbol the compiler can check.
     *
     * The resources are the other half, and the half a keep rule would not cover: `previewImage`
     * and `description` are referenced only from `res/xml/widget_*_info.xml`, which is exactly the
     * shape of reference the *resource* shrinker is free to decide nothing uses. A stripped
     * preview is not a crash — it is a blank tile in the widget picker.
     */
    @Test
    fun theWidgetsAreStillReachableByNameAndKeptTheirResources() {
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

        for (preview in listOf(
            R.drawable.widget_preview_lifetime,
            R.drawable.widget_preview_activity,
            R.drawable.widget_preview_chart,
        )) {
            assertNotNull("the widget picker preview was shrunk away", ContextCompat.getDrawable(context, preview))
        }
        // Throws Resources.NotFoundException if the shrinker decided nothing pointed at it.
        for (info in listOf(R.xml.widget_lifetime_info, R.xml.widget_activity_info, R.xml.widget_chart_info)) {
            context.resources.getXml(info).close()
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
