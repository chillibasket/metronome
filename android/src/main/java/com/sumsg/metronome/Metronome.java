package com.sumsg.metronome;

import static android.media.AudioTrack.PLAYSTATE_PLAYING;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioTimestamp;
import android.os.Build;
import android.os.SystemClock;

import android.media.AudioAttributes;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import io.flutter.plugin.common.EventChannel;

public class Metronome {
    /// Upper bound on a caller-supplied count-in. countInBeats * framesPerBeat is int
    /// arithmetic driving an allocation, so an unbounded value overflows negative and
    /// kills the writer thread inside System.arraycopy.
    private static final int MAX_COUNT_IN_BEATS = 16;

    /// Consecutive getTimestamp() failures tolerated before the scheduled start is
    /// abandoned in favour of an immediate one.
    private static final int MAX_TIMESTAMP_FAILURES = 50;

    /// Cap on how long a caller may be blocked waiting for the writer to exit. The
    /// writer normally leaves within a buffer of being unblocked; this is a safety net.
    private static final int WRITER_JOIN_TIMEOUT_MS = 500;

    private final Object mLock = new Object();
    private final AudioTrack audioTrack;
    private volatile short[] mainSound;
    private volatile short[] accentedSound;
    /// Tone for count-in clicks, or null to follow mainSound. Every click in a count-in
    /// uses this one tone - the count-in is not accented, whatever its length.
    private volatile short[] countInSoundOverride;
    private short[] audioBuffer;
    private final int SAMPLE_RATE;
    /// getMinBufferSize() returns BYTES; this is used as a short[] length, so the
    /// priming silence is about four times the minimum buffer measured in frames.
    private final int PRERUN_BYTES;
    public volatile int audioBpm;
    public volatile int audioTimeSignature;
    public float audioVolume;

    /// Rebuild the bar buffer at the next bar boundary and keep the current phase.
    private volatile boolean pendingRegenerate = false;
    /// Re-phase the grid to startTimeUs. Only a fresh play() may ask for this.
    private volatile boolean pendingResync = false;
    /// Time signature to adopt at the next bar boundary; -1 = none queued. 0 is a
    /// legal value (fewer than 2 beats disables accents), so it cannot be the sentinel.
    private volatile int pendingTimeSignature = -1;
    /// Beat count of the bar the writer has just composed; latched by onMarkerReached.
    private volatile int pendingBarBeats = 0;
    /// Beat count of the bar currently sounding. Owned by the notification thread.
    private volatile int barBeats = 0;

    private volatile boolean correctionRequired = false;
    /// Cleared to null when the Dart stream is cancelled, so the notification callbacks
    /// must null-check a local copy rather than dereference the field.
    private volatile EventChannel.EventSink eventTickSink;
    private volatile EventChannel.EventSink eventBarSink;
    private volatile int currentTick = 0;
    private int startBarFrames = 0;
    private int nextBarFrames = 0;

    /// The writer thread, while one may still be alive. Only ever one at a time:
    /// pause() clears PLAYSTATE_PLAYING while the writer is still blocked inside
    /// write(), so without this a following play() would start a second writer against
    /// the same AudioTrack.
    private volatile Thread writerThread = null;
    /// Cleared to ask the writer to leave its loop.
    private volatile boolean writerRunning = false;
    /// Set once the AudioTrack is released; every entry point becomes a no-op after.
    private volatile boolean released = false;

    // Synchronization primitives
    private final int MAX_DRIFT_CORRECTION;
    private volatile int framesPerBeat = 0;
    private volatile long startTimeUs = 0;
    private volatile long correctionUs = 0;

    /// Count-in clicks still to be written ahead of the scheduled downbeat.
    /// Latched at play(), consumed by the delay-buffer write, then zeroed.
    private volatile int countInBeats = 0;
    /// Tick the next marker notification should publish: -countInBeats for a
    /// count-in start, 0 for an ordinary bar boundary.
    private volatile int pendingMarkerTick = 0;

    public Metronome(byte[] mainFileBytes, byte[] accentedFileBytes, int bpm, int timeSignature, float volume,
            int sampleRate) {
        this(mainFileBytes, accentedFileBytes, new byte[0], bpm, timeSignature, volume, sampleRate);
    }

    @SuppressWarnings("deprecation")
    public Metronome(byte[] mainFileBytes, byte[] accentedFileBytes, byte[] countInFileBytes, int bpm,
            int timeSignature, float volume, int sampleRate) {
        SAMPLE_RATE = sampleRate;
        MAX_DRIFT_CORRECTION = sampleRate / 20;
        audioBpm = bpm;
        audioVolume = volume;
        audioTimeSignature = timeSignature;
        mainSound = byteArrayToShortArray(mainFileBytes);
        if (accentedFileBytes.length == 0) {
            accentedSound = mainSound;
        } else {
            accentedSound = byteArrayToShortArray(accentedFileBytes);
        }
        // Left null when unsupplied so countInSound() falls back to whatever mainSound
        // currently is, including after a later setAudioFile().
        countInSoundOverride = (countInFileBytes.length == 0)
                ? null
                : byteArrayToShortArray(countInFileBytes);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioFormat audioFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    // .setBufferSizeInBytes(SAMPLE_RATE)
                    // .setBufferSizeInBytes(SAMPLE_RATE * 2)
                    .build();
        } else {
            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, SAMPLE_RATE, AudioTrack.MODE_STREAM);
        }

        PRERUN_BYTES = audioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        );
        setVolume(volume);
    }

    public void play() {
        play(0, 0, 0);
    }

    public void play(long startTimeUs, long correctionUs) {
        play(startTimeUs, correctionUs, 0);
    }

    /// Start the metronome, optionally at a scheduled instant and with a count-in.
    ///
    /// @param startTimeUs Instant the FIRST BEAT OF BAR 1 sounds, on the boot-monotonic
    ///   clock, in microseconds (0 = immediate start).
    /// @param correctionUs Drift correction in microseconds (0 = none).
    /// @param countInBeats Clicks to sound BEFORE startTimeUs. Needs a scheduled start
    ///   to count back from; ignored while already playing.
    public void play(long startTimeUs, long correctionUs, int countInBeats) {
        if (released) {
            return;
        }
        if (!isPlaying()) {
            this.startTimeUs = startTimeUs;
            this.correctionUs = correctionUs;
            // The count-in is written into the pre-start delay, so it needs a scheduled
            // start to count back from. An immediate start has no delay buffer.
            this.countInBeats = (startTimeUs != 0 && countInBeats > 0)
                    ? Math.min(countInBeats, MAX_COUNT_IN_BEATS)
                    : 0;
            this.pendingMarkerTick = -this.countInBeats;
            this.currentTick = 0;
            this.startBarFrames = 1;
            this.nextBarFrames = 1;
            pendingRegenerate = true;
            pendingResync = true;
            onTick();

            // Send immediate tick event to match iOS behavior. Suppressed during a
            // count-in: beat 0 has not happened yet, and the marker publishes -n.
            if (eventTickSink != null && this.countInBeats == 0) {
                eventTickSink.success(0);  // Send tick 0 immediately
            }

            startMetronome();
        } else {
            // Already running: a re-schedule, not a start. Inserting count-in clicks
            // into a bar that is mid-flight would put them off the beat grid.
            this.startTimeUs = startTimeUs;
            this.correctionUs = correctionUs;
            pendingRegenerate = true;
        }
    }

    public void setCorrectionUs(long correctionUs) {
        this.correctionUs = correctionUs;
    }

    public void pause() {
        if (released) {
            return;
        }
        audioTrack.pause();
        retireWriter();
    }

    /// Stops the writer thread and waits for it to leave.
    ///
    /// A paused track stops draining, so a writer blocked in a blocking write() would
    /// never return on its own; flush() is what releases it. mLock is deliberately not
    /// taken here - the writer holds it for the whole loop body, write() included, so
    /// acquiring it from the platform thread would deadlock.
    private void retireWriter() {
        writerRunning = false;
        Thread previous = writerThread;
        writerThread = null;
        if (previous == null || previous == Thread.currentThread()) {
            return;
        }
        if (previous.isAlive()) {
            audioTrack.flush();
        }
        try {
            previous.join(WRITER_JOIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        if (released) {
            return;
        }
        audioTrack.flush();
        audioTrack.stop();
        retireWriter();
        // A cancelled count-in, or a meter queued and never consumed, must not leak
        // into the next play().
        countInBeats = 0;
        pendingMarkerTick = 0;
        pendingTimeSignature = -1;
        correctionRequired = false;
        pendingRegenerate = false;
        pendingResync = false;
        currentTick = 0;
        barBeats = 0;
    }

    public void setBPM(int bpm) {
        if (bpm != audioBpm) {
            audioBpm = bpm;
            pendingRegenerate = true;
        }
    }

    public int getTimeSignature() {
        int pending = pendingTimeSignature;
        return pending >= 0 ? pending : audioTimeSignature;
    }

    public void setTimeSignature(int timeSignature) {
        if (timeSignature < 0 || timeSignature == getTimeSignature()) {
            return;
        }
        if (isPlaying()) {
            // audioTimeSignature is read live by the notification callbacks, so let the
            // writer thread own the assignment and land it on a bar boundary.
            pendingTimeSignature = timeSignature;
        } else {
            audioTimeSignature = timeSignature;
        }
        pendingRegenerate = true;
    }

    /// Queue a time signature to take effect at the next bar boundary, leaving the
    /// phase untouched. Intended to be driven once per bar from a song meter map.
    ///
    /// The writer runs one AudioTrack buffer ahead of the speaker, so a caller
    /// reacting to tick 0 of a bar has that bar minus one buffer to be heard in the
    /// next bar. Calling later defers the change by a bar, which the bar-length event
    /// makes visible.
    public void setNextBarTimeSignature(int timeSignature) {
        if (timeSignature >= 0) {
            pendingTimeSignature = timeSignature;
            pendingRegenerate = true;
        }
    }

    public void setAudioFile(byte[] mainFileBytes, byte[] accentedFileBytes) {
        setAudioFile(mainFileBytes, accentedFileBytes, new byte[0]);
    }

    public void setAudioFile(byte[] mainFileBytes, byte[] accentedFileBytes, byte[] countInFileBytes) {
        if (mainFileBytes.length > 0) {
            mainSound = byteArrayToShortArray(mainFileBytes);
        }
        if (accentedFileBytes.length > 0) {
            accentedSound = byteArrayToShortArray(accentedFileBytes);
        }
        if (countInFileBytes.length > 0) {
            countInSoundOverride = byteArrayToShortArray(countInFileBytes);
        }
        // The count-in tone is not part of the bar buffer, so changing only that needs
        // no regenerate.
        if (mainFileBytes.length > 0 || accentedFileBytes.length > 0) {
            pendingRegenerate = true;
        }
    }

    @SuppressWarnings("deprecation")
    public void setVolume(float volume) {
        audioVolume = volume;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            audioTrack.setVolume(volume);
        } else {
            audioTrack.setStereoVolume(volume, volume);
        }
    }

    public boolean isPlaying() {
        return audioTrack.getPlayState() == PLAYSTATE_PLAYING;
    }

    public void enableTickCallback(EventChannel.EventSink _eventTickSink) {
        eventTickSink = _eventTickSink;
    }

    public void enableBarCallback(EventChannel.EventSink _eventBarSink) {
        eventBarSink = _eventBarSink;
    }

    private short[] byteArrayToShortArray(byte[] byteArray) {
        if (byteArray == null || byteArray.length % 2 != 0) {
            throw new IllegalArgumentException("Invalid byte array length for PCM_16BIT");
        }
        short[] shortArray = new short[byteArray.length / 2];
        ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortArray);
        return shortArray;
    }

    /// Beats in a bar. Fewer than 2 means a one-beat bar with no accent.
    private int beatsPerBar() {
        return Math.max(1, audioTimeSignature);
    }

    /// Exact frame offset of bar `bars` from the phase reference, rounded once from the
    /// true rational bar length instead of accumulating a per-bar rounding error.
    ///
    /// This is the drift correction target. Using the whole-frame bar length instead
    /// would lock the click to SAMPLE_RATE * 60 * beats / barFrames rather than to the
    /// requested BPM - a small error, but a systematic one that differs between sample
    /// rates, so two synced devices at 44.1 and 48 kHz would separate steadily.
    private long idealBarOffsetFrames(long bars) {
        long beats = beatsPerBar();
        return (bars * 60L * beats * SAMPLE_RATE + (audioBpm / 2)) / audioBpm;
    }

    private short[] generateBuffer() {
        final int beats = beatsPerBar();
        // Round the bar as a whole rather than truncating every beat: truncation lost up
        // to a frame per beat, which is what made the real tempo sample-rate dependent.
        // The clamp only bites on nonsense settings, where it keeps the allocation below
        // from overflowing into a negative array size.
        final int barFrames = (int) Math.min(idealBarOffsetFrames(1), 60L * SAMPLE_RATE);
        framesPerBeat = (barFrames + (beats / 2)) / beats;

        short[] bufferBar = new short[barFrames + MAX_DRIFT_CORRECTION];
        for (int i = 0; i < beats; i++) {
            // Spread the rounding remainder over the bar so no click sits more than a
            // frame from its ideal position.
            int offset = (int) (((long) i * barFrames + (beats / 2)) / beats);
            int nextOffset = (int) (((long) (i + 1) * barFrames + (beats / 2)) / beats);
            short[] sound = (i == 0 && audioTimeSignature >= 2) ? accentedSound : mainSound;
            int soundLength = Math.min(nextOffset - offset, sound.length);
            System.arraycopy(sound, 0, bufferBar, offset, soundLength);
        }

        return bufferBar;
    }

    /// Writes count-in clicks into the tail of the pre-start delay buffer.
    ///
    /// Aligned to the end, so the last click falls exactly one beat before the
    /// scheduled downbeat. Every click uses the same tone regardless of where it sits
    /// relative to the bar, which leaves the first accent for the downbeat itself and
    /// makes the count-in read as a lead-in rather than as a bar of music.
    private void writeCountIn(short[] delayBuffer, int beats) {
        final int offset = delayBuffer.length - (beats * framesPerBeat);
        final short[] sound = countInSound();
        final int len = Math.min(framesPerBeat, sound.length);
        for (int i = 0; i < beats; i++) {
            System.arraycopy(sound, 0, delayBuffer, offset + (i * framesPerBeat), len);
        }
    }

    /// Tone every count-in click uses: the one supplied through countInPath, or the
    /// main sound when none was. Resolved on use rather than cached so that changing
    /// the main sound also changes an unconfigured count-in.
    private short[] countInSound() {
        short[] configured = countInSoundOverride;
        return (configured != null) ? configured : mainSound;
    }

    /// Re-anchors the drift reference after the bar length changes, carrying over any
    /// correction still being slewed in under the old bar length.
    ///
    /// The steady-state maths measures whole bars from startBarFrames, so leaving it
    /// stale after a tempo or meter change leaves a residual of up to half a bar to be
    /// paid off at MAX_DRIFT_CORRECTION per bar. That is audible on one device, and the
    /// residual differs per device, which breaks multi-device sync.
    private void rebasePhase(int prevLength) {
        if (prevLength <= 0) {
            return;
        }
        long corr = correctionUs * SAMPLE_RATE / 1000000L;
        long run = (nextBarFrames - startBarFrames) - corr;
        long residual = Math.round(run / (float) prevLength) * (long) prevLength - run;
        startBarFrames = nextBarFrames - (int) corr + (int) residual;
    }

    void onTick() {
        // Installed unconditionally: a sink can be attached or cancelled at any time,
        // so the callbacks below read a local copy and null-check it instead.
        audioTrack.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override
            public void onMarkerReached(AudioTrack track) {
                // The writer set pendingBarBeats for this bar one buffer ago.
                barBeats = pendingBarBeats;
                EventChannel.EventSink barSink = eventBarSink;
                if (barSink != null) {
                    barSink.success(barBeats);
                }
                track.setPositionNotificationPeriod(framesPerBeat);
                currentTick = pendingMarkerTick;   // -countInBeats, or 0
                pendingMarkerTick = 0;             // only the first marker carries it
                EventChannel.EventSink tickSink = eventTickSink;
                if (tickSink != null) {
                    tickSink.success(currentTick);
                }
            }

            @Override
            public void onPeriodicNotification(AudioTrack track) {
                if (pendingResync) {
                    return;
                }

                final int beats = barBeats;

                if (currentTick < 0) {
                    currentTick++;                 // walking out of the count-in
                } else if (beats >= 2) {
                    currentTick++;
                }

                if (beats < 2) {
                    // One-beat bar: stop once the count-in has resolved to the downbeat.
                    if (currentTick >= 0) {
                        track.setPositionNotificationPeriod(0);
                    }
                } else if (currentTick >= beats - 1) {
                    track.setPositionNotificationPeriod(0);
                }

                EventChannel.EventSink tickSink = eventTickSink;
                if (tickSink != null) {
                    tickSink.success(currentTick);
                }
            }
        });
    }

    private void startMetronome() {
        // Never run two writers against one AudioTrack. pause() and stop() already
        // retire theirs; this covers any path that did not.
        retireWriter();
        writerRunning = true;

        Thread writer = new Thread(() -> {

            int trackLengthFrames = 0;
            int delayFrames = 0;
            boolean started = false;
            int timestampFailures = 0;
            // Tempo basis the current phase reference was established under. The exact
            // bar grid counts bars from that reference, so it is only valid while both
            // of these hold.
            int activeBpm = 0;
            int activeBeats = 0;
            correctionRequired = false;
            AudioTimestamp timestamp = new AudioTimestamp();

            // Prime the audio track with silence and then start playback. The length is
            // 2 * PRERUN_BYTES shorts, i.e. roughly four times the minimum buffer in
            // frames (~160-320 ms at 44.1 kHz): deliberate settling time so that
            // getTimestamp() returns a usable reading before the phase is computed.
            short[] silenceBuffer = new short[2 * PRERUN_BYTES];
            nextBarFrames = silenceBuffer.length + 1;
            startBarFrames = nextBarFrames;
            audioTrack.flush();
            audioTrack.write(silenceBuffer, 0, silenceBuffer.length);
            audioTrack.play();

            while (writerRunning && isPlaying()) {
                synchronized (mLock) {
                    if (!writerRunning || !isPlaying()) {
                        return;
                    }

                    final int prevLength = trackLengthFrames;
                    boolean regenerated = false;
                    boolean tempoChanged = false;

                    // pendingTimeSignature is part of the entry condition, not just a
                    // payload: otherwise an unrelated setBPM can consume pendingRegenerate
                    // first and strand a queued meter with no flag left to trigger it.
                    if (pendingRegenerate || pendingResync || pendingTimeSignature >= 0) {
                        if (pendingTimeSignature >= 0) {
                            audioTimeSignature = pendingTimeSignature;
                            pendingTimeSignature = -1;
                        }
                        audioBuffer = generateBuffer();
                        trackLengthFrames = audioBuffer.length - MAX_DRIFT_CORRECTION;
                        pendingBarBeats = beatsPerBar();
                        pendingRegenerate = false;
                        regenerated = true;
                        tempoChanged = (audioBpm != activeBpm) || (pendingBarBeats != activeBeats);
                        activeBpm = audioBpm;
                        activeBeats = pendingBarBeats;
                    }

                    if (pendingResync) {
                        pendingResync = false;

                        if (startTimeUs != 0) {
                            if (!started) {
                                // Play more silence to allow timing readings to settle
                                // Otherwise we cannot get a good reading of the start time
                                audioTrack.write(silenceBuffer, 0, silenceBuffer.length);
                                nextBarFrames += silenceBuffer.length;
                                // Exempt from the rebasePhase() rule: the correction
                                // branch overwrites startBarFrames on the next pass.
                                startBarFrames = nextBarFrames;
                            }
                            correctionRequired = true;
                        } else {
                            audioTrack.setNotificationMarkerPosition(nextBarFrames);
                        }

                        started = true;
                        continue;
                    }

                    // Only rebase when the tempo basis changed. Running this on every
                    // pass would force runFrames to 0 each bar, zeroing the drift
                    // correction and making setCorrectionUs a silent no-op. The test is
                    // on BPM and beats rather than on the bar length, because that is
                    // what idealBarOffsetFrames() counts from - a setAudioFile-only
                    // regenerate must leave the grid alone.
                    if (regenerated && !correctionRequired && tempoChanged) {
                        rebasePhase(prevLength);
                    }

                    if (correctionRequired) {

                        boolean timestampSuccess = audioTrack.getTimestamp(timestamp);

                        if (!timestampSuccess) {
                            // Nothing is written on this pass, so without a pause the loop
                            // spins at 100% CPU and starves the track, which is itself a
                            // cause of getTimestamp() failing.
                            if (++timestampFailures > MAX_TIMESTAMP_FAILURES) {
                                timestampFailures = 0;
                                correctionRequired = false;
                                startTimeUs = 0;
                                audioTrack.setNotificationMarkerPosition(nextBarFrames);
                            } else {
                                try {
                                    Thread.sleep(2);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                            continue;
                        }

                        timestampFailures = 0;
                        correctionRequired = false;
                        long monotonicTimeNs = System.nanoTime();
                        long bootTimeNs = SystemClock.elapsedRealtimeNanos();
                        long currentFrames = timestamp.framePosition;
                        long currentTimeUs = (timestamp.nanoTime + (bootTimeNs - monotonicTimeNs)) / 1000L;

                        // Wait for the scheduled start time - if time was missed, wait for next bar
                        int waitFrames = (int)((startTimeUs + correctionUs - currentTimeUs) * SAMPLE_RATE / 1000000L) - (int)(nextBarFrames - currentFrames);
                        startBarFrames = nextBarFrames + waitFrames - (int)(correctionUs * SAMPLE_RATE / 1000000L);

                        final int countIn = countInBeats;
                        final int countInFrames = countIn * framesPerBeat;

                        // The count-in has to fit in front of the scheduled downbeat. If
                        // the caller left too little lead, slip whole bars rather than
                        // clipping it: that keeps the downbeat on the beat grid. The
                        // postcondition waitFrames >= countInFrames is also what puts the
                        // count-in marker ahead of the playhead, so this must stay a while
                        // loop - one iteration is not enough when countIn exceeds a bar.
                        while (waitFrames < countInFrames) waitFrames += trackLengthFrames;

                        short[] delayBuffer = new short[waitFrames];

                        if (countIn > 0) {
                            writeCountIn(delayBuffer, countIn);
                            // Armed BEFORE the write: this position is inside the block
                            // about to be queued, and write() blocks until there is room,
                            // so arming it afterwards can miss it entirely - the count-in
                            // would sound with no ticks at all.
                            audioTrack.setNotificationMarkerPosition(
                                nextBarFrames + waitFrames - countInFrames);
                        }

                        nextBarFrames += delayBuffer.length;
                        audioTrack.write(delayBuffer, 0, delayBuffer.length);

                        if (countIn == 0) {
                            audioTrack.setNotificationMarkerPosition(nextBarFrames);
                        }

                        // Consumed. A later pass through this branch - a setting changed
                        // mid-play - must not count in again.
                        countInBeats = 0;

                    } else {
                        long runFrames = (nextBarFrames - startBarFrames) - (correctionUs * SAMPLE_RATE / 1000000L);
                        // Which bar boundary we are at, measured against the true bar
                        // duration rather than its whole-frame approximation.
                        long targetBars = Math.round(
                            (double) runFrames * audioBpm / (60.0 * beatsPerBar() * SAMPLE_RATE));
                        // Snap to the exact grid, so the sub-frame remainder is paid off
                        // instead of accumulating into a sample-rate dependent tempo error.
                        long errorCorrectionFrames = idealBarOffsetFrames(targetBars) - runFrames;

                        if (errorCorrectionFrames != 0) {
                            delayFrames = (int)(errorCorrectionFrames);
                            if (delayFrames > MAX_DRIFT_CORRECTION) {
                                delayFrames = MAX_DRIFT_CORRECTION;
                            } else if (delayFrames < -MAX_DRIFT_CORRECTION) {
                                delayFrames = -MAX_DRIFT_CORRECTION;
                            }
                        } else {
                            delayFrames = 0;
                        }

                        // Play the audio buffer
                        audioTrack.write(audioBuffer, 0, trackLengthFrames + delayFrames);
                        nextBarFrames += trackLengthFrames + delayFrames;
                        audioTrack.setNotificationMarkerPosition(nextBarFrames);
                    }
                }
            }
        });
        writerThread = writer;
        writer.start();
    }

    public void destroy() {
        if (released) {
            return;
        }
        // stop() retires the writer, so nothing can touch the track after release().
        stop();
        released = true;
        audioTrack.release();
    }
}
