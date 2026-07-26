package it.eldavo.ylih.ui

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.EndReason
import it.eldavo.ylih.data.PairEntity
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.ui.theme.YlihTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pair page is where a decade of history is read and where it can be destroyed, so both
 * halves matter: that the numbers reach the screen, and that the menu writes what it says it
 * writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class PairDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val db get() = app.container.database

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = app.container.clock.now()

    private var backs = 0

    @Before
    fun setUp() = runBlocking {
        db.deviceDao().deleteAll()
    }

    private fun seedPair(
        retiredAt: Long? = null,
        retireReason: String? = null,
        priceCents: Long? = 34_900,
        generation: Int = 2,
    ): Long = runBlocking {
        val deviceId = db.deviceDao().insert(
            DeviceEntity(
                deviceKey = "bt:5E:C2",
                kind = DeviceKind.BLUETOOTH,
                defaultName = "ACCENTUM Plus",
                firstSeenAt = now - 30 * day,
            ),
        )
        val pairId = db.pairDao().insert(
            PairEntity(
                deviceId = deviceId,
                label = "ACCENTUM Plus",
                generation = generation,
                startedAt = now - 30 * day,
                retiredAt = retiredAt,
                retireReason = retireReason,
                purchaseDate = now - 30 * day,
                priceCents = priceCents,
            ),
        )
        db.sessionDao().insert(
            SessionEntity(
                pairId = pairId,
                connectedAt = now - 3 * day,
                disconnectedAt = now - 3 * day + 4 * hour,
                playingMs = 3 * hour,
                heartbeatAt = now - 3 * day + 4 * hour,
                endReason = EndReason.DISCONNECTED,
            ),
        )
        db.sessionDao().insert(
            SessionEntity(
                pairId = pairId,
                connectedAt = now - 2 * day,
                disconnectedAt = now - 2 * day + hour,
                heartbeatAt = now - 2 * day + hour,
                endReason = EndReason.RECOVERED,
            ),
        )
        if (retiredAt == null) {
            db.sessionDao().insert(
                SessionEntity(
                    pairId = pairId,
                    connectedAt = now - hour,
                    heartbeatAt = now,
                ),
            )
        }
        pairId
    }

    private fun show(pairId: Long) {
        val viewModel = YlihViewModel(app)
        compose.setContent {
            YlihTheme {
                PairDetailScreen(
                    viewModel = viewModel,
                    pairId = pairId,
                    contentPadding = PaddingValues(),
                    onBack = { backs++ },
                )
            }
        }
        // The summary arrives from Room a frame or two after the first composition.
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(text(R.string.pair_sessions)) > 0 }
    }

    private fun text(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun nodeCount(value: String, substring: Boolean = false): Int =
        compose.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().size

    private fun openMenu(item: Int) {
        compose.onNodeWithContentDescription(text(R.string.pair_more)).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(text(item)) > 0 }
        compose.onNodeWithText(text(item)).performClick()
    }

    /** The session rows live below the fold of the stats header on a phone-sized screen. */
    private fun scrollTo(value: String) {
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(value, substring = true))
    }

    private fun pair(pairId: Long) = runBlocking { db.pairDao().byId(pairId) }

    @Test
    fun `the page reports lifetime, playback, cost and every session`() {
        val pairId = seedPair()

        show(pairId)

        compose.onNodeWithText("ACCENTUM Plus").assertExists()
        compose.onNodeWithText(text(R.string.pair_lifetime), substring = true).assertExists()
        compose.onNodeWithText(text(R.string.devices_generation, 2), substring = true).assertExists()
        compose.onNodeWithText(text(R.string.pair_connected_now, ""), substring = true).assertExists()
        // Playback was measured on one session, so the measured-time row is drawn.
        compose.onNodeWithText(text(R.string.pair_of_measured)).assertExists()
        // A price plus real hours is what makes cost-per-hour meaningful, so both rows appear.
        compose.onNodeWithText(text(R.string.pair_paid)).assertExists()
        compose.onNodeWithText(text(R.string.pair_per_hour)).assertExists()
        compose.onNodeWithText(text(R.string.pair_bought)).assertExists()
        // One row per session: the open one, and the one the heartbeat had to recover.
        scrollTo(text(R.string.session_ongoing))
        compose.onNodeWithText(text(R.string.session_ongoing), substring = true).assertExists()
        scrollTo(text(R.string.session_recovered))
        compose.onNodeWithText(text(R.string.session_recovered), substring = true).assertExists()
    }

    @Test
    fun `a pair with no price and no playback leaves those rows out`() {
        val pairId = runBlocking {
            val deviceId = db.deviceDao().insert(
                DeviceEntity(
                    deviceKey = "wired:headphones",
                    kind = DeviceKind.WIRED,
                    defaultName = "Wired headphones",
                    firstSeenAt = now - day,
                ),
            )
            db.pairDao().insert(
                PairEntity(
                    deviceId = deviceId,
                    label = "Wired headphones",
                    generation = 1,
                    startedAt = now - day,
                ),
            )
        }

        show(pairId)

        assertEquals(0, nodeCount(text(R.string.pair_paid)))
        assertEquals(0, nodeCount(text(R.string.pair_of_measured)))
        assertEquals("generation 1 is not worth saying", 0, nodeCount("pair #1", substring = true))
    }

    @Test
    fun `a retired pair says so and cannot be retired twice`() {
        val pairId = seedPair(retiredAt = now - day, retireReason = "left earcup died")

        show(pairId)

        compose.onNodeWithText("left earcup died", substring = true).assertExists()

        compose.onNodeWithContentDescription(text(R.string.pair_more)).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(text(R.string.pair_menu_delete)) > 0 }
        assertEquals(0, nodeCount(text(R.string.pair_menu_retire)))
    }

    @Test
    fun `a pair retired without a reason still shows the date`() {
        val pairId = seedPair(retiredAt = now - day)

        show(pairId)

        compose.onNodeWithText(text(R.string.pair_retired_on, ""), substring = true).assertExists()
    }

    @Test
    fun `renaming from the menu writes the new label`() {
        val pairId = seedPair()
        show(pairId)

        openMenu(R.string.pair_menu_rename)
        compose.onNode(hasSetTextAction()).performTextReplacement("WH-1000XM5")
        compose.onNodeWithText(text(R.string.action_save)).performClick()

        compose.waitUntil(timeoutMillis = 10_000) { pair(pairId)?.label == "WH-1000XM5" }
    }

    @Test
    fun `cancelling the rename dialog changes nothing`() {
        val pairId = seedPair()
        show(pairId)

        openMenu(R.string.pair_menu_rename)
        compose.onNode(hasSetTextAction()).performTextReplacement("Nope")
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            nodeCount(text(R.string.pair_rename_title)) == 0
        }
        assertEquals("ACCENTUM Plus", pair(pairId)?.label)
    }

    @Test
    fun `the price dialog is entered in whole units and stored in cents`() {
        val pairId = seedPair(priceCents = null)
        show(pairId)

        openMenu(R.string.pair_menu_purchase)
        compose.onNode(hasSetTextAction()).performTextReplacement("249.50")
        compose.onNodeWithText(text(R.string.action_save)).performClick()

        compose.waitUntil(timeoutMillis = 10_000) { pair(pairId)?.priceCents == 24_950L }
        // With no purchase date of its own, the pair falls back to when it started.
        assertNotNull(pair(pairId)?.purchaseDate)
    }

    @Test
    fun `a price that is not a number clears the figure rather than guessing`() {
        val pairId = seedPair()
        show(pairId)

        openMenu(R.string.pair_menu_purchase)
        compose.onNode(hasSetTextAction()).performTextReplacement("free, a gift")
        compose.onNodeWithText(text(R.string.action_save)).performClick()

        compose.waitUntil(timeoutMillis = 10_000) { pair(pairId)?.priceCents == null }
    }

    @Test
    fun `retiring from the menu freezes the totals with the reason typed`() {
        val pairId = seedPair()
        show(pairId)

        openMenu(R.string.pair_menu_retire)
        compose.onNode(hasSetTextAction()).performTextReplacement("sold")
        compose.onNodeWithText(text(R.string.pair_retire_confirm)).performClick()

        compose.waitUntil(timeoutMillis = 10_000) { pair(pairId)?.retiredAt != null }
        assertEquals("sold", pair(pairId)?.retireReason)
    }

    @Test
    fun `deleting is confirmed first, and then goes back`() {
        val pairId = seedPair()
        show(pairId)

        openMenu(R.string.pair_menu_delete)
        compose.onNodeWithText(text(R.string.pair_delete_body)).assertExists()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(text(R.string.pair_delete_body)) == 0 }
        assertNotNull("cancelling must not delete a decade of history", pair(pairId))
        assertEquals(0, backs)

        openMenu(R.string.pair_menu_delete)
        compose.onNodeWithText(text(R.string.action_delete)).performClick()

        compose.waitUntil(timeoutMillis = 10_000) { pair(pairId) == null }
        assertEquals("the page it was showing no longer exists", 1, backs)
        assertNull(runBlocking { db.sessionDao().getAll().firstOrNull() })
    }

    @Test
    fun `a pair that is not in the database still draws a page`() {
        // Reachable by deleting a pair on one screen while the other is in the back stack.
        show(pairId = 404)

        compose.onNodeWithText(text(R.string.pair_fallback_title)).assertExists()
        compose.onNodeWithText(text(R.string.pair_lifetime), substring = true).assertExists()
    }
}
