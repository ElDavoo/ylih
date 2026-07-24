package it.eldavo.ylih.tracking

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler

/**
 * Measures how much of a connected span is *actual playback*.
 *
 * Driven by [AudioManager.AudioPlaybackCallback] edges — the callback delivers the list of
 * currently active players, which is all we need since the per-player details are anonymised
 * for apps without MODIFY_AUDIO_ROUTING. [AudioManager.isMusicActive] is polled on every tick
 * as a safety net in case a player never shows up in that list.
 */
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
            refresh(System.currentTimeMillis())
        }
    }

    fun start(handler: Handler) {
        audioManager.registerAudioPlaybackCallback(callback, handler)
    }

    fun stop(now: Long) {
        audioManager.unregisterAudioPlaybackCallback(callback)
        update(active = false, now = now)
    }

    /** Re-evaluates playback state and credits time accumulated so far. */
    fun refresh(now: Long) {
        val active = configsActive || audioManager.isMusicActive
        if (active && playingSince != null) {
            // Still playing: bank the elapsed slice so long sessions are credited as they go.
            onDelta(now - playingSince!!)
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

    private fun AudioAttributes.isMediaLike(): Boolean = usage in MEDIA_USAGES

    private companion object {
        val MEDIA_USAGES = setOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
        )
    }
}
