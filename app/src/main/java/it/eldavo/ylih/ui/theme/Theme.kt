package it.eldavo.ylih.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/*
 * One tonal palette, seeded from the cyan-blue the launcher icon is drawn on. The tone numbers in
 * the names are Material's: 40/90/10 carry a light scheme, 80/30/90 the dark one.
 *
 * This used to be three colours passed to `lightColorScheme()`, which quietly left every
 * container role at Material's default purple — a blue app with lilac chips. Roles come in sets;
 * overriding one member of a set and not the rest is what produced that.
 */
private val Primary10 = Color(0xFF001E2C)
private val Primary20 = Color(0xFF00344B) // also @color/ic_launcher_background
private val Primary30 = Color(0xFF004C6B)
private val Primary40 = Color(0xFF00658E)
private val Primary80 = Color(0xFF84CFFF)
private val Primary90 = Color(0xFFC7E7FF)

private val Secondary10 = Color(0xFF0B1D28)
private val Secondary20 = Color(0xFF21333E)
private val Secondary30 = Color(0xFF364955)
private val Secondary40 = Color(0xFF4E616D)
private val Secondary80 = Color(0xFFB5CAD7)
private val Secondary90 = Color(0xFFD1E6F4)

private val Tertiary10 = Color(0xFF1E1735)
private val Tertiary20 = Color(0xFF332D4B)
private val Tertiary30 = Color(0xFF4A4363)
private val Tertiary40 = Color(0xFF625A7C)
private val Tertiary80 = Color(0xFFCBC1E9)
private val Tertiary90 = Color(0xFFE7DEFF)

// Not private: the home-screen widgets have their own theme entry point (glance's GlanceTheme
// cannot be nested inside this one) and it wraps these two schemes, so the app and the widgets
// cannot end up on different palettes.
internal val LightColors = lightColorScheme(
    primary = Primary40,
    onPrimary = Color.White,
    primaryContainer = Primary90,
    onPrimaryContainer = Primary10,
    secondary = Secondary40,
    onSecondary = Color.White,
    secondaryContainer = Secondary90,
    onSecondaryContainer = Secondary10,
    tertiary = Tertiary40,
    onTertiary = Color.White,
    tertiaryContainer = Tertiary90,
    onTertiaryContainer = Tertiary10,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFCFCFF),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFCFCFF),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF41484D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3F6),
    surfaceContainer = Color(0xFFEDEDF0),
    surfaceContainerHigh = Color(0xFFE7E8EB),
    surfaceContainerHighest = Color(0xFFE2E2E5),
    outline = Color(0xFF71787E),
    outlineVariant = Color(0xFFC1C7CE),
)

/** Internal for the same reason as [LightColors]. */
internal val DarkColors = darkColorScheme(
    primary = Primary80,
    onPrimary = Primary20,
    primaryContainer = Primary30,
    onPrimaryContainer = Primary90,
    secondary = Secondary80,
    onSecondary = Secondary20,
    secondaryContainer = Secondary30,
    onSecondaryContainer = Secondary90,
    tertiary = Tertiary80,
    onTertiary = Tertiary20,
    tertiaryContainer = Tertiary30,
    onTertiaryContainer = Tertiary90,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF191C1E),
    onBackground = Color(0xFFE2E2E5),
    surface = Color(0xFF191C1E),
    onSurface = Color(0xFFE2E2E5),
    surfaceVariant = Color(0xFF41484D),
    onSurfaceVariant = Color(0xFFC1C7CE),
    surfaceContainerLowest = Color(0xFF0C0F11),
    surfaceContainerLow = Color(0xFF191C1E),
    surfaceContainer = Color(0xFF1D2022),
    surfaceContainerHigh = Color(0xFF272A2D),
    surfaceContainerHighest = Color(0xFF323538),
    outline = Color(0xFF8B9198),
    outlineVariant = Color(0xFF41484D),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YlihTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Off for the Play listing screenshots only. Dynamic colour resolves to whatever the user's
     * wallpaper says, and under Robolectric that is the AOSP default — nobody's real phone. The
     * store images show the palette above, which is the app's own identity.
     */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialExpressiveTheme(
        colorScheme = colors,
        // The expressive scheme is springier and slightly overshoots. It is the whole point of
        // the M3 Expressive update, and it costs nothing here because every animation in the app
        // is a component default.
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
