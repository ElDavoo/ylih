package it.eldavo.ylih.ui

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.annotation.Config

/**
 * The devices list is the app's front page, and the distinction it draws — in use above, retired
 * below — is the whole reason a pair has a generation at all: retiring freezes a total instead of
 * deleting it, so a retired pair must still be visible and still carry its hours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class DevicesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val db get() = app.container.database

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = app.container.clock.now()

    private val opened = mutableListOf<Long>()

    @Before
    fun setUp() = runBlocking {
        db.deviceDao().deleteAll()
    }

    private suspend fun seed(
        key: String,
        label: String,
        generation: Int = 1,
        retired: Boolean = false,
        open: Boolean = false,
        lastUsedDaysAgo: Long = 2,
    ): Long {
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
                generation = generation,
                startedAt = now - 30 * day,
                retiredAt = (now - day).takeIf { retired },
            ),
        )
        db.sessionDao().insert(
            SessionEntity(
                pairId = pairId,
                connectedAt = now - lastUsedDaysAgo * day,
                disconnectedAt = now - lastUsedDaysAgo * day + 4 * hour,
                heartbeatAt = now - lastUsedDaysAgo * day + 4 * hour,
            ),
        )
        if (open) {
            db.sessionDao().insert(
                SessionEntity(pairId = pairId, connectedAt = now - hour, heartbeatAt = now),
            )
        }
        return pairId
    }

    /** No `listState`: the default is what every caller but [YlihNavHost] uses. */
    private fun show(until: () -> Boolean) {
        val viewModel = YlihViewModel(app)
        compose.setContent {
            YlihTheme {
                DevicesScreen(
                    viewModel = viewModel,
                    contentPadding = PaddingValues(),
                    onOpenPair = { opened += it },
                )
            }
        }
        // The summaries arrive from Room a frame or two after the first composition.
        compose.waitUntil(timeoutMillis = 10_000, condition = until)
    }

    private fun text(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun nodeCount(value: String, substring: Boolean = false): Int =
        compose.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().size

    /**
     * The chips are informational and carry the card's own action, so four buttons per pair all
     * doing the same thing is what a screen reader used to hear. Their semantics are cleared and
     * what they say is gathered into the card's description, which is where this looks.
     */
    private fun describedCount(value: String): Int = compose
        .onAllNodesWithContentDescription(value, substring = true)
        .fetchSemanticsNodes().size

    @Test
    fun `no headphones yet is a sentence, not a blank page`() {
        show { nodeCount(text(R.string.devices_empty_title)) > 0 }

        compose.onNodeWithText(text(R.string.devices_empty_body)).assertExists()
        assertEquals("nothing to head", 0, nodeCount(text(R.string.devices_in_use)))
    }

    @Test
    fun `a pair in use and a retired one are kept apart and both keep their hours`() {
        runBlocking {
            seed(key = "bt:5E:C2", label = IN_USE, generation = 2, open = true)
            seed(key = "bt:1A:2B", label = RETIRED, retired = true, lastUsedDaysAgo = 20)
        }

        show { nodeCount(IN_USE) > 0 }

        compose.onNodeWithText(text(R.string.devices_in_use)).assertExists()
        compose.onNodeWithText(text(R.string.devices_retired)).assertExists()
        compose.onNodeWithText(RETIRED).assertExists()
        // Generation is only worth saying once a pair has been replaced.
        compose.onNodeWithText(text(R.string.devices_generation, 2), substring = true).assertExists()
        assertEquals(0, nodeCount(text(R.string.devices_generation, 1), substring = true))
        // Four closed hours plus a live one, and the chip counting them up as they pass.
        compose.onNodeWithText(formatHours(5 * hour)).assertExists()
        assertEquals(1, describedCount(text(R.string.devices_connected_for, "")))
        // The retired pair last listened to two days ago, so it has no seven-day chip to show.
        assertEquals(1, describedCount(text(R.string.devices_recent, "")))
    }

    @Test
    fun `tapping a card opens that pair and no other`() {
        val pairId = runBlocking { seed(key = "bt:5E:C2", label = IN_USE) }

        show { nodeCount(IN_USE) > 0 }
        compose.onNodeWithText(IN_USE).performClick()

        assertEquals(listOf(pairId), opened)
    }

    private companion object {
        const val IN_USE = "ACCENTUM Plus"
        const val RETIRED = "HD 25"
    }
}
