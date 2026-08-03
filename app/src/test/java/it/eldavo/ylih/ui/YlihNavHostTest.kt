package it.eldavo.ylih.ui

import android.Manifest
import android.os.Build
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.PairEntity
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.ui.theme.YlihTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.OutputStream

/**
 * The shell that puts the three tabs and the pair page in front of the user. What it owns that
 * the screens do not is the back stack, and the one thing that can go wrong there is a route
 * argument: `pair/{pairId}` is a string until something parses it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class YlihNavHostTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val db get() = app.container.database

    private lateinit var nav: NavHostController
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var viewModel: YlihViewModel

    @Before
    fun setUp() = runBlocking {
        // The settings tab reaches TrackingController, which schedules or cancels the heartbeat.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        db.deviceDao().deleteAll()
        // The welcome sits above the whole app, tabs included, and would swallow every tap.
        app.container.settings.setOnboardingDone(true)
    }

    private fun show() {
        // Built here rather than left to `viewModel()`: without an activity the store owner is
        // process-wide, so the second test in this class would inherit the first one's view model
        // — and with it Room flows still bound to the database instance that test had.
        viewModel = YlihViewModel(app)
        compose.setContent {
            nav = rememberNavController()
            // The system back button reaches the app through this, and the tabs put a handler of
            // their own on it. Held so a test can press back without an activity to press it on.
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            YlihTheme { YlihNavHost(viewModel = viewModel, navController = nav) }
        }
        compose.waitUntil(timeoutMillis = 10_000) { route() == TABS_ROUTE }
    }

    private fun route(): String? = nav.currentBackStackEntry?.destination?.route

    private fun text(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun nodeCount(value: String): Int =
        compose.onAllNodesWithText(value).fetchSemanticsNodes().size

    private fun tap(label: Int) {
        compose.onNodeWithText(text(label)).performClick()
    }

    /**
     * Waits for a string only one of the three tabs draws. Which tab is showing is not a route any
     * more — the tabs are pages of a pager — so what the user can see is the only honest answer,
     * and a page the pager has scrolled away from is not composed at all.
     */
    private fun awaitTab(@StringRes title: Int) {
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(text(title)) > 0 }
    }

    /**
     * Drags the pager. It is found by the one thing on screen that scrolls sideways — the lists
     * inside the tabs all scroll vertically — rather than by the root, which a swipe would hand to
     * whichever composition happened to be on top.
     */
    private fun swipe(direction: TouchInjectionScope.() -> Unit) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .performTouchInput(direction)
    }

    private fun seedPair(label: String): Long = runBlocking {
        val deviceId = db.deviceDao().insert(
            DeviceEntity(
                deviceKey = "bt:5E:C2",
                kind = DeviceKind.BLUETOOTH,
                defaultName = label,
                firstSeenAt = 1_700_000_000_000L,
            ),
        )
        val pairId = db.pairDao().insert(
            PairEntity(
                deviceId = deviceId,
                label = label,
                generation = 1,
                startedAt = 1_700_000_000_000L,
            ),
        )
        db.sessionDao().insert(
            SessionEntity(
                pairId = pairId,
                connectedAt = 1_700_000_000_000L,
                disconnectedAt = 1_700_003_600_000L,
                heartbeatAt = 1_700_003_600_000L,
            ),
        )
        pairId
    }

    @Test
    fun `each tab leads somewhere and the app bar stays put across them`() {
        show()

        tap(R.string.nav_stats)
        awaitTab(R.string.stats_title)
        tap(R.string.nav_settings)
        awaitTab(R.string.settings_detailed_title)
        tap(R.string.nav_headphones)
        awaitTab(R.string.devices_empty_title)

        // The three tabs share one app bar; only the pair page brings its own.
        compose.onNodeWithText(text(R.string.app_title)).assertExists()
        // And the tabs are all one destination, so none of that touched the back stack.
        assertEquals(TABS_ROUTE, route())
    }

    @Test
    fun `the tabs are a swipe apart in both directions`() {
        // The reason they are a pager at all. A tap could be served by anything; only pages laid
        // out side by side can be dragged between, which is what the nav bar cannot offer.
        show()

        swipe { swipeLeft() }
        awaitTab(R.string.stats_title)
        swipe { swipeLeft() }
        awaitTab(R.string.settings_detailed_title)

        swipe { swipeRight() }
        awaitTab(R.string.stats_title)

        assertEquals("and none of it is a navigation", TABS_ROUTE, route())
    }

    @Test
    fun `back off a tab returns to the first one rather than leaving the app`() {
        // What the back stack used to do for free: every tab was navigated to with popUpTo(start),
        // which left the headphones tab underneath. A pager has no stack, so this is by hand.
        show()

        tap(R.string.nav_settings)
        awaitTab(R.string.settings_detailed_title)

        compose.runOnUiThread { back.onBackPressed() }
        awaitTab(R.string.devices_empty_title)
    }

    @Test
    fun `opening a pair swaps the app bar for its own, and back brings it back`() {
        val label = "ACCENTUM Plus"
        seedPair(label)
        show()

        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(label) > 0 }
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { route() == PAIR_ROUTE }

        compose.onNodeWithContentDescription(text(R.string.pair_back)).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { route() == TABS_ROUTE }
    }

    @Test
    fun `a tab tapped from the pair page comes back to the tabs and shows that one`() {
        // The nav bar is drawn on the pair page too, where a tab means both things at once: leave
        // the page, and land on that tab rather than on whichever one it was opened from.
        val label = "ACCENTUM Plus"
        seedPair(label)
        show()

        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(label) > 0 }
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { route() == PAIR_ROUTE }

        tap(R.string.nav_stats)
        compose.waitUntil(timeoutMillis = 10_000) { route() == TABS_ROUTE }
        awaitTab(R.string.stats_title)
    }

    @Test
    fun `a pair route carrying something that is not an id goes straight back`() {
        // Not reachable by tapping — every caller builds the route from a Long. It is the route
        // argument being a string that makes the guard necessary at all.
        show()

        compose.runOnUiThread { nav.navigate("pair/deleted-while-in-the-back-stack") }
        compose.waitUntil(timeoutMillis = 10_000) { route() == TABS_ROUTE }

        assertEquals("and no half-drawn pair page is left behind", 0, nodeCount(text(R.string.pair_fallback_title)))
        compose.onNodeWithText(text(R.string.app_title)).assertExists()
    }

    @Test
    fun `a message from the view model is shown wherever the user happens to be`() {
        // The shell owns the only snackbar host in the app, so an export that failed on the
        // settings tab has nowhere else to be reported — including after the user has moved on.
        val failure = "no space left on device"
        val uri = "content://test/backup.json".toUri()
        shadowOf(app.contentResolver).registerOutputStream(
            uri,
            object : OutputStream() {
                override fun write(b: Int) = throw IOException(failure)
                override fun write(b: ByteArray) = throw IOException(failure)
            },
        )
        show()

        tap(R.string.nav_stats)
        awaitTab(R.string.stats_title)
        compose.runOnUiThread { viewModel.exportTo(uri) }

        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(failure) > 0 }
    }

    private companion object {
        const val TABS_ROUTE = "tabs"
        const val PAIR_ROUTE = "pair/{pairId}"
    }
}
