package it.eldavo.ylih.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.ui.theme.YlihTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Fixed qualifiers because the press tests below measure in pixels: at mdpi a dp is a pixel, so the
// scroll distance and the spacer above the tile can be compared without asking the density.
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class ComponentsTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The kinds are what a pair's card and its page call the thing being worn, and the `when` that
     * maps them is the sort of list a new kind gets added to without a new string — which shows up
     * as two kinds sharing a name rather than as a crash.
     */
    @Test
    fun `every kind of output has a name of its own`() {
        lateinit var names: List<String>
        compose.setContent {
            names = DeviceKind.entries.map { it.displayName() }
        }
        compose.waitForIdle()

        assertEquals(DeviceKind.entries.size, names.toSet().size)
        assertTrue("a kind with no name would read as a blank separator", names.none { it.isBlank() })
    }

    /**
     * The point of laying the row out by hand: a press has to move the *other* pills, the way a
     * Material 3 Expressive `ButtonGroup` moves the button beside the one being held. A pill that
     * simply scaled itself up would pass a "it got bigger" assertion and still leave the row dead
     * around it, so what is pinned here is the neighbours giving the width up and the row keeping
     * its own edges.
     */
    @Test
    fun `pressing a figure takes width from the ones beside it`() {
        setContent()
        val atRest = FIGURES.map { bounds(it) }

        tile(FIGURES.first()).performTouchInput { down(center) }
        compose.waitForIdle()

        val held = FIGURES.map { bounds(it) }
        assertTrue(
            "the held pill went from ${atRest[0].width} to ${held[0].width}",
            held[0].width > atRest[0].width,
        )
        assertTrue(
            "the neighbours went to ${held.drop(1).map { it.width }} and did not give anything up",
            held.drop(1).indices.all { held[it + 1].width < atRest[it + 1].width },
        )
        assertEquals(
            "the row's own edges moved: ${span(held)} against ${span(atRest)} at rest",
            span(atRest),
            span(held),
            1f,
        )

        tile(FIGURES.first()).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(
            "let go and left uneven",
            atRest.map { it.width },
            FIGURES.map { bounds(it).width },
        )
    }

    /** The tick is the whole point of the gesture: without it a press is only a picture. */
    @Test
    fun `a tap on a figure ticks once`() {
        val haptics = RecordingHaptics()
        setContent(haptics)

        tile(FIGURES.first()).performTouchInput { down(center) }
        tile(FIGURES.first()).performTouchInput { up() }
        compose.waitForIdle()

        assertEquals("one tap, one tick", listOf(HapticFeedbackType.SegmentTick), haptics.performed)
    }

    /**
     * Both screens that show these tiles are `LazyColumn`s, so a drag that starts on a figure is a
     * press until it isn't. Ticking on the touch down would buzz on every scroll begun from a tile,
     * which on the stats screen is most of the screen — so the tick waits for a release the scroll
     * did not take away, and the row has to square itself up again when it does.
     */
    @Test
    fun `scrolling away from a figure neither ticks nor leaves the row uneven`() {
        val haptics = RecordingHaptics()
        setContent(haptics, scrollable = true)
        val atRest = FIGURES.map { bounds(it) }

        tile(FIGURES.first()).performTouchInput { down(center) }
        compose.waitForIdle()
        // Without this the rest proves nothing: a gesture that never reached the row would also
        // leave it even and silent.
        assertTrue("the press never registered at all", bounds(FIGURES[1]).width < atRest[1].width)

        tile(FIGURES.first()).performTouchInput {
            moveBy(Offset(0f, -SCROLL_PX))
            up()
        }
        compose.waitForIdle()

        assertEquals("a scroll is not a tap", emptyList<HapticFeedbackType>(), haptics.performed)
        assertEquals(
            "scrolled away and left the row uneven",
            atRest.map { it.width },
            FIGURES.map { bounds(it).width },
        )
    }

    /**
     * A figure is a number, not a control. `clickable` would have been the short way to a press
     * state and would have cost a button role and an activate action on every tile — a screen
     * reader announcing eight buttons per page, none of which does anything when double-tapped.
     */
    @Test
    fun `a figure is not announced as something to activate`() {
        setContent()

        FIGURES.forEach { tile(it).assertHasNoClickAction() }
    }

    private fun setContent(
        haptics: HapticFeedback = RecordingHaptics(),
        scrollable: Boolean = false,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                YlihTheme(dynamicColor = false) {
                    if (scrollable) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            // Room above as well as below, so the drag has somewhere to go without
                            // carrying the row off the top of the viewport — an unmeasurable pill
                            // reports zero bounds, which is not the same answer as an even one.
                            Spacer(Modifier.height(LEAD_DP.dp))
                            StatRow(FIGURES)
                            Spacer(Modifier.height(2_000.dp))
                        }
                    } else {
                        StatRow(FIGURES)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun tile(figure: Pair<String, String>): SemanticsNodeInteraction =
        compose.onNodeWithContentDescription("${figure.first}: ${figure.second}")

    private fun bounds(figure: Pair<String, String>): Rect =
        tile(figure).fetchSemanticsNode().boundsInRoot

    /** Left edge of the first pill to the right edge of the last — what the row occupies. */
    private fun span(bounds: List<Rect>): Float = bounds.last().right - bounds.first().left

    private class RecordingHaptics : HapticFeedback {
        val performed = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            performed += hapticFeedbackType
        }
    }

    private companion object {
        // Three, because that is what every row on the stats screen holds and it is the arrangement
        // with a neighbour on each side of the middle pill.
        val FIGURES = listOf(
            "today" to "1.5 h",
            "7 days" to "12.0 h",
            "30 days" to "1,240.5 h",
        )

        // The qualifiers are mdpi, so a dp is a pixel and these two are in the same units.
        const val LEAD_DP = 200

        // Well past the touch slop, so the scroll takes the gesture over rather than the row
        // reading the whole thing as a slow tap, and short of LEAD_DP so it stays on screen.
        const val SCROLL_PX = 150f
    }
}
