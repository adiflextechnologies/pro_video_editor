import AVFoundation
import Foundation

class ConcatenateVideos {
    
    /// Concatenates multiple videos into a single video file
    /// - Parameters:
    ///   - inputPaths: List of video file paths to concatenate
    ///   - outputPath: Output file path for the concatenated video
    ///   - onProgress: Callback for progress updates (0.0 to 1.0)
    ///   - onComplete: Callback when concatenation is complete
    ///   - onError: Callback when an error occurs
    static func concatenate(
        inputPaths: [String],
        outputPath: String,
        onProgress: @escaping (Double) -> Void,
        onComplete: @escaping (String) -> Void,
        onError: @escaping (Error) -> Void
    ) {
        Task {
            do {
                guard !inputPaths.isEmpty else {
                    throw NSError(
                        domain: "ConcatenateVideos",
                        code: 1,
                        userInfo: [NSLocalizedDescriptionKey: "No input videos provided"]
                    )
                }
                
                // If only one video, just copy it
                if inputPaths.count == 1 {
                    let inputURL = URL(fileURLWithPath: inputPaths[0])
                    let outputURL = URL(fileURLWithPath: outputPath)
                    try? FileManager.default.removeItem(at: outputURL)
                    try FileManager.default.copyItem(at: inputURL, to: outputURL)
                    onProgress(1.0)
                    onComplete(outputPath)
                    return
                }
                
                print("[\(Tags.concatenate)] Starting video concatenation:")
                print("[\(Tags.concatenate)]   Input videos: \(inputPaths.count)")
                
                // Log metadata for each input video
                for (index, path) in inputPaths.enumerated() {
                    let asset = AVURLAsset(url: URL(fileURLWithPath: path))
                    
                    if #available(iOS 15.0, *) {
                        let duration = try await asset.load(.duration)
                        let tracks = try await asset.loadTracks(withMediaType: .video)
                        
                        if let videoTrack = tracks.first {
                            let size = try await videoTrack.load(.naturalSize)
                            let fps = try await videoTrack.load(.nominalFrameRate)
                            print("[\(Tags.concatenate)]   [\(index)] duration=\(CMTimeGetSeconds(duration))s size=\(size.width)x\(size.height) fps=\(fps)")
                        }
                    } else {
                        let duration = asset.duration
                        let tracks = asset.tracks(withMediaType: .video)
                        
                        if let videoTrack = tracks.first {
                            let size = videoTrack.naturalSize
                            let fps = videoTrack.nominalFrameRate
                            print("[\(Tags.concatenate)]   [\(index)] duration=\(CMTimeGetSeconds(duration))s size=\(size.width)x\(size.height) fps=\(fps)")
                        }
                    }
                }
                
                print("[\(Tags.concatenate)]   Output: \(outputPath)")
                
                // Create composition
                let composition = AVMutableComposition()
                var currentTime = CMTime.zero
                
                // Get the first video's properties to use as reference
                let firstAsset = AVURLAsset(url: URL(fileURLWithPath: inputPaths[0]))
                
                let firstVideoTracks: [AVAssetTrack]
                if #available(iOS 15.0, *) {
                    firstVideoTracks = try await firstAsset.loadTracks(withMediaType: .video)
                } else {
                    firstVideoTracks = firstAsset.tracks(withMediaType: .video)
                }
                
                guard let firstVideoTrack = firstVideoTracks.first else {
                    throw NSError(
                        domain: "ConcatenateVideos",
                        code: 2,
                        userInfo: [NSLocalizedDescriptionKey: "No video track found in first video"]
                    )
                }
                
                let referenceSize: CGSize
                let referenceTransform: CGAffineTransform
                
                if #available(iOS 15.0, *) {
                    referenceSize = try await firstVideoTrack.load(.naturalSize)
                    referenceTransform = try await firstVideoTrack.load(.preferredTransform)
                } else {
                    referenceSize = firstVideoTrack.naturalSize
                    referenceTransform = firstVideoTrack.preferredTransform
                }
                
                print("[\(Tags.concatenate)] Reference video size: \(referenceSize.width)x\(referenceSize.height)")
                
                // Add video and audio tracks to composition
                guard let compositionVideoTrack = composition.addMutableTrack(
                    withMediaType: .video,
                    preferredTrackID: kCMPersistentTrackID_Invalid
                ) else {
                    throw NSError(
                        domain: "ConcatenateVideos",
                        code: 3,
                        userInfo: [NSLocalizedDescriptionKey: "Failed to create video track in composition"]
                    )
                }
                
                let compositionAudioTrack = composition.addMutableTrack(
                    withMediaType: .audio,
                    preferredTrackID: kCMPersistentTrackID_Invalid
                )
                
                // Process each video
                for (index, inputPath) in inputPaths.enumerated() {
                    let asset = AVURLAsset(url: URL(fileURLWithPath: inputPath))
                    
                    let duration: CMTime
                    let videoTracks: [AVAssetTrack]
                    let audioTracks: [AVAssetTrack]
                    
                    if #available(iOS 15.0, *) {
                        duration = try await asset.load(.duration)
                        videoTracks = try await asset.loadTracks(withMediaType: .video)
                        audioTracks = try await asset.loadTracks(withMediaType: .audio)
                    } else {
                        duration = asset.duration
                        videoTracks = asset.tracks(withMediaType: .video)
                        audioTracks = asset.tracks(withMediaType: .audio)
                    }
                    
                    // Add video track
                    if let videoTrack = videoTracks.first {
                        let timeRange = CMTimeRange(start: .zero, duration: duration)
                        try compositionVideoTrack.insertTimeRange(
                            timeRange,
                            of: videoTrack,
                            at: currentTime
                        )
                        
                        print("[\(Tags.concatenate)] Added video [\(index)] at time \(CMTimeGetSeconds(currentTime))s, duration \(CMTimeGetSeconds(duration))s")
                    }
                    
                    // Add audio track if available
                    if let audioTrack = audioTracks.first, let compositionAudioTrack = compositionAudioTrack {
                        let timeRange = CMTimeRange(start: .zero, duration: duration)
                        try? compositionAudioTrack.insertTimeRange(
                            timeRange,
                            of: audioTrack,
                            at: currentTime
                        )
                    }
                    
                    currentTime = CMTimeAdd(currentTime, duration)
                    
                    // Report progress during composition building
                    let progress = Double(index + 1) / Double(inputPaths.count) * 0.3 // 30% for building composition
                    onProgress(progress)
                }
                
                print("[\(Tags.concatenate)] Total composition duration: \(CMTimeGetSeconds(composition.duration))s")
                
                // Create video composition for proper rendering
                let videoComposition = AVMutableVideoComposition()
                videoComposition.renderSize = referenceSize
                videoComposition.frameDuration = CMTime(value: 1, timescale: 30) // 30 fps
                
                // Create instruction for the entire duration
                let instruction = AVMutableVideoCompositionInstruction()
                instruction.timeRange = CMTimeRange(start: .zero, duration: composition.duration)
                
                let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: compositionVideoTrack)
                layerInstruction.setTransform(referenceTransform, at: .zero)
                
                instruction.layerInstructions = [layerInstruction]
                videoComposition.instructions = [instruction]
                
                // Setup export session
                let outputURL = URL(fileURLWithPath: outputPath)
                
                // Remove existing file if any
                try? FileManager.default.removeItem(at: outputURL)
                
                guard let exportSession = AVAssetExportSession(
                    asset: composition,
                    presetName: AVAssetExportPresetHighestQuality
                ) else {
                    throw NSError(
                        domain: "ConcatenateVideos",
                        code: 4,
                        userInfo: [NSLocalizedDescriptionKey: "Failed to create export session"]
                    )
                }
                
                exportSession.outputURL = outputURL
                exportSession.outputFileType = .mp4
                exportSession.videoComposition = videoComposition
                exportSession.shouldOptimizeForNetworkUse = true
                
                print("[\(Tags.concatenate)] Starting export...")
                
                // Monitor export progress
                let progressTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { _ in
                    let exportProgress = Double(exportSession.progress)
                    // 30% was composition building, remaining 70% is export
                    let totalProgress = 0.3 + (exportProgress * 0.7)
                    onProgress(totalProgress)
                }
                
                // Start export
                await exportSession.export()
                
                progressTimer.invalidate()
                
                // Check export status
                switch exportSession.status {
                case .completed:
                    print("[\(Tags.concatenate)] ✅ Concatenation completed successfully")
                    
                    if let outputFileSize = try? FileManager.default.attributesOfItem(atPath: outputPath)[.size] as? Int64 {
                        print("[\(Tags.concatenate)]   Output size: \(outputFileSize / 1024)KB")
                    }
                    
                    onProgress(1.0)
                    onComplete(outputPath)
                    
                case .failed:
                    throw exportSession.error ?? NSError(
                        domain: "ConcatenateVideos",
                        code: 5,
                        userInfo: [NSLocalizedDescriptionKey: "Export failed with unknown error"]
                    )
                    
                case .cancelled:
                    throw NSError(
                        domain: "ConcatenateVideos",
                        code: 6,
                        userInfo: [NSLocalizedDescriptionKey: "Export was cancelled"]
                    )
                    
                default:
                    throw NSError(
                        domain: "ConcatenateVideos",
                        code: 7,
                        userInfo: [NSLocalizedDescriptionKey: "Export ended with unexpected status: \(exportSession.status.rawValue)"]
                    )
                }
                
            } catch {
                print("[\(Tags.concatenate)] ❌ Concatenation failed: \(error.localizedDescription)")
                
                // Clean up output file on error
                try? FileManager.default.removeItem(atPath: outputPath)
                
                onError(error)
            }
        }
    }
}
