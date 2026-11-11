PHRASELOOPER

A record-and-loop app for practicing and sketching musical phrases. Record short pieces, trim them, arrange them into a loop, play along with a metronome, and save the result.


GETTING STARTED

1. Use headphones, or connect a bluetooth speaker, if recording with the metronome enabled.
2. Tap RECORD, play phrase, then tap STOP RECORDING. Each take becomes a segment.
3. Tap PLAY LOOP to hear your segments repeat endlessly. Record more segments to build up a track.
4. Tap SAVE to keep the result as an audio file.


THE SCREEN

- Status line: tells you what the app is doing.
- METRONOME and METRONOME SETTINGS: switch the metronome on or off, and open its settings.
- LOAD and SAVE: bring in an audio file, or save your loop.
- RECORD and PLAY: record a new segment, or play the loop. If a segment is selected, PLAY loops just that segment, otherwise it will loop the whole timeline.
- Trim tool: appears when you select a segment in the segments list.
- Timeline: a colored bar showing all your segments in order, with a red marker for the playhead. Tap or drag on it to move the marker.
- Segment list: every segment with its number and length.
- README (top left) opens this page. The sun/moon button (top right) switches between light and dark.


SEGMENTS

Each recording, loaded file and created segment is a numbered segment. Numbers and colors stay with a segment for good, so deleting or moving one never renames or recolors the others.

- Tap a segment in the list to select it, and tap it again to deselect it.
- ▲ and ▼ move a segment earlier or later in the loop. This is only available while playback is stopped.
- 🗑 discards a segment.
- Everything you record or load is kept in memory, up to about 10 minutes in total.


THE TRIM TOOL

Select a segment to open the trim tool. Your trim decides which part of the segment plays and gets saved.

- Tap START or END to switch that marker on. The slider and the arrow buttons stay locked until you do, so scrolling can't nudge a marker by accident. Tap the button again to lock it.
- Drag the marker on the slider, or use the arrow buttons beside the title: ◀ and ▶ move it 0.01 seconds, ◀◀ and ▶▶ move it 0.1 seconds.
- Your trim is applied when you let go of the slider.
- ➕ CREATE SEGMENT (between START and END) copies the trimmed part into a brand new segment, added at the end of the loop. It does not select the new segment, so you can keep working on the same one: move the markers to another part and create more segments from it.
- To duplicate a whole segment, leave it untrimmed and tap CREATE SEGMENT.


LOADING AND SAVING

- LOAD adds an audio file as a new segment. Most common formats work (MP3, M4A/AAC, OGG, FLAC, WAV and others your phone can decode). A single file loads only its first 5 minutes. If it doesn't fit in the memory you have left, the app tells you to discard a segment first.
- SAVE writes your whole loop, with all trims applied, as an M4A (AAC) file in mono.
- Suggested file names: with the metronome enabled, the name starts with the tempo and time signature, like 120bpm_7-8_Nov.9.26.234am.m4a, so takes made with the same settings sort together. With the metronome off, it is just the date and time. You can change the name in the save dialog.
- Your segments live in memory, so save anything you want to keep before closing the app.


THE METRONOME

Switch it on with the METRONOME button. It plays only while you are recording, and starts and stops with the recording. It is never recorded into your segments or saved files. Tap METRONOME SETTINGS to change it. The PREVIEW button at the top of the settings lets you hear it while you adjust, and DONE stops it.

- Tempo: 40 to 240 BPM, with - and + buttons or a slider.
- Time signature: 2 to 16 beats per bar, counted in quarter notes (/4) or eighth notes (/8). The 4 or 8 is a label and doesn't change the sound. Every click is one beat, and the BPM is how many clicks play per minute.
- Grouping: for longer bars you can choose how the beats are grouped, for example 2+2+3 for a bar of 7. Beat 1 is the strongest click, the first beat of each other group is a full click, and the beats in between are softer. Choose None for even clicks.
- Sound: Beep, Wood or Thump. Beat 1 uses a lower, heavier sound than the other beats.
- Volume.

Your metronome settings are remembered between launches.

PERMISSIONS

The app asks for microphone access, which it needs to record. Audio is processed on your phone, and the app does not use the internet.


CREDITS

PhraseLooper was designed and directed by ocybin~solo.
You can support me on Venmo, user @boxel 

The code was written with assistance from two AIs:
- Gemini, by Google
- Claude, by Anthropic

Built with these tools and libraries:
- Kotlin and Kotlin Coroutines (JetBrains)
- Jetpack Compose: UI, Foundation, Runtime, Material 3 and Activity Compose (Google)
- AndroidX Core (Google)
- Android's built-in audio and media APIs: AudioRecord, AudioTrack, MediaCodec, MediaExtractor and MediaMuxer
- Android Studio (Google)

The click sounds are generated in code, so no sound files from other sources are used.


LICENSE

PhraseLooper is free software, released under the GNU General Public License, version 3. Tap LICENSE.TXT at the top of this page for the full text.
