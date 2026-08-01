package it.eldavo.ylih.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.glance.GlanceTheme
import androidx.glance.material3.ColorProviders
import it.eldavo.ylih.R
import it.eldavo.ylih.ui.theme.DarkColors
import it.eldavo.ylih.ui.theme.LightColors

/**
 * The app's palette, translated into what Glance understands.
 *
 * `GlanceTheme` cannot be nested inside `YlihTheme` — a widget is composed in this process but
 * laid out in the launcher's, so there is no Compose UI tree to inherit from — but it can be fed
 * the very same two schemes, which is what stops the home screen and the app drifting apart.
 */
@Composable
fun WidgetTheme(content: @Composable () -> Unit) {
    GlanceTheme(
        colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Glance's own default: the wallpaper-derived system_* tokens, matching what
            // YlihTheme does with dynamicLightColorScheme/dynamicDarkColorScheme.
            GlanceTheme.colors
        } else {
            YlihGlanceColors
        },
        content = content,
    )
}

private val YlihGlanceColors = ColorProviders(light = LightColors, dark = DarkColors)

/**
 * The accent the Chronometer and the chart bars are drawn in.
 *
 * Resolved here rather than through [GlanceTheme] because neither of those is Glance: one is a
 * raw `RemoteViews`, the other a bitmap. Below API 31 that also means the value is baked at
 * update time — but so is everything else Glance draws below 31, which resolves its own day/night
 * colours the same way.
 */
internal fun accentColor(context: Context): Color = Color(ContextCompat.getColor(context, R.color.widget_accent))

/** The unfilled part of a chart bar; [accentColor]'s caveats apply. */
internal fun trackColor(context: Context): Color = Color(ContextCompat.getColor(context, R.color.widget_track))
