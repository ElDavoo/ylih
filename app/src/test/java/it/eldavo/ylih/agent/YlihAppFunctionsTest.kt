package it.eldavo.ylih.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.AppContainer
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.PairEntity
import it.eldavo.ylih.data.SessionEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.time.LocalDate
import java.time.ZoneId

/**
 * What this app promises an on-device agent, read back out of the build rather than off the
 * source.
 *
 * The functions themselves cannot be called from here: the generated service extends a platform
 * class that only exists on Android 17, and the unit suite runs against an older framework — so a
 * test that instantiated it would fail on the class loader rather than on anything this
 * repository wrote. What *is* reachable is everything the OS reads before it ever binds the
 * service, and that is where the decisions live: the schema KSP wrote into `assets/`, the
 * `<service>` element that points at it, and the app-level metadata beside it. The one that
 * matters most is `enabledByDefault`, because it is the difference between an opt-in and a
 * gesture — shipped `true`, every function would be callable in the window between install and
 * the user first opening settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class YlihAppFunctionsTest {

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val context: Context get() = app

    // Fixed zone and wall clock, as in WidgetDataTest: every window here is bucketed by local
    // midnight, so a test borrowing the machine's zone would pass or fail depending on where it
    // ran.
    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private val clockNow: Long =
        LocalDate.of(2026, 3, 18).atTime(15, 0).atZone(zone).toInstant().toEpochMilli()

    private lateinit var container: AppContainer

    @Before
    fun setUp() = runTest {
        container = AppContainer(app) { clockNow }
        container.database.deviceDao().deleteAll()
        container.settings.setPlaybackOnly(false)
    }

    /**
     * The schema KSP wrote, off the classpath rather than off `AssetManager`.
     *
     * The processor emits it as a *java* resource whose path happens to start `assets/`, which is
     * what puts it under `assets/` in the packaged APK and so in front of the platform's asset
     * manager on a device. Robolectric's asset manager reads the merged `src/main/assets` tree
     * instead, which the generated file never passes through — so the classloader is the one view
     * of it a unit test has.
     */
    private val schema: String by lazy {
        checkNotNull(javaClass.classLoader?.getResourceAsStream(SCHEMA_ASSET)) {
            "$SCHEMA_ASSET is not on the classpath: KSP did not write the app function schema"
        }.use { it.readBytes().decodeToString() }
    }

    @Test
    fun `the hours an agent is handed are the ones the app itself would show`() = runTest {
        // The point of routing both functions through widgetDataFlow rather than opening a third
        // query path: an agent quoting a different number from the screen is worse than an agent
        // that cannot answer. The expectations here are WidgetDataTest's own.
        val veteran = seedPair("Sennheiser HD 25")
        seedSession(veteran, from = clockNow - 200 * DAY, to = clockNow - 200 * DAY + 100 * HOUR)
        val newcomer = seedPair("Galaxy Buds3 Pro")
        seedSession(newcomer, from = clockNow - 2 * HOUR, to = null)

        val hours = headphoneHours(container, zone)

        assertEquals(
            "the pair being worn right now leads, however few hours it has",
            listOf("Galaxy Buds3 Pro", "Sennheiser HD 25"),
            hours.pairs.map { it.name },
        )
        assertEquals(listOf(true, false), hours.pairs.map { it.inUseNow })
        assertEquals("and it is counted live", 2.0, hours.pairs.first().hours, 0.0)
        assertEquals(100.0, hours.pairs.last().hours, 0.0)
        assertEquals(102.0, hours.lifetimeHours, 0.0)

        val totals = listeningTotals(container, zone)

        assertEquals("only the open session is inside today", 2.0, totals.todayHours, 0.0)
        assertEquals(2.0, totals.lastSevenDaysHours, 0.0)
        assertEquals(
            "the 200-day-old session is outside every window",
            2.0,
            totals.lastThirtyDaysHours,
            0.0,
        )
        assertEquals("but not outside the lifetime total", 102.0, totals.lifetimeHours, 0.0)
        assertEquals("connected time until the user says otherwise", false, totals.playbackOnly)
    }

    @Test
    fun `playback-only mode reaches the agent too`() = runTest {
        // The app, the notification and the widgets all honour it; an agent quoting connected
        // hours while every screen showed playback hours would be the worst of both.
        val pair = seedPair("Galaxy Buds3 Pro")
        seedSession(pair, from = clockNow - 10 * HOUR, to = clockNow, playingMs = 3 * HOUR)
        container.settings.setPlaybackOnly(true)

        assertEquals(true, listeningTotals(container, zone).playbackOnly)
        assertEquals(3.0, headphoneHours(container, zone).pairs.single().hours, 0.0)
    }

    @Test
    fun `every function ships disabled`() {
        // One <enabledByDefault> per function, and every one of them false. Asserted as a count
        // rather than by name so that adding a function without thinking about this fails here.
        val flags = ENABLED_BY_DEFAULT.findAll(schema).map { it.groupValues[1] }.toList()
        assertEquals("one enabledByDefault per function", 2, flags.size)
        assertEquals(
            "off is what makes the settings switch an opt-in rather than a gesture",
            listOf("false", "false"),
            flags,
        )
    }

    @Test
    fun `the functions an agent can see are the read-only ones`() {
        val ids = FUNCTION_ID.findAll(schema).map { it.groupValues[1] }.distinct().toList()
        assertEquals(
            listOf(
                "it.eldavo.ylih.agent.YlihAppFunctions#getHeadphoneHours",
                "it.eldavo.ylih.agent.YlihAppFunctions#getListeningTotals",
            ),
            ids.sorted(),
        )
    }

    @Test
    fun `the KDoc is what the agent is told`() {
        // isDescribedByKDoc = true is the whole reason those comments are written for a caller
        // who cannot see the code. If it were ever dropped the build would still pass, the
        // functions would still work, and an agent would be handed two names and no description.
        val descriptions = DESCRIPTION.findAll(schema).map { it.groupValues[1] }.toList()
        assertTrue("a description per function, at least", descriptions.size >= 2)
        assertTrue(
            "the lifetime function explains what a lifetime figure is",
            descriptions.any { it.contains("lifetime figure") },
        )
        assertTrue(
            "the totals function says whose midnight the day buckets use",
            descriptions.any { it.contains("local midnight") },
        )
    }

    @Test
    fun `the manifest points the platform at the generated service and the schema`() {
        // Read out of the merged manifest rather than written down again here: the service class
        // does not exist until KSP has run, and its name is the only thing connecting the two.
        val service = context.packageManager
            // MATCH_DISABLED_COMPONENTS because it *is* disabled here: the element carries the
            // library's `enablePlatformAppFunctionService` boolean, which is false below
            // Android 17 precisely so that an OS with no app functions never binds a class it
            // cannot even load.
            .getPackageInfo(
                context.packageName,
                PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            .services
            .orEmpty()
            .single { it.name == "it.eldavo.ylih.agent.YlihAppFunctionService" }

        assertEquals(
            "the system is the caller, so it has to be able to bind it",
            "android.permission.BIND_APP_FUNCTION_SERVICE",
            service.permission,
        )
        assertTrue("bound from outside the app", service.exported)
    }

    @Test
    fun `the app-level metadata says the same thing to the agent and to the user`() {
        // What an agent is told before it looks at any one function. The user-visible half is a
        // resource on purpose and is the settings row's own sentence: the promise made by the
        // switch and the promise shown by whatever asks for it have to be one promise.
        val parser = context.resources.getXml(R.xml.app_metadata)
        while (parser.eventType != XmlPullParser.START_TAG) parser.next()

        val description = parser.getAttributeValue(RES_AUTO, "description")
        assertNotNull("the model is told what these functions are for", description)
        assertTrue("and that they only read", description!!.contains("read-only"))

        assertEquals(
            R.string.settings_agent_body,
            parser.getAttributeResourceValue(RES_AUTO, "displayDescription", 0),
        )
    }

    @Test
    fun `pushing the switch below android 17 changes nothing and throws nothing`() {
        // The platform has no app functions here, so there is no id to enable and the stored row
        // is simply waiting for an OS that has one. Silence is the correct behaviour; a throw
        // would surface as a snackbar on a settings screen the user cannot do anything about.
        runBlocking {
            pushAgentAccess(context, enabled = true)
            pushAgentAccess(context, enabled = false)
        }
    }

    private fun seedPair(label: String): Long = runBlocking {
        val deviceId = container.database.deviceDao().insert(
            DeviceEntity(
                deviceKey = "bt:${label.hashCode() and 0xFF}",
                kind = DeviceKind.BLUETOOTH,
                defaultName = label,
                firstSeenAt = clockNow - 400 * DAY,
            ),
        )
        container.database.pairDao().insert(
            PairEntity(
                deviceId = deviceId,
                label = label,
                generation = 1,
                startedAt = clockNow - 400 * DAY,
            ),
        )
    }

    private fun seedSession(pairId: Long, from: Long, to: Long?, playingMs: Long? = null) =
        runBlocking {
            container.database.sessionDao().insert(
                SessionEntity(
                    pairId = pairId,
                    connectedAt = from,
                    disconnectedAt = to,
                    heartbeatAt = to ?: from,
                    playingMs = playingMs,
                ),
            )
        }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR

        const val SCHEMA_ASSET = "assets/ylih_app_functions.xml"

        /** Where the library looks for its own attributes when Gradle merged the resources. */
        const val RES_AUTO = "http://schemas.android.com/apk/res-auto"

        val ENABLED_BY_DEFAULT = Regex("<enabledByDefault>(\\w+)</enabledByDefault>")
        val FUNCTION_ID = Regex("<functionId>([^<]+)</functionId>")
        val DESCRIPTION = Regex("<description>([^<]*)</description>", RegexOption.DOT_MATCHES_ALL)
    }
}
