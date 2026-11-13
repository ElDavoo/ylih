package it.eldavo.ylih.tracking

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import androidx.core.app.NotificationManagerCompat

/**
 * Which apps say they are playing, read off their media sessions — the only per-app account of
 * playback the platform gives an app like this one, and only once [SessionAccessService] is
 * granted.
 *
 * A *claim*, not a measurement: a session reports whatever state its app last set, and nothing
 * links it to the anonymous players [PlaybackWatcher] hears. That is why it can only ever take
 * time away (see [isListening]), and why an app that never publishes a playing session cannot be
 * left out at all — no permission open to a third-party app, on either build, says more.
 */
class MediaSessions(
    private val manager: MediaSessionManager,
    private val listener: ComponentName,
) {
    private var handler: Handler? = null
    private var onChange: () -> Unit = {}
    private var listening = false
    private var controllers: List<MediaController> = emptyList()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { latest ->
        track(latest.orEmpty())
        onChange()
    }

    private val stateChanged = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            onChange()
        }
    }

    fun start(handler: Handler, onChange: () -> Unit) {
        this.handler = handler
        this.onChange = onChange
        resync()
    }

    /**
     * Re-reads the sessions, and starts listening if it was not allowed to before.
     *
     * Called on every tick, because the grant comes and goes on a system screen this has no way
     * to hear from. Granted after [start], the listener would otherwise never be added; revoked,
     * it goes quiet rather than failing, which would leave the last controllers read standing as
     * "playing" for as long as the service ran.
     */
    fun resync() {
        val handler = handler ?: return
        try {
            if (!listening) {
                manager.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler)
                listening = true
            }
            track(manager.getActiveSessions(listener))
        } catch (e: SecurityException) {
            // No grant, or no longer one: there is nothing to tell apart, so nobody is left out.
            release()
        }
    }

    fun stop() {
        release()
        handler = null
        onChange = {}
    }

    fun playing(excluded: Set<String>): SessionPlayback = tally(controllers, excluded)

    private fun release() {
        if (listening) manager.removeOnActiveSessionsChangedListener(sessionsChanged)
        listening = false
        track(emptyList())
    }

    private fun track(latest: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(stateChanged) }
        controllers = latest
        val handler = handler ?: return
        latest.forEach { it.registerCallback(stateChanged, handler) }
    }

    companion object {
        fun listener(context: Context) = ComponentName(context, SessionAccessService::class.java)

        fun accessGranted(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /**
         * The apps playing right now, for the settings screen, which has no [MediaSessions] of its
         * own running: the moment it comes back from granting access is when the user expects to
         * see the app they meant to leave out.
         */
        fun playingNow(context: Context): Set<String> = try {
            val manager = context.getSystemService(MediaSessionManager::class.java)
            tally(manager.getActiveSessions(listener(context)), emptySet()).packages
        } catch (e: SecurityException) {
            emptySet()
        }
    }
}

/** What the sessions claim at one moment, counted in apps rather than sessions. */
data class SessionPlayback(
    /** Apps the user left out that say they are playing. */
    val excluded: Int,
    /** Every other app that says it is playing. */
    val others: Int,
    /** All of them, left out or not: the apps settings offers to leave out. */
    val packages: Set<String>,
) {
    companion object {
        val NONE = SessionPlayback(excluded = 0, others = 0, packages = emptySet())
    }
}

internal fun tally(controllers: List<MediaController>, excluded: Set<String>): SessionPlayback {
    val playing = controllers
        .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        // A cast plays out of the television, not out of these headphones.
        .filter { it.playbackInfo.playbackType != MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE }
        .mapTo(mutableSetOf()) { it.packageName }
    val left = playing.count { it in excluded }
    return SessionPlayback(excluded = left, others = playing.size - left, packages = playing)
}
