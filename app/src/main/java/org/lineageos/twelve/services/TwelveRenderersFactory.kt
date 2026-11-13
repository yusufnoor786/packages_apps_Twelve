/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.services

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider

@OptIn(UnstableApi::class)
class TwelveRenderersFactory(
    context: Context,
    enableAudioFloatOutput: Boolean,
    private val onAudioDeviceInfoChanged: (AudioDeviceInfo?) -> Unit,
    private val onAudioTrackConfigChanged: (AudioSink.AudioTrackConfig?) -> Unit,
) : DefaultRenderersFactory(context) {
    init {
        setEnableAudioFloatOutput(enableAudioFloatOutput)
        setEnableAudioOutputPlaybackParameters(true)
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ) = TwelveAudioSink(
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioOutputProvider(
                AudioTrackAudioOutputProvider.Builder(context)
                    .setAudioOffloadSupportProvider(DefaultAudioOffloadSupportProvider(context))
                    // A bigger buffer makes playback resilient to the short CPU stalls that
                    // happen when a track starts, which are heard as crackling
                    .setAudioTrackBufferSizeProvider(
                        DefaultAudioTrackBufferSizeProvider.Builder()
                            .setMinPcmBufferDurationUs(PCM_MIN_BUFFER_DURATION_US)
                            .setMaxPcmBufferDurationUs(PCM_MAX_BUFFER_DURATION_US)
                            .setPcmBufferMultiplicationFactor(PCM_BUFFER_MULTIPLICATION_FACTOR)
                            .build()
                    )
                    .build()
            )
            .build(),
        onAudioDeviceInfoChanged,
        onAudioTrackConfigChanged,
    )

    companion object {
        private const val PCM_MIN_BUFFER_DURATION_US = 500_000
        private const val PCM_MAX_BUFFER_DURATION_US = 1_500_000
        private const val PCM_BUFFER_MULTIPLICATION_FACTOR = 8
    }
}
