package com.sumsg.metronome;

import static android.media.AudioTrack.PLAYSTATE_PLAYING;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
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
    public int audioBpm;
    public int audioTimeSignature;
    public float audioVolume;
    private boolean updated = false;
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

            audioTrack.flush();
            audioTrack.play();
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

            while (isPlaying()) {
                synchronized (mLock) {
                    if (!isPlaying()) {
                        return;
                    }

                    if (updated) {
                        audioBuffer = generateBuffer();
                        trackLengthFrames = audioBuffer.length - MAX_DRIFT_CORRECTION;

                        // Wait for the scheduled start time - if time was missed, wait for next bar
                        if (startTimeUs != 0) {
                            long waitTimeUs = (startTimeUs + correctionUs) - (System.nanoTime() / 1000L);

                            while (waitTimeUs < -1000L) waitTimeUs += timePerBarUs;
                            while (waitTimeUs > timePerBarUs) waitTimeUs -= timePerBarUs;

                            //Log.d("Metronome", "Start time:" + startTimeUs + ", Correction:" + correctionUs + ", Current time:" + (System.nanoTime() / 1000L) + ", Wait time:" + waitTimeUs + ", Time per bar:" + timePerBarUs);

                            if (waitTimeUs > 1000L) {
                                short[] delayBuffer = new short[(int) (waitTimeUs * SAMPLE_RATE / 1000000L)];
                                audioTrack.setPositionNotificationPeriod(0);
                                audioTrack.write(delayBuffer, 0, delayBuffer.length);
                                nextBarFrames += delayBuffer.length;
                                startBarFrames = nextBarFrames - (int)(correctionUs * SAMPLE_RATE / 1000000L);
                            }
                        }

                        audioTrack.setNotificationMarkerPosition(nextBarFrames);

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
