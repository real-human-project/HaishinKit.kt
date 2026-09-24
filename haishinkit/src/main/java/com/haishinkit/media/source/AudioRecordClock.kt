package com.haishinkit.media.source

/**
 * Maps returned PCM frame positions to monotonic presentation time, not exact hardware capture time.
 * Forward changes in the observed mapping retain elapsed gaps. Backward changes are clamped to
 * prevent already presented audio from overlapping, including the transition out of warmup.
 */
internal class AudioRecordClock {
    private var readFrames = 0L
    private var originNanos = Long.MIN_VALUE

    fun update(
        framePosition: Long,
        nanoTime: Long,
        sampleRate: Int,
    ) {
        originNanos = maxOf(originNanos, nanoTime - frameTimeNanos(framePosition, sampleRate))
    }

    fun timestamp(
        frameCount: Int,
        sampleRate: Int,
        readCompletionNanos: Long,
    ): Long {
        if (originNanos == Long.MIN_VALUE) {
            // With no capture observation, only the first read's completion anchors the estimate.
            // Reanchoring every read would mistake scheduling stalls with queued PCM for lost audio.
            originNanos = readCompletionNanos - frameTimeNanos(readFrames + frameCount, sampleRate)
        }
        val timestamp = (originNanos + frameTimeNanos(readFrames, sampleRate)) / 1000L
        readFrames += frameCount
        return timestamp
    }

    fun reset() {
        readFrames = 0L
        originNanos = Long.MIN_VALUE
    }

    private fun frameTimeNanos(
        frames: Long,
        sampleRate: Int,
    ): Long = frames / sampleRate * 1000000000L + frames % sampleRate * 1000000000L / sampleRate
}
