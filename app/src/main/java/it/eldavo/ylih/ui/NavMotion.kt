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

// The duration is shared by the alpha and the scale of every transition below, and that sharing is
// the point: a scale that outlives its fade ends by snapping out of existence, which is what the
// pop used to do. 700ms is the number these transitions have always run at; only the shape changed.
internal const val NAV_MOTION_MS = 700

// A screen that is further away, and a screen that is closer than the one in front of the user.
// Forward navigation grows the arriving page from AWAY while the leaving one swells past TOWARD;
// the pop is the mirror, so going back compresses exactly where going in expanded. Predictive back
// seeks our own popExit rather than adding one of its own, so the gesture is this same animation.
internal const val NAV_SCALE_AWAY = 0.92f
internal const val NAV_SCALE_TOWARD = 1.05f

private val navSpec = tween<Float>(durationMillis = NAV_MOTION_MS)

internal fun navEnter(): EnterTransition =
    fadeIn(navSpec) + scaleIn(navSpec, initialScale = NAV_SCALE_AWAY)

internal fun navExit(): ExitTransition =
    fadeOut(navSpec) + scaleOut(navSpec, targetScale = NAV_SCALE_TOWARD)

internal fun navPopEnter(): EnterTransition =
    fadeIn(navSpec) + scaleIn(navSpec, initialScale = NAV_SCALE_TOWARD)

internal fun navPopExit(): ExitTransition =
    fadeOut(navSpec) + scaleOut(navSpec, targetScale = NAV_SCALE_AWAY)

// The bar fades and does not scale. It trades places with the pair page's own bar and has to hold
// its height for the whole exit — see the comment at its AnimatedVisibility — so scaling it would
// bring back the jerk in the content underneath that holding the height exists to prevent.
internal fun barEnter(): EnterTransition = fadeIn(navSpec)

internal fun barExit(): ExitTransition = fadeOut(navSpec)
