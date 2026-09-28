package com.haishinkit.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import kotlin.properties.Delegates

/**
 * The AudioCodec translate audio data to another format.
 */
class AudioCodec : Codec() {
    @Suppress("UNUSED")
    data class Setting(
        private var codec: AudioCodec? = null,
    ) : Codec.Setting(codec) {
        /**
         * The channel of audio output.
         */
        var channelCount: Int by Delegates.observable(DEFAULT_CHANNEL_COUNT) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.channelCount = newValue
            }
        }

        /**
         * The bitRate of audio output.
         */
        var bitRate: Int by Delegates.observable(DEFAULT_BIT_RATE) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.bitRate = newValue
            }
        }

        /**
         * The sampleRate of audio output.
         */
        var sampleRate: Int by Delegates.observable(DEFAULT_SAMPLE_RATE) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                codec?.sampleRate = newValue
            }
        }
    }

    var sampleRate = DEFAULT_SAMPLE_RATE
        set(value) {
            field = value
            buffer.sampleRate = value
        }
    var channelCount = DEFAULT_CHANNEL_COUNT
        set(value) {
            field = value
            buffer.channelCount = value
        }
    var bitRate = DEFAULT_BIT_RATE
    var aacProfile = DEFAULT_AAC_PROFILE
    override var inputMimeType = MediaFormat.MIMETYPE_AUDIO_RAW
    override var outputMimeType = MediaFormat.MIMETYPE_AUDIO_AAC
    private val buffer = AudioCodecBuffer()
    private val renderResult = AudioCodecBuffer.RenderResult()

    /**
     * Copies the valid PCM16 range, timed at its first frame in monotonic microseconds.
     * The input position and limit are preserved. Queue eviction does not rebase later timestamps.
     */
    fun append(
        byteBuffer: ByteBuffer,
        presentationTimeUs: Long,
    ) {
        if (!isRunning.get()) return
        buffer.append(byteBuffer, presentationTimeUs)
    }

    override fun onInputBufferAvailable(
        codec: MediaCodec,
        index: Int,
    ) {
        if (mode == MODE_ENCODE) {
            try {
                val inputBuffer = codec.getInputBuffer(index) ?: return
                if (!buffer.render(inputBuffer, renderResult)) return
                codec.queueInputBuffer(
                    index,
                    0,
                    renderResult.size,
                    renderResult.presentationTimeUs,
                    0,
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, e)
            }
        } else {
            super.onInputBufferAvailable(codec, index)
        }
    }

    override fun createMediaFormat(mime: String): MediaFormat =
        MediaFormat.createAudioFormat(mime, sampleRate, channelCount).apply {
            if (mode == MODE_ENCODE) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            } else {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_KEY_MAX_INPUT_SIZE)
            }
        }

    override fun configure(codec: MediaCodec) {
        buffer.start()
        super.configure(codec)
    }

    override fun dispose() {
        buffer.clear()
        super.dispose()
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE: Int = 44100
        const val DEFAULT_CHANNEL_COUNT: Int = 1
        const val DEFAULT_BIT_RATE: Int = 64000
        const val DEFAULT_AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC
        const val DEFAULT_KEY_MAX_INPUT_SIZE = 1024 * 2

        private val TAG = AudioCodec::class.java.simpleName
    }
}
