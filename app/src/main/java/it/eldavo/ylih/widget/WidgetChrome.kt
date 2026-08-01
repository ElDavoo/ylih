package it.eldavo.ylih.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import it.eldavo.ylih.MainActivity
import it.eldavo.ylih.R

/**
 * A launcher grid measurement in the dp `minWidth`/`minHeight` are expressed in: 70 per cell,
 * less the 30 of inter-cell gap that does not belong to the widget.
 */
internal fun cells(count: Int): Dp = (70 * count - 30).dp

/**
 * The frame all three widgets share: rounded background, padding, and a tap that opens the app.
 *
 * `appWidgetBackground()` marks the view Android 12+ launchers round and clip to their own
 * radius; below that the drawable's own corners are all there is.
 */
@Composable
internal fun WidgetRoot(context: Context, content: @Composable ColumnScope.() -> Unit) {
    WidgetTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(ImageProvider(R.drawable.widget_background))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable(openApp(context)),
            content = content,
        )
    }
}

/** The line a widget puts above its figures — or the warning that the figures mean something else. */
@Composable
internal fun WidgetHeader(context: Context, title: String, playbackOnly: Boolean) {
    Text(
        text = if (playbackOnly) context.getString(R.string.stats_playback_only_note) else title,
        maxLines = 1,
        style = TextStyle(
            color = if (playbackOnly) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}

/** A figure over its caption. The unit the activity widget is built out of. */
@Composable
internal fun WidgetTile(value: String, label: String, modifier: GlanceModifier = GlanceModifier) {
    // The gap belongs to the tile rather than to a Spacer between tiles: a caption that fills its
    // whole share of the row otherwise runs straight into the next one with nothing between them,
    // and "all headphonestoday" is what that reads as.
    Column(modifier.padding(end = 8.dp)) {
        Text(
            text = value,
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Text(
            text = label,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
        )
    }
}

internal fun openApp(context: Context): Action = openIntent(context, null)

internal fun openPair(context: Context, pairId: Long): Action = openIntent(context, pairId)

/**
 * Every tap target gets its own `data` URI. A `PendingIntent` is told apart by action, data, type,
 * component and categories and never by its extras, so without this the eight rows of a tall
 * lifetime widget would collapse into one intent and all open the same pair.
 */
private fun openIntent(context: Context, pairId: Long?): Action =
    actionStartActivity(
        Intent(context, MainActivity::class.java).apply {
            data = "ylih://widget/${pairId ?: "home"}".toUri()
            pairId?.let { putExtra(MainActivity.EXTRA_PAIR_ID, it) }
        },
    )
