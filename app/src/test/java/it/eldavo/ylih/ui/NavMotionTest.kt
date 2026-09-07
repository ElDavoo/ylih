package it.eldavo.ylih.ui

import android.os.Build
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.navigationevent.NavigationEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The spec every in-app navigation runs on. What is worth pinning is not the numbers themselves —
 * they are a judgement — but the relations that stop the motion looking broken: that every
 * transition carries an alpha as well as a scale, that the two run together rather than in turn,
 * that going back is the mirror of going in, and that a back *gesture* runs the same thing a
 * released one does.
 *
 * A transition's own fields are internal to the animation library, so the shape is read back the
 * only way a caller can: by rebuilding the transition from a scale recovered out of its `toString`
 * and asserting it is equal to the real one. Equality covers everything the rebuild does *not*
 * mention as well — a slide or a size change would make these fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class NavMotionTest {

    private val spec = tween<Float>(durationMillis = NAV_MOTION_MS)

    /** The scale factor a transition animates through, as it describes itself. */
    private fun scaleOf(transition: Any): Float {
        val description = transition.toString()
        val match = SCALE.find(description)
        assertTrue("no scale in $description", match != null)
        return checkNotNull(match).groupValues[1].toFloat()
    }

    @Test
    fun `every navigation transition fades and scales on one spec`() {
        // Two failures at once. A scale with no alpha at all ends with the page still fully drawn
        // and then simply gone, which is what going back looked like. And an alpha given a spec of
        // its own — a shorter one, or a delayed one — leaves a gap in the middle of the animation
        // where neither page is drawn, which is what going in looked like. The same tween for both
        // halves of every transition is the whole of the fix.
        assertEquals(
            "forward enter",
            fadeIn(spec) + scaleIn(spec, initialScale = scaleOf(navEnter())),
            navEnter(),
        )
        assertEquals(
            "forward exit",
            fadeOut(spec) + scaleOut(spec, targetScale = scaleOf(navExit())),
            navExit(),
        )
        assertEquals(
            "pop enter",
            fadeIn(spec) + scaleIn(spec, initialScale = scaleOf(navPopEnter())),
            navPopEnter(),
        )
        assertEquals(
            "pop exit",
            fadeOut(spec) + scaleOut(spec, targetScale = scaleOf(navPopExit())),
            navPopExit(),
        )
    }

    @Test
    fun `going back is the mirror of going in`() {
        // Whatever the numbers are, the pair page has to leave the way it arrived and the tabs have
        // to come back the way they left; otherwise a push and its pop are two different animations
        // and a back gesture, which is committed by playing the rest of the pop, jumps at the
        // moment the finger lets go.
        assertEquals("the arriving page compresses back the way it grew",
            scaleOf(navEnter()), scaleOf(navPopExit()), 0f)
        assertEquals("and the page underneath returns from where it went",
            scaleOf(navExit()), scaleOf(navPopEnter()), 0f)

        assertTrue("one direction is further away", scaleOf(navEnter()) < 1f)
        assertTrue("the other is closer", scaleOf(navExit()) > 1f)
    }

    @Test
    fun `a held back gesture is given the pop and not the library's own`() {
        // This is the #40/#41 fix, and the earlier two missed because they moved the alpha on
        // popExit — which navigation-compose 2.10 stopped seeking. A gesture reads these instead,
        // and a NavHost that leaves them out gets DefaultNavTransitions.predictivePopExitTransition,
        // a bare scaleOut(0.7f) with no alpha, however carefully the pop beside it was written.
        // They have to be the pop and not merely *a* fade, because releasing the gesture hands over
        // to the pop mid-flight.
        for (edge in listOf(
            NavigationEvent.EDGE_LEFT,
            NavigationEvent.EDGE_RIGHT,
            NavigationEvent.EDGE_NONE,
        )) {
            assertEquals("edge $edge enter", navPopEnter(), navPredictivePopEnter(edge))
            assertEquals("edge $edge exit", navPopExit(), navPredictivePopExit(edge))
        }
    }

    @Test
    fun `the app bar fades on the same duration and does not scale`() {
        // It is outside the NavHost and faded by hand, so this is the only thing keeping it in step
        // with the destinations. It stays scale-free on purpose: it holds its height.
        assertEquals(fadeIn(spec), barEnter())
        assertEquals(fadeOut(spec), barExit())
    }

    private companion object {
        val SCALE = Regex("""Scale\(scale=(-?[0-9.]+)""")
    }
}
