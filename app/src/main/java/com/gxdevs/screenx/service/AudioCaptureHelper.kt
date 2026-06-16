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
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
    private var systemReaderThread: Thread? = null
    private var micReaderThread: Thread? = null

    private val systemFifo = ShortArrayFifo()
    private val micFifo = ShortArrayFifo()

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

        // Clear FIFOs
        systemFifo.clear()
        micFifo.clear()

        // Start reader threads
        val recordBufferSize = bufferSize
        if (systemAudioRecord != null) {
            systemReaderThread = thread(start = true, name = "SystemAudioReader") {
                try {
                    systemAudioRecord?.startRecording()
                    val tempBuf = ShortArray(recordBufferSize / 2)
                    while (isRecording) {
                        val read = systemAudioRecord?.read(tempBuf, 0, tempBuf.size) ?: -1
                        if (read > 0) {
                            systemFifo.write(tempBuf, read)
                        } else if (read == 0) {
                            Thread.sleep(10)
                        } else {
                            break
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (micAudioRecord != null) {
            micReaderThread = thread(start = true, name = "MicAudioReader") {
                try {
                    micAudioRecord?.startRecording()
                    val tempBuf = ShortArray(recordBufferSize / 2)
                    while (isRecording) {
                        val read = micAudioRecord?.read(tempBuf, 0, tempBuf.size) ?: -1
                        if (read > 0) {
                            micFifo.write(tempBuf, read)
                        } else if (read == 0) {
                            Thread.sleep(10)
                        } else {
                            break
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Start capture/encode loop on background thread
        recordingThread = thread(start = true, name = "AudioRecordThread") {
            runCaptureLoop()
        }
    }

    private fun runCaptureLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        var audioTrackIndex = -1
        var muxerStarted = false
        val presentationStartTimeNs = System.nanoTime()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        var volumeRatio = 1f
        var loopCount = 0

        val systemBuf = ShortArray(1024)
        val micBuf = ShortArray(1024)
        val mixedBuffer = ShortArray(1024)

        var lastSystemReadTime = 0L
        var lastMicReadTime = 0L

        while (isRecording) {
            val now = System.currentTimeMillis()

            // Update volume ratio every ~100ms (approx. 10 loops)
            if (loopCount % 10 == 0) {
                try {
                    val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    volumeRatio = if (maxVolume > 0) currentVolume.toFloat() / maxVolume.toFloat() else 1f
                } catch (e: Exception) {
                    volumeRatio = 1f
                }
            }
            loopCount++

            val systemEnabled = systemAudioRecord != null
            val micEnabled = micAudioRecord != null

            val systemSize = systemFifo.size()
            val micSize = micFifo.size()

            var hasChunk = false

            if (systemEnabled && micEnabled) {
                val micIsActive = (now - lastMicReadTime) < 30 || micSize >= 1024
                val systemIsActive = (now - lastSystemReadTime) < 30 || systemSize >= 1024

                if (systemSize >= 1024 && micSize >= 1024) {
                    systemFifo.read(systemBuf, 1024)
                    micFifo.read(micBuf, 1024)
                    lastSystemReadTime = now
                    lastMicReadTime = now
                    hasChunk = true
                } else if (systemSize >= 1024 && !micIsActive) {
                    systemFifo.read(systemBuf, 1024)
                    micBuf.fill(0)
                    lastSystemReadTime = now
                    hasChunk = true
                } else if (micSize >= 1024 && !systemIsActive) {
                    systemBuf.fill(0)
                    micFifo.read(micBuf, 1024)
                    lastMicReadTime = now
                    hasChunk = true
                }
            } else if (systemEnabled) {
                if (systemSize >= 1024) {
                    systemFifo.read(systemBuf, 1024)
                    micBuf.fill(0)
                    lastSystemReadTime = now
                    hasChunk = true
                }
            } else if (micEnabled) {
                if (micSize >= 1024) {
                    systemBuf.fill(0)
                    micFifo.read(micBuf, 1024)
                    lastMicReadTime = now
                    hasChunk = true
                }
            }

            if (!hasChunk) {
                Thread.sleep(5)
                continue
            }

            // Mix systemBuf and micBuf
            for (i in 0 until 1024) {
                val systemSample = (systemBuf[i] * volumeRatio).toInt()
                val micSample = micBuf[i].toInt()
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
                
                // Write shorts directly into the codec's input buffer using native byte order
                inputBuffer.order(ByteOrder.nativeOrder())
                val shortBuffer = inputBuffer.asShortBuffer()
                shortBuffer.put(mixedBuffer, 0, 1024)

                val presentationTimeUs = (System.nanoTime() - presentationStartTimeNs) / 1000
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    1024 * 2,
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
            systemAudioRecord?.stop()
            systemAudioRecord?.release()
        } catch (e: Exception) {}
        try {
            micAudioRecord?.stop()
            micAudioRecord?.release()
        } catch (e: Exception) {}

        try {
            systemReaderThread?.join(100)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            micReaderThread?.join(100)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            recordingThread?.join(500)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        systemReaderThread = null
        micReaderThread = null
        recordingThread = null
    }
}

private class ShortArrayFifo {
    private var buffer = ShortArray(1024 * 16)
    private var head = 0
    private var tail = 0
    private var size = 0

    @Synchronized
    fun write(data: ShortArray, length: Int) {
        ensureCapacity(size + length)
        for (i in 0 until length) {
            buffer[tail] = data[i]
            tail = (tail + 1) % buffer.size
        }
        size += length
    }

    @Synchronized
    fun read(out: ShortArray, length: Int): Int {
        val toRead = minOf(length, size)
        for (i in 0 until toRead) {
            out[i] = buffer[head]
            head = (head + 1) % buffer.size
        }
        size -= toRead
        return toRead
    }

    @Synchronized
    fun size(): Int = size

    @Synchronized
    fun clear() {
        head = 0
        tail = 0
        size = 0
    }

    private fun ensureCapacity(requiredCapacity: Int) {
        if (requiredCapacity > buffer.size) {
            var newCapacity = buffer.size * 2
            while (newCapacity < requiredCapacity) {
                newCapacity *= 2
            }
            val newBuffer = ShortArray(newCapacity)
            for (i in 0 until size) {
                newBuffer[i] = buffer[(head + i) % buffer.size]
            }
            buffer = newBuffer
            head = 0
            tail = size
        }
    }
}
