package com.ocybin.phraselooper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A click track with two sounds: one for beat 1 of each bar (the "accent") and a lighter
 * one for the other beats.
 *
 * It plays on its own AudioTrack and is never mixed into the segments or exported files.
 * Beats are written as exact numbers of audio samples, so the spacing stays even
 * (a timer would drift). Settings are saved and restored between launches.
 */
class Metronome(context: Context) {

    companion object {
        const val MIN_BPM = 40
        const val MAX_BPM = 240
        val BEATS_OPTIONS = 2..16
        val NOTE_VALUES = listOf(4, 8)
        val SOUND_NAMES = listOf("Beep", "Wood", "Thump")
        private const val SR = AudioEngine.SAMPLE_RATE

        /** How much quieter the in-between beats of a group are (the "soft" level). */
        private const val SOFT_GAIN = 0.45f

        /** "2+2+3" becomes [2, 2, 3]. Anything unreadable gives an empty list. */
        fun parseGrouping(grouping: String): List<Int> {
            if (grouping.isEmpty()) return emptyList()
            val parts = grouping.split("+").map { it.trim().toIntOrNull() ?: return emptyList() }
            return if (parts.all { it > 0 }) parts else emptyList()
        }

        /** True for "no grouping" (empty) or when the group sizes add up to the beats in the bar. */
        fun isValidGrouping(grouping: String, beats: Int): Boolean =
            grouping.isEmpty() || parseGrouping(grouping).sum() == beats

        /** Common ways to count a bar of this many beats. The first one is the usual default. */
        fun groupingPresets(beats: Int): List<String> {
            val presets = when (beats) {
                4 -> listOf("2+2")
                5 -> listOf("3+2", "2+3")
                6 -> listOf("3+3", "2+2+2")
                7 -> listOf("2+2+3", "3+2+2", "2+3+2", "4+3")
                8 -> listOf("3+3+2", "2+3+3", "4+4", "3+2+3")
                9 -> listOf("3+3+3", "2+2+2+3")
                10 -> listOf("3+3+2+2", "5+5")
                11 -> listOf("3+3+3+2", "3+3+2+3")
                12 -> listOf("3+3+3+3", "4+4+4")
                13 -> listOf("3+3+3+2+2", "4+3+3+3")
                14 -> listOf("3+3+3+3+2", "4+4+3+3")
                15 -> listOf("3+3+3+3+3", "5+5+5")
                16 -> listOf("4+4+4+4", "3+3+3+3+4")
                else -> emptyList()
            }
            return presets.filter { isValidGrouping(it, beats) }
        }

        /** Bars of 5 beats or more start with the first preset (7 beats gives 2+2+3); shorter bars start ungrouped. */
        fun defaultGrouping(beats: Int): String =
            if (beats >= 5) groupingPresets(beats).firstOrNull() ?: "" else ""

        /** Whether this beat (0 = first of the bar) starts a group. With no grouping, every beat counts. */
        fun isGroupStart(beat: Int, grouping: String): Boolean {
            val sizes = parseGrouping(grouping)
            if (sizes.isEmpty()) return true
            var start = 0
            for (size in sizes) {
                if (beat == start) return true
                start += size
            }
            return false
        }
    }

    private val TAG = "Metronome"
    private val prefs = context.applicationContext
        .getSharedPreferences("metronome_settings", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // SETTINGS (observable, so the screen updates by itself)
    // ------------------------------------------------------------------
    /** Whether Record should play the metronome. */
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false))
        private set

    var bpm by mutableIntStateOf(prefs.getInt("bpm", 100).coerceIn(MIN_BPM, MAX_BPM))
        private set

    var beatsPerBar by mutableIntStateOf(
        prefs.getInt("beats", 4).coerceIn(BEATS_OPTIONS.first, BEATS_OPTIONS.last)
    )
        private set

    /** The bottom number of the time signature: 4 (quarter notes) or 8 (eighth notes). */
    var noteValue by mutableIntStateOf(
        prefs.getInt("note", 4).let { if (it in NOTE_VALUES) it else 4 }
    )
        private set

    /** How the beats of a bar are grouped, like "2+2+3". Empty means no grouping. */
    var grouping by mutableStateOf(
        prefs.getString("grouping", null)?.takeIf { isValidGrouping(it, beatsPerBar) }
            ?: defaultGrouping(beatsPerBar)
    )
        private set

    /** The time signature as text, like "7/8". */
    val signatureLabel: String
        get() = "$beatsPerBar/$noteValue"

    /** 0.1 - 1.0 */
    var volume by mutableFloatStateOf(prefs.getFloat("volume", 0.8f).coerceIn(0.1f, 1f))
        private set

    /** Index into SOUND_NAMES. */
    var soundStyle by mutableIntStateOf(
        prefs.getInt("sound", 0).coerceIn(0, SOUND_NAMES.lastIndex)
    )
        private set

    /** True while clicks are actually playing. */
    var isRunning by mutableStateOf(false)
        private set

    private fun save() {
        prefs.edit()
            .putBoolean("enabled", enabled)
            .putInt("bpm", bpm)
            .putInt("beats", beatsPerBar)
            .putInt("note", noteValue)
            .putString("grouping", grouping)
            .putFloat("volume", volume)
            .putInt("sound", soundStyle)
            .apply()
    }

    fun toggleEnabled() {
        enabled = !enabled
        save()
    }

    fun updateBpm(value: Int) {
        val v = value.coerceIn(MIN_BPM, MAX_BPM)
        if (v == bpm) return
        bpm = v
        save()
    }

    fun updateBeatsPerBar(value: Int) {
        val v = value.coerceIn(BEATS_OPTIONS.first, BEATS_OPTIONS.last)
        if (v == beatsPerBar) return
        beatsPerBar = v
        // The old grouping only fits the old bar length
        if (!isValidGrouping(grouping, v)) grouping = defaultGrouping(v)
        save()
    }

    fun updateNoteValue(value: Int) {
        if (value !in NOTE_VALUES || value == noteValue) return
        noteValue = value
        save()
    }

    fun updateGrouping(value: String) {
        if (value == grouping || !isValidGrouping(value, beatsPerBar)) return
        grouping = value
        save()
    }

    fun updateVolume(value: Float) {
        val v = value.coerceIn(0.1f, 1f)
        if (v == volume) return
        volume = v
        save()
    }

    fun updateSoundStyle(value: Int) {
        val v = value.coerceIn(0, SOUND_NAMES.lastIndex)
        if (v == soundStyle) return
        soundStyle = v
        save()
    }

    // ------------------------------------------------------------------
    // PLAYBACK
    // ------------------------------------------------------------------
    private var track: AudioTrack? = null
    @Volatile private var running = false
    // Changes on every start and stop, so an old writer thread can never affect a newer run
    @Volatile private var session = 0

    @Synchronized
    fun start() {
        if (running) return

        val minBuffer = AudioTrack.getMinBufferSize(
            SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "Invalid audio parameters.")
            return
        }

        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                // A roomy buffer (about 0.37 s): the metronome doesn't need low latency,
                // and a full buffer keeps the sound steady if the phone gets busy for a moment
                .setBufferSizeInBytes(maxOf(minBuffer * 4, 32768))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack creation failed: ${e.message}")
            return
        }

        // Get everything ready BEFORE the sound starts: make both clicks, build the first
        // beat, and put its first part in the buffer. Starting with an empty buffer can
        // glitch (a repeated click) while the phone is still warming up.
        click(soundStyle, true)
        click(soundStyle, false)
        val firstExact = SR * 60.0 / bpm
        val firstFrames = firstExact.toInt()
        val firstCarry = firstExact - firstFrames
        val firstBeat = buildBeat(firstFrames, accent = true, soft = false)
        val prefill = minOf(4096, firstFrames) // always fits, so this write can't block
        t.write(firstBeat, 0, prefill)

        track = t
        running = true
        isRunning = true
        val mySession = ++session
        t.play()

        val writer = Thread(Runnable {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            fun alive() = running && session == mySession

            var beat = 0
            var carry = firstCarry // leftover fraction of a sample, so long runs stay on tempo
            var data = firstBeat
            var frames = firstFrames
            var offset = prefill
            var failed = false

            while (alive() && !failed) {
                while (offset < frames && alive()) {
                    val written = t.write(data, offset, minOf(2048, frames - offset))
                    if (written <= 0) {
                        if (alive()) failed = true
                        break
                    }
                    offset += written
                }
                if (failed || !alive()) break

                // Next beat. Tempo and sound are read fresh each time, so edits apply on the next click
                beat++
                if (beat >= beatsPerBar) beat = 0
                val exact = SR * 60.0 / bpm + carry
                frames = exact.toInt()
                carry = exact - frames
                // Three levels: beat 1 is the accent sound, the start of each other group is a full click,
                // and the beats in between are softer
                data = buildBeat(
                    frames,
                    accent = beat == 0,
                    soft = beat != 0 && !isGroupStart(beat, grouping)
                )
                offset = 0
            }

            if (failed) {
                Log.e(TAG, "Metronome output stopped unexpectedly.")
                if (session == mySession) stop()
            }
        }, "MetronomeWriter")
        writer.start()
        Log.d(TAG, "Metronome started")
    }

    @Synchronized
    fun stop() {
        if (!running && track == null) return
        running = false
        session++

        val t = track
        track = null
        try {
            t?.pause()
            t?.flush() // cut the sound immediately
            t?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping metronome: ${e.message}")
        }
        isRunning = false
        Log.d(TAG, "Metronome stopped")
    }

    /** Call when the app is closing for good. */
    fun release() {
        stop()
    }

    // ------------------------------------------------------------------
    // SOUNDS (generated in code, so no audio files are needed)
    // ------------------------------------------------------------------
    private val clickCache = ConcurrentHashMap<Int, ShortArray>()

    private fun click(style: Int, accent: Boolean): ShortArray =
        clickCache.getOrPut(style * 2 + if (accent) 1 else 0) { synthesize(style, accent) }

    /** One beat of audio: the click at the start, then silence until the next beat. */
    private fun buildBeat(frames: Int, accent: Boolean, soft: Boolean): ShortArray {
        val out = ShortArray(frames)
        val c = click(soundStyle, accent)
        val gain = if (soft) volume * SOFT_GAIN else volume
        val len = minOf(c.size, frames)
        for (i in 0 until len) out[i] = (c[i] * gain).toInt().toShort()
        return out
    }

    private fun synthesize(style: Int, accent: Boolean): ShortArray = when (style) {
        // Beat 1 is lower and louder; the other beats are higher and lighter
        1 -> wood(freq = if (accent) 520.0 else 880.0, amp = if (accent) 0.9 else 0.55)
        2 -> if (accent) thump() else tick()
        else -> beep(freq = if (accent) 800.0 else 1200.0, amp = if (accent) 0.9 else 0.55)
    }

    private fun toShort(v: Double): Short =
        (v * 32767.0).toInt().coerceIn(-32768, 32767).toShort()

    /** A short pure tone that fades out quickly. */
    private fun beep(freq: Double, amp: Double): ShortArray {
        val n = SR * 100 / 1000
        return ShortArray(n) { i ->
            val t = i.toDouble() / SR
            val attack = minOf(1.0, i / (SR * 0.001))
            val env = exp(-t / 0.015)
            toShort(sin(2 * PI * freq * t) * env * attack * amp)
        }
    }

    /** A "tok" like a woodblock: a tone plus a higher, inharmonic overtone, with a very fast fade. */
    private fun wood(freq: Double, amp: Double): ShortArray {
        val n = SR * 80 / 1000
        return ShortArray(n) { i ->
            val t = i.toDouble() / SR
            val attack = minOf(1.0, i / (SR * 0.0005))
            val env = exp(-t / 0.012)
            val body = sin(2 * PI * freq * t) + 0.5 * sin(2 * PI * freq * 2.76 * t)
            toShort(body / 1.5 * env * attack * amp)
        }
    }

    /** A kick-drum style thump: a sine that sweeps quickly downward in pitch. */
    private fun thump(): ShortArray {
        val n = SR * 300 / 1000
        var phase = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / SR
            val freq = 100.0 + 250.0 * exp(-t / 0.02)
            phase += 2 * PI * freq / SR
            val attack = minOf(1.0, i / (SR * 0.002))
            toShort(sin(phase) * exp(-t / 0.05) * attack * 0.95)
        }
    }

    /** A light hi-hat style tick: a very short burst of high-passed noise. */
    private fun tick(): ShortArray {
        val n = SR * 30 / 1000
        val rnd = Random(1234)
        var prev = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / SR
            val noise = rnd.nextDouble() * 2 - 1
            val high = (noise - prev) / 2 // crude high-pass: keeps the hiss, drops the rumble
            prev = noise
            toShort(high * exp(-t / 0.005) * 0.6)
        }
    }
}
