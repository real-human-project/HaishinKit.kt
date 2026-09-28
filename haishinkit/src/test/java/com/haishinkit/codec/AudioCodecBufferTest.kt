package com.haishinkit.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class AudioCodecBufferTest {
    @Test
    fun continuousPcmAdvancesInMicroseconds() {
        val buffer = AudioCodecBuffer().apply { start() }
        val output = ByteBuffer.allocate(2048)
        val result = AudioCodecBuffer.RenderResult()
        buffer.append(ByteBuffer.allocate(2048), 0L)
        buffer.render(output, result)
        assertEquals(2048, result.size)
        assertEquals(0L, result.presentationTimeUs)

        output.clear()
        buffer.append(ByteBuffer.allocate(2048), 1024L * 1_000_000 / 44100)
        buffer.render(output, result)
        assertEquals(2048, result.size)
        assertEquals(1024L * 1_000_000 / 44100, result.presentationTimeUs)
    }

    @Test
    fun sourceTimeGapsDoNotDependOnDeliveredByteCount() {
        val buffer = AudioCodecBuffer().apply { start() }
        val result = AudioCodecBuffer.RenderResult()
        val output = ByteBuffer.allocate(2048)
        buffer.append(ByteBuffer.wrap(ByteArray(2048) { 1 }), 1_000_000L)
        buffer.append(ByteBuffer.wrap(ByteArray(2048) { 2 }), 1_300_000L)
        assertTrue(buffer.render(output, result))
        assertEquals(1_000_000L, result.presentationTimeUs)
        output.clear()
        assertTrue(buffer.render(output, result))
        assertEquals(1_300_000L, result.presentationTimeUs)
        assertArrayEquals(ByteArray(2048) { 2 }, output.array())
    }

    @Test
    fun evictingUnconsumedPcmPreservesTheLaterCaptureTimestamps() {
        val buffer = AudioCodecBuffer().apply { start() }
        val result = AudioCodecBuffer.RenderResult()
        val output = ByteBuffer.allocate(2)
        buffer.append(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 0)), 0L)
        assertTrue(buffer.render(output, result))
        repeat(AudioCodecBuffer.CAPACITY) { index ->
            buffer.append(ByteBuffer.wrap(byteArrayOf((index + 1).toByte(), 0)), (index + 1) * 100_000L)
        }
        repeat(AudioCodecBuffer.CAPACITY) { index ->
            output.clear()
            assertTrue(buffer.render(output, result))
            assertEquals((index + 1) * 100_000L, result.presentationTimeUs)
            assertArrayEquals(byteArrayOf((index + 1).toByte(), 0), output.array())
        }
    }

    @Test
    fun partialRenderingUsesTheOriginalFrameOffsetWithoutRoundingDrift() {
        val buffer = AudioCodecBuffer().apply { start() }
        val input = ByteBuffer.wrap(ByteArray(1024 * 2) { (it % 127).toByte() })
        val output = ByteBuffer.allocate(2)
        val result = AudioCodecBuffer.RenderResult()
        buffer.append(input, 4_000_000L)
        repeat(1024) { frame ->
            output.clear()
            assertTrue(buffer.render(output, result))
            assertEquals(2, result.size)
            assertEquals(4_000_000L + frame * 1_000_000L / 44100, result.presentationTimeUs)
            assertEquals(input.get(frame * 2), output.get(0))
            assertEquals(input.get(frame * 2 + 1), output.get(1))
        }
    }

    @Test
    fun stereoRenderingCountsWholeFramesAndHonorsTheDestinationRange() {
        val buffer =
            AudioCodecBuffer().apply {
                sampleRate = 48000
                channelCount = 2
                start()
            }
        val input = ByteArray(16) { it.toByte() }
        buffer.append(ByteBuffer.wrap(input), 500L)
        val output =
            ByteBuffer.allocate(20).apply {
                position(3)
                limit(13)
            }
        val result = AudioCodecBuffer.RenderResult()
        assertTrue(buffer.render(output, result))
        assertEquals(8, result.size)
        assertEquals(11, output.position())
        assertEquals(500L, result.presentationTimeUs)
        assertArrayEquals(input.copyOfRange(0, 8), output.array().copyOfRange(3, 11))

        output.clear()
        assertTrue(buffer.render(output, result))
        assertEquals(8, result.size)
        assertEquals(541L, result.presentationTimeUs)
        assertArrayEquals(input.copyOfRange(8, 16), output.array().copyOfRange(0, 8))
    }

    @Test
    fun reusedStorageCopiesOnlyValidBytesAcrossFullShortAndLargerReads() {
        val buffer = AudioCodecBuffer().apply { start() }
        val result = AudioCodecBuffer.RenderResult()
        val output = ByteBuffer.allocate(64)
        for (length in listOf(16, 4, 32)) {
            repeat(AudioCodecBuffer.CAPACITY) { index ->
                val bytes = ByteArray(length + 8) { 99 }
                bytes.fill((length + index).toByte(), 4, 4 + length)
                val input =
                    ByteBuffer.wrap(bytes).apply {
                        position(4)
                        limit(4 + length)
                    }
                val timestamp = length * 1_000L + index
                buffer.append(input, timestamp)
                assertEquals(4, input.position())
                assertEquals(4 + length, input.limit())
                output.clear()
                assertTrue(buffer.render(output, result))
                assertEquals(length, result.size)
                assertEquals(length, output.position())
                assertEquals(timestamp, result.presentationTimeUs)
                assertArrayEquals(ByteArray(length) { (length + index).toByte() }, output.array().copyOf(length))
            }
        }
    }

    @Test
    fun clearDiscardsPartialAndQueuedPcmButDoesNotMutateTheRenderedResult() {
        val buffer = AudioCodecBuffer().apply { start() }
        val output = ByteBuffer.allocate(2)
        val result = AudioCodecBuffer.RenderResult()
        buffer.append(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 9_000L)
        buffer.append(ByteBuffer.wrap(byteArrayOf(5, 6)), 10_000L)
        assertTrue(buffer.render(output, result))
        buffer.clear()
        assertEquals(9_000L, result.presentationTimeUs)
        assertEquals(2, result.size)
        output.clear()
        assertFalse(buffer.render(output, result))
        buffer.append(ByteBuffer.wrap(byteArrayOf(7, 8)), 11_000L)
        buffer.start()
        buffer.append(ByteBuffer.wrap(byteArrayOf(9, 10)), 0L)
        assertTrue(buffer.render(output, result))
        assertEquals(0L, result.presentationTimeUs)
        assertArrayEquals(byteArrayOf(9, 10), output.array())
    }

    @Test
    fun clearReleasesAConsumerWaitingForPcm() {
        val buffer = AudioCodecBuffer().apply { start() }
        val render =
            FutureTask {
                buffer.render(ByteBuffer.allocate(2), AudioCodecBuffer.RenderResult())
            }
        val consumer =
            Thread(render).apply {
                isDaemon = true
                start()
            }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (consumer.state != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.WAITING, consumer.state)
            buffer.clear()
            assertFalse(render.get(5, TimeUnit.SECONDS))
        } finally {
            buffer.clear()
            consumer.interrupt()
            consumer.join(5000)
        }
    }

    @Test
    fun concurrentEvictionKeepsEveryRenderedPayloadWithItsOwnTimestamp() {
        val buffer = AudioCodecBuffer().apply { start() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val consumer =
                executor.submit<Int> {
                    val output = ByteBuffer.allocate(4)
                    val result = AudioCodecBuffer.RenderResult()
                    var previous = -1
                    while (previous != 999) {
                        output.clear()
                        assertTrue(buffer.render(output, result))
                        val value = output.getInt(0)
                        assertTrue(value > previous)
                        assertEquals(value * 100_000L, result.presentationTimeUs)
                        previous = value
                    }
                    previous
                }
            val input = ByteBuffer.allocate(4)
            repeat(1000) { value ->
                input.putInt(0, value)
                buffer.append(input, value * 100_000L)
            }
            assertEquals(999, consumer.get(5, TimeUnit.SECONDS).toInt())
        } finally {
            buffer.clear()
            executor.shutdownNow()
        }
    }
}
