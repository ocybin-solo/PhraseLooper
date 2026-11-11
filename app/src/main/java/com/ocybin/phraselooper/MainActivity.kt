package com.ocybin.phraselooper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ocybin.phraselooper.ui.theme.PhraseLooperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// Segment colors (they cycle if there are more clips than colors)
private val segmentColors = listOf(
    Color(0xFF42A5F5), // blue
    Color(0xFFFFA726), // orange
    Color(0xFF66BB6A), // green
    Color(0xFFAB47BC), // purple
    Color(0xFF26A69A), // teal
    Color(0xFFFFCA28), // yellow
    Color(0xFF8D6E63)  // brown
)

// The color follows the clip's number, so deleting or moving a segment never recolors the others
private fun colorFor(seg: Segment): Color =
    segmentColors[Math.floorMod(seg.clipNumber - 1, segmentColors.size)]

private fun seconds(samples: Int): String =
    "%.2f".format(samples / AudioEngine.SAMPLE_RATE.toFloat())

// How far the small and big arrow buttons move a trim marker
private const val CENTISECOND = AudioEngine.SAMPLE_RATE / 100 // 0.01 s
private const val DECISECOND = AudioEngine.SAMPLE_RATE / 10   // 0.1 s

/**
 * The suggested file name when saving. With the metronome on it starts with the tempo and time
 * signature, so takes made with the same settings sort together: "120bpm_7-8_Nov.9.26.234am.m4a"
 * (2:34 am on Nov 9, 2026). With it off, just the date and time: "Nov.9.26.234am.m4a".
 */
private fun defaultExportName(metronome: Metronome): String {
    val stamp = java.text.SimpleDateFormat("MMM.d.yy.hmma", java.util.Locale.US)
        .format(java.util.Date())
        .lowercase(java.util.Locale.US)
    return if (metronome.enabled) {
        "%03dbpm_%d-%d_%s.m4a".format(
            java.util.Locale.US, metronome.bpm, metronome.beatsPerBar, metronome.noteValue, stamp
        )
    } else {
        "$stamp.m4a"
    }
}

class MainActivity : ComponentActivity() {
    private val audioEngine = AudioEngine()
    private lateinit var metronome: Metronome

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        metronome = Metronome(applicationContext)

        // The screen stays on while the app is open, even if you haven't touched it
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        setContent {
            // Starts out following the phone's setting, then remembers your choice
            val systemDark = isSystemInDarkTheme()
            var darkTheme by remember {
                mutableStateOf(
                    if (prefs.contains("dark_theme")) prefs.getBoolean("dark_theme", systemDark) else systemDark
                )
            }
            var showInfo by remember { mutableStateOf(false) }

            PhraseLooperTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LooperDashboard(
                            engine = audioEngine,
                            metronome = metronome,
                            darkTheme = darkTheme,
                            onToggleTheme = {
                                darkTheme = !darkTheme
                                prefs.edit().putBoolean("dark_theme", darkTheme).apply()
                            },
                            onOpenInfo = { showInfo = true }
                        )
                        if (showInfo) {
                            InfoPanel(onClose = { showInfo = false })
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        metronome.stop() // never leave clicks running in the background
    }

    override fun onDestroy() {
        super.onDestroy()
        metronome.release()
    }
}

@Composable
fun LooperDashboard(
    engine: AudioEngine,
    metronome: Metronome,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenInfo: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var statusText by remember { mutableStateOf("Ready to record") }
    var isBusy by remember { mutableStateOf(false) } // true while saving or loading a file
    var playheadIndex by remember { mutableIntStateOf(0) }
    var showMetronomeSettings by remember { mutableStateOf(false) }
    // The segment the person tapped 🗑 on, waiting for them to confirm
    var segmentToDiscard by remember { mutableStateOf<Segment?>(null) }
    // How long the current recording has been running
    var recordingMillis by remember { mutableStateOf(0L) }

    val segs = engine.segments
    val selected = engine.selectedIndex
    val selectedSegment = segs.getOrNull(selected)
    val total = engine.totalSamples

    // Which trim marker is switched on: -1 = none (the slider and arrow buttons stay locked), 0 = start, 1 = end.
    // Every newly selected segment starts locked, so scrolling the screen can't nudge a marker by accident.
    var activeMarker by remember(selected) { mutableIntStateOf(-1) }

    // The trim slider is rebuilt from the segment whenever the selection or the clip list changes
    var sliderRange by remember(selected, engine.bufferVersion) {
        val seg = engine.segments.getOrNull(selected)
        val from = if (seg != null && seg.audio.isNotEmpty()) seg.start.toFloat() / seg.audio.size else 0f
        val to = if (seg != null && seg.audio.isNotEmpty()) seg.end.toFloat() / seg.audio.size else 1f
        mutableStateOf(from..to)
    }

    // While playing, ask the engine where the speaker is ~25 times per second
    LaunchedEffect(engine.isPlaying) {
        while (engine.isPlaying) {
            playheadIndex = engine.currentPlaybackIndex()
            delay(40)
        }
    }

    // If a recording ends by itself (memory limit), update the message
    LaunchedEffect(engine.isRecording) {
        if (engine.isRecording) {
            val startedAt = SystemClock.elapsedRealtime()
            recordingMillis = 0L
            while (engine.isRecording) {
                recordingMillis = SystemClock.elapsedRealtime() - startedAt
                delay(100)
            }
        }
    }

    LaunchedEffect(engine.isRecording) {
        if (!engine.isRecording) {
            metronome.stop()
            if (statusText == "Recording audio...") {
                statusText = "Recording finished."
            }
        }
    }

    fun beginRecording() {
        if (engine.startRecording()) {
            statusText = "Recording audio..."
            if (metronome.enabled) metronome.start()
        } else {
            val reason = engine.lastRecordError ?: "Couldn't start the microphone."
            statusText = reason
            Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
        }
    }

    // --- LOAD: system file picker ---
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isBusy = true
                statusText = "Decoding audio file..."
                val success = withContext(Dispatchers.IO) {
                    engine.loadAndAppendAudioFromUri(context, uri)
                }
                isBusy = false
                if (success) {
                    statusText = "Added segment ${engine.segments.lastOrNull()?.label}. Loop length: ${"%.2f".format(engine.totalSeconds)} s" +
                            if (engine.lastLoadTruncated) " (file was over 5 min, so only the first 5 min was loaded)" else ""
                    Toast.makeText(context, "Audio Loaded Successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    val reason = engine.lastLoadError ?: "Failed to load audio file."
                    statusText = reason
                    Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- SAVE: system "save as" picker (no storage permission needed) ---
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/mp4")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isBusy = true
                statusText = "Saving loop..."
                val loopSeconds = engine.totalSeconds
                val success = withContext(Dispatchers.IO) {
                    engine.saveAudioToUri(context, uri)
                }
                isBusy = false
                if (success) {
                    statusText = "Loop saved! (${"%.2f".format(loopSeconds)} s)"
                    Toast.makeText(context, "Saved Successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    statusText = "Export failed."
                    Toast.makeText(context, "Error exporting file.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            beginRecording()
        } else {
            Toast.makeText(context, "Microphone permission is required.", Toast.LENGTH_LONG).show()
        }
    }

    val playheadColor = Color.Red
    val outlineColor = MaterialTheme.colorScheme.onBackground
    val controlsEnabled = !engine.isRecording && !isBusy

    // Where the red marker is: the live playhead while playing, otherwise the spot Play will start from
    val markerIndex = (if (engine.isPlaying) playheadIndex else engine.cuePosition)
        .coerceIn(0, maxOf(total - 1, 0))

    // Turns a touch position on the timeline into a loop position and jumps there
    val seekFromX: (Float, Int) -> Unit = { x, width ->
        if (width > 0 && total > 0 && controlsEnabled) {
            val index = (x / width * total).toInt().coerceIn(0, total - 1)
            engine.seekTo(index)
            playheadIndex = index
        }
    }

    // --- MAIN DASHBOARD INTERFACE UI ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // TOP BAR: readme on the left, light/dark switch on the right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onOpenInfo) { Text("📖 README") }
            OutlinedButton(onClick = onToggleTheme) { Text(if (darkTheme) "☀ LIGHT" else "🌙 DARK") }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (engine.isRecording) {
                val totalSeconds = (recordingMillis / 1000).toInt()
                "Recording...  %d:%02d".format(java.util.Locale.US, totalSeconds / 60, totalSeconds % 60)
            } else {
                statusText
            },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Time readout
        if (engine.hasAudio) {
            val count = segs.size
            Text(
                text = "Loop: ${"%.2f".format(engine.totalSeconds)} s   |   $count ${if (count == 1) "segment" else "segments"}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(20.dp))
        }

        // METRONOME: the left button switches it on or off, the right one opens the settings
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { metronome.toggleEnabled() },
                enabled = controlsEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (metronome.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (metronome.enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).height(60.dp)
            ) {
                Text(
                    text = if (metronome.enabled) "METRONOME: ENABLED" else "METRONOME: DISABLED",
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = { showMetronomeSettings = true },
                enabled = controlsEnabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).height(60.dp)
            ) {
                Text(text = "METRONOME SETTINGS", textAlign = TextAlign.Center)
            }
        }
        Text(
            text = "${metronome.bpm} BPM  ·  ${metronome.signatureLabel}" +
                    if (metronome.grouping.isNotEmpty()) "  (${metronome.grouping})" else "",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )

        segmentToDiscard?.let { seg ->
            AlertDialog(
                onDismissRequest = { segmentToDiscard = null },
                title = { Text("Discard segment ${seg.label}?") },
                text = { Text("This removes it (${seconds(seg.length)} s) from the loop. It can't be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val index = engine.segments.indexOf(seg)
                            segmentToDiscard = null
                            if (index >= 0) {
                                engine.discardSegment(index)
                                statusText = "Segment ${seg.label} discarded."
                            }
                        }
                    ) {
                        Text("DISCARD")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { segmentToDiscard = null }) { Text("CANCEL") }
                }
            )
        }

        if (showMetronomeSettings) {
            MetronomeSettingsDialog(
                metronome = metronome,
                onDismiss = {
                    metronome.stop() // ends the preview, if it was running
                    showMetronomeSettings = false
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // LOAD and SAVE
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { filePickerLauncher.launch("audio/*") },
                enabled = controlsEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).height(55.dp)
            ) {
                Text(text = "📂 LOAD", textAlign = TextAlign.Center)
            }

            Button(
                onClick = { exportLauncher.launch(defaultExportName(metronome)) },
                enabled = engine.hasAudio && controlsEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).height(55.dp)
            ) {
                Text(text = "💾 SAVE", textAlign = TextAlign.Center)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // RECORD and PLAY side by side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (!engine.isRecording) {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPermission) {
                            beginRecording()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        engine.stopRecording()
                        metronome.stop()
                        statusText = "Recording stopped."
                    }
                },
                enabled = !isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (engine.isRecording) Color.Red else MaterialTheme.colorScheme.primary
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).height(60.dp)
            ) {
                Text(
                    text = if (engine.isRecording) "■ STOP RECORDING" else "● RECORD",
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = {
                    if (!engine.isPlaying) {
                        engine.startPlaybackLoop()
                        statusText = when {
                            !engine.isPlaying -> "Loop is too short to play."
                            selected >= 0 -> "Looping segment ${segs[selected].label}..."
                            else -> "Playing loop..."
                        }
                    } else {
                        engine.stopPlaybackLoop()
                        statusText = "Loop paused."
                    }
                },
                enabled = engine.hasAudio && controlsEnabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).height(60.dp)
            ) {
                Text(
                    text = when {
                        engine.isPlaying -> "■ STOP"
                        selected >= 0 -> "▶ PLAY SEGMENT"
                        else -> "▶ PLAY LOOP"
                    },
                    textAlign = TextAlign.Center
                )
            }
        }


        Spacer(modifier = Modifier.height(32.dp))

        if (!engine.hasAudio) {
            Text(
                text = "Record or load audio to begin.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            // ------------------------------------------------------------
            // TRIM EDITOR (only while a segment is selected)
            // ------------------------------------------------------------
            if (selectedSegment != null) {
                val clipSize = selectedSegment.audio.size

                // Moves the chosen marker by a number of samples (the arrow buttons beside the title)
                val markerOn = controlsEnabled && activeMarker >= 0

                val nudge: (Int) -> Unit = { delta ->
                    if (activeMarker >= 0) {
                        var s = selectedSegment.start
                        var e = selectedSegment.end
                        if (activeMarker == 0) s = (s + delta).coerceIn(0, e - 1)
                        else e = (e + delta).coerceIn(s + 1, clipSize)
                        sliderRange = (s.toFloat() / clipSize)..(e.toFloat() / clipSize)
                        engine.trimSegmentSamples(selected, s, e)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NudgeButton("◀◀", markerOn) { nudge(-DECISECOND) }
                    NudgeButton("◀", markerOn) { nudge(-CENTISECOND) }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "Trim tool", style = MaterialTheme.typography.bodyLarge)
                        Text(text = "segment ${selectedSegment.label}", style = MaterialTheme.typography.bodyLarge)
                    }
                    NudgeButton("▶", markerOn) { nudge(CENTISECOND) }
                    NudgeButton("▶▶", markerOn) { nudge(DECISECOND) }
                }

                val liveLength = ((sliderRange.endInclusive - sliderRange.start) * clipSize).toInt()
                Text(
                    text = "Plays ${seconds(liveLength)} s of ${seconds(clipSize)} s",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    RangeSlider(
                        value = sliderRange,
                        onValueChange = { range ->
                            // Only the marker that is switched on can move; the other one stays put
                            sliderRange = when (activeMarker) {
                                0 -> range.start..sliderRange.endInclusive
                                1 -> sliderRange.start..range.endInclusive
                                else -> sliderRange
                            }
                        },
                        // The trim is applied when you let go, so the loop stays clean while you drag
                        onValueChangeFinished = {
                            engine.trimSegment(selected, sliderRange.start, sliderRange.endInclusive)
                        },
                        valueRange = 0f..1f,
                        enabled = markerOn,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (engine.hasAudio) {
                        // Only draw the red line while the marker is inside this segment
                        var before = 0
                        for (i in 0 until selected) before += segs[i].length
                        val inside = markerIndex - before
                        if (inside >= 0 && inside < selectedSegment.length) {
                            val fraction = (selectedSegment.start + inside).toFloat() / selectedSegment.audio.size
                            // The slider track is inset by about 10dp on each side, so match that here
                            Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                                val x = size.width * fraction
                                drawLine(
                                    color = playheadColor,
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = 3.dp.toPx()
                                )
                            }
                        }
                    }
                }

                // START and END switch a marker on (tap again to lock it). CREATE SEGMENT sits between them.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToggleButton(
                        text = "START\n${seconds((sliderRange.start * clipSize).roundToInt())} s",
                        on = activeMarker == 0,
                        enabled = controlsEnabled,
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        activeMarker = if (activeMarker == 0) -1 else 0
                    }

                    // Copies the trimmed part of this segment into a new segment (added at the end, not selected)
                    Button(
                        onClick = {
                            if (engine.createSegmentFromLoop()) {
                                val made = engine.segments.last()
                                statusText = "Created segment ${made.label} (${seconds(made.length)} s)."
                                Toast.makeText(context, "Created segment ${made.label}", Toast.LENGTH_SHORT).show()
                            } else {
                                val reason = engine.lastCreateError ?: "Couldn't create a segment."
                                statusText = reason
                                Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
                            }
                        },
                        enabled = controlsEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        contentPadding = PaddingValues(4.dp),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Text(text = "➕ CREATE\nSEGMENT", textAlign = TextAlign.Center, fontSize = 13.sp)
                    }

                    ToggleButton(
                        text = "END\n${seconds((sliderRange.endInclusive * clipSize).roundToInt())} s",
                        on = activeMarker == 1,
                        enabled = controlsEnabled,
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        activeMarker = if (activeMarker == 1) -1 else 1
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // ------------------------------------------------------------
            // COMBINED LOOP TIMELINE
            // ------------------------------------------------------------
            Text(text = "Timeline", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .pointerInput(total, controlsEnabled) {
                        detectTapGestures { offset -> seekFromX(offset.x, size.width) }
                    }
                    .pointerInput(total, controlsEnabled) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> seekFromX(offset.x, size.width) }
                        ) { change, _ -> seekFromX(change.position.x, size.width) }
                    }
            ) {
                if (total > 0) {
                    var x = 0f
                    segs.forEachIndexed { i, seg ->
                        val w = size.width * seg.length / total
                        val rectWidth = maxOf(w - 2.dp.toPx(), 3.dp.toPx())
                        val dimmed = selected >= 0 && i != selected
                        drawRect(
                            color = colorFor(seg).copy(alpha = if (dimmed) 0.45f else 1f),
                            topLeft = Offset(x, 0f),
                            size = Size(rectWidth, size.height)
                        )
                        if (i == selected) {
                            drawRect(
                                color = outlineColor,
                                topLeft = Offset(x, 0f),
                                size = Size(rectWidth, size.height),
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }
                        x += w
                    }
                    run {
                        val px = size.width * markerIndex / total
                        drawLine(
                            color = playheadColor,
                            start = Offset(px, 0f),
                            end = Offset(px, size.height),
                            strokeWidth = 3.dp.toPx()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ------------------------------------------------------------
            // SEGMENT LIST: tap a row to select it, tap again to deselect
            // ------------------------------------------------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                segs.forEachIndexed { i, seg ->
                    val isSelected = i == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                            )
                            .clickable(enabled = controlsEnabled) { engine.toggleSelection(i) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(colorFor(seg))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "${seg.label}  ·  ${seconds(seg.length)} s",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Reordering waits until playback is stopped, so the playhead doesn't jump around
                        val canMove = controlsEnabled && !engine.isPlaying
                        IconButton(
                            onClick = { engine.moveSegment(i, -1) },
                            enabled = canMove && i > 0,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text(text = "▲")
                        }
                        IconButton(
                            onClick = { engine.moveSegment(i, 1) },
                            enabled = canMove && i < segs.lastIndex,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text(text = "▼")
                        }
                        // A gap, so the discard button isn't next to the ▲ ▼ buttons
                        Spacer(modifier = Modifier.width(20.dp))
                        IconButton(
                            onClick = { segmentToDiscard = seg },
                            enabled = controlsEnabled,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text(text = "🗑")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tap a segment to open it in the trim tool. Play then loops just that segment. Switch on START or END to unlock that marker, then drag it or use ◀ ▶ (0.01 s) and ◀◀ ▶▶ (0.1 s); your trim is applied when you let go. CREATE SEGMENT copies the trimmed part into a new segment. Tap the segment again to hear the whole loop. Tap or drag on the timeline to jump the red marker. Use ▲ ▼ to reorder (while stopped).",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ----------------------------------------------------------------------
// METRONOME SETTINGS POPUP
// ----------------------------------------------------------------------
@Composable
fun MetronomeSettingsDialog(metronome: Metronome, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Metronome settings")
                Spacer(modifier = Modifier.height(12.dp))
                // Pinned up here so it is always in reach, even while you scroll through the settings below.
                // It keeps playing while you change things; DONE stops it.
                Button(
                    onClick = { if (metronome.isRunning) metronome.stop() else metronome.start() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (metronome.isRunning) "■ STOP PREVIEW" else "▶ PREVIEW")
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = "Tempo", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { metronome.updateBpm(metronome.bpm - 1) }) { Text("−") }
                    Text(text = "${metronome.bpm} BPM", style = MaterialTheme.typography.headlineSmall)
                    OutlinedButton(onClick = { metronome.updateBpm(metronome.bpm + 1) }) { Text("+") }
                }
                Slider(
                    value = metronome.bpm.toFloat(),
                    onValueChange = { metronome.updateBpm(it.roundToInt()) },
                    valueRange = Metronome.MIN_BPM.toFloat()..Metronome.MAX_BPM.toFloat()
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Time signature: ${metronome.signatureLabel}",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { metronome.updateBeatsPerBar(metronome.beatsPerBar - 1) }) { Text("−") }
                    Text(text = "${metronome.beatsPerBar} beats", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { metronome.updateBeatsPerBar(metronome.beatsPerBar + 1) }) { Text("+") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OptionRow(
                    options = listOf("Quarter  /4", "Eighth  /8"),
                    selectedIndex = Metronome.NOTE_VALUES.indexOf(metronome.noteValue),
                    onSelect = { metronome.updateNoteValue(Metronome.NOTE_VALUES[it]) }
                )
                Text(
                    text = "Each click is one ${if (metronome.noteValue == 8) "eighth" else "quarter"} note, and the BPM counts those clicks.",
                    style = MaterialTheme.typography.bodySmall
                )

                val presets = Metronome.groupingPresets(metronome.beatsPerBar)
                if (presets.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Grouping", style = MaterialTheme.typography.titleSmall)
                    ChoiceGrid(
                        options = listOf("") + presets,
                        selected = metronome.grouping,
                        label = { if (it.isEmpty()) "None" else it },
                        onSelect = { metronome.updateGrouping(it) }
                    )
                    Text(
                        text = "Beat 1 is the strongest click. The first beat of each group is a full click, and the beats in between are softer.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Sound", style = MaterialTheme.typography.titleSmall)
                OptionRow(
                    options = Metronome.SOUND_NAMES,
                    selectedIndex = metronome.soundStyle,
                    onSelect = { metronome.updateSoundStyle(it) }
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Volume", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = metronome.volume,
                    onValueChange = { metronome.updateVolume(it) },
                    valueRange = 0.1f..1f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("DONE") }
        }
    )
}

/** Buttons laid out in rows of [perRow], where exactly one is highlighted. */
@Composable
private fun ChoiceGrid(
    options: List<String>,
    selected: String,
    label: (String) -> String,
    onSelect: (String) -> Unit,
    perRow: Int = 3
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.chunked(perRow).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rowOptions.forEach { option ->
                    if (option == selected) {
                        Button(
                            onClick = { onSelect(option) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = label(option), fontSize = 13.sp, maxLines = 1)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSelect(option) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = label(option), fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
                // Empty slots keep the last row's buttons the same width as the others
                repeat(perRow - rowOptions.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** A row of buttons where exactly one is highlighted. */
@Composable
private fun OptionRow(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            if (i == selectedIndex) {
                Button(
                    onClick = { onSelect(i) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = label, maxLines = 1)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelect(i) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = label, maxLines = 1)
                }
            }
        }
    }
}

/** A two-line button that is filled in while switched on and outlined while off. */
@Composable
private fun ToggleButton(
    text: String,
    on: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    if (on) {
        Button(
            onClick = onClick,
            enabled = enabled,
            contentPadding = PaddingValues(4.dp),
            modifier = modifier
        ) {
            Text(text = text, textAlign = TextAlign.Center, fontSize = 13.sp)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            contentPadding = PaddingValues(4.dp),
            modifier = modifier
        ) {
            Text(text = text, textAlign = TextAlign.Center, fontSize = 13.sp)
        }
    }
}

/** A small arrow button for fine-tuning a trim marker. */
@Composable
private fun NudgeButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.padding(horizontal = 2.dp).size(width = 44.dp, height = 40.dp)
    ) {
        Text(text = label, fontSize = 13.sp, maxLines = 1)
    }
}

// ----------------------------------------------------------------------
// README / LICENSE PANEL
// Shows readme.txt or license.txt from the app's assets folder.
// ----------------------------------------------------------------------
@Composable
fun InfoPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    var showLicense by remember { mutableStateOf(false) }
    val fileName = if (showLicense) "license.txt" else "readme.txt"

    val text = remember(fileName) {
        try {
            context.assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Couldn't find $fileName.\n\nAdd it to the app's assets folder (app/src/main/assets) in Android Studio and it will show up here."
        }
    }

    BackHandler { onClose() }

    Surface(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {}, // keeps touches from reaching the screen underneath
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onClose) { Text("◀ BACK") }
                OutlinedButton(onClick = { showLicense = !showLicense }) {
                    Text(if (showLicense) "README.TXT" else "LICENSE.TXT")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(text = fileName, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}