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

    /**
     * [PlaybackWatcher.refresh] and [PlaybackWatcher.stop] hand their slice back rather than
     * pushing it, so that the service can credit it *before* closing the session it belongs to.
     * These stand in for that caller; [credited] is what the phone's database would hold.
     */
    private fun refresh(now: Long, minSliceMs: Long = 0L) {
        credited += watcher.refresh(now, minSliceMs)
    }

    private fun stop(now: Long) {
        credited += watcher.stop(now)
    }

    @Test
    fun `silence credits nothing however often it is looked at`() {
        refresh(now = 1_000)
        refresh(now = 60_000)

        assertFalse(watcher.isPlaying)
        assertEquals(0L, credited)
    }

    @Test
    fun `a player that never appears in the callback list is caught by the poll`() {
        // The safety net: some apps never show up in the active-configuration list at all.
        shadowAudio.setIsMusicActive(true)

        refresh(now = 1_000)
        assertTrue(watcher.isPlaying)
        assertEquals("nothing has elapsed yet", 0L, credited)

        refresh(now = 4_000)
        assertEquals(3_000L, credited)
    }

    @Test
    fun `time is banked as it goes rather than only at the end`() {
        // A twelve-hour flight has to be credited even if the process dies before the disconnect.
        shadowAudio.setIsMusicActive(true)
        refresh(now = 0)

        refresh(now = 3_600_000)
        refresh(now = 7_200_000)

        assertEquals(7_200_000L, credited)
    }

    @Test
    fun `a slice too short to be worth a write keeps accruing instead`() {
        // What the floor is for: the callback fires on any app's player changing state, and
        // without it a chatty phone writes to the database on every one of them.
        shadowAudio.setIsMusicActive(true)
        refresh(now = 0)

        refresh(now = 5_000, minSliceMs = 30_000)
        assertEquals("too small a slice to bank", 0L, credited)

        refresh(now = 40_000, minSliceMs = 30_000)
        assertEquals("and none of it was dropped", 40_000L, credited)
    }

    @Test
    fun `the floor never costs a span that has actually ended`() {
        shadowAudio.setIsMusicActive(true)
        refresh(now = 0)
        refresh(now = 1_000, minSliceMs = 30_000)

        shadowAudio.setIsMusicActive(false)
        refresh(now = 2_000, minSliceMs = 30_000)

        assertEquals("stopping credits in full, floor or no floor", 2_000L, credited)
    }

    @Test
    fun `pausing credits the slice played and then stops the clock`() {
        shadowAudio.setIsMusicActive(true)
        refresh(now = 1_000)

        shadowAudio.setIsMusicActive(false)
        refresh(now = 3_000)
        assertFalse(watcher.isPlaying)
        assertEquals(2_000L, credited)

        refresh(now = 900_000)
        assertEquals("a pause is not listening", 2_000L, credited)
    }

    @Test
    fun `stopping the watcher banks the last slice`() {
        shadowAudio.setIsMusicActive(true)
        watcher.start(Handler(Looper.getMainLooper()))
        refresh(now = 1_000)

        stop(now = 5_000)

        assertFalse(watcher.isPlaying)
        assertEquals(4_000L, credited)
    }

    /**
     * What swapping headphones mid-song looks like from here.
     *
     * The service banks at the moment the new pair connects and credits the slice to the old one,
     * then keeps the clock running for the new one. This used to `rebase` instead — the same reset
     * of the clock, but throwing the slice away rather than handing it back — so the pair just
     * taken off silently lost everything it had played since the last tick.
     */
    @Test
    fun `banking at a swap credits the pair that was playing and keeps the clock running`() {
        shadowAudio.setIsMusicActive(true)
        refresh(now = 1_000)

        refresh(now = 5_000)
        assertEquals("the outgoing pair is credited in full", 4_000L, credited)
        assertTrue("and the new one starts measuring straight away", watcher.isPlaying)

        refresh(now = 6_000)
        assertEquals("with no part of the swap counted twice", 5_000L, credited)
    }

    @Test
    fun `banking while nothing plays leaves the watcher idle`() {
        refresh(now = 5_000)
        refresh(now = 6_000)

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
