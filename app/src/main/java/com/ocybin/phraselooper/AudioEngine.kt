package com.ocybin.phraselooper

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteOrder

/**
 * One clip in the combined loop. The full audio is always kept; only the part from
 * [start] up to (not including) [end] plays. That makes trims non-destructive:
 * you can widen them again later.
 */
class Segment(
    val audio: ShortArray,
    val start: Int = 0,
    val end: Int = audio.size,
    /** Which recorded, loaded or created clip this is (1, 2, 3...). It never changes, even if segments move or others are deleted. */
    val clipNumber: Int = 0
) {
    val length: Int
        get() = end - start

    /** What the list shows. */
    val label: String
        get() = "$clipNumber"

    /** Same clip, same number, different trim. */
    fun withTrim(newStart: Int, newEnd: Int) = Segment(audio, newStart, newEnd, clipNumber)
}

class AudioEngine {
    private val TAG = "AudioEngine"

    companion object {
        const val SAMPLE_RATE = 44100 // CD quality
        const val MAX_IMPORT_SECONDS = 300 // longest single file we will decode (5 minutes)
        const val MAX_TOTAL_SAMPLES = SAMPLE_RATE * 60 * 10 // all clips together: about 10 minutes
    }

    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // ------------------------------------------------------------------
    // OBSERVABLE STATE (the screen redraws automatically when these change)
    // ------------------------------------------------------------------
    var isRecording by mutableStateOf(false)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    /** The clips that make up the combined loop, in play order. */
    var segments by mutableStateOf<List<Segment>>(emptyList())
        private set

    /** Index of the segment being edited, or -1 when nothing is selected. */
    var selectedIndex by mutableIntStateOf(-1)
        private set

    /** Length of the whole combined loop, counting only the trimmed parts. */
    var totalSamples by mutableIntStateOf(0)
        private set

    /** Bumps whenever segments are added, removed or replaced (but not on a plain trim). */
    var bufferVersion by mutableIntStateOf(0)
        private set

    /** Where playback starts, and where the red marker rests while stopped (in loop samples). */
    var cuePosition by mutableIntStateOf(0)
        private set

    /** A readable reason when recording could not start, otherwise null. */
    var lastRecordError: String? = null
        private set

    val hasAudio: Boolean
        get() = totalSamples > 0

    val totalSeconds: Float
        get() = totalSamples / SAMPLE_RATE.toFloat()

    /** True if the last loaded file was longer than MAX_IMPORT_SECONDS and got cut off. */
    var lastLoadTruncated = false
        private set

    /** A readable reason when the last load failed, otherwise null. */
    var lastLoadError: String? = null
        private set

    // ------------------------------------------------------------------
    // INTERNAL STATE
    // ------------------------------------------------------------------
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

    @Volatile private var stopRequested = false
    @Volatile private var writerPlayhead = 0   // next loop position the writer will send to the speaker
    @Volatile private var framesWritten = 0L   // total frames handed to the AudioTrack so far
    @Volatile private var seekRequest = -1       // loop position the writer should jump to, or -1
    @Volatile private var seekMarkFrames = -1L   // frames that were already queued when the latest jump happened
    @Volatile private var seekTargetIndex = 0    // where the latest jump landed

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------------------------
    // SEGMENT MANAGEMENT
    // ------------------------------------------------------------------
    private fun lengthOf(list: List<Segment>): Int {
        var total = 0
        for (s in list) total += s.length
        return total
    }

    private fun offsetOf(list: List<Segment>, index: Int): Int {
        var offset = 0
        for (i in 0 until index) offset += list[i].length
        return offset
    }

    /** Memory in use: the full audio of every segment, trimmed or not. */
    private fun usedSamples(): Long {
        var used = 0L
        for (s in segments) used += s.audio.size
        return used
    }

    private var clipCounter = 0

    /** Wraps freshly recorded or loaded audio as the next numbered clip. */
    private fun newSegment(audio: ShortArray) = Segment(audio, 0, audio.size, ++clipCounter)

    private fun commit(newSegments: List<Segment>, structureChanged: Boolean) {
        segments = newSegments
        totalSamples = lengthOf(newSegments)
        if (structureChanged) bufferVersion++
    }

    /** Throws away everything currently in the workspace. */
    fun discard() {
        stopPlaybackLoop()
        selectedIndex = -1
        cuePosition = 0
        clipCounter = 0 // numbering starts again from 1
        commit(emptyList(), true)
    }

    /** Removes one segment. Removing the last remaining one clears everything. */
    fun discardSegment(index: Int) {
        val list = segments
        if (index !in list.indices) return
        if (list.size == 1) {
            discard()
            return
        }

        selectedIndex = when {
            selectedIndex == index -> -1
            selectedIndex > index -> selectedIndex - 1
            else -> selectedIndex
        }
        commit(list.filterIndexed { i, _ -> i != index }, true)
    }

    /** Moves a segment one place earlier (direction -1) or later (+1). The selection stays with it. */
    fun moveSegment(index: Int, direction: Int) {
        val list = segments
        val target = index + direction
        if (index !in list.indices || target !in list.indices) return

        val updated = list.toMutableList()
        updated.add(target, updated.removeAt(index))

        selectedIndex = when (selectedIndex) {
            index -> target
            target -> index
            else -> selectedIndex
        }
        commit(updated, true)
    }

    /** A readable reason when createSegmentFromLoop failed, otherwise null. */
    var lastCreateError: String? = null
        private set

    /**
     * Makes a new segment out of whatever is currently looping: the selected segment's
     * trimmed part, or the whole combined loop when nothing is selected. The audio is
     * copied, so the new segment is independent of where it came from. It goes at the
     * end of the loop and is NOT selected.
     */
    fun createSegmentFromLoop(): Boolean {
        lastCreateError = null
        val list = segments
        if (list.isEmpty() || totalSamples <= 0) {
            lastCreateError = "Nothing to copy yet."
            return false
        }

        val source = list.getOrNull(selectedIndex)
        val length = source?.length ?: totalSamples
        if (usedSamples() + length > MAX_TOTAL_SAMPLES) {
            lastCreateError = "Not enough room: all clips together are limited to about 10 minutes. Discard a segment first."
            return false
        }

        val audio = try {
            ShortArray(length)
        } catch (e: OutOfMemoryError) {
            lastCreateError = "Not enough memory to copy this."
            return false
        }
        if (source != null) {
            System.arraycopy(source.audio, source.start, audio, 0, length)
        } else {
            readSamples(list, 0, audio, length)
        }

        commit(list + newSegment(audio), true)
        return true
    }

    /** Selects a segment for trimming. Selecting the already-selected one deselects it. */
    fun toggleSelection(index: Int) {
        selectedIndex = if (index !in segments.indices || index == selectedIndex) -1 else index
    }

    /**
     * Sets the trim of one segment. The fractions (0.0 - 1.0) are positions within the
     * segment's FULL audio, so the slider always spans the whole original clip.
     */
    fun trimSegment(index: Int, startFraction: Float, endFraction: Float) {
        val len = segments.getOrNull(index)?.audio?.size ?: return
        trimSegmentSamples(index, (len * startFraction).toInt(), (len * endFraction).toInt())
    }

    /** Same as [trimSegment] but with exact sample positions (used by the fine-tune buttons). */
    fun trimSegmentSamples(index: Int, startSample: Int, endSample: Int) {
        val list = segments
        if (index !in list.indices) return

        val seg = list[index]
        val len = seg.audio.size
        if (len <= 0) return

        val start = startSample.coerceIn(0, len - 1)
        val end = endSample.coerceIn(start + 1, len)
        if (start == seg.start && end == seg.end) return

        val updated = list.toMutableList()
        updated[index] = seg.withTrim(start, end)
        commit(updated, false)

        // If this segment is looping, replay the new trim from its beginning
        if (isPlaying && index == selectedIndex) seekRequest = offsetOf(updated, index)
    }

    /** Copies [count] samples of the combined loop, beginning at loop position [position], into [dest]. */
    private fun readSamples(segs: List<Segment>, position: Int, dest: ShortArray, count: Int) {
        var remaining = count
        var destOffset = 0
        var pos = position
        var segmentStart = 0

        for (seg in segs) {
            val len = seg.length
            if (pos < segmentStart + len) {
                val offsetInSegment = pos - segmentStart
                val n = minOf(remaining, len - offsetInSegment)
                System.arraycopy(seg.audio, seg.start + offsetInSegment, dest, destOffset, n)
                destOffset += n
                pos += n
                remaining -= n
                if (remaining <= 0) return
            }
            segmentStart += len
        }
    }

    // ------------------------------------------------------------------
    // RECORDING
    // ------------------------------------------------------------------
    /** Returns true if recording actually started. The take is added as a new segment when it stops. */
    @SuppressLint("MissingPermission") // Permission is checked in the UI layer
    fun startRecording(): Boolean {
        if (isRecording) return false
        lastRecordError = null

        // Recording adds a new segment, so leave room under the memory limit
        val room = (MAX_TOTAL_SAMPLES - usedSamples()).toInt()
        if (room < SAMPLE_RATE) {
            lastRecordError = "Not enough room: all clips together are limited to about 10 minutes. Discard a segment first."
            return false
        }

        stopPlaybackLoop()

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            Log.e(TAG, "Invalid audio parameters.")
            return false
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 2
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord creation failed: ${e.message}")
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord could not initialize.")
            record.release()
            return false
        }

        stopRequested = false
        isRecording = true
        record.startRecording()
        Log.d(TAG, "Recording started...")

        // This background job owns the recorder from start to finish, so the
        // audio is fully built before isRecording flips back to false.
        scope.launch {
            val temp = ShortArray(minBufferSize)
            var data = ShortArray(SAMPLE_RATE * 10) // starts at 10 seconds, grows as needed
            var size = 0

            try {
                while (!stopRequested && size < room) {
                    val read = record.read(temp, 0, temp.size)
                    if (read < 0) break
                    if (read > 0) {
                        if (size + read > data.size) {
                            data = data.copyOf(maxOf(data.size * 2, size + read))
                        }
                        System.arraycopy(temp, 0, data, size, read)
                        size += read
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}")
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }

            size = minOf(size, room)
            if (size > 0) {
                commit(segments + newSegment(data.copyOf(size)), true)
                selectedIndex = segments.lastIndex
                Log.d(TAG, "Recording saved. Total samples captured: $size")
            }
            isRecording = false
        }
        return true
    }

    /** Asks the recorder to finish. isRecording turns false once the audio is ready. */
    fun stopRecording() {
        if (!isRecording) return
        stopRequested = true
    }

    // ------------------------------------------------------------------
    // PLAYBACK (always plays the whole combined loop)
    // ------------------------------------------------------------------
    /** Builds a small-buffer output track for looping playback. */
    private fun buildOutputTrack(): AudioTrack? {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AUDIO_FORMAT
        )

        // A small buffer keeps what you SEE (playhead) close to what you HEAR,
        // and makes trim changes take effect quickly.
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBufferSize * 2, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack creation failed: ${e.message}")
            null
        }
    }

    /** Plays the whole loop, or just the selected segment if one is selected. */
    fun startPlaybackLoop() {
        if (isPlaying) return
        if (totalSamples <= 1) return

        val track = buildOutputTrack() ?: return

        val startAt = cuePosition.coerceIn(0, totalSamples - 1)
        audioTrack = track
        framesWritten = 0L
        writerPlayhead = startAt
        seekRequest = -1
        seekMarkFrames = -1L
        isPlaying = true
        track.play()
        Log.d(TAG, "Playback loop started")

        playbackJob = scope.launch {
            val chunk = ShortArray(1024)
            var playhead = startAt

            while (isActive && isPlaying) {
                // Re-read the clip list every pass so trims and discards apply immediately
                val segs = segments
                val total = lengthOf(segs)

                // With a segment selected only that segment loops; otherwise the whole timeline does
                var rangeStart = 0
                var rangeEnd = total
                val selection = selectedIndex
                if (selection in segs.indices) {
                    rangeStart = offsetOf(segs, selection)
                    rangeEnd = rangeStart + segs[selection].length
                }

                if (rangeEnd - rangeStart <= 1) {
                    delay(10)
                    continue
                }

                // A tap on the timeline (or a finished trim) asks the writer to jump to a new spot
                val request = seekRequest
                if (request >= 0) {
                    seekRequest = -1
                    playhead = request.coerceIn(rangeStart, rangeEnd - 1)
                    seekTargetIndex = playhead
                    seekMarkFrames = framesWritten
                }

                if (playhead < rangeStart || playhead >= rangeEnd) playhead = rangeStart

                val count = minOf(chunk.size, rangeEnd - playhead)
                readSamples(segs, playhead, chunk, count)
                playhead += count
                if (playhead >= rangeEnd) playhead = rangeStart // wrap back to the start of the loop

                val written = track.write(chunk, 0, count)
                if (written < 0) break
                framesWritten += written
                writerPlayhead = playhead
            }
        }
    }

    fun stopPlaybackLoop() {
        if (!isPlaying) return
        isPlaying = false

        playbackJob?.cancel()
        playbackJob = null

        val track = audioTrack
        audioTrack = null
        try {
            track?.pause()
            track?.flush() // cut the sound immediately instead of playing out the buffer
            track?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio track: ${e.message}")
        }
        Log.d(TAG, "Playback loop stopped.")
    }

    /**
     * The loop position that is actually coming out of the speaker right now.
     * (The writer runs slightly ahead of the speaker, so we subtract what is still queued.)
     */
    fun currentPlaybackIndex(): Int {
        val track = audioTrack ?: return 0
        val segs = segments
        val total = lengthOf(segs)
        if (total <= 0) return 0

        // The range that is looping: one selected segment, or the whole timeline
        var rangeStart = 0
        var rangeEnd = total
        val selection = selectedIndex
        if (selection in segs.indices) {
            rangeStart = offsetOf(segs, selection)
            rangeEnd = rangeStart + segs[selection].length
        }
        val length = (rangeEnd - rangeStart).toLong()
        if (length <= 0) return rangeStart

        return try {
            val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

            // Right after a jump, the old audio is still draining from the buffer (about 0.2 s).
            // Show the marker at the new spot straight away instead of letting it flicker back.
            if (seekMarkFrames >= 0) {
                if (played < seekMarkFrames) return seekTargetIndex.coerceIn(0, total - 1)
                seekMarkFrames = -1
            }

            val queued = (framesWritten - played).coerceAtLeast(0L)
            val position = writerPlayhead.toLong() - queued
            (rangeStart + ((position - rangeStart) % length + length) % length).toInt()
        } catch (e: Exception) {
            rangeStart
        }
    }

    /**
     * Moves the red marker to a loop position. While playing, playback jumps there;
     * while stopped, the next press of Play starts from there.
     * Touching outside the selected segment deselects it, so the whole loop plays again.
     */
    fun seekTo(index: Int) {
        val segs = segments
        val total = lengthOf(segs)
        if (total <= 0) return
        val target = index.coerceIn(0, total - 1)

        val selection = selectedIndex
        if (selection in segs.indices) {
            val start = offsetOf(segs, selection)
            if (target < start || target >= start + segs[selection].length) selectedIndex = -1
        }

        cuePosition = target
        if (isPlaying) seekRequest = target
    }

    // ------------------------------------------------------------------
    // SAVING (the whole combined loop becomes one file)
    // ------------------------------------------------------------------
    /** Encodes the combined loop to M4A and writes it to the location the user picked. */
    fun saveAudioToUri(context: Context, uri: Uri): Boolean {
        val segs = segments
        val total = lengthOf(segs)
        if (total <= 0) return false

        val tempFile = File(context.cacheDir, "temp_export.m4a")
        if (tempFile.exists()) tempFile.delete()

        val encoded = try {
            encodeToFile(segs, total, tempFile)
        } catch (e: Exception) {
            Log.e(TAG, "Encoding failed: ${e.message}")
            false
        }
        if (!encoded) {
            tempFile.delete()
            return false
        }

        return try {
            val stream = context.contentResolver.openOutputStream(uri) ?: return false
            stream.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Write to Uri failed: ${e.message}")
            false
        } finally {
            tempFile.delete()
        }
    }

    private fun encodeToFile(segs: List<Segment>, total: Int, outFile: File): Boolean {
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 10)

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var encoder: MediaCodec? = null
        var muxerStarted = false

        try {
            val enc = MediaCodec.createEncoderByType(mime)
            encoder = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            var trackIndex = -1
            val info = MediaCodec.BufferInfo()
            val temp = ShortArray(1024 * 5)
            var sampleIdx = 0
            var inputDone = false
            var presentationTimeUs = 0L

            while (true) {
                if (!inputDone) {
                    val inIndex = enc.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuf = enc.getInputBuffer(inIndex) ?: return false
                        inBuf.clear()
                        inBuf.order(ByteOrder.LITTLE_ENDIAN)

                        val count = minOf(inBuf.remaining() / 2, total - sampleIdx, temp.size)
                        if (count <= 0) {
                            enc.queueInputBuffer(inIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            readSamples(segs, sampleIdx, temp, count)
                            for (i in 0 until count) inBuf.putShort(temp[i])
                            enc.queueInputBuffer(inIndex, 0, count * 2, presentationTimeUs, 0)
                            presentationTimeUs += (count * 1_000_000L) / SAMPLE_RATE
                            sampleIdx += count
                        }
                    }
                }

                val outIndex = enc.dequeueOutputBuffer(info, 10000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(enc.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                } else if (outIndex >= 0) {
                    val outBuf = enc.getOutputBuffer(outIndex)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0
                    }
                    if (info.size > 0 && muxerStarted && outBuf != null) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, outBuf, info)
                    }

                    enc.releaseOutputBuffer(outIndex, false)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
            return true
        } finally {
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------
    // LOADING (MP3, M4A/AAC, OGG, FLAC, WAV... anything Android can decode)
    // ------------------------------------------------------------------
    /**
     * Decodes an audio file and adds it as a new segment at the end of the loop.
     * Existing segments (and their trims) are left exactly as they are.
     */
    fun loadAndAppendAudioFromUri(context: Context, fileUri: Uri): Boolean {
        lastLoadTruncated = false
        lastLoadError = null

        val newTrackAudio = try {
            decodeToMono44k(context, fileUri)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "File is too large to load into memory.")
            lastLoadError = "That file is too large to load into memory."
            null
        } catch (e: Exception) {
            Log.e(TAG, "Decoding failed: ${e.message}")
            null
        }

        if (newTrackAudio == null || newTrackAudio.isEmpty()) {
            if (lastLoadError == null) lastLoadError = "Couldn't read that audio file."
            return false
        }

        val existing = segments
        val used = usedSamples()
        if (used + newTrackAudio.size > MAX_TOTAL_SAMPLES) {
            lastLoadError = "Not enough room: all clips together are limited to about 10 minutes. Discard a segment first."
            return false
        }

        commit(existing + newSegment(newTrackAudio), true)
        selectedIndex = segments.lastIndex
        return true
    }

    /**
     * Decodes any supported audio file into one channel (stereo is averaged down)
     * and converts it to the engine's 44.1 kHz sample rate.
     * Stops after MAX_IMPORT_SECONDS to protect the phone's memory.
     */
    private fun decodeToMono44k(context: Context, fileUri: Uri): ShortArray? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null

        try {
            context.contentResolver.openFileDescriptor(fileUri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return null

            // Find the audio track inside the file
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex == -1) return null

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null

            // What the file claims to be. The decoder reports the real values once it starts.
            var sourceRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else SAMPLE_RATE
            var channels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var maxSamples = sourceRate * MAX_IMPORT_SECONDS

            val dec = MediaCodec.createDecoderByType(mimeType)
            decoder = dec
            dec.configure(inputFormat, null, null, 0)
            dec.start()

            // Pre-size the array from the file's duration so it rarely needs to grow
            val estimatedSeconds = if (inputFormat.containsKey(MediaFormat.KEY_DURATION))
                inputFormat.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 else 30.0
            val startSeconds = minOf(estimatedSeconds, MAX_IMPORT_SECONDS.toDouble()) + 1.0
            var mono = ShortArray((startSeconds * sourceRate).toInt().coerceAtLeast(SAMPLE_RATE))
            var monoSize = 0

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuf = dec.getInputBuffer(inIndex) ?: break
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = dec.dequeueOutputBuffer(info, 10000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The decoder tells us the true channel count, sample rate and sample type
                    val f = dec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sourceRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmEncoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    maxSamples = sourceRate * MAX_IMPORT_SECONDS
                } else if (outIndex >= 0) {
                    val outBuf = dec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)
                        val ch = maxOf(channels, 1)

                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = outBuf.asFloatBuffer()
                            val frames = fb.remaining() / ch
                            if (monoSize + frames > mono.size) {
                                mono = mono.copyOf(maxOf(mono.size * 3 / 2, monoSize + frames))
                            }
                            for (frame in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until ch) sum += fb.get()
                                val value = (sum / ch * 32767f).toInt().coerceIn(-32768, 32767)
                                mono[monoSize++] = value.toShort()
                            }
                        } else if (pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) {
                            val sb = outBuf.asShortBuffer()
                            val frames = sb.remaining() / ch
                            if (monoSize + frames > mono.size) {
                                mono = mono.copyOf(maxOf(mono.size * 3 / 2, monoSize + frames))
                            }
                            for (frame in 0 until frames) {
                                var sum = 0
                                for (c in 0 until ch) sum += sb.get()
                                mono[monoSize++] = (sum / ch).toShort()
                            }
                        } else {
                            Log.e(TAG, "Unsupported PCM encoding: $pcmEncoding")
                            dec.releaseOutputBuffer(outIndex, false)
                            return null
                        }
                    }

                    dec.releaseOutputBuffer(outIndex, false)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                    if (monoSize >= maxSamples) {
                        monoSize = maxSamples
                        lastLoadTruncated = true
                        outputDone = true
                    }
                }
            }

            if (monoSize == 0) return null
            return resampleToEngineRate(mono, monoSize, sourceRate)

        } finally {
            try {
                decoder?.stop()
                decoder?.release()
            } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** Simple linear-interpolation resampler. Good enough for sketching and demos. */
    private fun resampleToEngineRate(src: ShortArray, srcSize: Int, srcRate: Int): ShortArray {
        if (srcRate == SAMPLE_RATE || srcRate <= 0) return src.copyOf(srcSize)

        val outSize = ((srcSize.toLong() * SAMPLE_RATE) / srcRate).toInt()
        val out = ShortArray(outSize)
        val step = srcRate.toDouble() / SAMPLE_RATE

        for (i in 0 until outSize) {
            val pos = i * step
            val i0 = pos.toInt()
            val frac = pos - i0
            val a = src[i0].toInt()
            val b = if (i0 + 1 < srcSize) src[i0 + 1].toInt() else a
            out[i] = (a + (b - a) * frac).toInt().toShort()
        }
        return out
    }
}
