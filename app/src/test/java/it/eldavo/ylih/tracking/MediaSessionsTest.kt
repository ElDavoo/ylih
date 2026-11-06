package it.eldavo.ylih.tracking

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.PlaybackInfoBuilder
import org.robolectric.shadows.ShadowMediaSessionManager

/**
 * The grant behind leaving apps out lives on a system screen, given and taken away while the
 * service runs, and nothing tells the app either way — so what must hold is that no grant means
 * no claims, and that a grant changing is noticed by the next resync rather than never.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], shadows = [ShadowGatedMediaSessionManager::class])
class MediaSessionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var changes = 0
    private val sessions = MediaSessions(manager, MediaSessions.listener(context))

    @Before
    fun setUp() {
        ShadowGatedMediaSessionManager.granted = true
    }

    private fun session(packageName: String, state: Int = PlaybackState.STATE_PLAYING): MediaController {
        val controller = MediaController(context, MediaSession(context, packageName).sessionToken)
        shadowOf(controller).setPackageName(packageName)
        shadowOf(controller).setPlaybackInfo(
            PlaybackInfoBuilder.newBuilder()
                .setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL)
                .build(),
        )
        shadowOf(controller).setPlaybackState(PlaybackState.Builder().setState(state, 0L, 1f).build())
        shadowOf(manager).addController(controller)
        return controller
    }

    @Test
    fun `without the grant nothing is claimed, so nobody is left out`() {
        ShadowGatedMediaSessionManager.granted = false
        sessions.start(handler) { changes++ }
        session(AUTOMATE)

        assertEquals(SessionPlayback.NONE, sessions.playing(setOf(AUTOMATE)))
        assertEquals("and no listener was left behind to call back", 0, changes)
    }

    @Test
    fun `a grant given while running is picked up on the next resync`() {
        ShadowGatedMediaSessionManager.granted = false
        sessions.start(handler) { changes++ }
        ShadowGatedMediaSessionManager.granted = true
        session(AUTOMATE)
        assertEquals(SessionPlayback.NONE, sessions.playing(setOf(AUTOMATE)))

        sessions.resync()
        assertEquals(1, sessions.playing(setOf(AUTOMATE)).excluded)

        // Listening now, so a new session is an edge rather than waiting for the next tick.
        session(SPOTIFY)
        assertEquals(1, changes)
        assertEquals(1, sessions.playing(setOf(AUTOMATE)).others)
    }

    @Test
    fun `a grant taken away ends the claims instead of freezing the last ones`() {
        sessions.start(handler) { changes++ }
        session(AUTOMATE)
        assertEquals(1, sessions.playing(setOf(AUTOMATE)).excluded)

        ShadowGatedMediaSessionManager.granted = false
        sessions.resync()

        assertEquals(SessionPlayback.NONE, sessions.playing(setOf(AUTOMATE)))
        val before = changes
        session(SPOTIFY)
        assertEquals("the listener went with the grant", before, changes)
    }

    @Test
    fun `a state change on a session is an edge`() {
        sessions.start(handler) { changes++ }
        val automate = session(AUTOMATE, state = PlaybackState.STATE_PAUSED)
        val before = changes

        shadowOf(automate).executeOnPlaybackStateChanged(
            PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 1f).build(),
        )
        // Delivered through the handler the callback was registered with, as on a phone.
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(before + 1, changes)
    }

    @Test
    fun `stopping lets go of every session`() {
        sessions.start(handler) { changes++ }
        val automate = session(AUTOMATE)

        sessions.stop()

        assertEquals(SessionPlayback.NONE, sessions.playing(setOf(AUTOMATE)))
        assertTrue(shadowOf(automate).callbacks.isEmpty())
        session(SPOTIFY)
        assertEquals("nothing calls back into a stopped watcher", 1, changes)
    }

    @Test
    fun `an app counts once however many sessions it holds`() {
        // The rule weighs claims against players, and one app's two sessions are not two players.
        sessions.start(handler) {}
        session(AUTOMATE)
        session(AUTOMATE)

        assertEquals(SessionPlayback(excluded = 1, others = 0, packages = setOf(AUTOMATE)), sessions.playing(setOf(AUTOMATE)))
    }

    @Test
    fun `the grant is read from the enabled listeners`() {
        val listener = MediaSessions.listener(context)
        Settings.Secure.putString(context.contentResolver, ENABLED_LISTENERS, listener.flattenToString())
        assertTrue(MediaSessions.accessGranted(context))

        Settings.Secure.putString(context.contentResolver, ENABLED_LISTENERS, "")
        assertFalse(MediaSessions.accessGranted(context))
    }

    @Test
    fun `settings can ask what is playing right now, and gets nothing without the grant`() {
        session(AUTOMATE)
        session(SPOTIFY, state = PlaybackState.STATE_STOPPED)
        assertEquals(setOf(AUTOMATE), MediaSessions.playingNow(context))

        ShadowGatedMediaSessionManager.granted = false
        assertEquals(emptySet<String>(), MediaSessions.playingNow(context))
    }

    @Test
    fun `the listener hands its binding straight back`() {
        // Bound, it would keep the process alive for as long as access stays on.
        val service = Robolectric.buildService(SessionAccessService::class.java).create().get()

        service.onListenerConnected()

        assertEquals(1, shadowOf(service).unbindRequestCount)
    }

    /**
     * Pins what the system reads before listing the app under notification access, since a
     * mistake in any of it fails silently: the app is just never offered there.
     */
    @Test
    fun `the listener is declared the way the system looks for one`() {
        val listener = ComponentName(context, SessionAccessService::class.java)
        val info = context.packageManager.getServiceInfo(
            listener,
            PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
        assertEquals(android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, info.permission)
        assertTrue(info.exported)
        assertFalse(
            "bound, the listener keeps the process alive and Bluetooth-only mode stops being idle",
            info.metaData.getBoolean(NotificationListenerService.META_DATA_DEFAULT_AUTOBIND, true),
        )
        val resolved = context.packageManager.queryIntentServices(
            android.content.Intent(NotificationListenerService.SERVICE_INTERFACE).setPackage(context.packageName),
            PackageManager.ResolveInfoFlags.of(0),
        )
        assertNotNull(resolved.singleOrNull { it.serviceInfo.name == SessionAccessService::class.java.name })
    }

    private companion object {
        const val AUTOMATE = "com.llamalab.automate"
        const val SPOTIFY = "com.spotify.music"

        // Settings.Secure.ENABLED_NOTIFICATION_LISTENERS is hidden; NotificationManagerCompat
        // reads the same key by name.
        const val ENABLED_LISTENERS = "enabled_notification_listeners"
    }
}

/**
 * The real service refuses a caller without the grant; Robolectric's never does, which would
 * leave the one branch that matters most untested.
 */
@Implements(MediaSessionManager::class)
class ShadowGatedMediaSessionManager : ShadowMediaSessionManager() {

    @Implementation
    override fun getActiveSessions(notificationListener: ComponentName?): List<MediaController> {
        if (!granted) throw SecurityException("Missing permission to control media.")
        return super.getActiveSessions(notificationListener)
    }

    @Implementation
    override fun addOnActiveSessionsChangedListener(
        sessionListener: MediaSessionManager.OnActiveSessionsChangedListener,
        notificationListener: ComponentName?,
        handler: Handler?,
    ) {
        if (!granted) throw SecurityException("Missing permission to control media.")
        super.addOnActiveSessionsChangedListener(sessionListener, notificationListener, handler)
    }

    companion object {
        var granted = true
    }
}
