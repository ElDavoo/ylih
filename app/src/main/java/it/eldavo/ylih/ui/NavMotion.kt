package it.eldavo.ylih.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

/**
 * The one motion every in-app navigation runs on.
 *
 * It is a file rather than a handful of constants beside their callers because the app bar is not a
 * destination — it sits in the Scaffold, outside the NavHost — and has to be faded by hand to stay
 * in step with the screens behind it. Two specs written twice stay in step only as long as whoever
 * edits one remembers the other; one spec read from both places makes it literal.
 */

// Half of the 700ms these transitions used to run at. 700 is navigation-compose's own default and
// it is a long time to spend crossing between two screens of the same app: at that length the
// animation stops reading as movement and starts reading as a wait for the page to arrive.
internal const val NAV_MOTION_MS = 350

// A screen that is further away, and a screen that is closer than the one in front of the user.
// Forward navigation grows the arriving page from AWAY while the leaving one swells past TOWARD;
// the pop is the mirror, so going back compresses exactly where going in expanded.
internal const val NAV_SCALE_AWAY = 0.92f
internal const val NAV_SCALE_TOWARD = 1.05f

// One tween for the alpha and the scale alike, so the two pages cross over each other while the
// zoom runs rather than taking turns at it. The alpha used to be a shortened fade-out followed by a
// delayed fade-in, on the theory that two half-drawn pages read as mud. What that actually produced
// was a hole in the middle of the animation where neither page was drawn at all — a beat of bare
// Scaffold background between them, which reads as a blink and not as a transition. Overlapped, the
// arriving page's alpha covers exactly what the leaving one gives up, both of them are drawn over
// the same Scaffold surface, and the zoom is the only thing the eye is asked to follow.
private val navSpec = tween<Float>(durationMillis = NAV_MOTION_MS)

internal fun navEnter(): EnterTransition =
    fadeIn(navSpec) + scaleIn(navSpec, initialScale = NAV_SCALE_AWAY)

internal fun navExit(): ExitTransition =
    fadeOut(navSpec) + scaleOut(navSpec, targetScale = NAV_SCALE_TOWARD)

internal fun navPopEnter(): EnterTransition =
    fadeIn(navSpec) + scaleIn(navSpec, initialScale = NAV_SCALE_TOWARD)

internal fun navPopExit(): ExitTransition =
    fadeOut(navSpec) + scaleOut(navSpec, targetScale = NAV_SCALE_AWAY)

// A held back gesture does not run popExit any more, and that is why going back had no fade on it
// however the alpha there was rewritten — twice, in #40 and #41, both times on the wrong knob.
// navigation-compose 2.10 grew `predictivePopEnterTransition`/`predictivePopExitTransition`, a
// second pair of callbacks it seeks by finger progress, and a NavHost that does not name them gets
// DefaultNavTransitions': `scaleOut(targetScale = 0.7f)` for the page being dragged away, with no
// alpha in it at all, and a spring fadeIn for the one underneath. So the pair page shrank under the
// finger and stayed fully opaque to the end, which is exactly what was reported.
//
// Ours are the pop itself rather than a gesture-specific variant, because the two meet: a gesture
// released halfway is committed by animating the rest of the *pop* from where the seek left it, so
// anything the two do not agree about is a jump at the handover.
//
// The swipe edge is ignored on purpose — this is a zoom along the z axis and it looks the same
// whichever side of the screen the finger came from.
internal fun navPredictivePopEnter(swipeEdge: Int): EnterTransition = navPopEnter()

internal fun navPredictivePopExit(swipeEdge: Int): ExitTransition = navPopExit()

// The bar fades and does not scale. It trades places with the pair page's own bar and has to hold
// its height for as long as the screen behind it is still drawn — see the comment at its
// AnimatedVisibility for how — so scaling it would bring back the jerk in the content underneath
// that holding the height exists to prevent. It reads the same spec as the destinations, which is
// the whole reason this file exists.
internal fun barEnter(): EnterTransition = fadeIn(navSpec)

internal fun barExit(): ExitTransition = fadeOut(navSpec)
