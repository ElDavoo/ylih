package it.eldavo.ylih.widget

import android.content.Context
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
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

    override val sizeMode = SizeMode.Responsive(setOf(ONE_ROW, THREE_ROWS, EIGHT_ROWS))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (localized, data) = widgetContent(context)
        provideContent { LifetimeContent(localized, data) }
    }

    private companion object {
        val ONE_ROW = DpSize(cells(4), cells(1))
        val THREE_ROWS = DpSize(cells(4), cells(2))
        val EIGHT_ROWS = DpSize(cells(4), cells(4))
    }
}

// Internal rather than private purely so provideGlance's lambda can reach it without the compiler
// inserting a synthetic accessor (lint's SyntheticAccessor); the other two widgets do the same.
@Composable
internal fun LifetimeContent(context: Context, data: WidgetData) {
    val height = LocalSize.current.height
    // One row is the connected pair, because the list is sorted to put it first. That is the
    // whole content of the shortest widget: a lifetime total with no context is a wall of
    // numbers, and the connected pair is the one changing.
    val rows = when {
        height >= cells(4) -> 8
        height >= cells(2) -> 3
        else -> 1
    }
    WidgetRoot(context) {
        if (rows > 1) {
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
            data.rows.take(rows).forEach { PairRow(context, it, data.now) }
        }
    }
}

@Composable
private fun ColumnScope.PairRow(context: Context, row: WidgetRow, now: Long) {
    val connected = row.openSince != null
    Row(
        // Weighted rather than wrapped: the size bucket says how many rows to draw, but the
        // launcher decides the actual height, and a widget with its rows crammed at the top and a
        // band of empty background underneath looks broken rather than roomy.
        modifier = GlanceModifier
            .fillMaxWidth()
            .defaultWeight()
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
            if (row.openSince != null) ConnectedFor(context, row.openSince, now)
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
 */
@Composable
private fun ConnectedFor(context: Context, openSince: Long, now: Long) {
    AndroidRemoteViews(
        remoteViews = RemoteViews(context.packageName, R.layout.widget_chronometer).apply {
            setChronometer(
                R.id.widget_chronometer,
                chronometerBase(openSince, now, SystemClock.elapsedRealtime()),
                context.getString(R.string.devices_connected_for),
                true,
            )
        },
    )
}
