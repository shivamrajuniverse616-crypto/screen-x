package com.gxdevs.screenx.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class AudioCaptureHelper(
    private val context: Context,
    private val mediaProjection: MediaProjection?,
    private val audioSource: String, // "Mic", "System", "MicSystem"
    private val outputFile: File
) {
    private var systemAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    
    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormatEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val channelCount = 2
    private val bitRate = 128000 // AAC bitrate

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return
        isRecording = true

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormatEncoding) * 2

        // 1. Setup AudioRecord for System/Internal Audio (Android 10+ required)
        if ((audioSource == "System" || audioSource == "MicSystem") && mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(audioFormatEncoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            try {
                systemAudioRecord = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Setup AudioRecord for Microphone
        if (audioSource == "Mic" || audioSource == "MicSystem") {
            try {
                micAudioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormatEncoding,
                    bufferSize
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Initialize MediaCodec AAC Encoder
        val mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
        mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)

        try {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            e.printStackTrace()
            isRecording = false
            return
        }

        // Start capture/encode loop on background thread
        recordingThread = thread(start = true, name = "AudioRecordThread") {
            runCaptureLoop(bufferSize)
        }
    }

    private fun runCaptureLoop(bufferSize: Int) {
        systemAudioRecord?.startRecording()
        micAudioRecord?.startRecording()

        val systemBuffer = ShortArray(bufferSize / 2)
        val micBuffer = ShortArray(bufferSize / 2)
        val mixedBuffer = ShortArray(bufferSize / 2)

        val bufferInfo = MediaCodec.BufferInfo()
        var audioTrackIndex = -1
        var muxerStarted = false
        val presentationStartTimeNs = System.nanoTime()

        while (isRecording) {
            var systemBytesRead = 0
            var micBytesRead = 0

            // Read from System Audio Record
            systemAudioRecord?.let {
                systemBytesRead = it.read(systemBuffer, 0, systemBuffer.size)
            }

            // Read from Mic Audio Record
            micAudioRecord?.let {
                micBytesRead = it.read(micBuffer, 0, micBuffer.size)
            }

            val maxSamples = maxOf(
                if (systemBytesRead > 0) systemBytesRead else 0,
                if (micBytesRead > 0) micBytesRead else 0
            )

            if (maxSamples <= 0) {
                if (!isRecording) break
                Thread.sleep(10)
                continue
            }

            // Mix System and Mic Audio (add and clamp)
            for (i in 0 until maxSamples) {
                val systemSample = if (systemBytesRead > 0 && i < systemBytesRead) systemBuffer[i].toInt() else 0
                val micSample = if (micBytesRead > 0 && i < micBytesRead) micBuffer[i].toInt() else 0
                var mixed = systemSample + micSample
                if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE.toInt()
                if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE.toInt()
                mixedBuffer[i] = mixed.toShort()
            }

            // Feed mixed PCM to MediaCodec
            val codec = mediaCodec ?: break
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                inputBuffer.clear()
                
                // Convert ShortArray to ByteBuffer
                val byteBuffer = ByteBuffer.allocate(maxSamples * 2)
                for (i in 0 until maxSamples) {
                    byteBuffer.putShort(mixedBuffer[i])
                }
                byteBuffer.flip()
                inputBuffer.put(byteBuffer)

                val presentationTimeUs = (System.nanoTime() - presentationStartTimeNs) / 1000
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    maxSamples * 2,
                    presentationTimeUs,
                    0
                )
            }

            // Drain MediaCodec to MediaMuxer
            var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            while (outputBufferIndex >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size > 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    if (muxerStarted && audioTrackIndex != -1) {
                        mediaMuxer?.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                    }
                }

                codec.releaseOutputBuffer(outputBufferIndex, false)
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }

            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                mediaMuxer?.let {
                    audioTrackIndex = it.addTrack(newFormat)
                    it.start()
                    muxerStarted = true
                }
            }
        }

        // Cleanup and close
        try {
            systemAudioRecord?.stop()
            systemAudioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        systemAudioRecord = null

        try {
            micAudioRecord?.stop()
            micAudioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        micAudioRecord = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaCodec = null

        try {
            if (muxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaMuxer = null
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        try {
            recordingThread?.join(1000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        recordingThread = null
    }
}
