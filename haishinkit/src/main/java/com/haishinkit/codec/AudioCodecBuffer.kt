package com.haishinkit.codec

import java.nio.ByteBuffer

internal class AudioCodecBuffer {
    var sampleRate: Int = 44100
    var channelCount: Int = 1
    private val lock = Object()
    private val buffers = Array(CAPACITY) { Buffer() }
    private var head = 0
    private var size = 0
    private var isRunning = false
    private var generation = 0L

    class RenderResult {
        var size = 0
        var presentationTimeUs = 0L
    }

    private class Buffer {
        var payload: ByteBuffer? = null
        var presentationTimeUs = 0L
    }

    fun start() {
        synchronized(lock) {
            isRunning = true
        }
    }

    fun append(
        byteBuffer: ByteBuffer,
        presentationTimeUs: Long,
    ) {
        synchronized(lock) {
            if (!isRunning || !byteBuffer.hasRemaining()) return
            val entry = buffers[(head + size) % CAPACITY]
            val payload =
                entry.payload?.takeIf { it.capacity() >= byteBuffer.remaining() }
                    ?: ByteBuffer.allocateDirect(byteBuffer.remaining()).also { entry.payload = it }
            payload.clear()
            val position = byteBuffer.position()
            try {
                payload.put(byteBuffer)
            } finally {
                byteBuffer.position(position)
            }
            payload.flip()
            entry.presentationTimeUs = presentationTimeUs
            if (size == CAPACITY) {
                head = (head + 1) % CAPACITY
            } else {
                size++
            }
            lock.notifyAll()
        }
    }

    fun render(
        byteBuffer: ByteBuffer,
        result: RenderResult,
    ): Boolean {
        synchronized(lock) {
            val generation = this.generation
            while (isRunning && generation == this.generation && size == 0) {
                lock.wait()
            }
            if (!isRunning || generation != this.generation) return false
            val entry = buffers[head]
            val payload = checkNotNull(entry.payload)
            val bytesPerFrame = channelCount * 2
            val count = minOf(byteBuffer.remaining(), payload.remaining()) / bytesPerFrame * bytesPerFrame
            require(count > 0) { "The input buffer must hold a complete PCM frame." }
            result.presentationTimeUs =
                entry.presentationTimeUs + payload.position().toLong() / bytesPerFrame * 1_000_000 / sampleRate
            result.size = count
            val limit = payload.limit()
            payload.limit(payload.position() + count)
            byteBuffer.put(payload)
            payload.limit(limit)
            if (!payload.hasRemaining()) {
                head = (head + 1) % CAPACITY
                size--
            }
            return true
        }
    }

    fun clear() {
        synchronized(lock) {
            isRunning = false
            generation++
            head = 0
            size = 0
            lock.notifyAll()
        }
    }

    companion object {
        const val CAPACITY = 4
    }
}
