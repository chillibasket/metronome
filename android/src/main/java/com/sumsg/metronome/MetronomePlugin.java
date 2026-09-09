package com.sumsg.metronome;

import android.os.SystemClock;
import androidx.annotation.NonNull;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/** MetronomePlugin */
public class MetronomePlugin implements FlutterPlugin, MethodCallHandler {
  /// The MethodChannel that will the communication between Flutter and native
  /// Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine
  /// and unregister it
  /// when the Flutter Engine is detached from the Activity
  private MethodChannel channel;
  //
  private EventChannel eventTick;
  private EventChannel.EventSink eventTickSink;
  private EventChannel eventBar;
  private EventChannel.EventSink eventBarSink;
  // private final String TAG = "metronome";
  /// Metronome
  private Metronome metronome = null;
  /// Whether init() asked for tick events. onListen can arrive before or after init,
  /// so both wire the sink, and both have to honour this opt-out.
  private boolean tickCallbackEnabled = false;

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "metronome");
    channel.setMethodCallHandler(this);
    //
    eventTick = new EventChannel(flutterPluginBinding.getBinaryMessenger(),
        "metronome_tick");
    eventTick.setStreamHandler(new EventChannel.StreamHandler() {
      @Override
      public void onListen(Object args, EventChannel.EventSink events) {
        eventTickSink = events;
        // onListen is delivered asynchronously, so init() can reach us first. Wiring
        // here as well as in metronomeInit() covers either arrival order.
        if (metronome != null && tickCallbackEnabled) {
          metronome.enableTickCallback(events);
        }
      }

      @Override
      public void onCancel(Object args) {
        eventTickSink = null;
        // Otherwise the engine keeps publishing to a cancelled sink.
        if (metronome != null) {
          metronome.enableTickCallback(null);
        }
      }
    });
    //
    eventBar = new EventChannel(flutterPluginBinding.getBinaryMessenger(),
        "metronome_bar");
    eventBar.setStreamHandler(new EventChannel.StreamHandler() {
      @Override
      public void onListen(Object args, EventChannel.EventSink events) {
        eventBarSink = events;
        if (metronome != null) {
          metronome.enableBarCallback(events);
        }
      }

      @Override
      public void onCancel(Object args) {
        eventBarSink = null;
        if (metronome != null) {
          metronome.enableBarCallback(null);
        }
      }
    });
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    // Every branch below must reply exactly once: a MethodChannel handler that returns
    // without touching Result leaves the Dart Future pending forever.
    if (metronome == null && !"init".equals(call.method)) {
      result.error("not_initialized", "init() must be called before " + call.method, null);
      return;
    }
    switch (call.method) {
      case "init":
        metronomeInit(call);
        result.success(null);
        break;
      case "play":
        long startTimeUs = 0;
        long correctionUs = 0;
        int countInBeats = 0;

        if (call.argument("startTimeUs") != null) {
          Number startTimeUsNum = call.argument("startTimeUs");
          startTimeUs = startTimeUsNum != null ? startTimeUsNum.longValue() : 0L;
        }
        if (call.argument("correctionUs") != null) {
          Number correctionUsNum = call.argument("correctionUs");
          correctionUs = correctionUsNum != null ? correctionUsNum.longValue() : 0L;
        }
        if (call.argument("countInBeats") != null) {
          Number countInBeatsNum = call.argument("countInBeats");
          countInBeats = countInBeatsNum != null ? Math.max(0, countInBeatsNum.intValue()) : 0;
        }

        metronome.play(startTimeUs, correctionUs, countInBeats);
        result.success(null);
        break;
      case "pause":
        metronome.pause();
        result.success(null);
        break;
      case "stop":
        metronome.stop();
        result.success(null);
        break;
      case "getVolume":
        // audioVolume is a 0.0-1.0 float; the Dart API is an int 0-100 and decodes
        // the reply as int, so a raw float arrives as a double and fails the cast.
        result.success(Math.round(metronome.audioVolume * 100));
        break;
      case "setVolume":
        setVolume(call);
        result.success(null);
        break;
      case "isPlaying":
        result.success(metronome.isPlaying());
        break;
      case "setBPM":
        setBPM(call);
        result.success(null);
        break;
      case "getBPM":
        result.success(metronome.audioBpm);
        break;
      case "setTimeSignature":
        setTimeSignature(call);
        result.success(null);
        break;
      case "setNextBarTimeSignature":
        setNextBarTimeSignature(call);
        result.success(null);
        break;
      case "getTimeSignature":
        result.success(metronome.getTimeSignature());
        break;
      case "setAudioFile":
        setAudioFile(call);
        result.success(null);
        break;
      case "getTimeUs":
        result.success(SystemClock.elapsedRealtimeNanos() / 1000L);
        break;
      case "setCorrectionUs":
        correctionUs = 0;
        if (call.argument("correctionUs") != null) {
          Number correctionUsNum = call.argument("correctionUs");
          correctionUs = correctionUsNum != null ? correctionUsNum.longValue() : 0L;
        }
        metronome.setCorrectionUs(correctionUs);
        result.success(null);
        break;
      case "destroy":
        metronome.destroy();
        // Leave nothing reachable that points at a released AudioTrack.
        metronome = null;
        tickCallbackEnabled = false;
        result.success(null);
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    channel.setMethodCallHandler(null);
    eventTick.setStreamHandler(null);
    eventBar.setStreamHandler(null);
    eventTickSink = null;
    eventBarSink = null;
    // The plugin instance outlives the Dart isolate, so without this every engine
    // teardown leaks an AudioTrack and its writer thread.
    if (metronome != null) {
      metronome.destroy();
      metronome = null;
    }
    tickCallbackEnabled = false;
  }

  private void metronomeInit(@NonNull MethodCall call) {
    // init() can be called again on the same plugin instance - a hot restart does
    // exactly that - so retire the previous engine rather than leaking it.
    if (metronome != null) {
      metronome.destroy();
      metronome = null;
    }

    byte[] mainFileBytes = call.argument("mainFileBytes");
    if (mainFileBytes == null) {
      mainFileBytes = new byte[0];
    }
    byte[] accentedFileBytes = call.argument("accentedFileBytes");
    if (accentedFileBytes == null) {
      accentedFileBytes = new byte[0];
    }
    byte[] countInFileBytes = call.argument("countInFileBytes");
    if (countInFileBytes == null) {
      countInFileBytes = new byte[0];
    }
    boolean enableTickCallback = Boolean.TRUE.equals(call.argument("enableTickCallback"));

    Integer timeSignature = call.argument("timeSignature");
    int timeSignatureValue = (timeSignature != null) ? timeSignature : 0;

    Integer bpmValue = call.argument("bpm");
    int bpm = (bpmValue != null) ? bpmValue : 120;

    Double volumeValue = call.argument("volume");
    float volume = (volumeValue != null) ? volumeValue.floatValue() : 0.5F;

    Integer sampleRateValue = call.argument("sampleRate");
    int sampleRate = (sampleRateValue != null) ? sampleRateValue : 44100;

    metronome = new Metronome(mainFileBytes, accentedFileBytes, countInFileBytes, bpm, timeSignatureValue, volume,
        sampleRate);

    tickCallbackEnabled = enableTickCallback;
    if (enableTickCallback && eventTickSink != null) {
      metronome.enableTickCallback(eventTickSink);
    }
    if (eventBarSink != null) {
      metronome.enableBarCallback(eventBarSink);
    }
  }

  private void setVolume(@NonNull MethodCall call) {
    if (metronome != null) {
      Double _volume = call.argument("volume");
      if (_volume != null) {
        float _volume1 = _volume.floatValue();
        metronome.setVolume(_volume1);
      }
    }
  }

  private void setBPM(@NonNull MethodCall call) {
    if (metronome != null) {
      Integer _bpm = call.argument("bpm");
      if (_bpm != null) {
        metronome.setBPM(_bpm);
      }
    }
  }

  private void setTimeSignature(@NonNull MethodCall call) {
    if (metronome != null) {
      Integer _timeSignature = call.argument("timeSignature");
      if (_timeSignature != null) {
        metronome.setTimeSignature(_timeSignature);
      }
    }
  }

  private void setNextBarTimeSignature(@NonNull MethodCall call) {
    if (metronome != null) {
      Integer _timeSignature = call.argument("timeSignature");
      if (_timeSignature != null) {
        metronome.setNextBarTimeSignature(_timeSignature);
      }
    }
  }

  private void setAudioFile(@NonNull MethodCall call) {
    if (metronome != null) {
      byte[] mainFileBytes = call.argument("mainFileBytes");
      byte[] accentedFileBytes = call.argument("accentedFileBytes");
      byte[] countInFileBytes = call.argument("countInFileBytes");

      if (mainFileBytes == null) {
        mainFileBytes = new byte[0];
      }
      if (accentedFileBytes == null) {
        accentedFileBytes = new byte[0];
      }
      if (countInFileBytes == null) {
        countInFileBytes = new byte[0];
      }
      metronome.setAudioFile(mainFileBytes, accentedFileBytes, countInFileBytes);
    }
  }
}
