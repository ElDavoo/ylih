package it.eldavo.ylih.widget

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import it.eldavo.ylih.R
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.ui.formatHours

/**
 * Lifetime hours per pair, connected pair first and with a live timer on it.
 *
 * This is the widget the app exists for: the number it spends years accumulating, on the home
 * screen, where it can be read without opening anything. The ticking timer does a second job — in
 * Bluetooth-only mode nothing of the app is resident and there is no notification, so it is the
 * only ambient sign that background tracking is alive at all.
 */
class LifetimeWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (localized, data) = widgetContent(context)
        provideContent { LifetimeContent(localized, data) }
    }
}

// Internal rather than private purely so provideGlance's lambda can reach it without the compiler
// inserting a synthetic accessor (lint's SyntheticAccessor); the other two widgets do the same.
@Composable
internal fun LifetimeContent(context: Context, data: WidgetData) {
    val size = LocalSize.current
    // One row is the connected pair, because the list is sorted to put it first. That is the
    // whole content of the shortest widget: a lifetime total with no context is a wall of
    // numbers, and the connected pair is the one changing.
    val header = size.height >= cells(2)
    val rows = lifetimeRows(size.height.value, header)
    // The phrase under a connected pair's name is a sentence, not a number, and at a couple of
    // cells across it is ellipsised into nothing useful. The row's own colour already says which
    // pair is on.
    val timers = size.width >= cells(3)
    WidgetRoot(context) {
        if (header) {
            WidgetHeader(
                context = context,
                title = context.getString(R.string.nav_headphones),
                playbackOnly = data.counting == Counting.PLAYBACK,
            )
            Spacer(GlanceModifier.height(4.dp))
        }
        if (data.rows.isEmpty()) {
            Text(
                text = context.getString(R.string.devices_empty_title),
                maxLines = 2,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            )
        } else {
            val shown = data.rows.take(rows)
            val stretch = lifetimeStretches(size.height.value, header, shown.size)
            shown.forEach { PairRow(context, it, data.now, timers, stretch) }
            if (!stretch) Spacer(GlanceModifier.defaultWeight())
        }
    }
}

/** The height the rows get to share: what is left once the padding and the header have theirs. */
private fun lifetimeFree(heightDp: Float, header: Boolean): Float =
    heightDp - VERTICAL_PADDING_DP - if (header) HEADER_DP else 0f

/**
 * How many pairs a widget [heightDp] tall can list.
 *
 * [ROW_DP] is what a row needs rather than what it gets — a row with room to spare takes it. The
 * ceiling exists because a `RemoteViews` is an IPC payload: every row carries a `PendingIntent`
 * and possibly a Chronometer, and a launcher that lets a widget be dragged to the full height of
 * the screen would otherwise build one out of forty of them.
 */
internal fun lifetimeRows(heightDp: Float, header: Boolean): Int =
    fits(lifetimeFree(heightDp, header), ROW_DP, max = MAX_ROWS)

/**
 * Whether [pairs] rows should share the whole height between them, or take a settled one and leave
 * the rest at the bottom.
 *
 * Sharing it out is right up to a point: rows crammed at the top with a band of empty background
 * underneath look broken rather than roomy. Past [ROOMY_ROW_DP] apiece it stops being right —
 * three pairs spread evenly down a widget five cells tall sit a row's own height apart, which
 * reads as a list with holes in it rather than a short list.
 */
internal fun lifetimeStretches(heightDp: Float, header: Boolean, pairs: Int): Boolean =
    pairs * ROOMY_ROW_DP >= lifetimeFree(heightDp, header)

private const val ROW_DP = 24f
private const val ROOMY_ROW_DP = 48f
private const val MAX_ROWS = 12

@Composable
private fun ColumnScope.PairRow(
    context: Context,
    row: WidgetRow,
    now: Long,
    timers: Boolean,
    stretch: Boolean,
) {
    val connected = row.openSince != null
    Row(
        // See [lifetimeStretches] for which of the two this is and why.
        modifier = GlanceModifier
            .fillMaxWidth()
            .then(if (stretch) GlanceModifier.defaultWeight() else GlanceModifier.height(ROOMY_ROW_DP.dp))
            .padding(vertical = 2.dp)
            .clickable(openPair(context, row.pairId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(GlanceModifier.defaultWeight()) {
            Text(
                text = row.label,
                maxLines = 1,
                style = TextStyle(
                    color = if (connected) GlanceTheme.colors.primary else GlanceTheme.colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = if (connected) FontWeight.Medium else FontWeight.Normal,
                ),
            )
            // Emitted for every row, connected or not — see [ConnectedFor].
            if (timers) ConnectedFor(context, row.openSince, now)
        }
        Text(
            text = formatHours(row.lifetimeMs),
            maxLines = 1,
            style = TextStyle(
                color = if (connected) GlanceTheme.colors.primary else GlanceTheme.colors.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

/**
 * The live element, handed to the system rather than repainted.
 *
 * `devices_connected_for` is used as the Chronometer's own format string. Passing the phrase in
 * rather than putting a separate "connected" label beside the timer is what keeps it grammatical
 * in the languages that put the duration first, and it is already a format string everywhere else
 * in the app, so lint holds all 77 translations to the same placeholder.
 *
 * A disconnected row hides the timer rather than leaving it out, because a launcher does not
 * re-inflate a widget it already has: `AppWidgetHostView` recycles the view and *reapplies* the new
 * `RemoteViews` onto it. Reapplying a tree of a different shape lands a `TextView`'s action on
 * whatever now sits at that position, which throws and leaves the launcher showing the half-updated
 * view it already had — with a `Chronometer` the system goes on ticking, counting a session that
 * ended minutes ago. Composing the same shape either way is what makes an update land at all.
 */
@Composable
private fun ConnectedFor(context: Context, openSince: Long?, now: Long) {
    AndroidRemoteViews(
        remoteViews = RemoteViews(context.packageName, R.layout.widget_chronometer).apply {
            setViewVisibility(R.id.widget_chronometer, if (openSince == null) View.GONE else View.VISIBLE)
            setChronometer(
                R.id.widget_chronometer,
                chronometerBase(openSince ?: now, now, SystemClock.elapsedRealtime()),
                context.getString(R.string.devices_connected_for),
                openSince != null,
            )
        },
    )
}
