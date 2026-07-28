package it.eldavo.ylih.ui

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.PairEntity
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.ui.theme.YlihTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stats page is the one screen that answers the question the app was written for, and it
 * answers it twice — connected time or measured playback. Whichever it is counting, it has to
 * say so: a lifetime headline that halved overnight with no explanation would read as lost
 * history, which is the one thing this app must never look like it has done.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class StatsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val db get() = app.container.database
    private val settings get() = app.container.settings

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = app.container.clock.now()

    @Before
    fun setUp() = runBlocking {
        db.deviceDao().deleteAll()
        settings.setPlaybackOnly(false)
        seed(key = "bt:5E:C2", label = IN_USE, connectedMs = 4 * hour, playingMs = 3 * hour)
        seed(key = "bt:1A:2B", label = RETIRED, connectedMs = hour, retired = true)
    }

    @After
    fun tearDown() = runBlocking {
        // The preferences file is real and the DataStore behind it is a process singleton.
        settings.setPlaybackOnly(false)
    }

    private suspend fun seed(
        key: String,
        label: String,
        connectedMs: Long,
        playingMs: Long? = null,
        retired: Boolean = false,
    ) {
        val deviceId = db.deviceDao().insert(
            DeviceEntity(
                deviceKey = key,
                kind = DeviceKind.BLUETOOTH,
                defaultName = label,
                firstSeenAt = now - 30 * day,
            ),
        )
        val pairId = db.pairDao().insert(
            PairEntity(
                deviceId = deviceId,
                label = label,
                generation = 1,
                startedAt = now - 30 * day,
                retiredAt = (now - day).takeIf { retired },
            ),
        )
        // Only finished sessions, so every figure on the page is arithmetic rather than a race
        // against the view model's one-second tick.
        db.sessionDao().insert(
            SessionEntity(
                pairId = pairId,
                connectedAt = now - 2 * day,
                disconnectedAt = now - 2 * day + connectedMs,
                playingMs = playingMs,
                heartbeatAt = now - 2 * day + connectedMs,
            ),
        )
    }

    /** No `listState`: the default is what every caller but [YlihNavHost] uses. */
    private fun show(totalHeadline: String) {
        val viewModel = YlihViewModel(app)
        compose.setContent {
            YlihTheme {
                StatsScreen(viewModel = viewModel, contentPadding = PaddingValues())
            }
        }
        // The spans arrive from Room a frame or two after the first composition, and the headline
        // reads 0.0 h until they do.
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(totalHeadline) > 0 }
    }

    private fun text(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun sessions(count: Int): String =
        app.resources.getQuantityString(R.plurals.session_count, count, count)

    private fun nodeCount(value: String, substring: Boolean = false): Int =
        compose.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().size

    /** The per-pair ranking sits below the header block and its 30-day chart. */
    private fun scrollTo(value: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(value))
    }

    @Test
    fun `the ranking gives every pair its hours and says which are retired`() {
        show(totalHeadline = formatHours(5 * hour))

        val inUse = text(R.string.stats_pair_row, formatHours(4 * hour), sessions(1))
        val retired = text(R.string.stats_pair_row_retired, formatHours(hour), sessions(1))
        scrollTo(inUse)
        compose.onNodeWithText(inUse).assertExists()
        scrollTo(retired)
        compose.onNodeWithText(retired).assertExists()
    }

    @Test
    fun `measured playback earns its own row only while the headline is connected time`() {
        show(totalHeadline = formatHours(5 * hour))

        // One pair recorded playback, so the measured-span block is drawn at all.
        compose.onNodeWithText(text(R.string.stats_measured_span)).assertExists()
        compose.onNodeWithText(text(R.string.stats_playing)).assertExists()
        assertEquals("nothing is counting playback yet", 0, nodeCount(text(R.string.stats_playback_only_note)))
    }

    @Test
    fun `counting playback says so, and stops repeating the headline beside it`() {
        runBlocking { settings.setPlaybackOnly(true) }

        show(totalHeadline = formatHours(3 * hour))

        compose.onNodeWithText(text(R.string.stats_playback_only_note)).assertExists()
        // The headline above already *is* the playing figure; saying it twice invites the reader
        // to add them up.
        assertEquals(0, nodeCount(text(R.string.stats_playing)))
        compose.onNodeWithText(text(R.string.stats_measured_span)).assertExists()

        // A pair that never measured playback counts zero here rather than its connected hours.
        val retired = text(R.string.stats_pair_row_retired, formatHours(0L), sessions(1))
        scrollTo(retired)
        compose.onNodeWithText(retired).assertExists()
    }

    private companion object {
        const val IN_USE = "ACCENTUM Plus"
        const val RETIRED = "HD 25"
    }
}
