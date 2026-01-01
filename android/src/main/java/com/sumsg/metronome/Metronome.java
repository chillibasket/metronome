package com.sumsg.metronome;

import static android.media.AudioTrack.PLAYSTATE_PLAYING;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioTimestamp;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import android.media.AudioAttributes;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import io.flutter.plugin.common.EventChannel;

public class Metronome {
    private final Object mLock = new Object();
    private final AudioTrack audioTrack;
    private short[] mainSound;
    private short[] accentedSound;
    private short[] audioBuffer;
    private final int SAMPLE_RATE;
    private final int PRERUN_FRAMES;
    public int audioBpm;
    public int audioTimeSignature;
    public float audioVolume;
    private boolean updated = false;
    private boolean correctionRequired = false;
    private EventChannel.EventSink eventTickSink;
    private int currentTick = 0;
    private int startBarFrames = 0;
    private int nextBarFrames = 0;
    
    // Synchronization primitives
    private final int MAX_DRIFT_CORRECTION;
    private long timePerBarUs = 0;
    private long startTimeUs = 0; 
    private volatile long correctionUs = 0;

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

        PRERUN_FRAMES = audioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        );
        setVolume(volume);
    }

    public void play() {
        play(0, 0);
    }

    public void play(long startTimeUs, long correctionUs) {
        if (!isPlaying()) {
            this.startTimeUs = startTimeUs;
            this.correctionUs = correctionUs;
            this.startBarFrames = 1;
            this.nextBarFrames = 1;
            updated = true;
            onTick();

            // Send immediate tick event to match iOS behavior
            if (eventTickSink != null) {
                eventTickSink.success(0);  // Send tick 0 immediately
            }

            startMetronome();
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
    }

    public void setBPM(int bpm) {
        if (bpm != audioBpm) {
            audioBpm = bpm;
            if (isPlaying()) {
                pause();
                play();
            }
        }
    }

    public void setTimeSignature(int timeSignature) {
        if (timeSignature != audioTimeSignature) {
            audioTimeSignature = timeSignature;
            if (isPlaying()) {
                pause();
                play();
            }
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
            if (isPlaying()) {
                pause();
                play();
            }
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

    private short[] byteArrayToShortArray(byte[] byteArray) {
        if (byteArray == null || byteArray.length % 2 != 0) {
            throw new IllegalArgumentException("Invalid byte array length for PCM_16BIT");
        }
        short[] shortArray = new short[byteArray.length / 2];
        ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortArray);
        return shortArray;
    }

    private short[] generateBuffer() {
        currentTick = 0;
        int framesPerBeat = (int) (SAMPLE_RATE * 60 / audioBpm);
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

        updated = false;
        return bufferBar;
    }

    void onTick() {
        if (eventTickSink == null)
            return;
        int framesPerBeat = (int) (SAMPLE_RATE * 60 / audioBpm);

        audioTrack.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override
            public void onMarkerReached(AudioTrack track) {
                track.setPositionNotificationPeriod(framesPerBeat);
                currentTick = 0;
                eventTickSink.success(currentTick);
            }

            @Override
            public void onPeriodicNotification(AudioTrack track) {
                if (!updated) {
                    if (audioTimeSignature < 2) {
                        track.setPositionNotificationPeriod(0);
                    } else {
                        currentTick++;
                        if (currentTick >= audioTimeSignature - 1) {
                            track.setPositionNotificationPeriod(0);
                        }
                    }
                    eventTickSink.success(currentTick);
                }
            }
        });
    }

    private void startMetronome() {
        new Thread(() -> {

            int trackLengthFrames = 0;
            int delayFrames = 0;
            correctionRequired = false;
            AudioTimestamp timestamp = new AudioTimestamp();

            // Prime the audio track with silence and then start playback
            short[] silenceBuffer = new short[2 * PRERUN_FRAMES];
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

                    if (updated) {
                        audioBuffer = generateBuffer();
                        trackLengthFrames = audioBuffer.length - MAX_DRIFT_CORRECTION;

                        if (startTimeUs != 0) {
                            // Play more silence to allow timing readings to settle
                            // Otherwise we can't get a good reading of the start time
                            audioTrack.write(silenceBuffer, 0, silenceBuffer.length);
                            nextBarFrames += silenceBuffer.length;
                            startBarFrames = nextBarFrames;
                            correctionRequired = true;   
                        } else {
                            audioTrack.setNotificationMarkerPosition(nextBarFrames);
                        }

                    } else if (correctionRequired) {

                        boolean timestampSuccess = audioTrack.getTimestamp(timestamp);

                        if (timestampSuccess) {
                            correctionRequired = false;
                            long monotonicTimeNs = System.nanoTime();
                            long bootTimeNs = SystemClock.elapsedRealtimeNanos();
                            long currentFrames = (int)timestamp.framePosition;
                            long currentTimeUs = (timestamp.nanoTime + (bootTimeNs - monotonicTimeNs)) / 1000L;

                            // Wait for the scheduled start time - if time was missed, wait for next bar
                            int waitFrames = (int)((startTimeUs + correctionUs - currentTimeUs) * SAMPLE_RATE / 1000000L) - (int)(nextBarFrames - currentFrames);
                            startBarFrames = nextBarFrames + waitFrames - (int)(correctionUs * SAMPLE_RATE / 1000000L);

                            while (waitFrames > trackLengthFrames) waitFrames -= trackLengthFrames;
                            while (waitFrames < 0) waitFrames += trackLengthFrames;

                            short[] delayBuffer = new short[waitFrames];
                            nextBarFrames += delayBuffer.length;

                            audioTrack.write(delayBuffer, 0, delayBuffer.length);
                            audioTrack.setNotificationMarkerPosition(nextBarFrames);

                            Log.d("Metronome", "Start time:" + startTimeUs 
                                + ", Correction:" + correctionUs
                                + ", Timestamp Success: " + timestampSuccess
                                + ", CurrentFrames: " + currentFrames 
                                + ", Current time:" + currentTimeUs 
                                + ", Prerun Frames: " + PRERUN_FRAMES
                                + ", Wait Frames:" + waitFrames
                                + ", Time per bar:" + timePerBarUs
                                + ", NextBarFrames: " + nextBarFrames 
                                + ", StartBarFrames: " + startBarFrames 
                                + ", TrackLength Frames: " + trackLengthFrames
                                + ", delayBuffer.length: " + delayBuffer.length);
                        }

                    } else if (!correctionRequired) {
                        long runFrames = (nextBarFrames - startBarFrames) - (correctionUs * SAMPLE_RATE / 1000000L);
                        long targetBars = Math.round(runFrames / (float)(trackLengthFrames));
                        long errorCorrectionFrames = (targetBars * trackLengthFrames) - runFrames;

                        boolean timestampSuccess = audioTrack.getTimestamp(timestamp);
                        if (timestampSuccess) {
                            long monotonicTimeNs = System.nanoTime();
                            long bootTimeNs = SystemClock.elapsedRealtimeNanos();
                            long currentFrames = timestamp.framePosition;
                            long timeNowUs = (timestamp.nanoTime + (bootTimeNs - monotonicTimeNs)) / 1000L;
                            long expectedFrames = (timeNowUs - startTimeUs - correctionUs) * SAMPLE_RATE / 1000000L;
                            long errorFrames = currentFrames - startBarFrames - expectedFrames;
                            
                            //if (Math.abs(erorFrames) > 100) {
                            //    errorCorrectionFrames -= errorFrames;
                            //    startBarFrames -= errorFrames;
                            //}

                            Log.d("Metronome", "Start time: " + startTimeUs 
                                + ", Correction: " + correctionUs 
                                + ", Timestamp Success: " + timestampSuccess
                                + ", Current time: " + timeNowUs
                                + ", Current frames: " + (currentFrames - startBarFrames)
                                + ", Expected frames: " + expectedFrames
                                + ", Error Frames: " + errorFrames
                                + ", Correction Frames: " + errorCorrectionFrames
                                + ", Bar: " + targetBars);
                        }

                        if (errorCorrectionFrames != 0) {
                            delayFrames = (int)(errorCorrectionFrames);
                            if (delayFrames > MAX_DRIFT_CORRECTION) {
                                delayFrames = MAX_DRIFT_CORRECTION;
                            } else if (delayFrames < -MAX_DRIFT_CORRECTION) {
                                delayFrames = -MAX_DRIFT_CORRECTION;
                            }
                            //Log.d("Metronome", "delayFrames: " + delayFrames + ", nextBarFrames: " + nextBarFrames + ", startBarFrames: " + startBarFrames + ", runFrames: " + runFrames + ", targetBars: " + targetBars + ", errorCorrectionFrames: " + errorCorrectionFrames);
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
