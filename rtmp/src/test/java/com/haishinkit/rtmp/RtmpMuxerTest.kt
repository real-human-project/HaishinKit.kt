package com.haishinkit.rtmp

import android.media.MediaCodec
import android.media.MediaFormat
import com.haishinkit.net.NetSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RtmpMuxerTest {
    @Test
    fun backwardAacTimestampsPreserveFramingAndBase() {
        assertBackwardTimestamps(MediaFormat.MIMETYPE_AUDIO_AAC)
    }

    @Test
    fun backwardAvcTimestampsPreserveFramingAndBase() {
        assertBackwardTimestamps(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    @Test
    fun backwardEnhancedVideoTimestampsPreserveFramingAndBase() {
        for (mime in enhancedVideoTypes) {
            assertBackwardTimestamps(mime)
        }
    }

    @Test
    fun largeDeltasStopBeforeTheExtendedTimestampMarker() {
        for (mime in mediaTypes) {
            val base = 1_000_000L
            val maximum = 0xFFFFFE
            assertEncodedSamples(
                mime,
                listOf(base, base + maximum * 1000L, base + (maximum * 2L + 1) * 1000L, base + (maximum * 2L + 3) * 1000L),
                listOf(0, maximum, maximum, 3),
            )
        }
    }

    private fun assertBackwardTimestamps(mime: String) {
        for (behindUs in listOf(1000L, 1500L, 1999L)) {
            assertEncodedSamples(
                mime,
                listOf(1_000_000L, 1_020_500L, 1_020_000L - behindUs, 1_023_500L, 1_024_000L),
                listOf(0, 20, 0, 3, 1),
            )
        }
    }

    private fun assertEncodedSamples(
        mime: String,
        timestamps: List<Long>,
        expectedDeltas: List<Int>,
    ) {
        val connection = RtmpConnection()
        val transport = CapturingSocket()
        RtmpSocket::class.java.getDeclaredField("socket").apply {
            isAccessible = true
            set(connection.socket, transport)
        }
        val stream = RtmpStream(RuntimeEnvironment.getApplication(), connection)
        val audio = mime == MediaFormat.MIMETYPE_AUDIO_AAC
        val chunkStreamID = if (audio) RtmpChunk.AUDIO else RtmpChunk.VIDEO
        val sample = if (audio) byteArrayOf(0x11, 0x22, 0x33) else byteArrayOf(0, 0, 0, 1, 0x41, 0x11, 0x22, 0x33)
        val payload =
            when (mime) {
                MediaFormat.MIMETYPE_AUDIO_AAC -> byteArrayOf(0xAF.toByte(), 1) + sample
                MediaFormat.MIMETYPE_VIDEO_AVC -> byteArrayOf(0x27, 1, 0, 0, 0, 0, 0, 0, 4, 0x41, 0x11, 0x22, 0x33)
                else -> {
                    val header = ByteBuffer.allocate(5)
                    header.put(if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0xA3.toByte() else 0xA1.toByte())
                    header.putInt(RtmpMuxer.getVideoFourCCByType(mime))
                    header.array() + if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) byteArrayOf(0, 0, 0, 4, 0x41, 0x11, 0x22, 0x33) else sample
                }
            }
        for (timestamp in timestamps) {
            val info = MediaCodec.BufferInfo().apply { set(0, sample.size, timestamp, 0) }
            assertTrue(stream.muxer.onSampleOutput(mime, 0, info, ByteBuffer.wrap(sample.copyOf())))
        }
        val bytes = ByteBuffer.wrap(transport.output.toByteArray())
        for ((index, delta) in expectedDeltas.withIndex()) {
            assertEquals("$mime message $index offset", index * (8 + payload.size), bytes.position())
            assertEquals(0x40 or chunkStreamID.toInt(), bytes.get().toInt() and 0xFF)
            val timestampOffset = bytes.position()
            val encodedDelta =
                ((bytes.get(timestampOffset).toInt() and 0xFF) shl 16) or
                    ((bytes.get(timestampOffset + 1).toInt() and 0xFF) shl 8) or
                    (bytes.get(timestampOffset + 2).toInt() and 0xFF)
            assertNotEquals("$mime message $index must not signal an extended timestamp", 0xFFFFFF, encodedDelta)
            val decoded = RtmpChunk.ONE.decode(chunkStreamID, connection, bytes)
            assertEquals("$mime message $index delta", delta, decoded.timestamp)
            assertEquals(if (audio) 8 else 9, decoded.type.toInt())
            assertEquals(payload.size, decoded.length)
            val decodedPayload = ByteArray(decoded.length)
            bytes.get(decodedPayload)
            assertArrayEquals("$mime message $index payload", payload, decodedPayload)
        }
        assertEquals(0, bytes.remaining())
    }

    private class CapturingSocket : NetSocket {
        val output = ByteArrayOutputStream()
        override var timeout = 0
        override var listener: NetSocket.Listener? = null
        override val totalBytesIn = AtomicLong()
        override val totalBytesOut = AtomicLong()
        override val queueBytesOut = AtomicLong()

        override fun connect(
            dstName: String,
            dstPort: Int,
            isSecure: Boolean,
        ) = Unit

        override fun doOutput(buffer: ByteBuffer) {
            buffer.flip()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            output.write(bytes)
        }

        override fun close(disconnected: Boolean) = Unit

        override fun createByteBuffer(capacity: Int): ByteBuffer = ByteBuffer.allocate(capacity)
    }

    companion object {
        private val enhancedVideoTypes =
            listOf(MediaFormat.MIMETYPE_VIDEO_AV1, MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_VIDEO_HEVC)
        private val mediaTypes = listOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_VIDEO_AVC) + enhancedVideoTypes
    }
}
