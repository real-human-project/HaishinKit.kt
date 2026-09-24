package com.haishinkit.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.haishinkit.screen.Screen
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [MuxerBoundary::class])
class MediaRecorderMuxerTest {
    private val dataSource =
        object : MediaOutputDataSource {
            override val hasAudio = true
            override val hasVideo = false
            override val screen by lazy { Screen.create(RuntimeEnvironment.getApplication()) }

            override fun registerOutput(output: MediaOutput) = Unit

            override fun unregisterOutput(output: MediaOutput) = Unit
        }
    private lateinit var recorder: MediaRecorderMuxer
    private lateinit var muxer: MuxerBoundary

    @Before
    fun setUp() {
        val platformMuxer = MediaMuxer("unused.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = Shadow.extract(platformMuxer)
        recorder = MediaRecorderMuxer(WeakReference(dataSource), platformMuxer)
        recorder.onFormatChanged(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1))
    }

    @Test
    fun audioAtTimestampZeroIsWrittenAsMedia() {
        val info = MediaCodec.BufferInfo().apply { set(0, 2, 0L, 0) }
        recorder.onSampleOutput(MediaFormat.MIMETYPE_AUDIO_AAC, 0, info, ByteBuffer.wrap(byteArrayOf(1, 2)))
        assertEquals(1, muxer.samples.size)
        assertEquals(7, muxer.samples.single().track)
        assertEquals(0L, muxer.samples.single().timestamp)
        assertArrayEquals(byteArrayOf(1, 2), muxer.samples.single().bytes)
    }

    @Test
    fun codecConfigurationIsNotWrittenAsAudioRegardlessOfTimestamp() {
        for (timestamp in listOf(0L, 42L)) {
            val info = MediaCodec.BufferInfo().apply { set(0, 2, timestamp, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) }
            recorder.onSampleOutput(MediaFormat.MIMETYPE_AUDIO_AAC, 0, info, ByteBuffer.wrap(byteArrayOf(0x12, 0x10)))
        }
        val info = MediaCodec.BufferInfo().apply { set(0, 2, 1000L, 0) }
        recorder.onSampleOutput(MediaFormat.MIMETYPE_AUDIO_AAC, 0, info, ByteBuffer.wrap(byteArrayOf(3, 4)))
        assertEquals(1, muxer.samples.size)
        assertEquals(1000L, muxer.samples.single().timestamp)
        assertArrayEquals(byteArrayOf(3, 4), muxer.samples.single().bytes)
    }
}

@Implements(MediaMuxer::class)
class MuxerBoundary {
    data class Sample(val track: Int, val timestamp: Long, val bytes: ByteArray)

    val samples = mutableListOf<Sample>()

    @Suppress("ktlint:standard:function-naming")
    @Implementation
    fun __constructor__(
        path: String,
        format: Int,
    ) = Unit

    @Implementation
    fun addTrack(format: MediaFormat): Int = 7

    @Implementation
    fun start() = Unit

    @Implementation
    fun writeSampleData(
        trackIndex: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ) {
        val bytes = ByteArray(info.size)
        buffer.duplicate().apply { position(info.offset) }.get(bytes)
        samples.add(Sample(trackIndex, info.presentationTimeUs, bytes))
    }
}
