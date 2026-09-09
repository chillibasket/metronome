# Metronome

[![pub package](https://img.shields.io/pub/v/metronome.svg)](https://pub.dev/packages/metronome)

Efficient, accurate, cross-platform metronome; 
supports volume, BPM, time signature and audio source settings.

**Version 2.0 refactored most of the code, with better performance (BPM>600), less resource usage, and more accurate time signature callback.**

##

![Metronome](https://raw.githubusercontent.com/biner88/metronome/main/screenshot/demo2.png)

## Demo

[Live preview](https://biner88.github.io/metronome/)

## TODO

* [x] Add support for time signature [#2](https://github.com/biner88/metronome/issues/2)
* [x] Add windows support
* [x] Add tickCallback for web

## Quick Start 

### Init

```dart
final metronome = Metronome();
metronome.init(
    'assets/audio/snare.wav', 
    accentedPath: 'assets/audio/claves44_wav.wav',
    bpm: 120, 
    //0 ~ 100
    volume: 50,  
    enableTickCallback: true,
    // The time signature is the number of beats per measure,default is 4
    timeSignature: 4,
    sampleRate: 44100,
);
```

### Play

```dart
metronome.play();
```

#### Scheduled start, drift correction and count-in (Android only)

`startTimeUs` is the instant **beat 1 of bar 1 sounds**, on the reference clock returned by
`getTimeUs()`. Two devices given the same `startTimeUs` start together.

```dart
final now = await metronome.getTimeUs();
await metronome.play(
  startTimeUs: now + 3000000, // downbeat in 3 s
  correctionUs: 0,            // phase offset, 0 = none
  countInBeats: 4,            // 4 clicks BEFORE the downbeat
);
```

The count-in is scheduled *backwards* from `startTimeUs`, so extend your own lead by
`countInBeats * 60000000 ~/ bpm` microseconds. It needs a scheduled start, is ignored while
already playing, and is capped at 16. With `countInBeats: 0` (the default) behaviour is
unchanged.

While playing, `setCorrectionUs()` nudges the phase without restarting, and
`setNextBarTimeSignature()` changes the meter at the next bar boundary without re-phasing.

On iOS, macOS, Windows and web these arguments are accepted and ignored.

### Pause

```dart
metronome.pause();
```

### Stop

```dart
metronome.stop();
```

### Volume

```dart
metronome.getVolume();
metronome.setVolume(50);
```

### BPM

```dart
metronome.setBPM(120); 
metronome.getBPM(); 
```

### TimeSignature

Disable accents when less than 2

```dart
metronome.setTimeSignature(4); 
metronome.getTimeSignature(); 
```

For a mid-song meter change, `setNextBarTimeSignature()` takes effect at the next bar
boundary and leaves the phase alone, so the downbeat accent stays where it belongs
(Android only). Drive it once per bar from your meter map. `barStream` reports the beat
count of each bar as it starts, which is how you see a change that landed a bar late:

```dart
metronome.barStream.listen((int beats) => print("bar of $beats"));
metronome.setNextBarTimeSignature(2);
```

### isPlaying

Get play state

```dart
metronome.isPlaying();
```

### isInitialized

Getter that returns true if the metronome has completed initialization. After `init` is called.
Not platform dependant.

```dart
metronome.isInitialized;
```

### setAudioFile

main, accent can be set at the same time or individually

```dart
metronome.setAudioFile(
    mainPath:'assets/audio/snare.wav',
    accentedPath:'assets/audio/claves.wav'
);
metronome.setAudioFile(
    mainPath:'assets/audio/snare.wav',
);
```

### destroy

```dart
metronome.destroy();
```

### Tick callback

`enableTickCallback` must be set to `true` in init

```dart
metronome.tickStream.listen((int tick) {
  print("tick: $tick");
});
```

`tick` is the 0-based beat index within the bar. During a count-in requested through `play`
the values are **negative** and count up to zero — a 4-beat count-in emits `-4, -3, -2, -1`
and then `0` on the downbeat of bar 1. Negative values cannot appear unless you passed
`countInBeats > 0`.


