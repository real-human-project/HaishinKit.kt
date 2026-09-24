package com.haishinkit.media.source

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRecordClockTest {
    @Test
    fun fractionalFrameDurationsDoNotAccumulateRoundingDrift() {
        val clock = AudioRecordClock()
        clock.update(0, 1000000000L, 44100)
        repeat(44100) { clock.timestamp(1, 44100, 0) }
        assertEquals(2000000L, clock.timestamp(1, 44100, 0))
    }

    @Test
    fun longRecordingsDoNotOverflowFrameToNanosecondConversion() {
        val clock = AudioRecordClock()
        clock.update(0, 2000000000L, 44100)
        repeat(5) { clock.timestamp(Int.MAX_VALUE, 44100, 0) }
        val frames = 5L * Int.MAX_VALUE
        assertEquals(2000000L + frames * 1000000L / 44100L, clock.timestamp(1, 44100, 0))
    }

    @Test
    fun unavailableClockUsesOneCompletionAnchorNotSchedulingDelays() {
        val clock = AudioRecordClock()
        assertEquals(1000000L, clock.timestamp(480, 48000, 1010000000L))
        // A delayed reader can still receive PCM already queued in the native ring.
        assertEquals(1010000L, clock.timestamp(240, 48000, 5000000000L))
        assertEquals(1015000L, clock.timestamp(480, 48000, 6000000000L))
    }

    @Test
    fun newlyAvailableClockPreservesAnObservedForwardDiscontinuity() {
        val clock = AudioRecordClock()
        assertEquals(1000000L, clock.timestamp(480, 48000, 1010000000L))
        clock.update(480, 1210000000L, 48000)
        assertEquals(1210000L, clock.timestamp(480, 48000, 1220000000L))
        assertEquals(1220000L, clock.timestamp(480, 48000, 1230000000L))
    }

    @Test
    fun backwardMappingCannotOverlapAlreadyPresentedAudio() {
        val clock = AudioRecordClock()
        clock.update(0, 1000000000L, 48000)
        assertEquals(1000000L, clock.timestamp(480, 48000, 0))
        clock.update(480, 900000000L, 48000)
        assertEquals(1010000L, clock.timestamp(480, 48000, 0))
        clock.update(960, 1220000000L, 48000)
        assertEquals(1220000L, clock.timestamp(480, 48000, 0))
    }

    @Test
    fun warmupEstimateCannotBeRetroactivelyCorrectedByAnEarlierCaptureMapping() {
        val clock = AudioRecordClock()
        assertEquals(1000000L, clock.timestamp(480, 48000, 1010000000L))
        clock.update(480, 900000000L, 48000)
        assertEquals(1010000L, clock.timestamp(480, 48000, 0))
    }

    @Test
    fun resetDiscardsBothDeliveredFramesAndThePreviousClockMapping() {
        val clock = AudioRecordClock()
        clock.update(0, 10000000000L, 48000)
        clock.timestamp(480, 48000, 0)
        clock.reset()
        assertEquals(1000000L, clock.timestamp(240, 48000, 1005000000L))
        clock.reset()
        clock.update(0, 2000000000L, 48000)
        assertEquals(2000000L, clock.timestamp(480, 48000, 0))
    }
}
