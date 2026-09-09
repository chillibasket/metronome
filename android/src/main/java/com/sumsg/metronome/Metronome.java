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

    private final Object mLock = new Object();
    private final AudioTrack audioTrack;
    private volatile short[] mainSound;
    private volatile short[] accentedSound;
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
    private EventChannel.EventSink eventTickSink;
    private EventChannel.EventSink eventBarSink;
    private volatile int currentTick = 0;
    private int startBarFrames = 0;
    private int nextBarFrames = 0;

    // Synchronization primitives
    private final int MAX_DRIFT_CORRECTION;
    private long timePerBarUs = 0;
    private volatile int framesPerBeat = 0;
    private volatile long startTimeUs = 0;
    private volatile long correctionUs = 0;

    /// Count-in clicks still to be written ahead of the scheduled downbeat.
    /// Latched at play(), consumed by the delay-buffer write, then zeroed.
    private volatile int countInBeats = 0;
    /// Tick the next marker notification should publish: -countInBeats for a
    /// count-in start, 0 for an ordinary bar boundary.
    private volatile int pendingMarkerTick = 0;

    @SuppressWarnings("deprecation")
    public Metronome(byte[] mainFileBytes, byte[] accentedFileBytes, int bpm, int timeSignature, float volume,
            int sampleRate) {
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
        audioTrack.pause();
    }

    public void stop() {
        audioTrack.flush();
        audioTrack.stop();
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
        if (mainFileBytes.length > 0) {
            mainSound = byteArrayToShortArray(mainFileBytes);
        }
        if (accentedFileBytes.length > 0) {
            accentedSound = byteArrayToShortArray(accentedFileBytes);
        }
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

    private short[] generateBuffer() {
        framesPerBeat = (int) (SAMPLE_RATE * 60 / audioBpm);
        timePerBarUs = 60000000L * audioTimeSignature / audioBpm;

        short[] bufferBar;
        if (audioTimeSignature < 2) {
            bufferBar = new short[framesPerBeat + MAX_DRIFT_CORRECTION];
            int soundLength = Math.min(framesPerBeat, mainSound.length);
            System.arraycopy(mainSound, 0, bufferBar, 0, soundLength);
        } else {
            int bufferSize = (framesPerBeat * audioTimeSignature) + MAX_DRIFT_CORRECTION;
            bufferBar = new short[bufferSize];
            for (int i = 0; i < audioTimeSignature; i++) {
                short[] sound = (i == 0) ? accentedSound : mainSound;
                int soundLength = Math.min(framesPerBeat, sound.length);
                System.arraycopy(sound, 0, bufferBar, i * framesPerBeat, soundLength);
            }
        }

        return bufferBar;
    }

    /// Writes count-in clicks into the tail of the pre-start delay buffer.
    ///
    /// Aligned to the end, so the last click falls exactly one beat before the
    /// scheduled downbeat. Each click takes the sound of the bar position it stands
    /// in, counting back from the downbeat: two beats of 4/4 sound as beats 3 and 4,
    /// leaving the accent for the downbeat, while a whole bar of count-in accents its
    /// own first click.
    private void writeCountIn(short[] delayBuffer, int beats) {
        final int offset = delayBuffer.length - (beats * framesPerBeat);
        final int timeSignature = audioTimeSignature;
        for (int i = 0; i < beats; i++) {
            short[] sound = mainSound;
            if (timeSignature >= 2) {
                // Java % keeps the sign of the dividend, hence the ((x % n) + n) % n form.
                int barPos = ((timeSignature - beats + i) % timeSignature + timeSignature) % timeSignature;
                if (barPos == 0) {
                    sound = accentedSound;
                }
            }
            int len = Math.min(framesPerBeat, sound.length);
            System.arraycopy(sound, 0, delayBuffer, offset + (i * framesPerBeat), len);
        }
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
        if (eventTickSink == null)
            return;

        audioTrack.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override
            public void onMarkerReached(AudioTrack track) {
                // The writer set pendingBarBeats for this bar one buffer ago.
                barBeats = pendingBarBeats;
                if (eventBarSink != null) {
                    eventBarSink.success(barBeats);
                }
                track.setPositionNotificationPeriod(framesPerBeat);
                currentTick = pendingMarkerTick;   // -countInBeats, or 0
                pendingMarkerTick = 0;             // only the first marker carries it
                eventTickSink.success(currentTick);
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

                eventTickSink.success(currentTick);
            }
        });
    }

    private void startMetronome() {
        new Thread(() -> {

            int trackLengthFrames = 0;
            int delayFrames = 0;
            boolean started = false;
            int timestampFailures = 0;
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

            while (isPlaying()) {
                synchronized (mLock) {
                    if (!isPlaying()) {
                        return;
                    }

                    final int prevLength = trackLengthFrames;
                    boolean regenerated = false;

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
                        pendingBarBeats = (audioTimeSignature < 2) ? 1 : audioTimeSignature;
                        pendingRegenerate = false;
                        regenerated = true;
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

                    // Only rebase when the bar length actually changed. Running this on
                    // every pass would force runFrames to 0 each bar, zeroing the drift
                    // correction and making setCorrectionUs a silent no-op.
                    if (regenerated && !correctionRequired && trackLengthFrames != prevLength) {
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
                        long targetBars = Math.round(runFrames / (float)(trackLengthFrames));
                        long errorCorrectionFrames = (targetBars * trackLengthFrames) - runFrames;

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
        }).start();
    }

    public void destroy() {
        stop();
        audioTrack.release();
    }
}
