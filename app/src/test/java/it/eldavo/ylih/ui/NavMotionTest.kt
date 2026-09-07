package it.eldavo.ylih.ui

import android.os.Build
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The spec every in-app navigation runs on. What is worth pinning is not the numbers themselves —
 * they are a judgement — but the relations that stop the motion looking broken: that the alpha is
 * finished before the scale is, that nothing outlives the motion, and that going back is the mirror
 * of going in.
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
    private val out = tween<Float>(durationMillis = NAV_FADE_MS)
    private val into = tween<Float>(durationMillis = NAV_FADE_MS, delayMillis = NAV_FADE_DELAY_MS)

    /** The scale factor a transition animates through, as it describes itself. */
    private fun scaleOf(transition: Any): Float {
        val description = transition.toString()
        val match = SCALE.find(description)
        assertTrue("no scale in $description", match != null)
        return checkNotNull(match).groupValues[1].toFloat()
    }

    @Test
    fun `every navigation transition fades and scales on one spec`() {
        // The failure this rules out is the one that was reported: a scale with no alpha at all, or
        // an alpha put back on the full spec, ends with the screen still drawn and then simply gone.
        assertEquals(
            "forward enter",
            fadeIn(into) + scaleIn(spec, initialScale = scaleOf(navEnter())),
            navEnter(),
        )
        assertEquals(
            "forward exit",
            fadeOut(out) + scaleOut(spec, targetScale = scaleOf(navExit())),
            navExit(),
        )
        assertEquals(
            "pop enter",
            fadeIn(into) + scaleIn(spec, initialScale = scaleOf(navPopEnter())),
            navPopEnter(),
        )
        assertEquals(
            "pop exit",
            fadeOut(out) + scaleOut(spec, targetScale = scaleOf(navPopExit())),
            navPopExit(),
        )
    }

    @Test
    fun `the fade is finished before the scale is`() {
        // This is the #40 fix. A predictive-back gesture seeks the pop by finger progress instead of
        // playing it, so an alpha spread over the whole timeline is still most of the way opaque
        // where a real drag ends and the release has no duration left to fade it in — the page just
        // vanishes. Front-loaded, it is already transparent by the time the gesture hands over.
        assertTrue("alpha must end before the motion does", NAV_FADE_MS < NAV_MOTION_MS)
        assertTrue("and it must still be a fade, not a cut", NAV_FADE_MS > 0)
    }

    @Test
    fun `nothing outlives the motion`() {
        // The arriving page's fade is delayed so the two screens are never both half-drawn over each
        // other; delay plus fade is the duration exactly, so it stays inside the scale it rides on
        // and a push and its pop remain the same length.
        assertEquals(NAV_MOTION_MS, NAV_FADE_DELAY_MS + NAV_FADE_MS)
    }

    @Test
    fun `going back is the mirror of going in`() {
        // Whatever the numbers are, the pair page has to leave the way it arrived and the tabs have
        // to come back the way they left; otherwise a push and its pop are two different animations
        // and a predictive-back gesture, which seeks the pop, shows neither of them properly.
        assertEquals("the arriving page compresses back the way it grew",
            scaleOf(navEnter()), scaleOf(navPopExit()), 0f)
        assertEquals("and the page underneath returns from where it went",
            scaleOf(navExit()), scaleOf(navPopEnter()), 0f)

        assertTrue("one direction is further away", scaleOf(navEnter()) < 1f)
        assertTrue("the other is closer", scaleOf(navExit()) > 1f)
    }

    @Test
    fun `the app bar fades on the same duration and does not scale`() {
        // It is outside the NavHost and faded by hand, so this is the only thing keeping it in step
        // with the destinations — including the front-loaded alpha, or the bar would still be
        // fading over a page that had finished. It stays scale-free on purpose: it holds its height.
        assertEquals(fadeIn(into), barEnter())
        assertEquals(fadeOut(out), barExit())
    }

    private companion object {
        val SCALE = Regex("""Scale\(scale=(-?[0-9.]+)""")
    }
}
