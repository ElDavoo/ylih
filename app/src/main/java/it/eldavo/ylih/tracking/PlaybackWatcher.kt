package it.eldavo.ylih.tracking

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import it.eldavo.ylih.data.Clock

/**
 * Measures how much of a connected span is actual playback.
 *
 * Driven by [AudioManager.AudioPlaybackCallback] edges: the callback delivers the active-player
 * list, all this needs since per-player details are anonymised for apps without
 * MODIFY_AUDIO_ROUTING. [AudioManager.isMusicActive] is polled every tick as a safety net in
 * case a player never shows up there. The one thing that does name an app is a media session,
 * read where the user granted it, so time an app they left out claims can be subtracted — see
 * [isListening].
 *
 * Constructed only by [TrackingService], so it lives only while detailed tracking does. Nothing
 * else measures playback, so a session's `playingMs` is null where the service never ran,
 * rather than zero — the two mean different things to the stats.
 */
class PlaybackWatcher(
    private val audioManager: AudioManager,
    /**
     * The same clock the service stamps everything else with.
     *
     * Injected rather than read from the wall: this class holds a *pair* of instants — the one
     * banked in [playingSince] and the one a slice is measured to — and mixing sources between
     * them gives a difference that means nothing. In the app they are the same clock either way;
     * the payoff is a test that can bank a callback's slice without sleeping through it.
     */
    private val clock: Clock = Clock.Wall,
    /** Who says they are playing, for [excluded] to act on; null counts every app, as before. */
    private val sessions: MediaSessions? = null,
    /** Apps seen playing, each handed over once per watcher, for settings to offer leaving out. */
    private val onPlayingApps: (Set<String>) -> Unit = {},
    private val onDelta: (Long) -> Unit,
) {
    private var mediaLikePlayers = 0
    private var playingSince: Long? = null
    private val reportedApps = mutableSetOf<String>()

    /** Apps whose playback is not listening — see [isListening]. */
    var excluded: Set<String> = emptySet()

    val isPlaying: Boolean get() = playingSince != null

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            mediaLikePlayers = configs.count { it.audioAttributes.isMediaLike() }
            // The one edge that cannot be waited for, so the one that still has to push.
            onDelta(refresh(clock.now(), minSliceMs = MIN_BANKED_SLICE_MS))
        }
    }

    fun start(handler: Handler) {
        audioManager.registerAudioPlaybackCallback(callback, handler)
        // A session's state is an edge like a player's, and cannot be waited for either.
        sessions?.start(handler) { onDelta(refresh(clock.now(), minSliceMs = MIN_BANKED_SLICE_MS)) }
    }

    /** @return the last slice, for the caller to credit — see [refresh]. */
    fun stop(now: Long): Long {
        audioManager.unregisterAudioPlaybackCallback(callback)
        sessions?.stop()
        return update(active = false, now = now)
    }

    /** Picks up a session-access grant given or taken away since [start]; see [MediaSessions.resync]. */
    fun resyncSessions() {
        sessions?.resync()
    }

    /**
     * Re-evaluates playback state and banks the time accumulated so far.
     *
     * [minSliceMs] leaves a shorter slice accruing rather than banking it. The callback fires
     * whenever *any* app on the phone changes a player — a chime, an ad, a video starting
     * elsewhere — so without a floor a talkative phone turns each into a database write while
     * music plays. Nothing is lost by waiting: the slice keeps accruing, and the edges that end a
     * span ([update], [stop]) always credit it in full.
     *
     * @return the milliseconds banked by this call, zero if none. Handed back rather than pushed
     *   through [onDelta] because the caller is about to close the session this belongs to:
     *   playback is credited by *open* session, so the write must happen — and be waited for —
     *   before the disconnect, which only a value the caller can suspend on allows.
     */
    fun refresh(now: Long, minSliceMs: Long = 0L): Long {
        val claimed = sessions?.playing(excluded) ?: SessionPlayback.NONE
        if (!reportedApps.containsAll(claimed.packages)) {
            reportedApps += claimed.packages
            onPlayingApps(reportedApps.toSet())
        }
        val audible = mediaLikePlayers > 0 || audioManager.isMusicActive
        val active = isListening(mediaLikePlayers, audible, claimed)
        val since = playingSince
        if (active && since != null) {
            // Still playing: bank the elapsed slice so long sessions are credited as they go.
            val slice = now - since
            if (slice < minSliceMs) return 0L
            playingSince = now
            return slice
        }
        return update(active, now)
    }

    private fun update(active: Boolean, now: Long): Long {
        val since = playingSince
        return when {
            active && since == null -> {
                playingSince = now
                0L
            }

            !active && since != null -> {
                playingSince = null
                now - since
            }

            else -> 0L
        }
    }

    // Not private: the callback below is a separate class, so a private helper would cost a
    // synthetic accessor on every playback-config change (lint's SyntheticAccessor).
    internal fun AudioAttributes.isMediaLike(): Boolean = usage in MEDIA_USAGES

    private companion object {
        /**
         * Below this a still-playing refresh leaves the slice where it is. Well under the
         * service's tick, which passes no floor, so a process death still costs at most one
         * tick's worth.
         */
        const val MIN_BANKED_SLICE_MS = 30_000L

        val MEDIA_USAGES = setOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
        )
    }
}

/**
 * Whether what is sounding counts as listening, once the sessions have had their say.
 *
 * With no left-out app claiming playback this is the rule the watcher always had, so nobody who
 * never grants session access, or never unticks an app, sees anything change. Otherwise the
 * players are anonymous and the claims are only claims, so the left-out apps are taken to account
 * for one media-like player each, and time counts when something is plainly left over: another
 * app saying it is playing too, or more players than the left-out apps explain. A left-out app
 * playing beside something with no session and a player that isn't media-like still loses that
 * time — the one case nothing available here can tell apart.
 *
 * Built for #69: Automate holding a silent stream open to catch headset buttons, counted as
 * listening all day.
 */
internal fun isListening(mediaLikePlayers: Int, audible: Boolean, claimed: SessionPlayback): Boolean =
    audible && (
        claimed.excluded == 0 ||
            claimed.others > 0 ||
            mediaLikePlayers > claimed.excluded
        )
