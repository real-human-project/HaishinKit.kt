package com.haishinkit.media.source

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.util.Log
import com.haishinkit.media.MediaMixer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [AudioRecordBoundary::class])
class AudioRecordSourceTest {
    private lateinit var source: AudioRecordSource
    private lateinit var recorder: AudioRecordBoundary

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        source =
            AudioRecordSource(context).apply {
                sampleRate = 48000
                minBufferSize = 4096
            }
        recorder = Shadow.extract(source.audioRecord)
    }

    @Test
    fun fullReadsUseTheFirstSampleOfTheObservedCaptureClock() {
        val first = source.read(3)
        assertEquals(9000000L, first.timestamp)
        assertEquals(3, first.index)
        assertEquals(2048, first.payload!!.remaining())
        assertEquals(7.toByte(), first.payload!!.get(2047))
        val second = source.read(3)
        assertEquals(9021333L, second.timestamp)
        assertSame(first.payload, second.payload)
    }

    @Test
    fun shortReadsNeverExposeThePreviousBufferTail() {
        val storage = source.read(0).payload!!
        recorder.bytesToRead = 12
        recorder.sampleByte = 9
        val short = source.read(0)
        assertSame(storage, short.payload)
        assertEquals(0, short.payload!!.position())
        assertEquals(12, short.payload!!.limit())
        assertArrayEquals(ByteArray(12) { 9 }, bytes(short.payload!!))
        recorder.bytesToRead = 2048
        assertEquals(9021458L, source.read(0).timestamp)
    }

    @Test
    fun mutedFullAndShortReadsKeepTheirValidRangeAndClock() {
        source.isMuted = true
        val full = source.read(0)
        assertEquals(9000000L, full.timestamp)
        assertArrayEquals(ByteArray(2048), bytes(full.payload!!))
        recorder.bytesToRead = 8
        val short = source.read(0)
        assertEquals(9021333L, short.timestamp)
        assertArrayEquals(ByteArray(8), bytes(short.payload!!))
        source.isMuted = false
        recorder.bytesToRead = 2048
        val unmuted = source.read(0)
        assertEquals(9021416L, unmuted.timestamp)
        assertArrayEquals(ByteArray(2048) { 7 }, bytes(unmuted.payload!!))
    }

    @Test
    fun zeroReadsDoNotRepeatSamplesOrAdvanceTheCapturePosition() {
        source.read(0)
        recorder.bytesToRead = 0
        assertEquals(0, source.read(0).payload!!.remaining())
        recorder.bytesToRead = 2048
        assertEquals(9021333L, source.read(0).timestamp)
    }

    @Test
    fun negativeReadsReportUnavailableWithoutAdvancingAndLogOnlyTransitions() {
        source.read(0)
        recorder.bytesToRead = AudioRecord.ERROR_DEAD_OBJECT
        assertNull(source.read(0).payload)
        assertNull(source.read(0).payload)
        assertEquals(1, readErrors())
        recorder.bytesToRead = AudioRecord.ERROR_INVALID_OPERATION
        assertNull(source.read(0).payload)
        assertEquals(2, readErrors())
        recorder.bytesToRead = 2048
        assertEquals(9021333L, source.read(0).timestamp)
        recorder.bytesToRead = AudioRecord.ERROR_INVALID_OPERATION
        assertNull(source.read(0).payload)
        assertEquals(3, readErrors())
    }

    @Test
    fun temporaryClockUnavailabilityExtrapolatesTheLastObservedMapping() {
        source.read(0)
        recorder.timestampResult = AudioRecord.ERROR_INVALID_OPERATION
        recorder.timestampNanos += 200000000L
        assertEquals(9021333L, source.read(0).timestamp)
        recorder.timestampResult = AudioRecord.SUCCESS
        assertEquals(9242666L, source.read(0).timestamp)
    }

    @Test
    fun observedForwardClockChangesRemainGaps() {
        assertEquals(9000000L, source.read(0).timestamp)
        recorder.timestampNanos += 200000000L
        assertEquals(9221333L, source.read(0).timestamp)
    }

    @Test
    fun stereoReadCountsFramesRatherThanIndividualChannelSamples() {
        source =
            AudioRecordSource(RuntimeEnvironment.getApplication()).apply {
                sampleRate = 48000
                channel = AudioFormat.CHANNEL_IN_STEREO
                minBufferSize = 4096
            }
        source.read(0)
        assertEquals(9010666L, source.read(0).timestamp)
    }

    @Test
    fun openingAnAlreadyRecordingSourcePreservesTheCapturePosition() =
        runBlocking {
            val mixer = MediaMixer(RuntimeEnvironment.getApplication())
            source.open(mixer).getOrThrow()
            val platformRecord = source.audioRecord!!
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, platformRecord.recordingState)
            assertEquals(9000000L, source.read(0).timestamp)
            val before = AudioTimestamp()
            platformRecord.getTimestamp(before, AudioTimestamp.TIMEBASE_MONOTONIC)

            source.open(mixer).getOrThrow()
            val after = AudioTimestamp()
            platformRecord.getTimestamp(after, AudioTimestamp.TIMEBASE_MONOTONIC)
            assertEquals(before.framePosition, after.framePosition)
            assertEquals(before.nanoTime, after.nanoTime)
            recorder.timestampFrame += 1024L
            recorder.timestampNanos += 21333333L
            assertEquals(9021333L, source.read(0).timestamp)
            assertEquals(1, recorder.startCalls)
            source.close().getOrThrow()
        }

    @Test
    fun closeAndReopenResetTheFramePositionAndRecorder() =
        runBlocking {
            val mixer = MediaMixer(RuntimeEnvironment.getApplication())
            source.open(mixer).getOrThrow()
            val originalRecord = source.audioRecord
            source.read(0)
            source.close().getOrThrow()
            source.open(mixer).getOrThrow()
            assertNotSame(originalRecord, source.audioRecord)
            assertEquals(9000000L, source.read(0).timestamp)
            source.close().getOrThrow()
        }

    @Test
    @Config(sdk = [23, 28])
    fun unavailableCaptureClockEstimatesTheInitialReadFromItsCompletion() {
        recorder.timestampResult = AudioRecord.ERROR_INVALID_OPERATION
        val before = System.nanoTime() / 1000L
        val first = source.read(0)
        val after = System.nanoTime() / 1000L
        val durationUs = 1024L * 1000000L / 48000L
        assertTrue(first.timestamp in (before - durationUs - 1)..(after - durationUs))
        val second = source.read(0)
        assertTrue(second.timestamp - first.timestamp in durationUs..(durationUs + 1))
    }

    private fun readErrors(): Int = ShadowLog.getLogsForTag("AudioRecordSource").count { it.type == Log.ERROR }

    private fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
}

/** A platform boundary: AudioRecord writes at byte zero without adjusting position or limit. */
@Implements(AudioRecord::class)
class AudioRecordBoundary {
    var bytesToRead = 2048
    var sampleByte: Byte = 7
    var timestampFrame = 48000L
    var timestampNanos = 10000000000L
    var timestampResult = AudioRecord.SUCCESS
    var startCalls = 0

    @Implementation
    fun read(
        buffer: ByteBuffer,
        sizeInBytes: Int,
    ): Int {
        if (bytesToRead > 0) {
            val target = buffer.duplicate().apply { clear() }
            repeat(minOf(bytesToRead, sizeInBytes)) { target.put(it, sampleByte) }
        }
        return bytesToRead
    }

    @Implementation(minSdk = 24)
    fun getTimestamp(
        timestamp: AudioTimestamp,
        timebase: Int,
    ): Int {
        assertEquals(AudioTimestamp.TIMEBASE_MONOTONIC, timebase)
        timestamp.framePosition = timestampFrame
        timestamp.nanoTime = timestampNanos
        return timestampResult
    }

    @Implementation
    fun getState(): Int = AudioRecord.STATE_INITIALIZED

    // Keep framework startRecording/stop/getRecordingState so the platform owns lifecycle state.
    @Implementation
    fun native_start(
        syncEvent: Int,
        sessionId: Int,
    ): Int {
        startCalls++
        return AudioRecord.SUCCESS
    }

    @Implementation
    fun release() = Unit
}
