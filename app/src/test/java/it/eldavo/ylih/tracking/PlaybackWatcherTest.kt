package it.eldavo.ylih.tracking

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.data.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.PlaybackInfoBuilder

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

    /**
     * What the callback stamps its slices with. Every other path here is handed an instant by its
     * caller; before the watcher took a clock this one read the wall, so a slice measured between
     * the two meant nothing — the callback could only ever be asked whether it was playing, never
     * what it banked.
     */
    private var clockNow = 0L
    private val watcher = PlaybackWatcher(audioManager, Clock { clockNow }) { credited += it }

    private fun attributes(usage: Int): AudioAttributes =
        AudioAttributes.Builder().setUsage(usage).build()

    private fun report(vararg usages: Int) {
        shadowAudio.setActivePlaybackConfigurationsFor(usages.map(::attributes), true)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * [PlaybackWatcher.refresh] and [PlaybackWatcher.stop] hand their slice back rather than
     * pushing it, so the service can credit it *before* closing the session it belongs to. These
     * stand in for that caller; [credited] is what the phone's database would hold.
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
     * then keeps the clock running for the new one. This used to `rebase` instead — the same clock
     * reset, but throwing the slice away rather than handing it back — so the pair just taken off
     * silently lost everything played since the last tick.
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

    /**
     * The one edge that pushes rather than handing its slice back, measured.
     *
     * [PlaybackWatcher.refresh] and [PlaybackWatcher.stop] return their slice so the service can
     * credit it before closing a session; the callback has no caller to hand anything to and so
     * calls `onDelta` itself. That is the path a process death loses time on, untested for a value
     * until the watcher took a clock.
     */
    @Test
    fun `a callback edge banks the slice it measured`() {
        watcher.start(Handler(Looper.getMainLooper()))
        report(AudioAttributes.USAGE_MEDIA)
        assertEquals("nothing has elapsed yet", 0L, credited)

        clockNow += 40_000
        // A second player starting is an edge like any other; the time between them is the point.
        report(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME)

        assertEquals(40_000L, credited)
    }

    @Test
    fun `a callback edge below the floor banks nothing and loses nothing`() {
        watcher.start(Handler(Looper.getMainLooper()))
        report(AudioAttributes.USAGE_MEDIA)

        clockNow += 5_000
        report(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME)
        assertEquals("a chatty phone must not write on every player it starts", 0L, credited)

        // The service's own tick passes no floor, and finds the whole span still accruing.
        clockNow += 5_000
        refresh(now = clockNow)
        assertEquals(10_000L, credited)
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

    // --- leaving apps out (#69) ---------------------------------------------------------------

    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private var seenApps = emptySet<String>()
    private val sessionWatcher = PlaybackWatcher(
        audioManager,
        Clock { clockNow },
        sessions = MediaSessions(sessionManager, MediaSessions.listener(context)),
        onPlayingApps = { seenApps = it },
    ) { credited += it }

    /**
     * A media session as another app would publish one. The audio it stands for is reported
     * separately, through [report] — on a phone the two arrive unlinked, which is the whole
     * difficulty.
     */
    private fun session(
        packageName: String,
        state: Int = PlaybackState.STATE_PLAYING,
        playbackType: Int = MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL,
    ): MediaController {
        val controller = MediaController(context, MediaSession(context, packageName).sessionToken)
        shadowOf(controller).setPackageName(packageName)
        shadowOf(controller).setPlaybackInfo(
            PlaybackInfoBuilder.newBuilder().setVolumeType(playbackType).build(),
        )
        setState(controller, state)
        shadowOf(sessionManager).addController(controller)
        return controller
    }

    private fun setState(controller: MediaController, state: Int) {
        val playbackState = PlaybackState.Builder().setState(state, 0L, 1f).build()
        shadowOf(controller).setPlaybackState(playbackState)
        shadowOf(controller).executeOnPlaybackStateChanged(playbackState)
    }

    private fun sessionRefresh(now: Long) {
        credited += sessionWatcher.refresh(now)
    }

    @Test
    fun `with nothing left out the sessions change nothing`() {
        // The promise to everyone who never touches the list: the same rule as before.
        sessionWatcher.start(Handler(Looper.getMainLooper()))
        session(AUTOMATE)
        report(AudioAttributes.USAGE_MEDIA)

        clockNow += 10_000
        sessionRefresh(clockNow)

        assertTrue(sessionWatcher.isPlaying)
        assertEquals(10_000L, credited)
    }

    @Test
    fun `an app left out playing alone is not listening`() {
        // #69 itself: Automate holding a silent stream open to catch headset buttons.
        sessionWatcher.excluded = setOf(AUTOMATE)
        sessionWatcher.start(Handler(Looper.getMainLooper()))
        session(AUTOMATE)
        report(AudioAttributes.USAGE_MEDIA)

        clockNow += 600_000
        sessionRefresh(clockNow)

        assertFalse(sessionWatcher.isPlaying)
        assertEquals(0L, credited)
    }

    @Test
    fun `the poll does not count what an app left out is playing either`() {
        // isMusicActive is the safety net for players the callback list never shows, and it
        // cannot say whose music is active — it must not let the silent stream back in.
        sessionWatcher.excluded = setOf(AUTOMATE)
        sessionWatcher.start(Handler(Looper.getMainLooper()))
        session(AUTOMATE)
        shadowAudio.setIsMusicActive(true)

        clockNow += 60_000
        sessionRefresh(clockNow)

        assertEquals(0L, credited)
    }

    @Test
    fun `music beside an app left out still counts`() {
        sessionWatcher.excluded = setOf(AUTOMATE)
        sessionWatcher.start(Handler(Looper.getMainLooper()))
        session(AUTOMATE)
        session(SPOTIFY)
        // Spotify's own player need not be media-like for its claim to count.
        report(AudioAttributes.USAGE_MEDIA)

        sessionRefresh(clockNow)
        clockNow += 30_000
        sessionRefresh(clockNow)

        assertEquals(30_000L, credited)
    }

    @Test
    fun `more players than the apps left out explain still count`() {
        // A game publishes no session, so only the count of players can say it is there.
        sessionWatcher.excluded = setOf(AUTOMATE)
        sessionWatcher.start(Handler(Looper.getMainLooper()))
        session(AUTOMATE)
        report(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME)

        sessionRefresh(clockNow)
        clockNow += 20_000
        sessionRefresh(clockNow)

        assertEquals(20_000L, credited)
    }

    @Test
    fun `a paused app or one casting elsewhere says nothing about these headphones`() {
        sessionWatcher.excluded = setOf(AUTOMATE)
        sessionWatcher.start(Handler(Looper.getMainLooper()))
        session(AUTOMATE, state = PlaybackState.STATE_PAUSED)
        session("com.example.cast", playbackType = MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE)
        report(AudioAttributes.USAGE_MEDIA)

        sessionRefresh(clockNow)
        clockNow += 15_000
        sessionRefresh(clockNow)

        assertEquals("nothing left out claims to be playing here, so the rule is the old one", 15_000L, credited)
        assertEquals(emptySet<String>(), seenApps)
    }

    @Test
    fun `an app left out starting to play banks the music before it`() {
        // A session's state is an edge the watcher has to push, like a player's.
        sessionWatcher.excluded = setOf(AUTOMATE)
        sessionWatcher.start(Handler(Looper.getMainLooper()))
        val automate = session(AUTOMATE, state = PlaybackState.STATE_PAUSED)
        report(AudioAttributes.USAGE_MEDIA)
        assertEquals(0L, credited)

        clockNow += 45_000
        setState(automate, PlaybackState.STATE_PLAYING)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("the 45 s before it took over were listening", 45_000L, credited)
        assertFalse(sessionWatcher.isPlaying)
    }

    @Test
    fun `apps seen playing are handed over once each`() {
        val handed = mutableListOf<Set<String>>()
        val watcher = PlaybackWatcher(
            audioManager,
            Clock { clockNow },
            sessions = MediaSessions(sessionManager, MediaSessions.listener(context)),
            onPlayingApps = { handed += it },
        ) {}
        watcher.start(Handler(Looper.getMainLooper()))
        session(SPOTIFY)
        watcher.refresh(clockNow)
        watcher.refresh(clockNow)
        session(AUTOMATE)
        watcher.refresh(clockNow)

        assertEquals(listOf(setOf(SPOTIFY), setOf(SPOTIFY, AUTOMATE)), handed.distinct())
        assertEquals("an unchanged set is not reported again", 2, handed.size)
    }

    @Test
    fun `the rule, in full`() {
        val none = SessionPlayback.NONE
        val automate = SessionPlayback(excluded = 1, others = 0, packages = setOf(AUTOMATE))
        val both = SessionPlayback(excluded = 1, others = 1, packages = setOf(AUTOMATE, SPOTIFY))

        assertFalse("silence is never listening", isListening(0, audible = false, claimed = none))
        assertFalse(isListening(1, audible = false, claimed = automate))
        assertTrue("no claims: the old rule", isListening(1, audible = true, claimed = none))
        assertTrue(isListening(0, audible = true, claimed = none))
        assertFalse("only the app left out", isListening(1, audible = true, claimed = automate))
        assertFalse(isListening(0, audible = true, claimed = automate))
        assertTrue("one player over", isListening(2, audible = true, claimed = automate))
        assertTrue("another app says it is playing", isListening(1, audible = true, claimed = both))
    }

    private companion object {
        const val AUTOMATE = "com.llamalab.automate"
        const val SPOTIFY = "com.spotify.music"
    }
}
