import AVFoundation

class Metronome {
    private var eventTick: EventTickHandler?
    private var audioPlayerNode: AVAudioPlayerNode = AVAudioPlayerNode()
    private var audioEngine: AVAudioEngine = AVAudioEngine()
    private var mixerNode: AVAudioMixerNode
    private var audioBuffer: AVAudioPCMBuffer?
    //
    private var audioFileMain: AVAudioFile
    private var audioFileAccented: AVAudioFile
    public var audioBpm: Int = 120
    public var audioVolume: Float = 0.5
    public var audioTimeSignature: Int = 0

    private var sampleRate: Int = 44100
    private var timer: DispatchSourceTimer?
    private let timerQueue = DispatchQueue(label: "com.metronome.beat-timer", qos: .background)
    private var startTime: AVAudioTime?
    
    // Synchronization primitives
    private let MAX_DRIFT_CORRECTION: Int
    private var epochStartTimeMs: Int64 = 0
    private var scheduledStartTimeNs: Int64 = 0
    private var audioStartTimeNs: Int64 = 0
    private var targetCorrectionNs: Int64 = 0
    private var actualCorrectionNs: Int64 = 0
    
    /// Initialize the metronome with the main and accented audio files.
    init(
        mainFileBytes: Data,
        accentedFileBytes: Data,
        bpm: Int,
        timeSignature: Int = 0,
        volume: Float,
        sampleRate: Int,
        manageAudioSession: Bool = true
    ) {
        self.sampleRate = sampleRate
        self.MAX_DRIFT_CORRECTION = sampleRate / 20
        audioTimeSignature = timeSignature
        audioBpm = bpm
        audioVolume = volume
        // Initialize audio files
        audioFileMain = try! AVAudioFile(fromData: mainFileBytes)
        if accentedFileBytes.isEmpty {
            audioFileAccented = audioFileMain
        }else{
            audioFileAccented = try! AVAudioFile(fromData: accentedFileBytes)
        }
#if os(iOS)
        if manageAudioSession {
            do {
                let audioSession = AVAudioSession.sharedInstance()
                try audioSession.setCategory(
                    .playAndRecord,
                    mode: .videoRecording,
                    options: [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker, .mixWithOthers]
                )

                try audioSession.setActive(true)
            } catch {
                print("Failed to set audio session category: \(error)")
            }
        }
#endif
        // Initialize audio engine and player node
        audioEngine.attach(audioPlayerNode)
        // Set up mixer node
        mixerNode = audioEngine.mainMixerNode
        mixerNode.outputVolume = audioVolume
        // Connect nodes
        audioEngine.connect(audioPlayerNode, to: mixerNode, format: audioFileMain.processingFormat)
        audioEngine.prepare()
        // Start the audio engine
        if !self.audioEngine.isRunning {
            do {
                try self.audioEngine.start()
                print("Start the audio engine")
            } catch {
                print("Failed to start audio engine: \(error.localizedDescription)")
            }
        }
        // Set volume
        setVolume(volume:volume)
#if os(iOS)
        setupNotifications()
#endif
    }
    private func reconnectPlayerNode() {
        if !audioEngine.outputConnectionPoints(for: audioPlayerNode, outputBus: 0).isEmpty {
            audioEngine.disconnectNodeOutput(audioPlayerNode)
        }
        audioEngine.connect(audioPlayerNode, to: mixerNode, format: audioFileMain.processingFormat)
    }

    /// Start the metronome.
    func play() {
        play(startTimeMs: 0, driftCorrectionUs: 0)
    }
    
    func play(startTimeMs: Int64, driftCorrectionUs: Int64) {
        if !isPlaying {
            self.epochStartTimeMs = Int64(Date().timeIntervalSince1970 * 1000)
            self.audioStartTimeNs = Int64(DispatchTime.now().uptimeNanoseconds)
            self.targetCorrectionNs = driftCorrectionUs * 1000
            self.actualCorrectionNs = 0
            self.scheduledStartTimeNs = self.audioStartTimeNs
            
            if startTimeMs > 0 {
                self.scheduledStartTimeNs += (startTimeMs - self.epochStartTimeMs) * 1000000
            }
            
            if !audioEngine.isRunning {
                do {
                    try audioEngine.start()
                } catch {
                    print("Audio engine failed to start in play(): \(error)")
                   return
                }
            }
            audioBuffer = generateBuffer()
        }
    }
    
    func applyDriftCorrection(correctionUs: Int64) {
        self.targetCorrectionNs = correctionUs * 1000
    }

    /// Pause the metronome.
    func pause() {
        stop()
    }
    
    /// Stop the metronome.
    func stop() {
        // Stop the beat callback before operating on the player node.
        stopBeatTimer()
        if audioBuffer != nil {
            audioBuffer?.frameLength = 0
            self.audioPlayerNode.scheduleBuffer(audioBuffer!, at: nil, options: .interruptsAtLoop, completionHandler: nil)
        }
        audioPlayerNode.stop()
    }
    
    /// Set the BPM of the metronome.
    func setBPM(bpm: Int) {
        if audioBpm != bpm {
            audioBpm = bpm
            if isPlaying {
                pause()
                play()
            }
        }
    }
    ///Set the TimeSignature of the metronome.
    func setTimeSignature(timeSignature: Int) {
        if audioTimeSignature != timeSignature {
            audioTimeSignature = timeSignature
            if isPlaying {
                pause()
                play()
            }
        }
    }
    
    func setAudioFile(mainFileBytes: Data, accentedFileBytes: Data) {
        if mainFileBytes.isEmpty && accentedFileBytes.isEmpty { return }

        let wasPlaying = isPlaying
        if wasPlaying { stop() }

        if !mainFileBytes.isEmpty {
            audioFileMain = try! AVAudioFile(fromData: mainFileBytes)
        }
        if !accentedFileBytes.isEmpty {
            audioFileAccented = try! AVAudioFile(fromData: accentedFileBytes)
        }

        reconnectPlayerNode()

        if wasPlaying { play() }
    }
    
    var getTimeSignature: Int {
        return audioTimeSignature
    }
    
    var getVolume: Int {
        return Int(audioVolume * 100)
    }
    
    func setVolume(volume: Float) {
        audioVolume = volume
        mixerNode.outputVolume = volume
    }
    
    var isPlaying: Bool {
        return audioPlayerNode.isPlaying
    }
    
    /// Enable the tick callback.
    public func enableTickCallback(_eventTickSink: EventTickHandler) {
        self.eventTick = _eventTickSink
    }
#if os(iOS)
    private func setupNotifications() {
        NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main,
            using: handleInterruption
        )
        NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main,
            using: handleRouteChange
        )
    }

    private func handleInterruption(_ notification: Notification) {
        if isPlaying {
            pause()
        }
    }
    private func handleRouteChange(_ notification: Notification) {
        let wasPlaying = isPlaying
        if wasPlaying {
            self.stop()
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            self.audioEngine.stop()

            do {
                try self.audioEngine.start()
            } catch {
                print("Audio engine failed to restart: \(error.localizedDescription)")
            }

            if wasPlaying {
                self.play()
            }
        }
    }
#endif
    /// Generate buffer with accents based on time signature
    private func generateBuffer() -> AVAudioPCMBuffer {
        audioFileMain.framePosition = 0
        audioFileAccented.framePosition = 0

        let beatLength = AVAudioFrameCount(Double(self.sampleRate) * 60 / Double(self.audioBpm))
        // let beatLength = AVAudioFrameCount(audioFileMain.processingFormat.sampleRate * 60 / Double(self.audioBpm))
        let bufferMainClick = AVAudioPCMBuffer(pcmFormat: audioFileMain.processingFormat, frameCapacity: beatLength)!
        try! audioFileMain.read(into: bufferMainClick)
        bufferMainClick.frameLength = beatLength

        let bufferBar: AVAudioPCMBuffer
        if self.audioTimeSignature < 2 {
            bufferBar = AVAudioPCMBuffer(pcmFormat: audioFileMain.processingFormat, frameCapacity: beatLength + AVAudioFrameCount(MAX_DRIFT_CORRECTION))!
            bufferBar.frameLength = beatLength + AVAudioFrameCount(MAX_DRIFT_CORRECTION)

            let channelCount = Int(audioFileMain.processingFormat.channelCount)
            let mainClickArray = Array(UnsafeBufferPointer(start: bufferMainClick.floatChannelData![0], count: channelCount * Int(beatLength)))

            bufferBar.floatChannelData!.pointee.update(from: mainClickArray, count: channelCount * Int(beatLength))
        } else {
            let bufferAccentedClick = AVAudioPCMBuffer(pcmFormat: audioFileAccented.processingFormat, frameCapacity: beatLength)!
            try! audioFileAccented.read(into: bufferAccentedClick)
            bufferAccentedClick.frameLength = beatLength

            let totalCapacity = beatLength * AVAudioFrameCount(self.audioTimeSignature) + AVAudioFrameCount(MAX_DRIFT_CORRECTION)
            bufferBar = AVAudioPCMBuffer(pcmFormat: audioFileMain.processingFormat, frameCapacity: totalCapacity)!
            bufferBar.frameLength = totalCapacity

            let channelCount = Int(audioFileMain.processingFormat.channelCount)
            let mainClickArray = Array(UnsafeBufferPointer(start: bufferMainClick.floatChannelData![0], count: channelCount * Int(beatLength)))
            let accentedClickArray = Array(UnsafeBufferPointer(start: bufferAccentedClick.floatChannelData![0], count: channelCount * Int(beatLength)))

            var barArray = [Float]()
            for i in 0..<self.audioTimeSignature {
                if i == 0 {
                    barArray.append(contentsOf: accentedClickArray)
                } else {
                    barArray.append(contentsOf: mainClickArray)
                }
            }

            bufferBar.floatChannelData!.pointee.update(from: barArray, count: channelCount * Int(beatLength) * self.audioTimeSignature)
        }
        
        // Handle scheduled start with silence buffer
        let waitTimeNs = scheduledStartTimeNs + targetCorrectionNs - Int64(DispatchTime.now().uptimeNanoseconds)
        if waitTimeNs > 0 {
            actualCorrectionNs = targetCorrectionNs
            let delaySamples = AVAudioFrameCount(waitTimeNs * Int64(sampleRate) / 1000000000)
            if delaySamples > 0 {
                let delayBuffer = AVAudioPCMBuffer(pcmFormat: audioFileMain.processingFormat, frameCapacity: delaySamples)!
                delayBuffer.frameLength = delaySamples
                // Schedule silence buffer first
                self.audioPlayerNode.scheduleBuffer(delayBuffer, completionHandler: nil)
            }
        } else {
            actualCorrectionNs = targetCorrectionNs - waitTimeNs
        }
        
        //
        self.audioPlayerNode.scheduleBuffer(bufferBar, at: nil, options: .loops,completionHandler: nil)
        self.audioPlayerNode.play()
        startBeatTimer()
        return bufferBar
    }
    
    func stopBeatTimer() {
        if timer != nil {
            timer?.cancel()
            timer = nil
        }
    }
    
    private func startBeatTimer() {
        if self.eventTick == nil {return}
        let beatDuration = 60.0 / Double(audioBpm)
        let startUptime = DispatchTime.now().uptimeNanoseconds
        timer?.cancel()
        timer = DispatchSource.makeTimerSource(queue: timerQueue)
        timer?.schedule(deadline: .now(), repeating: beatDuration, leeway: .milliseconds(10))
        timer?.setEventHandler { [weak self] in
            guard let self = self else { return }
            // Use a monotonic clock to decouple the beat callback from the AVAudioNode lifecycle.
            let elapsedNanoseconds = DispatchTime.now().uptimeNanoseconds - startUptime
            let elapsedTime = Double(elapsedNanoseconds) / 1_000_000_000

            let currentBeat = Int(elapsedTime / beatDuration)
            let currentTick = (self.audioTimeSignature > 1) ? (currentBeat % self.audioTimeSignature) : 0

            DispatchQueue.main.async {
                self.eventTick?.send(res: currentTick)
            }
        }

        timer?.resume()
    }

    func destroy() {
        // Stop the beat callback before operating on the player node.
        stopBeatTimer()
        audioPlayerNode.stop()
        audioPlayerNode.reset()
        audioEngine.stop()
        audioEngine.reset()
        if audioEngine.attachedNodes.contains(audioPlayerNode) {
            audioEngine.detach(audioPlayerNode)
        }
        audioBuffer = nil
    }
}
extension AVAudioFile {
    convenience init(fromData data: Data) throws {
        let tempURL = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString + ".wav")
        do {
            try data.write(to: tempURL)
            //print("Temporary file created at: \(tempURL)")
        } catch {
            //print("Failed to write data to temporary file: \(error.localizedDescription)")
            throw error
        }
        do {
            try self.init(forReading: tempURL)
        } catch {
            //print("Failed to initialize AVAudioFile: \(error.localizedDescription)")
            throw error
        }
    }
}
