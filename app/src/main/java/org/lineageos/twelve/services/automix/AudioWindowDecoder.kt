/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.services.automix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes a stretch of a song to mono samples, enough to listen for its tempo.
 */
object AudioWindowDecoder {
    /**
     * @param mono Samples between -1 and 1
     * @param sampleRate Sample rate of [mono]
     * @param windowStartMs Where in the song [mono] starts
     * @param durationMs Length of the whole song, null if the file doesn't say
     */
    class Window(
        val mono: FloatArray,
        val sampleRate: Int,
        val windowStartMs: Long,
        val durationMs: Long?,
    )

    /**
     * @param startMs Where to start, negative to count back from the end
     * @param lengthMs How much to decode
     */
    fun decode(context: Context, uri: Uri, startMs: Long, lengthMs: Long): Window? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)

            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val durationMs = when (format.containsKey(MediaFormat.KEY_DURATION)) {
                true -> format.getLong(MediaFormat.KEY_DURATION) / 1000
                false -> null
            }

            val from = when {
                startMs >= 0 -> startMs
                durationMs != null -> (durationMs + startMs).coerceAtLeast(0)
                else -> return null
            }
            extractor.seekTo(from * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decimation = (sampleRate / TARGET_RATE).coerceAtLeast(1)

            val out = ArrayList<Float>(((lengthMs * TARGET_RATE) / 1000).toInt() + 1024)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var firstPtsUs = -1L
            val limitUs = lengthMs * 1000

            val deadline = System.nanoTime() + TIMEOUT_NS
            while (!outputDone && System.nanoTime() < deadline) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        val pts = extractor.sampleTime
                        when {
                            size < 0 || (firstPtsUs >= 0 && pts - firstPtsUs > limitUs) -> {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            }

                            else -> {
                                if (firstPtsUs < 0) firstPtsUs = pts
                                codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val changed = codec.outputFormat
                        sampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }

                    outputIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outputIndex)!!
                        buffer.order(ByteOrder.nativeOrder())
                        val shorts = buffer.asShortBuffer()
                        val frames = shorts.remaining() / channels

                        // Average of the channels, and of the samples that are merged into one
                        var accumulated = 0f
                        var merged = 0
                        for (frame in 0 until frames) {
                            var sum = 0f
                            for (channel in 0 until channels) {
                                sum += shorts.get(frame * channels + channel) / 32768f
                            }
                            accumulated += sum / channels
                            if (++merged == decimation) {
                                out.add(accumulated / decimation)
                                accumulated = 0f
                                merged = 0
                            }
                        }

                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            if (out.size < TARGET_RATE) {
                return null
            }

            return Window(
                FloatArray(out.size) { out[it] },
                sampleRate / decimation,
                (if (firstPtsUs >= 0) firstPtsUs else from * 1000) / 1000,
                durationMs,
            )
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private const val TARGET_RATE = 11_025
    private const val TIMEOUT_US = 10_000L
    private const val TIMEOUT_NS = 25_000_000_000L
}
