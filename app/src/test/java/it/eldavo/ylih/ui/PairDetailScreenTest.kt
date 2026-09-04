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
import it.eldavo.ylih.data.BatterySampleEntity
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.EndReason
import it.eldavo.ylih.data.PairEntity
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.ui.theme.YlihTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // Settings outlive a test method — the DataStore is the app container's, not the rule's.
    @Before
    fun setUp() = runBlocking {
        db.deviceDao().deleteAll()
        app.container.settings.setPlaybackOnly(false)
    }

    @After
    fun tearDown() = runBlocking {
        app.container.settings.setPlaybackOnly(false)
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

    private fun show(pairId: Long, known: Boolean = true) {
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
        // The stats header is drawn on the first composition, before Room has answered, so
        // waiting for a label in it waits for nothing — everything that comes *from* the pair
        // (its name, generation, price, the retired note) lands a frame or more later, and on a
        // slow machine that was long enough for the assertions below to run against the empty
        // page. The title is the one thing on the screen that says the summary has not arrived.
        compose.waitUntil(timeoutMillis = 10_000) {
            if (known) {
                nodeCount(text(R.string.pair_fallback_title)) == 0
            } else {
                nodeCount(text(R.string.pair_sessions)) > 0
            }
        }
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

    /** [scrollTo] where a substring would be ambiguous — "cycle 1" against "cycle 19". */
    private fun scrollToExact(value: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(value))
    }

    private fun pair(pairId: Long) = runBlocking { db.pairDao().byId(pairId) }

    /**
     * The row naming cycle [number], reporting [ms].
     *
     * Matched on its text rather than on its description, which is what tells it apart from the
     * chart above it: the chart describes its own axis as "cycle 1 – cycle 2 · 5.0h max" — so it
     * answers to the same names — but its labels are cleared from the semantics tree and it has no
     * text at all.
     */
    private fun assertCycleRow(number: Int, ms: Long) {
        val name = text(R.string.pair_cycle_number, number)
        // The rows are listed newest first, so the oldest cycle is the furthest down the list.
        scrollTo(name)
        compose.onNode(
            hasText(name, substring = true).and(hasText(formatHours(ms), substring = true)),
        ).assertExists()
    }

    /**
     * Two full cycles over the three sessions [seedPair] writes: sixty points in the first four
     * hours, forty in the next one — which completes the first hundred — and a hundred more in the
     * last hour. Six hours of listening for two cycles, so a charge is worth three.
     */
    private fun seedBatteryReadings() = runBlocking {
        val sessions = db.sessionDao().getAll()
        val pairId = db.pairDao().getAll().single().id
        fun reading(sessionId: Long, at: Long, level: Int) = runBlocking {
            db.batterySampleDao().insert(
                BatterySampleEntity(sessionId = sessionId, pairId = pairId, at = at, level = level),
            )
        }
        reading(sessions[0].id, now - 3 * day, 100)
        reading(sessions[0].id, now - 3 * day + 4 * hour, 40)
        reading(sessions[1].id, now - 2 * day, 100)
        reading(sessions[1].id, now - 2 * day + hour, 60)
        reading(sessions[2].id, now - hour, 100)
        reading(sessions[2].id, now, 0)
    }

    /**
     * Battery is the one thing here the headphones have to volunteer — it reaches Android over
     * HFP, Apple's vendor command or BLE's battery service, and plenty of headsets speak none of
     * them. A pair that has never reported one gets no section rather than an empty one.
     *
     * Asserted by scrolling rather than by counting nodes: the list composes what is on screen, so
     * "no node with this text" is true of every section below the fold and would pass whatever the
     * screen did.
     */
    @Test
    fun `a pair whose headphones never report their battery has no charge section`() {
        val pairId = seedPair()

        show(pairId)

        assertTrue(
            "the whole list was searched and there is no charge section in it",
            runCatching { scrollTo(text(R.string.pair_charge_cycles)) }.isFailure,
        )
    }

    /** One session per cycle, a hundred points each, so [count] cycles complete. */
    private fun seedManyCycles(pairId: Long, count: Int) = runBlocking {
        repeat(count) { i ->
            val at = now - (count - i) * day
            val sessionId = db.sessionDao().insert(
                SessionEntity(
                    pairId = pairId,
                    connectedAt = at,
                    disconnectedAt = at + 5 * hour,
                    heartbeatAt = at + 5 * hour,
                    endReason = EndReason.DISCONNECTED,
                ),
            )
            db.batterySampleDao()
                .insert(BatterySampleEntity(sessionId = sessionId, pairId = pairId, at = at, level = 100))
            db.batterySampleDao().insert(
                BatterySampleEntity(sessionId = sessionId, pairId = pairId, at = at + 5 * hour, level = 0),
            )
        }
    }

    /**
     * Cycles accumulate for the life of the pair — daily use for a decade is some three thousand —
     * and the day list above them is bounded by its window while these are not. Listing every one
     * would put an unbounded scroll between the chart and the sessions underneath it.
     */
    @Test
    fun `only the most recent charge cycles are listed, however many there are`() {
        val pairId = seedPair()
        seedManyCycles(pairId, count = 30)

        show(pairId)
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching { scrollTo(text(R.string.pair_charge_cycles)) }.isSuccess
        }

        // The newest is listed and the oldest is not, and the sessions below stay reachable.
        // Exactly, not by substring: "cycle 1" is a substring of "cycle 19", which *is* listed.
        scrollToExact(text(R.string.pair_cycle_number, 30))
        assertTrue(
            "the whole list was searched and cycle 1 is not in it",
            runCatching { scrollToExact(text(R.string.pair_cycle_number, 1)) }.isFailure,
        )
        scrollTo(text(R.string.session_ongoing))
    }

    @Test
    fun `charge cycles report what a charge is worth and what each one bought`() {
        val pairId = seedPair()
        seedBatteryReadings()

        show(pairId)
        // The readings arrive from Room a frame or more after the summary does, so the section
        // may not be in the list yet on the first attempt.
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching { scrollTo(text(R.string.pair_charge_cycles)) }.isSuccess
        }

        // Two hundred points over six hours, so a hundred of them is three. The three tiles are in
        // the same list item as the heading just scrolled to.
        compose.onNodeWithContentDescription(
            "${text(R.string.pair_per_charge)}: ${formatHours(3 * hour)}",
        ).assertExists()
        compose.onNodeWithContentDescription(
            "${text(R.string.pair_cycles)}: ${formatCycles(2.0)}",
        ).assertExists()
        compose.onNodeWithContentDescription(
            "${text(R.string.pair_charge_watched)}: ${formatHours(6 * hour)}",
        ).assertExists()

        // One row per completed cycle, and the shape of the two is the whole point of the section:
        // five of the six hours went to the first, one to the second — the same battery buying
        // less than it used to.
        assertCycleRow(number = 1, ms = 5 * hour)
        assertCycleRow(number = 2, ms = hour)
    }

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

    /**
     * That both sources of a figure on this page are wired up, which one assertion can cover
     * because they have to agree.
     *
     * The headline is read off the per-pair aggregate (`summarizeLifetime`) and the today/7/30 row
     * off the thirty-day window (`spansByPair`) — neither is derived from this pair's own session
     * list any more, because doing that re-summarised a decade of history on the main thread every
     * minute. The seeded pair has four hours, one hour and an hour still running, all inside the
     * last week, so every one of those figures is the same six hours. Drop either wiring and the
     * count falls to one: an empty window still leaves the headline right.
     */
    @Test
    fun `the headline and the recent windows are the same six hours`() {
        val pairId = seedPair()

        show(pairId)

        val sixHours = formatHours(6 * hour)
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(sixHours) > 1 }
        assertEquals(
            "the headline, the last 7 days and the last 30 all cover the same sessions",
            3,
            nodeCount(sixHours),
        )
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
    fun `counting playback, the headline stops calling itself a lifetime`() {
        // The figure above this line changes meaning with the mode, and this is the only place on
        // the page that can say which of the two the reader is looking at.
        runBlocking { app.container.settings.setPlaybackOnly(true) }
        val pairId = seedPair()

        show(pairId)

        compose.waitUntil(timeoutMillis = 10_000) {
            nodeCount(text(R.string.stats_playback_only_note), substring = true) > 0
        }
        assertEquals(0, nodeCount(text(R.string.pair_lifetime), substring = true))
    }

    @Test
    fun `a pair that is not in the database still draws a page`() {
        // Reachable by deleting a pair on one screen while the other is in the back stack.
        show(pairId = 404, known = false)

        compose.onNodeWithText(text(R.string.pair_fallback_title)).assertExists()
        compose.onNodeWithText(text(R.string.pair_lifetime), substring = true).assertExists()
    }

    /**
     * The chart can only show a shape; the list under it is where "how much did I listen
     * yesterday, on this pair" is actually answered, mirroring `StatsScreenTest`'s equivalent
     * assertion for the all-pairs chart.
     */
    @Test
    fun `the daily breakdown names yesterday and reaches back to the first day recorded`() {
        val pairId = seedPair()

        show(pairId)

        val yesterday = text(R.string.stats_yesterday)
        scrollTo(yesterday)
        compose.onNodeWithText(yesterday).assertExists()
        // seedPair()'s oldest session is three days back, so the list stops there rather than
        // running out the whole fourteen-day window on empty days.
        val oldest = dayLabel(now - 3 * day)
        scrollTo(oldest)
        compose.onNodeWithText(oldest).assertExists()
    }

    /**
     * Unlike the stats screen's own chart, this one must not fold in every other pair's history.
     * A second pair's session lands on the same calendar day as one of this pair's own, and that
     * day's row has to keep showing this pair's hour alone — the sum of the two is exactly what a
     * `spansByPair` lookup gone wrong (reading every pair's spans instead of just this one's)
     * would produce, and nothing else on the page would catch that.
     */
    @Test
    fun `the daily breakdown is scoped to this pair, not every pair`() {
        val pairId = seedPair()
        val zone = ZoneId.systemDefault()
        // Anchored to local noon on a day of its own, five days back, rather than to a fixed
        // offset from `now` the way `seedPair()`'s own sessions are: a session an hour long can
        // straddle midnight and split across two days depending on what time of day the suite
        // happens to run, which would make the exact-hour assertion below flaky.
        val fifthDayNoon = LocalDate.now(zone).minusDays(5).atTime(12, 0)
            .atZone(zone).toInstant().toEpochMilli()
        runBlocking {
            db.sessionDao().insert(
                SessionEntity(
                    pairId = pairId,
                    connectedAt = fifthDayNoon,
                    disconnectedAt = fifthDayNoon + hour,
                    heartbeatAt = fifthDayNoon + hour,
                    endReason = EndReason.DISCONNECTED,
                ),
            )
            val otherDeviceId = db.deviceDao().insert(
                DeviceEntity(
                    deviceKey = "bt:9F:00",
                    kind = DeviceKind.BLUETOOTH,
                    defaultName = "Other pair",
                    firstSeenAt = now - 30 * day,
                ),
            )
            val otherPairId = db.pairDao().insert(
                PairEntity(
                    deviceId = otherDeviceId,
                    label = "Other pair",
                    generation = 1,
                    startedAt = now - 30 * day,
                ),
            )
            db.sessionDao().insert(
                SessionEntity(
                    pairId = otherPairId,
                    connectedAt = fifthDayNoon + hour,
                    disconnectedAt = fifthDayNoon + hour + 5 * hour,
                    heartbeatAt = fifthDayNoon + hour + 5 * hour,
                ),
            )
        }

        show(pairId)

        // This pair's own session on that day is one hour long; a leak that merged the other
        // pair's five hours in would read six instead.
        val sharedDay = dayLabel(fifthDayNoon)
        scrollTo(sharedDay)
        compose.onNodeWithContentDescription("$sharedDay: ${formatHours(hour)}").assertExists()
    }

    private fun dayLabel(epochMs: Long): String {
        val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return "${formatWeekday(date)} · ${formatDayLabel(date)}"
    }
}
