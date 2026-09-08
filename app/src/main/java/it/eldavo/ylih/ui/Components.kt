package it.eldavo.ylih.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.eldavo.ylih.R
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.stats.Stats
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        // `heading()` is what gives a screen reader something to jump between; without it the app
        // is one flat run of text from the top of a list to the bottom.
        modifier = modifier
            .semantics { heading() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Reports a finger arriving on and leaving a pill, so [StatRow] can move the row around it. It is a
 * `pointerInput` and not a `clickable`, because `clickable` would add a button role and an activate
 * action: TalkBack would then announce every figure on the stats screen as a button, and
 * double-tapping one would do nothing. A bare gesture detector adds no semantics at all, so the tile
 * keeps the one merged description it already has.
 *
 * The haptic waits for a *completed* tap, and that is the one decision here that is not cosmetic.
 * Both screens showing these tiles are `LazyColumn`s, so a scroll that happens to start on a tile
 * arrives as a press — ticking on touch-down would buzz every time the list was dragged from a
 * figure, which on the stats screen is most of the screen. `tryAwaitRelease` returns false once the
 * scroll has taken the gesture over, so the row settles back and says nothing.
 */
@Composable
private fun Modifier.pressReporting(onPressed: (Boolean) -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    return this.pointerInput(onPressed) {
        detectTapGestures(
            onPress = {
                onPressed(true)
                val tapped = tryAwaitRelease()
                onPressed(false)
                if (tapped) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            },
        )
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onPressed: (Boolean) -> Unit = {},
) {
    Card(
        modifier = modifier.pressReporting(onPressed),
        // surfaceContainerHigh rather than surfaceVariant: Expressive builds elevation out of the
        // container roles, and surfaceVariant now reads as a flat fill.
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        // Merged, and read label-first: the two texts are one figure, and unmerged they arrive as
        // "1,240.5 h" then "lifetime", which is the wrong way round to hear.
        Column(
            Modifier
                .padding(14.dp)
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val PILL_GAP = 8.dp

/** No pill is being held. */
private const val NO_PILL = -1

// How much of the row the pressed pill takes from the ones beside it. Material's own ButtonGroup
// uses 0.15, but its items are single-line labels that cannot wrap; these carry a figure over a
// label, and a two-pill row would hand the whole of it to one neighbour — enough, in some of the 77
// languages, to wrap a label and jog the row's height for the length of a press.
private const val PILL_PRESS_EXPANSION = 0.10f

/**
 * A row of pills, laid out as one group the way a Material 3 Expressive `ButtonGroup` is: the pill
 * under the finger widens by taking width *from its neighbours* rather than growing over them, so
 * pressing one figure visibly moves the ones beside it and the row's own width never changes. That
 * shared width is the whole gesture — a pill on its own has nothing to take from and so does not
 * move, which is what a `ButtonGroup` of one does too.
 *
 * It is a `Layout` rather than a `Row` of animated `weight`s because the shares are read here, in
 * the measure block: a weight is a composition-phase argument, so animating one would recompose
 * every pill in the row on every frame of the spring instead of only re-measuring them.
 */
@Composable
fun StatRow(tiles: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    var pressed by remember { mutableIntStateOf(NO_PILL) }
    val others = (tiles.size - 1).coerceAtLeast(1)
    val shares = List(tiles.size) { index ->
        animateFloatAsState(
            targetValue = when {
                pressed == NO_PILL -> 1f
                pressed == index -> 1f + PILL_PRESS_EXPANSION
                // What the pressed pill took, shared out evenly among the rest.
                else -> 1f - PILL_PRESS_EXPANSION / others
            },
            // A spatial spec, not an effects one: expressive's spatial springs are underdamped and
            // overshoot, which is what makes this read as a squeeze rather than as a resize. The
            // effects springs are critically damped and would only slide the edges over and stop.
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "pillShare",
        )
    }
    Layout(
        content = {
            tiles.forEachIndexed { index, (label, value) ->
                StatTile(
                    label = label,
                    value = value,
                    onPressed = { down -> pressed = if (down) index else NO_PILL },
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gap = PILL_GAP.roundToPx()
        val free = constraints.maxWidth - gap * (measurables.size - 1)
        val total = shares.sumOf { it.value.toDouble() }
        var taken = 0
        val placeables = measurables.mapIndexed { index, measurable ->
            // The last pill takes whatever the rounding left, so the pills and the gaps add up to
            // the row exactly and its right edge never breathes during the animation.
            val width = if (index == measurables.lastIndex) {
                free - taken
            } else {
                (free * shares[index].value / total).roundToInt()
            }
            taken += width
            measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        }
        layout(constraints.maxWidth, placeables.maxOf { it.height }) {
            var x = 0
            placeables.forEach {
                // placeRelative, so the group reverses itself in RTL the way a Row would.
                it.placeRelative(x, 0)
                x += it.width + gap
            }
        }
    }
}

/**
 * The longest window any screen shows. Both screens build a series this long once and read their
 * shorter windows off its tail, so the history is walked once rather than once per figure.
 */
const val WINDOW_DAYS = 30

/** Total of the last [days] buckets of a dense series — see [Stats.dailySeries]. */
fun List<Pair<LocalDate, Long>>.tailMs(days: Int): Long = takeLast(days).sumOf { it.second }

/**
 * Today, the last seven days and the last thirty, all read off one [WINDOW_DAYS]-day series.
 *
 * The stats screen and pair page both showed this, each built from three separate `Stats.recentMs`
 * calls that bucketed the entire history from scratch, keyed on a clock ticking once a second.
 * Three walks became one; the series is `remember`ed by its callers so it isn't rebuilt for a
 * figure that changes hourly.
 */
@Composable
fun WindowStatRow(series: List<Pair<LocalDate, Long>>, modifier: Modifier = Modifier) {
    StatRow(
        listOf(
            stringResource(R.string.stats_today) to formatHours(series.tailMs(1)),
            stringResource(R.string.stats_last_7) to formatHours(series.tailMs(7)),
            stringResource(R.string.stats_last_30) to formatHours(series.tailMs(WINDOW_DAYS)),
        ),
        modifier,
    )
}

/** Composable because the name is a translated resource, not a constant. */
@Composable
fun DeviceKind.displayName(): String = stringResource(
    when (this) {
        DeviceKind.BLUETOOTH -> R.string.kind_bluetooth
        DeviceKind.BLE -> R.string.kind_ble
        DeviceKind.WIRED -> R.string.kind_wired
        DeviceKind.USB -> R.string.kind_usb
        DeviceKind.UNKNOWN -> R.string.kind_unknown
    },
)
