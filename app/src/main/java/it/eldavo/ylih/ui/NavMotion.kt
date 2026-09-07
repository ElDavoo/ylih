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

// 700ms is the number these transitions have always run at, and it is the scale that spends all of
// it. The alpha does not: a predictive-back gesture *seeks* the pop by finger progress rather than
// playing it, so a fade spread evenly over the whole timeline is still most of the way opaque where
// a real drag ends, and the release has almost nothing left of the duration to dispose of it in —
// the page simply disappeared, which is #40. Front-loading the alpha means the page is already
// transparent at the point the gesture hands over, whatever the commit then chooses to do with the
// remainder. The scale keeps the full duration and the pop stays the exact mirror of the push.
internal const val NAV_MOTION_MS = 700
internal const val NAV_FADE_MS = 400
internal const val NAV_FADE_DELAY_MS = NAV_MOTION_MS - NAV_FADE_MS

// A screen that is further away, and a screen that is closer than the one in front of the user.
// Forward navigation grows the arriving page from AWAY while the leaving one swells past TOWARD;
// the pop is the mirror, so going back compresses exactly where going in expanded. Predictive back
// seeks our own popExit rather than adding one of its own, so the gesture is this same animation.
internal const val NAV_SCALE_AWAY = 0.92f
internal const val NAV_SCALE_TOWARD = 1.05f

private val navSpec = tween<Float>(durationMillis = NAV_MOTION_MS)

// The leaving page fades first and the arriving one last, so the two are never both half-drawn over
// each other, and neither outlives the scale it rides on: the delay plus the fade is the duration.
private val fadeOutSpec = tween<Float>(durationMillis = NAV_FADE_MS)
private val fadeInSpec = tween<Float>(durationMillis = NAV_FADE_MS, delayMillis = NAV_FADE_DELAY_MS)

internal fun navEnter(): EnterTransition =
    fadeIn(fadeInSpec) + scaleIn(navSpec, initialScale = NAV_SCALE_AWAY)

internal fun navExit(): ExitTransition =
    fadeOut(fadeOutSpec) + scaleOut(navSpec, targetScale = NAV_SCALE_TOWARD)

internal fun navPopEnter(): EnterTransition =
    fadeIn(fadeInSpec) + scaleIn(navSpec, initialScale = NAV_SCALE_TOWARD)

internal fun navPopExit(): ExitTransition =
    fadeOut(fadeOutSpec) + scaleOut(navSpec, targetScale = NAV_SCALE_AWAY)

// The bar fades and does not scale. It trades places with the pair page's own bar and has to hold
// its height for as long as the screen behind it is still drawn — see the comment at its
// AnimatedVisibility — so scaling it would bring back the jerk in the content underneath that
// holding the height exists to prevent. It reads the same two fade specs as the destinations, which
// is the whole reason this file exists: AnimatedVisibility gives the height back when its own exit
// ends, and on the shortened alpha that is the instant the content underneath reaches zero anyway.
internal fun barEnter(): EnterTransition = fadeIn(fadeInSpec)

internal fun barExit(): ExitTransition = fadeOut(fadeOutSpec)
