/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Two songs sounding at once while one gives way to the other.
 *
 * A player renders one song at a time, so the end of a song is played by a second, silent helper
 * player that has been playing the very same song, in step with the first, for a few seconds. When
 * the fade starts the first player jumps to the next song (starting it from its beginning, rising)
 * and the helper, which carries on the song that was ending, falls. The listener hears a smooth
 * join because the helper was already at the exact spot, and the two never play the same audio at
 * a time they are audible together.
 *
 * Fades follow an equal power curve, so the two songs hold the same loudness all through.
 *
 * With a duration of zero none of this exists and the player is gapless on its own.
 *
 * @param fadeMs The duration of the fade, in milliseconds, asked every time it matters
 * @param plan Lets a smarter planner (Automix) shape a transition, see [TransitionPlan]
 */
class CrossfadeController(
    private val context: Context,
    private val player: ExoPlayer,
    private val audioAttributes: AudioAttributes,
    private val fadeMs: () -> Long,
    private val plan: (outgoing: MediaItem, incoming: MediaItem?, defaultFadeMs: Long) -> TransitionPlan? =
        { _, _, _ -> null },
) {
    /**
     * How a transition goes, where the default is a plain crossfade.
     *
     * @param fadeMs How long the songs overlap
     * @param incomingSpeed Playback speed the incoming song starts at, it glides back to normal
     *   during the fade, so that its tempo meets the one of the outgoing song
     * @param incomingStartMs Where in the incoming song the fade starts
     */
    data class TransitionPlan(
        val fadeMs: Long,
        val incomingSpeed: Float = 1f,
        val incomingStartMs: Long = 0L,
    )

    private enum class State { IDLE, PREROLL, FADING }

    private val handler = Handler(Looper.getMainLooper())
    private var helper: ExoPlayer? = null
    private var state = State.IDLE

    private var currentPlan = TransitionPlan(0)
    private var fadeStartedAt = 0L
    private var lastAlignAt = 0L
    private var isSelfSeeking = false
    private var isReleased = false

    private val tick = object : Runnable {
        override fun run() {
            if (isReleased) {
                return
            }

            update()
            handler.postDelayed(
                this,
                when (state) {
                    State.FADING -> FADE_TICK_MS
                    else -> IDLE_TICK_MS
                }
            )
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            helper?.takeIf { state != State.IDLE }?.let {
                it.playWhenReady = playWhenReady
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (isSelfSeeking) {
                return
            }

            // The user (or a queue change) moved on: whatever was prepared is out of date
            if (reason == Player.DISCONTINUITY_REASON_SEEK
                || reason == Player.DISCONTINUITY_REASON_SKIP
                || reason == Player.DISCONTINUITY_REASON_REMOVE
                || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            ) {
                abort()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!isSelfSeeking && state != State.IDLE) {
                abort()
            }
        }
    }

    init {
        player.addListener(playerListener)
        handler.post(tick)
    }

    fun release() {
        isReleased = true
        handler.removeCallbacks(tick)
        player.removeListener(playerListener)
        abort()
        helper?.release()
        helper = null
    }

    private fun update() {
        if (state == State.FADING) {
            ramp()
            return
        }

        val fade = fadeMs()
        val item = player.currentMediaItem
        val duration = player.duration

        val canFade = fade > 0
                && player.isPlaying
                && item != null
                && duration != C.TIME_UNSET
                && duration > MIN_SONG_MS
                && player.hasNextMediaItem()
                && player.repeatMode != Player.REPEAT_MODE_ONE

        if (!canFade) {
            if (state == State.PREROLL) {
                abort()
            }
            return
        }

        // A fade is never longer than a third of the song
        val effective = min(fade, duration / 3)
        val remaining = duration - player.currentPosition

        when {
            state == State.IDLE && remaining <= effective + PREROLL_MS -> preroll(item!!)
            state == State.PREROLL -> {
                if (remaining <= effective) {
                    startFade(effective)
                } else {
                    align()
                }
            }
        }
    }

    /**
     * Start the helper on the song that is playing now, silent, to have it ready.
     */
    private fun preroll(item: MediaItem) {
        val helper = helper ?: buildHelper().also { helper = it }

        helper.volume = 0f
        helper.setMediaItem(item)
        helper.prepare()
        helper.seekTo(player.currentPosition + PREROLL_LATENCY_MS)
        helper.playWhenReady = true
        state = State.PREROLL
        lastAlignAt = SystemClock.uptimeMillis()
    }

    /**
     * Keep the silent copy in step with the song, so that nothing jumps when it takes over.
     */
    private fun align() {
        val helper = helper ?: return
        val now = SystemClock.uptimeMillis()
        if (helper.playbackState != Player.STATE_READY || now - lastAlignAt < ALIGN_EVERY_MS) {
            return
        }

        lastAlignAt = now
        val difference = helper.currentPosition - player.currentPosition
        if (abs(difference) > ALIGN_TOLERANCE_MS) {
            helper.seekTo(player.currentPosition + PREROLL_LATENCY_MS)
        }
    }

    private fun startFade(effectiveFadeMs: Long) {
        val helper = helper ?: return
        val outgoing = player.currentMediaItem ?: return

        // The helper must be sounding already, otherwise there is nothing to fade out
        if (helper.playbackState != Player.STATE_READY) {
            abort()
            return
        }

        val incoming = player.getMediaItemAt(player.nextMediaItemIndex)
        val chosen = plan(outgoing, incoming, effectiveFadeMs)
            ?: TransitionPlan(effectiveFadeMs)
        currentPlan = chosen.copy(fadeMs = chosen.fadeMs.coerceAtLeast(MIN_FADE_MS))

        helper.volume = 1f
        player.volume = 0f

        isSelfSeeking = true
        try {
            if (currentPlan.incomingSpeed != 1f) {
                player.playbackParameters = PlaybackParameters(currentPlan.incomingSpeed, 1f)
            }
            player.seekTo(player.nextMediaItemIndex, currentPlan.incomingStartMs)
        } finally {
            isSelfSeeking = false
        }

        fadeStartedAt = SystemClock.uptimeMillis()
        state = State.FADING
        Log.d(LOG_TAG, "Crossfade of ${currentPlan.fadeMs} ms, speed ${currentPlan.incomingSpeed}")
    }

    private fun ramp() {
        val helper = helper
        val progress = ((SystemClock.uptimeMillis() - fadeStartedAt).toFloat() /
                max(1L, currentPlan.fadeMs)).coerceIn(0f, 1f)

        // Equal power: the two squares add up to one the whole way
        val angle = progress * PI.toFloat() / 2f
        helper?.volume = cos(angle)
        player.volume = sin(angle)

        // The incoming song's tempo glides back to normal while it takes over
        if (currentPlan.incomingSpeed != 1f) {
            val speed = currentPlan.incomingSpeed + (1f - currentPlan.incomingSpeed) * progress
            player.playbackParameters = PlaybackParameters(speed, 1f)
        }

        if (progress >= 1f || helper?.playbackState == Player.STATE_ENDED) {
            finish()
        }
    }

    private fun finish() {
        helper?.stop()
        helper?.clearMediaItems()
        player.volume = 1f
        player.playbackParameters = PlaybackParameters.DEFAULT
        state = State.IDLE
    }

    /**
     * Forget the transition: the player is just the player again.
     */
    private fun abort() {
        if (state == State.IDLE) {
            return
        }

        helper?.stop()
        helper?.clearMediaItems()
        player.volume = 1f
        if (state == State.FADING) {
            player.playbackParameters = PlaybackParameters.DEFAULT
        }
        state = State.IDLE
    }

    private fun buildHelper() = ExoPlayer.Builder(context)
        // The main player owns the focus
        .setAudioAttributes(audioAttributes, false)
        .build()

    private companion object {
        private const val LOG_TAG = "CrossfadeController"

        private const val IDLE_TICK_MS = 100L
        private const val FADE_TICK_MS = 16L

        // How early the helper starts rolling before the fade
        private const val PREROLL_MS = 3000L

        // The helper takes a moment to start, it is sent that far ahead
        private const val PREROLL_LATENCY_MS = 250L
        private const val ALIGN_EVERY_MS = 400L
        private const val ALIGN_TOLERANCE_MS = 60L

        private const val MIN_SONG_MS = 8_000L
        private const val MIN_FADE_MS = 500L
    }
}
