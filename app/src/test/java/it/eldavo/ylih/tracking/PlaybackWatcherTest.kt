package it.eldavo.ylih.tracking

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Playback time is the number that separates "the headphones were on my desk, connected" from
 * "I was listening", so the two ways it can be wrong both matter: crediting a span twice, and
 * crediting a span that never happened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class PlaybackWatcherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val shadowAudio = shadowOf(audioManager)

    private var credited = 0L
    private val watcher = PlaybackWatcher(audioManager) { credited += it }

    private fun attributes(usage: Int): AudioAttributes =
        AudioAttributes.Builder().setUsage(usage).build()

    private fun report(vararg usages: Int) {
        shadowAudio.setActivePlaybackConfigurationsFor(usages.map(::attributes), true)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `silence credits nothing however often it is looked at`() {
        watcher.refresh(now = 1_000)
        watcher.refresh(now = 60_000)

        assertFalse(watcher.isPlaying)
        assertEquals(0L, credited)
    }

    @Test
    fun `a player that never appears in the callback list is caught by the poll`() {
        // The safety net: some apps never show up in the active-configuration list at all.
        shadowAudio.setIsMusicActive(true)

        watcher.refresh(now = 1_000)
        assertTrue(watcher.isPlaying)
        assertEquals("nothing has elapsed yet", 0L, credited)

        watcher.refresh(now = 4_000)
        assertEquals(3_000L, credited)
    }

    @Test
    fun `time is banked as it goes rather than only at the end`() {
        // A twelve-hour flight has to be credited even if the process dies before the disconnect.
        shadowAudio.setIsMusicActive(true)
        watcher.refresh(now = 0)

        watcher.refresh(now = 3_600_000)
        watcher.refresh(now = 7_200_000)

        assertEquals(7_200_000L, credited)
    }

    @Test
    fun `pausing credits the slice played and then stops the clock`() {
        shadowAudio.setIsMusicActive(true)
        watcher.refresh(now = 1_000)

        shadowAudio.setIsMusicActive(false)
        watcher.refresh(now = 3_000)
        assertFalse(watcher.isPlaying)
        assertEquals(2_000L, credited)

        watcher.refresh(now = 900_000)
        assertEquals("a pause is not listening", 2_000L, credited)
    }

    @Test
    fun `stopping the watcher banks the last slice`() {
        shadowAudio.setIsMusicActive(true)
        watcher.start(Handler(Looper.getMainLooper()))
        watcher.refresh(now = 1_000)

        watcher.stop(now = 5_000)

        assertFalse(watcher.isPlaying)
        assertEquals(4_000L, credited)
    }

    @Test
    fun `rebasing throws away time rather than crediting it to the wrong session`() {
        // Happens when the headphones being watched change mid-playback: the time since the
        // last tick belongs to nobody, and must not land on the pair that just connected.
        shadowAudio.setIsMusicActive(true)
        watcher.refresh(now = 1_000)

        watcher.rebase(now = 5_000)
        watcher.refresh(now = 6_000)

        assertEquals(1_000L, credited)
    }

    @Test
    fun `rebasing while nothing plays leaves the watcher idle`() {
        watcher.rebase(now = 5_000)
        watcher.refresh(now = 6_000)

        assertFalse(watcher.isPlaying)
        assertEquals(0L, credited)
    }

    @Test
    fun `the usages a person would call listening start the clock`() {
        watcher.start(Handler(Looper.getMainLooper()))

        for (usage in listOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
        )) {
            report()
            report(usage)
            assertTrue("usage $usage should count as playback", watcher.isPlaying)
        }
    }

    @Test
    fun `a notification chime is not a listening session`() {
        watcher.start(Handler(Looper.getMainLooper()))

        for (usage in listOf(
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
        )) {
            report(usage)
            assertFalse("usage $usage should not count as playback", watcher.isPlaying)
        }

        // ...but a chime over the top of music does not end the music either.
        report(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_NOTIFICATION)
        assertTrue(watcher.isPlaying)
    }
}
