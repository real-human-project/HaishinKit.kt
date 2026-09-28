package com.haishinkit.media.source

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaType
import java.nio.ByteBuffer

/**
 * An audio source that captures a microphone by the AudioRecord api.
 *
 * PCM timestamps estimate the first returned frame's presentation time in monotonic microseconds.
 * On API 24+, AudioRecord's latest available frame/time observation supplies the capture mapping.
 * It describes the earliest available point in the capture pipeline, not each read's loss boundary:
 * native overruns can leave older PCM queued, so exact hardware-loss timing cannot be recovered.
 *
 * Until a capture observation is available (including API 21–23), the first successful read's
 * completion minus its duration anchors a sample-count estimate. Later unavailable observations
 * extrapolate the existing mapping. This cannot detect unobserved native losses or bound capture
 * latency. Forward mapping changes retain gaps; backward changes cannot overlap emitted audio,
 * so warmup estimation error may persist. Real capture restarts reset the clock; opening an
 * already recording source preserves its clock and frame position.
 */
@Suppress("MemberVisibilityCanBePrivate")
class AudioRecordSource(
    private val context: Context,
) : AudioSource {
    override var isMuted = false
    var channel = DEFAULT_CHANNEL
    var audioSource = DEFAULT_AUDIO_SOURCE
    var sampleRate = DEFAULT_SAMPLE_RATE
    var minBufferSize = -1
        get() {
            if (field == -1) {
                field = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            }
            return field
        }
    var audioRecord: AudioRecord? = null
        get() {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            if (field == null) {
                field = createAudioRecord(audioSource, sampleRate, channel, encoding, minBufferSize)
            }
            return field
        }
        private set

    private var encoding = DEFAULT_ENCODING
    private var sampleCount = DEFAULT_SAMPLE_COUNT
    private val byteBuffer: ByteBuffer = ByteBuffer.allocateDirect(sampleCount * 2)
    private val audioTimestamp = AudioTimestamp()
    private val clock = AudioRecordClock()
    private var lastReadError = AudioRecord.SUCCESS

    override suspend fun open(mixer: MediaMixer): Result<Unit> {
        try {
            audioRecord?.let { record ->
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    clock.reset()
                    lastReadError = AudioRecord.SUCCESS
                    record.startRecording()
                }
            }
        } catch (e: IllegalStateException) {
            return Result.failure(e)
        }
        return Result.success(Unit)
    }

    override suspend fun close(): Result<Unit> {
        try {
            audioRecord?.let { record ->
                try {
                    record.stop()
                } finally {
                    record.release()
                }
            }
        } catch (e: java.lang.IllegalStateException) {
            Log.w(TAG, e)
            return Result.failure(e)
        } finally {
            audioRecord = null
            clock.reset()
            lastReadError = AudioRecord.SUCCESS
        }
        return Result.success(Unit)
    }

    /**
     * Returns only the PCM bytes supplied by AudioRecord. A zero-byte read has an empty payload;
     * a negative read has a null payload and logs changes in the error code. Neither advances the
     * clock. Read errors do not trigger automatic recorder recovery.
     */
    override fun read(track: Int): MediaBuffer {
        byteBuffer.clear()
        val record = audioRecord
        val result = record?.read(byteBuffer, byteBuffer.capacity()) ?: AudioRecord.ERROR_INVALID_OPERATION
        val readCompletionNanos = System.nanoTime()
        if (result < 0) {
            byteBuffer.limit(0)
            if (lastReadError != result) {
                Log.e(TAG, "AudioRecord.read failed: $result")
                lastReadError = result
            }
            return MediaBuffer(type = MediaType.AUDIO, index = track, payload = null, sync = true)
        }
        lastReadError = AudioRecord.SUCCESS
        byteBuffer.position(0)
        byteBuffer.limit(result)
        var timestamp = 0L
        if (result > 0 && record != null) {
            val sampleRate = record.sampleRate
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                record.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
            ) {
                clock.update(audioTimestamp.framePosition, audioTimestamp.nanoTime, sampleRate)
            }
            timestamp = clock.timestamp(result / (record.channelCount * 2), sampleRate, readCompletionNanos)
            if (isMuted) {
                for (index in 0 until result) {
                    byteBuffer.put(index, 0)
                }
            }
        }
        return MediaBuffer(
            type = MediaType.AUDIO,
            index = track,
            payload = byteBuffer,
            timestamp = timestamp,
            sync = true,
        )
    }

    companion object {
        const val DEFAULT_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val DEFAULT_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_AUDIO_SOURCE = MediaRecorder.AudioSource.CAMCORDER
        const val DEFAULT_SAMPLE_COUNT = 1024

        @SuppressLint("MissingPermission")
        private fun createAudioRecord(
            audioSource: Int,
            sampleRate: Int,
            channel: Int,
            encoding: Int,
            minBufferSize: Int,
        ): AudioRecord {
            if (Build.VERSION_CODES.M <= Build.VERSION.SDK_INT) {
                return try {
                    AudioRecord
                        .Builder()
                        .setAudioSource(audioSource)
                        .setAudioFormat(
                            AudioFormat
                                .Builder()
                                .setEncoding(encoding)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channel)
                                .build(),
                        ).setBufferSizeInBytes(minBufferSize)
                        .build()
                } catch (_: Exception) {
                    AudioRecord(
                        audioSource,
                        sampleRate,
                        channel,
                        encoding,
                        minBufferSize,
                    )
                }
            } else {
                return AudioRecord(
                    audioSource,
                    sampleRate,
                    channel,
                    encoding,
                    minBufferSize,
                )
            }
        }

        private val TAG = AudioRecordSource::class.java.simpleName
    }
}
