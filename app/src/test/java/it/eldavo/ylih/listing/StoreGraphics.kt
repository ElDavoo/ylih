package it.eldavo.ylih.listing

import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.withTranslation
import com.github.takahirom.roborazzi.captureRoboImage
import it.eldavo.ylih.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot

/**
 * The two non-screenshot images the Play Console demands: a 512x512 icon and a 1024x500 feature
 * graphic. Both are rendered from `@mipmap/ic_launcher` itself rather than redrawn, so the
 * carefully solved geometry in `ic_launcher_foreground.xml` cannot drift out of the store listing.
 *
 * Exact pixel sizes come from the Robolectric qualifiers: at mdpi one dp is one pixel, so a
 * `w512dp-h512dp-mdpi` screen is a 512x512 canvas.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class StoreGraphics {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "en-rUS-w512dp-h512dp-mdpi")
    fun `store icon`() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(BRAND)) {
                LauncherArt(Modifier.fillMaxSize(), withBackground = true)
            }
        }
        compose.onRoot().captureRoboImage("icon-512.png")
    }

    @Test
    @Config(qualifiers = "en-rUS-w1024dp-h500dp-land-mdpi")
    fun `feature graphic`() {
        compose.setContent {
            Row(
                modifier = Modifier.fillMaxSize().background(BRAND).padding(horizontal = 96.dp),
                verticalAlignment = Alignment.CenterVertically,
                // Play crops the feature graphic differently across its surfaces, so the whole
                // lockup is centred rather than left-aligned against one edge.
                horizontalArrangement = Arrangement.spacedBy(56.dp, Alignment.CenterHorizontally),
            ) {
                // The background layer is already the surrounding colour, so only the artwork
                // is drawn — no second square inside the banner.
                LauncherArt(Modifier.size(240.dp), withBackground = false)
                Column {
                    Text(
                        text = "ylih",
                        color = Color.White,
                        fontSize = 108.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "How many hours did they last?",
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 34.sp,
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("feature-graphic-1024x500.png")
    }

    /**
     * GitHub's social preview — the card link unfurls show on Google, Twitter/X, Slack and
     * Mastodon. GitHub recommends 1280x640 and crops anything else, so the size is exact rather
     * than a scaled feature graphic.
     *
     * It cannot be set from the API: the upload lives only in the repository settings page, so
     * this writes a file for a human to attach. The committed copy is `docs/img/social-preview.png`.
     */
    @Test
    @Config(qualifiers = "en-rUS-w1280dp-h640dp-land-mdpi")
    fun `social preview`() {
        compose.setContent {
            Column(
                modifier = Modifier.fillMaxSize().background(BRAND).padding(horizontal = 112.dp),
                verticalArrangement = Arrangement.Center,
                // Every surface that shows this card crops it slightly differently, so the lockup
                // is centred rather than set against the left edge — as the feature graphic is.
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                ) {
                    // As in the feature graphic: the surrounding colour is already the icon's
                    // background layer, so drawing it again would box the artwork in.
                    LauncherArt(Modifier.size(200.dp), withBackground = false)
                    Column {
                        Text(
                            text = "ylih",
                            color = Color.White,
                            fontSize = 104.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "your life in headphones",
                            color = Color.White.copy(alpha = 0.82f),
                            fontSize = 36.sp,
                        )
                    }
                }
                Text(
                    // The card is read as a search result, so it repeats what the app does in
                    // words rather than repeating the name in a tagline.
                    text = "Track how many hours each pair of headphones lasts.\n" +
                        "Offline, forever, private.",
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 34.sp,
                    modifier = Modifier.padding(top = 56.dp),
                )
            }
        }
        compose.onRoot().captureRoboImage("social-preview-1280x640.png")
    }

    /**
     * Draws the adaptive icon's own layers instead of the drawable itself. Drawing the
     * `AdaptiveIconDrawable` would apply the platform's circular mask, and Play expects a full
     * square it can round off in its own way.
     *
     * The layers live on a 108dp canvas of which only the middle 72dp is ever visible; scaling by
     * 108/72 and centring crops that bleed away, so the artwork fills the square the way a store
     * icon should instead of floating at half size.
     */
    @Composable
    private fun LauncherArt(modifier: Modifier, withBackground: Boolean) {
        val context = LocalContext.current
        androidx.compose.foundation.Canvas(modifier) {
            val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                as AdaptiveIconDrawable
            drawLayers(icon, withBackground)
        }
    }

    private fun DrawScope.drawLayers(icon: AdaptiveIconDrawable, withBackground: Boolean) {
        val full = (size.width * 108f / 72f).toInt()
        val bleed = ((full - size.width) / 2f).toInt()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.withTranslation(-bleed.toFloat(), -bleed.toFloat()) {
                if (withBackground) {
                    icon.background?.apply {
                        setBounds(0, 0, full, full)
                        draw(native)
                    }
                }
                icon.foreground?.apply {
                    setBounds(0, 0, full, full)
                    draw(native)
                }
            }
        }
    }

    private companion object {
        /** `@color/ic_launcher_background`, the one colour the app calls its own. */
        val BRAND = Color(0xFF00344B)
    }
}
