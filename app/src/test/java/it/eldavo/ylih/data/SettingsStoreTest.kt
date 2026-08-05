package it.eldavo.ylih.data

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.YlihApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings all live in one table, so every one of them is a flow over the same query — and the
 * app collects four of them at once, one `stateIn` per setting in the view model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class SettingsStoreTest {

    private val settings = (ApplicationProvider.getApplicationContext() as YlihApp).container.settings

    /**
     * Pins the failure that made the welcome and hibernation prompts vanish: collected together,
     * the flows handed each other's values, and a `false` read as the `true` sitting in the next
     * row. Every setting here is deliberately given a different value from its neighbours, because
     * a fixture where they agree cannot tell a crossed read from a correct one.
     */
    @Test
    fun `settings collected together keep their own values`() = runBlocking {
        settings.setOnboardingDone(true)
        settings.setHibernationAsked(false)
        settings.setDetailedTracking(true)
        settings.setPlaybackOnly(false)
        settings.setLanguage("pt-BR")

        val seen = mutableMapOf<String, Any?>()
        val collectors = listOf(
            launch { settings.onboardingDone.collect { seen["onboarding"] = it } },
            launch { settings.hibernationAsked.collect { seen["hibernation"] = it } },
            launch { settings.detailedTracking.collect { seen["detailed"] = it } },
            launch { settings.playbackOnly.collect { seen["playback"] = it } },
            launch { settings.language.collect { seen["language"] = it } },
        )
        while (seen.size < 5) delay(10)
        collectors.forEach { it.cancel() }

        assertEquals(true, seen["onboarding"])
        assertEquals(false, seen["hibernation"])
        assertEquals(true, seen["detailed"])
        assertEquals(false, seen["playback"])
        assertEquals("pt-BR", seen["language"])
    }

    /** The same values through the one-shot reads, which the service and the receivers use. */
    @Test
    fun `the one-shot reads agree with the flows`() = runBlocking {
        settings.setOnboardingDone(true)
        settings.setHibernationAsked(false)
        settings.setDetailedTracking(true)
        settings.setPlaybackOnly(false)
        settings.setLanguage("pt-BR")

        assertEquals(true, settings.onboardingDoneNow())
        assertEquals(true, settings.detailedTrackingNow())
        assertEquals(false, settings.playbackOnlyNow())
        assertEquals("pt-BR", settings.languageNow())
    }

    /** An unwritten setting reads as its Kotlin default rather than as a missing row. */
    @Test
    fun `a setting never written falls back to its default`() = runBlocking {
        assertEquals(false, settings.onboardingDoneNow())
        assertEquals("", settings.languageNow())
    }
}
