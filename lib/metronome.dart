import 'dart:async';

import 'metronome_platform_interface.dart';

class Metronome {
  static final Metronome _instance = Metronome._internal();
  factory Metronome() {
    return _instance;
  }
  Metronome._internal();
  final MetronomePlatform _platform = MetronomePlatform.instance;
  bool get isInitialized => _initialized;
  bool _initialized = false;

  /// Beat index within the current bar, 0-based.
  ///
  /// While a count-in requested through [play] is sounding, the values are
  /// negative and count up to zero: a 4-beat count-in emits -4, -3, -2, -1 and
  /// then 0 on the downbeat of bar 1. Negative values are only ever emitted when
  /// [play] was called with `countInBeats > 0`.
  ///
  /// ```
  /// metronome.tickStream.listen(
  ///   (int tick) {
  ///     print("tick: $tick");
  ///   },
  /// );
  /// ```
  Stream<int> get tickStream => _platform.tickController.stream;

  /// Beat count of the bar that has just started, emitted alongside each
  /// downbeat. Use the latest value as the meter of the current bar rather than
  /// assuming strict interleaving with [tickStream] - they are separate
  /// channels. Android only; other platforms never emit.
  Stream<int> get barStream => _platform.barController.stream;

  ///initialize the metronome
  /// ```
  /// @param mainPath: the path of the main audio file
  /// @param accentedPath: the path of the accented audio file, default ''
  /// @param bpm: the beats per minute, default `120`
  /// @param volume: the volume of the metronome, default `50`%
  /// @param timeSignature: the timeSignature of the metronome, default `4`
  /// @param sampleRate: the sampleRate of the metronome, default `44100`
  /// @param manageAudioSession: whether the plugin configures and activates AVAudioSession on iOS, default `true`.
  /// @param countInPath: the path of the tone used for count-in clicks, default ''
  ///   (falls back to the main sound). Every click in a count-in uses this one tone.
  /// Set it to `false` when the host application manages the shared audio session. Ignored on other platforms.
  /// ```
  Future<void> init(
    String mainPath, {
    String accentedPath = '',
    int bpm = 120,
    int volume = 50,
    bool enableTickCallback = false,
    int timeSignature = 4,
    int sampleRate = 44100,
    bool manageAudioSession = true,
    String countInPath = '',
  }) async {
    try {
      MetronomePlatform.instance.init(
        mainPath,
        accentedPath: accentedPath,
        bpm: bpm,
        volume: volume,
        enableTickCallback: enableTickCallback,
        timeSignature: timeSignature,
        sampleRate: sampleRate,
        manageAudioSession: manageAudioSession,
        countInPath: countInPath,
      );
      _initialized = true;
      return;
    } catch (err) {
      _initialized = false;
      rethrow;
    }
  }

  /// Play the metronome with an optional scheduled start and count-in.
  ///
  /// @param startTimeUs Start time of the FIRST BEAT OF BAR 1, on the audio
  ///   reference clock, in microseconds (0 = immediate start).
  /// @param correctionUs Drift correction in microseconds for phase alignment
  ///   (0 = no correction).
  /// @param countInBeats Number of count-in clicks to sound BEFORE
  ///   [startTimeUs]. 0 (default) disables the count-in and reproduces the
  ///   previous behaviour exactly. Because the count-in is scheduled backwards
  ///   from [startTimeUs], the caller must extend its own lead by
  ///   `countInBeats * 60000000 ~/ bpm` microseconds. Requires a non-zero
  ///   [startTimeUs]; ignored otherwise, ignored if already playing, and capped
  ///   at 16. While it sounds, [tickStream] emits negative values.
  Future<void> play({
    int startTimeUs = 0,
    int correctionUs = 0,
    int countInBeats = 0,
  }) async {
    return MetronomePlatform.instance.play(
      startTimeUs: startTimeUs,
      correctionUs: correctionUs,
      countInBeats: countInBeats,
    );
  }

  ///pause the metronome
  Future<void> pause() async {
    return MetronomePlatform.instance.pause();
  }

  ///stop the metronome
  Future<void> stop() async {
    return MetronomePlatform.instance.stop();
  }

  ///get the volume of the metronome
  Future<int> getVolume() async {
    int? volume = await MetronomePlatform.instance.getVolume();
    return volume ?? 50;
  }

  ///set the volume of the metronome (0-100)
  Future<void> setVolume(int volume) async {
    return MetronomePlatform.instance.setVolume(volume);
  }

  ///check if the metronome is playing
  Future<bool?> isPlaying() async {
    return MetronomePlatform.instance.isPlaying();
  }

  ///set the audio file of the metronome
  ///
  /// [countInPath] sets the tone used for count-in clicks (Android only). Pass ''
  /// to leave whichever tone is already configured untouched.
  Future<void> setAudioFile({
    String mainPath = '',
    String accentedPath = '',
    String countInPath = '',
  }) async {
    return MetronomePlatform.instance.setAudioFile(
      mainPath: mainPath,
      accentedPath: accentedPath,
      countInPath: countInPath,
    );
  }

  ///set the bpm of the metronome
  Future<void> setBPM(int bpm) async {
    return MetronomePlatform.instance.setBPM(bpm);
  }

  ///get the bpm of the metronome
  Future<int> getBPM() async {
    int? bpm = await MetronomePlatform.instance.getBPM();
    return bpm ?? 120;
  }

  ///set the time signature of the metronome
  Future<void> setTimeSignature(int timeSignature) async {
    return MetronomePlatform.instance.setTimeSignature(timeSignature);
  }

  /// Queue a time signature to take effect at the next bar boundary, leaving the
  /// phase untouched. Intended to be driven once per bar from a song meter map.
  ///
  /// The native writer runs one audio buffer ahead of the speaker, so calling
  /// this on tick 0 of a bar leaves roughly that bar to be heard in the next one.
  /// Calling later defers the change by a bar, which [barStream] makes visible.
  /// Android only.
  Future<void> setNextBarTimeSignature(int timeSignature) async {
    return MetronomePlatform.instance.setNextBarTimeSignature(timeSignature);
  }

  ///get the signature of the metronome
  Future<int> getTimeSignature() async {
    int? timeSignature = await MetronomePlatform.instance.getTimeSignature();
    return timeSignature ?? 0;
  }

  /// Apply drift correction to align metronome phase
  /// @param correctionUs Phase shift in microseconds (non-accumulative)
  ///   - Positive value: shift forward (we're behind)
  ///   - Negative value: shift backward (we're ahead)
  Future<void> setCorrectionUs(int correctionUs) async {
    return MetronomePlatform.instance.setCorrectionUs(correctionUs);
  }

  ///get the current time of the audio refence clock
  Future<int> getTimeUs() async {
    int? timeUs = await MetronomePlatform.instance.getTimeUs();
    return timeUs ?? 0;
  }

  ///destroy the metronome
  Future<void> destroy() async {
    _initialized = false;
    return MetronomePlatform.instance.destroy();
  }
}
