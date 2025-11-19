import AVFoundation
import Foundation

public func applyAudio(
    from asset: AVAsset,
    to composition: AVMutableComposition,
    timeRange: CMTimeRange,
    enableAudio: Bool,
    customAudioPath: String?,
    customAudioVolume: Double,
    customAudioStartTime: Int64?,
    customAudioEndTime: Int64?,
    customAudioFadeInDuration: Int64,
    customAudioFadeOutDuration: Int64
) async -> AVMutableAudioMix? {
    print("[\(Tags.render)] 🎵 Audio mixing - enableAudio: \(enableAudio), customAudioPath: \(customAudioPath ?? "nil")")
    
    // Add original video audio if enabled
    if enableAudio {
        do {
            let audioTracks: [AVAssetTrack]
            if #available(iOS 15.0, *) {
                audioTracks = try await asset.loadTracks(withMediaType: .audio)
            } else {
                audioTracks = asset.tracks(withMediaType: .audio)
            }

            if let audioTrack = audioTracks.first {
                if let audioCompositionTrack = composition.addMutableTrack(
                    withMediaType: .audio,
                    preferredTrackID: kCMPersistentTrackID_Invalid
                ) {
                    try? audioCompositionTrack.insertTimeRange(timeRange, of: audioTrack, at: .zero)
                    print("[\(Tags.render)] ✅ Original audio track added")
                }
            } else {
                print("[\(Tags.render)] ℹ️ No original audio track found")
            }
        } catch {
            print("[\(Tags.render)] ⚠️ Failed to load original audio tracks: \(error.localizedDescription)")
        }
    } else {
        print("[\(Tags.render)] Original audio disabled")
    }
    
    // Add custom audio if provided
    guard let customAudioPath = customAudioPath else {
        print("[\(Tags.render)] No custom audio to add")
        return nil
    }
    
    print("[\(Tags.render)] 🎵 Adding custom audio from: \(customAudioPath)")
    print("[\(Tags.render)] 🎵 Custom audio volume: \(customAudioVolume)")
    
    let customAudioURL = URL(fileURLWithPath: customAudioPath)
    
    // Check if file exists
    guard FileManager.default.fileExists(atPath: customAudioPath) else {
        print("[\(Tags.render)] ⚠️ Custom audio file does not exist at path: \(customAudioPath)")
        return nil
    }
    
    let customAudioAsset = AVURLAsset(url: customAudioURL)
    
    do {
        // Load custom audio tracks
        let customAudioTracks: [AVAssetTrack]
        if #available(iOS 15.0, *) {
            customAudioTracks = try await customAudioAsset.loadTracks(withMediaType: .audio)
        } else {
            customAudioTracks = customAudioAsset.tracks(withMediaType: .audio)
        }
        
        guard let customAudioTrack = customAudioTracks.first else {
            print("[\(Tags.render)] ⚠️ No audio track found in custom audio file")
            return nil
        }
        
        // Create a new audio track for custom audio
        guard let customAudioCompositionTrack = composition.addMutableTrack(
            withMediaType: .audio,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ) else {
            print("[\(Tags.render)] ⚠️ Failed to create custom audio composition track")
            return nil
        }
        
        // Calculate time range for custom audio
        let customAudioDuration: CMTime
        if #available(iOS 15.0, *) {
            customAudioDuration = try await customAudioAsset.load(.duration)
        } else {
            customAudioDuration = customAudioAsset.duration
        }
        
        let videoDuration = timeRange.duration
        
        // Convert microseconds to CMTime
        let startTime: CMTime
        if let startUs = customAudioStartTime {
            startTime = CMTime(value: startUs, timescale: 1000000)
        } else {
            startTime = .zero
        }
        
        let endTime: CMTime
        if let endUs = customAudioEndTime {
            endTime = CMTime(value: endUs, timescale: 1000000)
        } else {
            endTime = customAudioDuration
        }
        
        // Calculate the actual duration to use
        let requestedDuration = CMTimeSubtract(endTime, startTime)
        let actualDuration = CMTimeMinimum(requestedDuration, videoDuration)
        
        let customAudioTimeRange = CMTimeRange(start: startTime, duration: actualDuration)
        
        print("[\(Tags.render)] 🎵 Custom audio time range: start=\(CMTimeGetSeconds(startTime))s, duration=\(CMTimeGetSeconds(actualDuration))s")
        
        // Insert custom audio track
        try customAudioCompositionTrack.insertTimeRange(
            customAudioTimeRange,
            of: customAudioTrack,
            at: .zero
        )
        
        // Apply volume using audio mix
        let audioMix = AVMutableAudioMix()
        var audioMixInputParameters: [AVMutableAudioMixInputParameters] = []
        
        // Custom audio volume with fade effects
        let customAudioMixParams = AVMutableAudioMixInputParameters(track: customAudioCompositionTrack)
        customAudioMixParams.trackID = customAudioCompositionTrack.trackID
        
        let volume = Float(customAudioVolume)
        
        // Apply fade in
        if customAudioFadeInDuration > 0 {
            let fadeInDuration = CMTime(value: customAudioFadeInDuration, timescale: 1000000)
            customAudioMixParams.setVolumeRamp(
                fromStartVolume: 0.0,
                toEndVolume: volume,
                timeRange: CMTimeRange(start: .zero, duration: fadeInDuration)
            )
            print("[\(Tags.render)] 🎵 Applied fade in: \(CMTimeGetSeconds(fadeInDuration))s")
        } else {
            customAudioMixParams.setVolume(volume, at: .zero)
        }
        
        // Apply fade out
        if customAudioFadeOutDuration > 0 {
            let fadeOutDuration = CMTime(value: customAudioFadeOutDuration, timescale: 1000000)
            let fadeOutStartTime = CMTimeSubtract(actualDuration, fadeOutDuration)
            
            if CMTimeCompare(fadeOutStartTime, .zero) > 0 {
                customAudioMixParams.setVolumeRamp(
                    fromStartVolume: volume,
                    toEndVolume: 0.0,
                    timeRange: CMTimeRange(start: fadeOutStartTime, duration: fadeOutDuration)
                )
                print("[\(Tags.render)] 🎵 Applied fade out: \(CMTimeGetSeconds(fadeOutDuration))s")
            }
        }
        
        audioMixInputParameters.append(customAudioMixParams)
        
        // If original audio is enabled, also set its volume parameters
        if enableAudio, let originalAudioTrack = composition.tracks(withMediaType: .audio).first,
           originalAudioTrack.trackID != customAudioCompositionTrack.trackID {
            let originalAudioMixParams = AVMutableAudioMixInputParameters(track: originalAudioTrack)
            originalAudioMixParams.trackID = originalAudioTrack.trackID
            originalAudioMixParams.setVolume(1.0, at: .zero) // Original audio at full volume
            audioMixInputParameters.append(originalAudioMixParams)
        }
        
        audioMix.inputParameters = audioMixInputParameters
        
        // Return the audio mix to be applied to the export session
        print("[\(Tags.render)] ✅ Custom audio track added successfully with volume: \(volume)")
        return audioMix
        
    } catch {
        print("[\(Tags.render)] ⚠️ Failed to add custom audio: \(error.localizedDescription)")
        return nil
    }
}
