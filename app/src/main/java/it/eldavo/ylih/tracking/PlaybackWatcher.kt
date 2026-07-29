package it.eldavo.ylih.tracking

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi

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
    private val onDelta: (Long) -> Unit,
) {
    private var configsActive = false
    private var playingSince: Long? = null

    val isPlaying: Boolean get() = playingSince != null

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            configsActive = configs.any { it.audioAttributes.isMediaLike() }
            refresh(System.currentTimeMillis(), minSliceMs = MIN_BANKED_SLICE_MS)
        }
    }

    fun start(handler: Handler) {
        audioManager.registerAudioPlaybackCallback(callback, handler)
    }

    fun stop(now: Long) {
        audioManager.unregisterAudioPlaybackCallback(callback)
        update(active = false, now = now)
    }

    /**
     * Re-evaluates playback state and credits time accumulated so far.
     *
     * [minSliceMs] leaves a slice shorter than that accruing instead of banking it. The callback
     * fires whenever *any* app on the phone changes a player — a chime, an ad, a video starting
     * in something else entirely — so without a floor a talkative phone turns each of those into
     * a database write while music plays. Nothing is lost by waiting: the slice keeps accruing,
     * and the edges that actually end a span ([update], [stop]) always credit it in full.
     */
    fun refresh(now: Long, minSliceMs: Long = 0L) {
        val active = configsActive || audioManager.isMusicActive
        if (active && playingSince != null) {
            // Still playing: bank the elapsed slice so long sessions are credited as they go.
            val slice = now - playingSince!!
            if (slice < minSliceMs) return
            onDelta(slice)
            playingSince = now
            return
        }
        update(active, now)
    }

    /** Drops time accrued so far without crediting it (used when the target session changes). */
    fun rebase(now: Long) {
        if (playingSince != null) playingSince = now
    }

    private fun update(active: Boolean, now: Long) {
        val since = playingSince
        when {
            active && since == null -> playingSince = now
            !active && since != null -> {
                onDelta(now - since)
                playingSince = null
            }
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
