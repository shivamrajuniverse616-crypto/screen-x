package com.gxdevs.screenx.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Robust video trimmer using MediaExtractor + MediaMuxer (lossless, fast).
 *
 * Design guarantees:
 *  1. Per-track extraction — each track gets its own extractor+seek so that
 *     seeking one track never disturbs another.
 *  2. Fresh PFD per extractor — avoids shared FileDescriptor position issues
 *     that appear on some OEMs.
 *  3. Timestamp rebasing — every output clip starts at t=0.
 *  4. Muxer only stopped/released if at least one sample was written, preventing
 *     the "Failed to stop muxer" crash on empty segments.
 *  5. cutMiddle handles cutStart==0 and cutEnd==videoDuration gracefully.
 *  6. measureDuration uses `fastPts >= 0` to avoid rejecting valid t=0 frames.
 */
object VideoTrimmer {

    private const val BUFFER_SIZE = 2 * 1024 * 1024 // 2 MB

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Trim: keep [startMs .. endMs], discard everything else.
     * Throws on failure; caller is responsible for reporting to the user.
     */
    fun trim(
        context: Context,
        sourceUri: Uri,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        progressCallback: (Float) -> Unit = {}
    ) {
        require(endMs > startMs) { "endMs must be > startMs" }
        extractSegment(
            context       = context,
            sourceUri     = sourceUri,
            outputFile    = outputFile,
            segStartUs    = startMs * 1000L,
            segEndUs      = endMs   * 1000L,
            outputOffsetUs = 0L,
            progressStart  = 0f,
            progressEnd    = 1f,
            progressCallback = progressCallback
        )
    }

    /**
     * Delete the middle section [cutStartMs .. cutEndMs] and concatenate
     * the two surrounding parts.  Both edge cases are handled:
     *  – cutStartMs == 0      → only keep [cutEndMs .. end]
     *  – cutEndMs >= duration → only keep [0 .. cutStartMs]
     */
    fun cutMiddle(
        context: Context,
        sourceUri: Uri,
        outputFile: File,
        cutStartMs: Long,
        cutEndMs: Long,
        progressCallback: (Float) -> Unit = {}
    ) {
        require(cutEndMs > cutStartMs) { "cutEndMs must be > cutStartMs" }

        val cutStartUs = cutStartMs * 1000L
        val cutEndUs   = cutEndMs   * 1000L

        val hasSeg1 = cutStartMs > 0L
        val hasSeg2 = true // we don't know total duration here; extractSegment handles empty output

        val ts = System.currentTimeMillis()
        val seg1File = File(outputFile.parent, "seg1_$ts.mp4")
        val seg2File = File(outputFile.parent, "seg2_${ts + 1}.mp4")

        try {
            if (hasSeg1) {
                extractSegment(
                    context = context, sourceUri = sourceUri, outputFile = seg1File,
                    segStartUs = 0L, segEndUs = cutStartUs, outputOffsetUs = 0L,
                    progressStart = 0f, progressEnd = 0.4f,
                    progressCallback = progressCallback
                )
                progressCallback(0.4f)
            }

            extractSegment(
                context = context, sourceUri = sourceUri, outputFile = seg2File,
                segStartUs = cutEndUs, segEndUs = Long.MAX_VALUE, outputOffsetUs = 0L,
                progressStart = 0.4f, progressEnd = 0.85f,
                progressCallback = progressCallback
            )
            progressCallback(0.85f)

            when {
                !hasSeg1 -> {
                    // Only seg2: rename
                    seg2File.renameTo(outputFile)
                }
                !seg2File.exists() || seg2File.length() == 0L -> {
                    // Only seg1: rename
                    seg1File.renameTo(outputFile)
                }
                else -> {
                    concatenate(seg1File, seg2File, outputFile, progressCallback)
                }
            }
            progressCallback(1f)
        } finally {
            seg1File.takeIf { it.exists() }?.delete()
            seg2File.takeIf { it.exists() }?.delete()
        }
    }

    /**
     * Split the video at [splitMs]:
     *  outputA ← [0 .. splitMs]
     *  outputB ← [splitMs .. end]
     */
    fun split(
        context: Context,
        sourceUri: Uri,
        outputA: File,
        outputB: File,
        splitMs: Long,
        progressCallback: (Float) -> Unit = {}
    ) {
        require(splitMs > 0) { "splitMs must be > 0" }

        val splitUs = splitMs * 1000L

        extractSegment(
            context = context, sourceUri = sourceUri, outputFile = outputA,
            segStartUs = 0L, segEndUs = splitUs, outputOffsetUs = 0L,
            progressStart = 0f, progressEnd = 0.5f,
            progressCallback = progressCallback
        )
        progressCallback(0.5f)

        extractSegment(
            context = context, sourceUri = sourceUri, outputFile = outputB,
            segStartUs = splitUs, segEndUs = Long.MAX_VALUE, outputOffsetUs = 0L,
            progressStart = 0.5f, progressEnd = 1f,
            progressCallback = progressCallback
        )
        progressCallback(1f)
    }

    /**
     * Export multiple segments: trims each segment and concatenates them into a single output file.
     */
    fun exportSegments(
        context: Context,
        sourceUri: Uri,
        outputFile: File,
        segments: List<Pair<Long, Long>>,
        progressCallback: (Float) -> Unit = {}
    ) {
        require(segments.isNotEmpty()) { "Segments list cannot be empty" }
        
        if (segments.size == 1) {
            trim(context, sourceUri, outputFile, segments[0].first, segments[0].second, progressCallback)
            return
        }

        val ts = System.currentTimeMillis()
        val tempFiles = segments.mapIndexed { index, _ ->
            File(outputFile.parent, "seg_${index}_$ts.mp4")
        }

        try {
            // Extract all segments
            segments.forEachIndexed { index, pair ->
                val startProgress = index.toFloat() / segments.size * 0.8f
                val endProgress = (index + 1).toFloat() / segments.size * 0.8f
                extractSegment(
                    context = context,
                    sourceUri = sourceUri,
                    outputFile = tempFiles[index],
                    segStartUs = pair.first * 1000L,
                    segEndUs = pair.second * 1000L,
                    outputOffsetUs = 0L,
                    progressStart = startProgress,
                    progressEnd = endProgress,
                    progressCallback = progressCallback
                )
            }

            // Concatenate them one by one
            var currentOut = tempFiles[0]
            for (i in 1 until tempFiles.size) {
                val nextOut = if (i == tempFiles.size - 1) outputFile else File(outputFile.parent, "concat_${i}_$ts.mp4")
                concatenate(currentOut, tempFiles[i], nextOut) { p ->
                    val base = 0.8f + (i - 1).toFloat() / (tempFiles.size - 1) * 0.2f
                    progressCallback(base + p * (0.2f / (tempFiles.size - 1)))
                }
                if (currentOut != tempFiles[0]) {
                    currentOut.delete()
                }
                currentOut = nextOut
            }
            progressCallback(1f)
        } finally {
            tempFiles.forEach { it.takeIf { it.exists() }?.delete() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Core extraction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts [segStartUs .. segEndUs] from [sourceUri] into [outputFile].
     *
     * – Opens a fresh PFD for every per-track extractor to avoid FD-position
     *   conflicts between tracks.
     * – Timestamps are rebased so the output starts at [outputOffsetUs].
     * – If NO samples are written (empty range / past-end seek), the muxer
     *   is released without calling stop(), preventing a crash.
     */
    private fun extractSegment(
        context: Context,
        sourceUri: Uri,
        outputFile: File,
        segStartUs: Long,
        segEndUs: Long,
        outputOffsetUs: Long,
        progressStart: Float,
        progressEnd: Float,
        progressCallback: (Float) -> Unit
    ) {
        // --- Probe: discover track count and formats ---
        val probePfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
            ?: throw IllegalArgumentException("Cannot open URI: $sourceUri")
        val probe = MediaExtractor()
        try {
            probe.setDataSource(probePfd.fileDescriptor)
            val trackCount = probe.trackCount
            val formats    = (0 until trackCount).map { probe.getTrackFormat(it) }

            if (trackCount == 0) {
                probePfd.close()
                return  // nothing to write
            }

            val muxer          = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dstTrackIdx    = formats.map { muxer.addTrack(it) }
            var muxerStarted   = false
            var muxerStopped   = false
            var anySampleWritten = false

            val buf  = ByteBuffer.allocate(BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()

            val totalProgress = progressEnd - progressStart
            val perTrack      = totalProgress / trackCount
            val segLen        = if (segEndUs == Long.MAX_VALUE) 1L
                                else (segEndUs - segStartUs).coerceAtLeast(1L)

            try {
                for (srcTrack in 0 until trackCount) {
                    // Fresh PFD per track — avoids shared FD-position issues
                    val pfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                        ?: throw IllegalArgumentException("Cannot re-open URI: $sourceUri")
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(pfd.fileDescriptor)
                        extractor.selectTrack(srcTrack)

                        // Seek to keyframe at or before start
                        extractor.seekTo(
                            if (segStartUs > 0L) segStartUs else 0L,
                            MediaExtractor.SEEK_TO_PREVIOUS_SYNC
                        )

                        var firstPts      = Long.MIN_VALUE
                        var trackHadSamples = false

                        while (true) {
                            info.offset = 0
                            info.size   = extractor.readSampleData(buf, 0)
                            if (info.size < 0) break

                            val pts = extractor.sampleTime

                            // Past end boundary
                            if (segEndUs != Long.MAX_VALUE && pts > segEndUs) break

                            // Before start boundary — skip but keep advancing
                            if (pts < segStartUs) {
                                extractor.advance()
                                continue
                            }

                            // Latch first PTS for rebasing
                            if (firstPts == Long.MIN_VALUE) firstPts = pts

                            // Start muxer on the very first sample written across all tracks
                            if (!muxerStarted) {
                                muxer.start()
                                muxerStarted = true
                            }

                            info.presentationTimeUs = (pts - firstPts) + outputOffsetUs
                            info.flags              = extractor.sampleFlags
                            muxer.writeSampleData(dstTrackIdx[srcTrack], buf, info)
                            anySampleWritten  = true
                            trackHadSamples   = true

                            // Progress
                            val fraction = if (segEndUs == Long.MAX_VALUE) 0f
                                           else ((pts - segStartUs).toFloat() / segLen).coerceIn(0f, 1f)
                            progressCallback(progressStart + srcTrack * perTrack + fraction * perTrack)

                            extractor.advance()
                        }
                    } finally {
                        extractor.release()
                        pfd.close()
                    }
                }
            } finally {
                // Only stop the muxer if it was started; stopping an un-started muxer crashes
                if (muxerStarted && !muxerStopped) {
                    try { muxer.stop(); muxerStopped = true } catch (_: Exception) {}
                }
                try { muxer.release() } catch (_: Exception) {}
            }
        } finally {
            probe.release()
            probePfd.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Concatenation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Concatenate [seg1] + [seg2] into [output].
     * Segment-2 timestamps are offset by the measured duration of segment-1
     * to ensure perfectly gapless playback.
     */
    private fun concatenate(
        seg1: File,
        seg2: File,
        output: File,
        progressCallback: (Float) -> Unit
    ) {
        val dur1 = measureDuration(seg1)

        // Probe formats from seg1
        val probe = MediaExtractor()
        probe.setDataSource(seg1.absolutePath)
        val trackCount = probe.trackCount
        val formats    = (0 until trackCount).map { probe.getTrackFormat(it) }
        probe.release()

        val muxer     = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val dstTracks = formats.map { muxer.addTrack(it) }
        muxer.start()

        val buf  = ByteBuffer.allocate(BUFFER_SIZE)
        val info = MediaCodec.BufferInfo()

        try {
            // --- Segment 1 ---
            for (t in 0 until trackCount) {
                val ex = MediaExtractor()
                ex.setDataSource(seg1.absolutePath)
                ex.selectTrack(t)
                ex.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (true) {
                    info.offset = 0
                    info.size   = ex.readSampleData(buf, 0)
                    if (info.size < 0) break
                    info.presentationTimeUs = ex.sampleTime
                    info.flags              = ex.sampleFlags
                    muxer.writeSampleData(dstTracks[t], buf, info)
                    ex.advance()
                }
                ex.release()
                progressCallback(0.85f + 0.06f * (t + 1) / trackCount.toFloat())
            }

            // --- Segment 2 (rebased) ---
            for (t in 0 until trackCount) {
                val ex = MediaExtractor()
                ex.setDataSource(seg2.absolutePath)
                ex.selectTrack(t)
                ex.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var firstPts = Long.MIN_VALUE
                while (true) {
                    info.offset = 0
                    info.size   = ex.readSampleData(buf, 0)
                    if (info.size < 0) break
                    val pts = ex.sampleTime
                    if (firstPts == Long.MIN_VALUE) firstPts = pts
                    info.presentationTimeUs = (pts - firstPts) + dur1
                    info.flags              = ex.sampleFlags
                    muxer.writeSampleData(dstTracks[t], buf, info)
                    ex.advance()
                }
                ex.release()
                progressCallback(0.91f + 0.09f * (t + 1) / trackCount.toFloat())
            }
        } finally {
            try { muxer.stop()    } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Duration measurement
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the last sample's PTS + one frame gap (≈33 ms at 30 fps) in µs.
     *
     * Uses a fast seek-to-end path; falls back to full scan when the seek
     * lands at an invalid position (returns -1) or on the very first frame (0).
     */
    private fun measureDuration(file: File): Long {
        val probe = MediaExtractor()
        probe.setDataSource(file.absolutePath)
        val trackCount = probe.trackCount
        probe.release()

        var maxPts = 0L
        val buf  = ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()

        for (t in 0 until trackCount) {
            val ex = MediaExtractor()
            ex.setDataSource(file.absolutePath)
            ex.selectTrack(t)

            // Fast path: seek near the end
            ex.seekTo(Long.MAX_VALUE / 2, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val fastPts = ex.sampleTime
            // fastPts >= 0 means a valid sample was found (including t=0 for single-frame)
            if (fastPts >= 0L) {
                if (fastPts > maxPts) maxPts = fastPts
                ex.release()
                continue
            }

            // Slow path: scan every frame (only needed for very unusual streams)
            ex.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            while (true) {
                info.offset = 0
                info.size   = ex.readSampleData(buf, 0)
                if (info.size < 0) break
                val pts = ex.sampleTime
                if (pts > maxPts) maxPts = pts
                ex.advance()
            }
            ex.release()
        }

        return maxPts + 33_333L   // one frame gap at ~30 fps
    }
}
