## 2.1.0

Android only. All additions default to the previous behaviour, so existing callers are
unaffected.

### Cross-device synchronisation (previously unreleased)

* `play(startTimeUs:, correctionUs:)` schedules the first downbeat on the boot-monotonic
  clock (`SystemClock.elapsedRealtimeNanos`), accurate to within about 5 ms.
* `setCorrectionUs()` applies a non-accumulative phase shift while playing, slewed in at up
  to 50 ms per bar.
* `getTimeUs()` returns the reference clock so callers can schedule against it.

### New in this release

* Count-in: `play(countInBeats: n)` sounds `n` clicks *before* `startTimeUs`, which keeps
  meaning "the instant beat 1 of bar 1 sounds". `tickStream` emits `-n … -1` during the
  count-in and `0` on the downbeat. Capped at 16; ignored without a scheduled start.
  Every count-in click uses one tone, so the first accent is the downbeat itself.
* `countInPath` on `init` and `setAudioFile` selects the count-in tone. Defaults to the
  main sound, and keeps following it if never set.
* `setNextBarTimeSignature()` changes the meter at the next bar boundary without re-phasing
  the click, for mid-song meter changes.
* New `barStream` reports the beat count of each bar as it starts.

### Fixed

* A mid-play `setBPM` / `setTimeSignature` / `setAudioFile` no longer re-phases the click.
  It previously inserted up to a full bar of silence and re-anchored the grid to
  `startTimeUs`, so moving a tempo slider during playback stumbled audibly.
* The drift reference is re-anchored when the bar length changes, instead of leaving a
  residual of up to half a bar to be worked off — which resolved differently on each device
  and broke multi-device sync after a tempo or meter change.
* A failing `AudioTrack.getTimestamp()` during a scheduled start no longer busy-spins at
  100% CPU (which starved the track and prolonged the failure); it retries, then falls back
  to an immediate start.
* The tick stream no longer stalls for up to a bar after a time-signature change, and the
  end-of-bar test now uses the meter of the bar that is actually sounding.
* The click ran at a slightly wrong tempo. `framesPerBeat` was an integer-truncated
  `SAMPLE_RATE * 60 / bpm`, so the real tempo was whatever that whole number of frames
  produced — at 130 BPM / 44.1 kHz, 130.022 BPM, drifting ~1.5 s per 20000 bars against
  the wall clock. Worse, the truncation differs by sample rate, so a 44.1 kHz device and
  a 48 kHz device ran at measurably different tempos and separated steadily (~125 ms
  over the same span) with nothing to pull them back. Bars are now rounded as a whole and
  the drift correction snaps to the exact rational grid, which holds both errors under
  0.03 ms indefinitely. Beat placement within a bar also improved from up to 3.7 frames
  off to under 0.7.
* `init`, `play`, `pause`, `stop`, `setVolume`, `setBPM`, `setTimeSignature`,
  `setAudioFile`, `setCorrectionUs` and `destroy` now reply on the method channel.
  Previously they returned without touching the result, so `await metronome.play()` and
  the others never completed.
* `getVolume()` returned 0 rather than the real volume: the native side replied with a
  0.0-1.0 float where Dart decodes an int 0-100. It now replies with an int percentage.
* `pause()` followed by `play()` could leave two writer threads running against one
  `AudioTrack`, both advancing the bar counter. `pause()` clears the playing state while
  the writer is still blocked inside `write()`, so the next `play()` saw an idle track and
  started a second writer. Writers are now retired deterministically.
* Cancelling `tickStream` left the engine publishing to a dead sink; the sinks are now
  cleared on cancel and null-checked in the audio callbacks.
* `destroy()` left a released `AudioTrack` reachable, so a later call could use it after
  free. Calling a method before `init` threw `NullPointerException` instead of reporting
  an error. Re-running `init` (as a hot restart does) and detaching the Flutter engine
  both leaked an `AudioTrack` and its writer thread.

## 2.0.13

* Add the `manageAudioSession` parameter to `Metronome.init`.
* On iOS, audio session management remains enabled by default for backward compatibility. Set `manageAudioSession` to `false` when the host application configures and activates the shared `AVAudioSession`.

## 2.0.12

* Fix the source_files path configuration in darwin/metronome.podspec.

## 2.0.11

* chore(platform): upgrade iOS deployment target to 13.0

## 2.0.10

* chore(platform): upgrade macOS deployment target to 10.15

## 2.0.9

* Fix crashes in handleRouteChange during audio engine reset. [#34](https://github.com/biner88/metronome/pull/36)

## 2.0.8

* Fix: Metronome audio handling. [#33](https://github.com/biner88/metronome/pull/33)

## 2.0.7

* Fix 1-beat offset in Android for tick callback [#28](https://github.com/biner88/metronome/pull/28)

## 2.0.6

* Fix(iOS): when third-party audio software plays sound, it does not interrupt the playback. The metronome also works well. [#25](https://github.com/biner88/metronome/pull/25)

## 2.0.5

* Added(iOS): a temporary `isInitialized` getter in Metronome class to check whether the metronome has been initialized or not [#24](https://github.com/biner88/metronome/pull/24)

## 2.0.4

* Fix(iOS): optimize audio routing change handling and application background mode [#15]https://github.com/biner88/metronome/issues/15)
* Added(iOS): Swift Package Manager support

## 2.0.3

* Fix(MacOS): problem compiling for macOS [#21](https://github.com/biner88/metronome/issues/21)

## 2.0.2

* Fix multi-package support [#20](https://github.com/biner88/metronome/issues/20)

## 2.0.1

* Fix pub points
* remove path_provider dependency

## 2.0.0

* Add time signature [#2](https://github.com/biner88/metronome/issues/2)
* Add `sampleRate` parameter
* Add [Live preview](https://biner88.github.io/metronome/)
* Add windows support
* Add CallBack function on Tick for web
* Refactoring for MacOS, IOS, Android, Web
* Deprecated `onListenTick`, use `tickStream` instead
* Remove BPM parameter from `play()` method
* Remove `enableSession` parameter, no need to explicitly set audio session
* Remove IOS Media Player Widget [#16](https://github.com/biner88/metronome/issues/16)
* Fix Bluetooth headset and default player switching error [#15](https://github.com/biner88/metronome/issues/15)
* Update example

## 1.1.5

* Update readme

## 1.1.4

* Added `enableTickCallback` parameter in init, onTick will be called only when onListenTick is true

## 1.1.3

* Update example

## 1.1.2

* Update example
* Update path_provider
* Update minimum iOS implementation version
* Add privacy manifest
* Fix Android: remove destroy from onDetachedFromEngine [#10](https://github.com/biner88/metronome/issues/10) , [#7](https://github.com/biner88/metronome/pull/7) 

## 1.1.1+1

* Fix BPM must be greater than 0 [#8](https://github.com/biner88/metronome/issues/8)

## 1.1.1

* Add enableSession parameter for IOS [#5](https://github.com/biner88/metronome/issues/5)

## 1.1.0+1

* Fix init volume being ignored [#6](https://github.com/biner88/metronome/issues/6)

## 1.1.0

* Add CallBack function on Tick [#1](https://github.com/biner88/metronome/issues/1)
* Add getBMP() function
* Add MacOS support
* Fix IOS getVolume()
* Fix Android setVolume()
* Fix no sound with AirPods [#3](https://github.com/biner88/metronome/issues/3)
* Change ⚠️ volume type (double to int)
* Change ⚠️ BPM type (double to int)

## 1.0.4

* add pause() method
* fix stop() method replace pause() method

## 1.0.3

* fix android volume control

## 1.0.2

* update screenshot

## 1.0.0

* add android support
* add web support
* add example
* add screenshot

## 0.0.1

* Metronome, currently ios only.
