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
 * A file, not constants beside their callers: the app bar isn't a destination — it sits in the
 * Scaffold, outside the NavHost — and must be faded by hand to stay in step with the screens
 * behind it. One spec read from both places keeps that literal, instead of two that stay in step
 * only as long as whoever edits one remembers the other.
 */

// Half the 700ms these transitions used to run at — navigation-compose's default, and a long time
// to cross between two screens of the same app: at that length the animation reads as a wait for
// the page rather than movement.
internal const val NAV_MOTION_MS = 350

// A screen further away, and one closer than the one in front. Forward navigation grows the
// arriving page from AWAY while the leaving one swells past TOWARD; the pop mirrors it, so going
// back compresses exactly where going in expanded.
internal const val NAV_SCALE_AWAY = 0.92f
internal const val NAV_SCALE_TOWARD = 1.05f

// One tween for alpha and scale, so the two pages cross over while the zoom runs rather than
// taking turns. The alpha used to be a shortened fade-out then a delayed fade-in, on the theory
// that two half-drawn pages read as mud — instead it left a hole mid-animation with neither page
// drawn, a beat of bare Scaffold background reading as a blink, not a transition. Overlapped, the
// arriving page's alpha covers exactly what the leaving one gives up; both draw over the same
// Scaffold surface, so the zoom is the only thing the eye follows.
private val navSpec = tween<Float>(durationMillis = NAV_MOTION_MS)

internal fun navEnter(): EnterTransition =
    fadeIn(navSpec) + scaleIn(navSpec, initialScale = NAV_SCALE_AWAY)

internal fun navExit(): ExitTransition =
    fadeOut(navSpec) + scaleOut(navSpec, targetScale = NAV_SCALE_TOWARD)

internal fun navPopEnter(): EnterTransition =
    fadeIn(navSpec) + scaleIn(navSpec, initialScale = NAV_SCALE_TOWARD)

internal fun navPopExit(): ExitTransition =
    fadeOut(navSpec) + scaleOut(navSpec, targetScale = NAV_SCALE_AWAY)

// A held back gesture no longer runs popExit, which is why going back had no fade however the
// alpha was rewritten — twice, in #40 and #41, both on the wrong knob. navigation-compose 2.10
// added `predictivePopEnterTransition`/`predictivePopExitTransition`, seeked by finger progress;
// unnamed, a NavHost gets DefaultNavTransitions': `scaleOut(targetScale = 0.7f)` with no alpha,
// and a spring fadeIn underneath — so the pair page shrank under the finger and stayed fully
// opaque, exactly as reported.
//
// Ours are the pop itself, not a gesture-specific variant: a gesture released halfway commits by
// animating the rest of the *pop* from where the seek left it, so anything the two disagree about
// would jump at the handover.
//
// The swipe edge is ignored: this is a zoom along the z axis and looks the same from either side.
internal fun navPredictivePopEnter(swipeEdge: Int): EnterTransition = navPopEnter()

internal fun navPredictivePopExit(swipeEdge: Int): ExitTransition = navPopExit()

// The bar fades and doesn't scale: it trades places with the pair page's own bar and must hold
// its height as long as the screen behind it draws (see the AnimatedVisibility comment), so
// scaling it would bring back the content jerk that holding the height prevents. It reads the
// same spec as the destinations, which is why this file exists.
internal fun barEnter(): EnterTransition = fadeIn(navSpec)

internal fun barExit(): ExitTransition = fadeOut(navSpec)
