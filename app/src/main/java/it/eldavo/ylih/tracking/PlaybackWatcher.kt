package it.eldavo.ylih.tracking

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi
import it.eldavo.ylih.data.Clock

/**
 * Measures how much of a connected span is *actual playback*.
 *
 * Driven by [AudioManager.AudioPlaybackCallback] edges — the callback delivers the list of
 * currently active players, which is all we need since the per-player details are anonymised
 * for apps without MODIFY_AUDIO_ROUTING. [AudioManager.isMusicActive] is polled on every tick
 * as a safety net in case a player never shows up in that list.
 *
 * Only ever constructed from [TrackingService], which [TrackingController.detailedTrackingSupported]
 * keeps off below Android 8 (API 26) — that's where [AudioManager.AudioPlaybackCallback] starts.
 */
@RequiresApi(Build.VERSION_CODES.O)
class PlaybackWatcher(
    private val audioManager: AudioManager,
    /**
     * The same clock the service stamps everything else with.
     *
     * Injected rather than read from the wall, because this class holds a *pair* of instants — the
     * one banked in [playingSince] and the one a slice is measured to — and mixing two sources
     * between them gives a difference that means nothing. In the app they are the same clock either
     * way; what it buys is a test that can bank a callback's slice without sleeping through it.
     */
    private val clock: Clock = Clock.Wall,
    private val onDelta: (Long) -> Unit,
) {
    private var configsActive = false
    private var playingSince: Long? = null

    val isPlaying: Boolean get() = playingSince != null

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            configsActive = configs.any { it.audioAttributes.isMediaLike() }
            // The one edge that cannot be waited for, so the one that still has to push.
            onDelta(refresh(clock.now(), minSliceMs = MIN_BANKED_SLICE_MS))
        }
    }

    fun start(handler: Handler) {
        audioManager.registerAudioPlaybackCallback(callback, handler)
    }

    /** @return the last slice, for the caller to credit — see [refresh]. */
    fun stop(now: Long): Long {
        audioManager.unregisterAudioPlaybackCallback(callback)
        return update(active = false, now = now)
    }

    /**
     * Re-evaluates playback state and banks the time accumulated so far.
     *
     * [minSliceMs] leaves a slice shorter than that accruing instead of banking it. The callback
     * fires whenever *any* app on the phone changes a player — a chime, an ad, a video starting
     * in something else entirely — so without a floor a talkative phone turns each of those into
     * a database write while music plays. Nothing is lost by waiting: the slice keeps accruing,
     * and the edges that actually end a span ([update], [stop]) always credit it in full.
     *
     * @return the milliseconds banked by this call, zero if none. Handed back rather than pushed
     *   through [onDelta] because the caller that matters is about to close the session this
     *   belongs to: playback is credited by *open* session, so the write has to happen — and be
     *   waited for — before the disconnect, which only a value the caller can suspend on allows.
     */
    fun refresh(now: Long, minSliceMs: Long = 0L): Long {
        val active = configsActive || audioManager.isMusicActive
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
         * service's tick, which passes no floor, so what a process death can cost is still one
         * tick's worth and no more.
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
