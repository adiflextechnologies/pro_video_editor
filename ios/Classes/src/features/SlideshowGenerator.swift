import Foundation
import AVFoundation
import UIKit
import CoreImage

private let SLIDESHOW_TAG = "ProVideoEditor:SlideshowGenerator"

/// SlideshowGenerator: Creates a video slideshow from multiple images with transitions
///
/// This implementation creates a single video by compositing images at their designated
/// time positions using AVFoundation. This avoids concatenation bugs and supports
/// multiple images with smooth fade transitions.
class SlideshowGenerator {
    
    struct SlideConfig {
        let imagePath: String
        let durationMs: Int64
        let transitionInType: String
        let transitionOutType: String
        let transitionInDurationMs: Int64
        let transitionOutDurationMs: Int64
    }
    
    /// Generate a slideshow video from multiple images
    ///
    /// - Parameters:
    ///   - slides: List of slide configurations with image paths and durations
    ///   - outputPath: Output file path for the slideshow video
    ///   - width: Video width (default: 1920)
    ///   - height: Video height (default: 1080)
    ///   - fps: Frames per second (default: 30)
    ///   - audioPath: Optional audio file to add to the slideshow
    ///   - onProgress: Callback for progress updates (0.0 to 1.0)
    ///   - onComplete: Callback when slideshow generation is complete
    ///   - onError: Callback when an error occurs
    static func generateSlideshow(
        slides: [SlideConfig],
        outputPath: String,
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 30,
        audioPath: String?,
        onProgress: @escaping (Double, String?) -> Void,
        onComplete: @escaping (String) -> Void,
        onError: @escaping (Error) -> Void
    ) {
        guard !slides.isEmpty else {
            onError(NSError(domain: SLIDESHOW_TAG, code: -1, userInfo: [NSLocalizedDescriptionKey: "No slides provided"]))
            return
        }
        
        print("\(SLIDESHOW_TAG): 🎬 Starting slideshow generation")
        print("\(SLIDESHOW_TAG):   Slides: \(slides.count)")
        print("\(SLIDESHOW_TAG):   Resolution: \(width)x\(height)@\(fps)fps")
        print("\(SLIDESHOW_TAG):   Output: \(outputPath)")
        
        // Calculate total duration
        let totalDurationMs = slides.reduce(0) { $0 + ($1.durationMs + $1.transitionInDurationMs + $1.transitionOutDurationMs) }
        let totalDuration = CMTime(value: totalDurationMs * 1000, timescale: 1_000_000)
        
        print("\(SLIDESHOW_TAG):   Total duration: \(totalDurationMs)ms")
        
        onProgress(0.0, "slideshow")
        
        Task {
            do {
                // Step 1: Create video with images (70% of progress)
                let videoPath: String
                if audioPath != nil {
                    let tempURL = FileManager.default.temporaryDirectory
                        .appendingPathComponent("slideshow_video_\(UUID().uuidString).mp4")
                    videoPath = tempURL.path
                } else {
                    videoPath = outputPath
                }
                
                try await createVideoWithImages(
                    slides: slides,
                    outputPath: videoPath,
                    width: width,
                    height: height,
                    fps: fps,
                    totalDuration: totalDuration,
                    onProgress: { progress in
                        onProgress(progress * 0.7, "slideshow")
                    }
                )
                
                onProgress(0.7, "slideshow")
                
                // Step 2: Add audio if provided (30% of progress)
                if let audioPath = audioPath {
                    print("\(SLIDESHOW_TAG): 🎵 Adding audio to slideshow...")
                    try await mixAudioWithVideo(
                        videoPath: videoPath,
                        audioPath: audioPath,
                        outputPath: outputPath,
                        durationMs: totalDurationMs,
                        onProgress: { progress in
                            onProgress(0.7 + (progress * 0.3), "slideshow")
                        }
                    )
                    
                    // Clean up temp video file
                    try? FileManager.default.removeItem(atPath: videoPath)
                }
                
                onProgress(1.0, "slideshow")
                
                print("\(SLIDESHOW_TAG): ✅ Slideshow generated successfully")
                let fileSize = try? FileManager.default.attributesOfItem(atPath: outputPath)[.size] as? Int64
                if let size = fileSize {
                    print("\(SLIDESHOW_TAG):   Output size: \(size / 1024)KB")
                }
                
                onComplete(outputPath)
                
            } catch {
                print("\(SLIDESHOW_TAG): ❌ Error generating slideshow: \(error.localizedDescription)")
                onError(error)
            }
        }
    }
    
    /// Create a video by compositing images at their designated time positions
    private static func createVideoWithImages(
        slides: [SlideConfig],
        outputPath: String,
        width: Int,
        height: Int,
        fps: Int,
        totalDuration: CMTime,
        onProgress: @escaping (Double) -> Void
    ) async throws {
        let outputURL = URL(fileURLWithPath: outputPath)
        
        // Remove existing file
        try? FileManager.default.removeItem(at: outputURL)
        
        // Create asset writer
        let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
        
        // Configure video settings
        let videoSettings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: 8_000_000,
                AVVideoMaxKeyFrameIntervalKey: fps,
            ]
        ]
        
        let writerInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
        writerInput.expectsMediaDataInRealTime = false
        
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: writerInput,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
            ]
        )
        
        guard writer.canAdd(writerInput) else {
            throw NSError(domain: SLIDESHOW_TAG, code: -1, userInfo: [NSLocalizedDescriptionKey: "Cannot add writer input"])
        }
        
        writer.add(writerInput)
        
        // Start writing session
        guard writer.startWriting() else {
            throw NSError(domain: SLIDESHOW_TAG, code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to start writing"])
        }
        
        writer.startSession(atSourceTime: .zero)
        
        // Load and prepare all images
        print("\(SLIDESHOW_TAG): Loading \(slides.count) images...")
        let loadedImages = try await loadImages(slides: slides, width: width, height: height)
        print("\(SLIDESHOW_TAG): ✅ Loaded \(loadedImages.count) images")
        
        // Calculate frame count
        let frameDuration = CMTime(value: 1, timescale: Int32(fps))
        let totalFrames = Int(CMTimeGetSeconds(totalDuration) * Double(fps))
        
        // Process frames
        var currentFrame = 0
        var currentTime = CMTime.zero
        
        while currentFrame < totalFrames {
            // Wait for input to be ready
            while !writerInput.isReadyForMoreMediaData {
                try await Task.sleep(nanoseconds: 10_000_000) // 10ms
            }
            
            // Determine which slide this frame belongs to
            let (slideIndex, frameProgress, slideStartFrame) = getSlideForFrame(
                frameIndex: currentFrame,
                slides: slides,
                fps: fps
            )
            
            // Get the image for this frame
            guard slideIndex < loadedImages.count else {
                print("\(SLIDESHOW_TAG): ⚠️ Slide index \(slideIndex) out of bounds")
                break
            }
            
            let image = loadedImages[slideIndex]
            
            // Calculate transition alpha
            let slideTotalDurationMs = slides[slideIndex].durationMs + slides[slideIndex].transitionInDurationMs + slides[slideIndex].transitionOutDurationMs
            let alpha = calculateTransitionAlpha(
                frameProgress: frameProgress,
                transitionIn: slides[slideIndex].transitionInType,
                transitionOut: slides[slideIndex].transitionOutType,
                transitionInDurationMs: slides[slideIndex].transitionInDurationMs,
                transitionOutDurationMs: slides[slideIndex].transitionOutDurationMs,
                slideTotalDurationMs: slideTotalDurationMs
            )
            
            // Create pixel buffer with image and alpha
            let transform = calculateTransform(
                frameProgress: frameProgress,
                transitionIn: slides[slideIndex].transitionInType,
                transitionOut: slides[slideIndex].transitionOutType,
                transitionInDurationMs: slides[slideIndex].transitionInDurationMs,
                transitionOutDurationMs: slides[slideIndex].transitionOutDurationMs,
                slideTotalDurationMs: slideTotalDurationMs,
                width: width,
                height: height
            )
            // Check for crossfade condition: if within current slide's transitionIn and either current in or previous out is crossfade
            var composedImage: UIImage? = nil
            let currSlide = slides[slideIndex]
            let inFraction = slideTotalDurationMs > 0 ? CGFloat(currSlide.transitionInDurationMs) / CGFloat(slideTotalDurationMs) : 0.0
            if frameProgress < inFraction && slideIndex > 0 {
                let prevIndex = slideIndex - 1
                let prevSlide = slides[prevIndex]
                if currSlide.transitionInType == "crossfade" || prevSlide.transitionOutType == "crossfade" {
                    print("\(SLIDESHOW_TAG):   Crossfade active between slides \(prevIndex) and \(slideIndex)")
                    // Compute prev slide progress
                    let prevTotalMs = prevSlide.durationMs + prevSlide.transitionInDurationMs + prevSlide.transitionOutDurationMs
                    let prevFrames = Int(Double(prevTotalMs) / 1000.0 * Double(fps))
                    let prevStartFrame = slideStartFrame - prevFrames
                    let prevFrameIndex = currentFrame - prevStartFrame
                    let prevProgress = prevFrameIndex >= 0 && prevFrames > 0 ? CGFloat(prevFrameIndex) / CGFloat(prevFrames) : 1.0
                    let prevAlpha = calculateTransitionAlpha(
                        frameProgress: prevProgress,
                        transitionIn: prevSlide.transitionInType,
                        transitionOut: prevSlide.transitionOutType,
                        transitionInDurationMs: prevSlide.transitionInDurationMs,
                        transitionOutDurationMs: prevSlide.transitionOutDurationMs,
                        slideTotalDurationMs: prevTotalMs
                    )
                    let prevTransform = calculateTransform(
                        frameProgress: prevProgress,
                        transitionIn: prevSlide.transitionInType,
                        transitionOut: prevSlide.transitionOutType,
                        transitionInDurationMs: prevSlide.transitionInDurationMs,
                        transitionOutDurationMs: prevSlide.transitionOutDurationMs,
                        slideTotalDurationMs: prevTotalMs,
                        width: width,
                        height: height
                    )

                    let prevImage = loadedImages[prevIndex]
                    if let transformedPrev = createTransformedImage(from: prevImage, width: width, height: height, tx: prevTransform.tx, ty: prevTransform.ty, scale: prevTransform.scale, alpha: prevAlpha),
                       let transformedCurr = createTransformedImage(from: image, width: width, height: height, tx: transform.tx, ty: transform.ty, scale: transform.scale, alpha: alpha) {
                        composedImage = composeImages(prev: transformedPrev, prevAlpha: prevAlpha, curr: transformedCurr, currAlpha: alpha, width: width, height: height)
                        print("\(SLIDESHOW_TAG):   Composited crossfade for frameIndex=\(currentFrame) -> prevAlpha=\(prevAlpha) currAlpha=\(alpha)")
                    }
                }
            }

            // If no composition occurred, just use single transformed image
            if composedImage == nil {
                if let transformed = createTransformedImage(from: image, width: width, height: height, tx: transform.tx, ty: transform.ty, scale: transform.scale, alpha: alpha) {
                    composedImage = transformed
                }
            }

            if let finalImage = composedImage,
               let pixelBuffer = createPixelBuffer(
                    from: finalImage,
                    width: width,
                    height: height,
                    alpha: 1.0
                ) {
                print("[Slideshow] frameIndex=\(slideIndex) progress=\(frameProgress) alpha=\(alpha) tx=\(transform.tx) ty=\(transform.ty) scale=\(transform.scale)")
                adaptor.append(pixelBuffer, withPresentationTime: currentTime)
            }
            
            currentTime = CMTimeAdd(currentTime, frameDuration)
            currentFrame += 1
            
            // Report progress every 2 seconds
            if currentFrame % (fps * 2) == 0 {
                let progress = Double(currentFrame) / Double(totalFrames)
                onProgress(progress)
                print("\(SLIDESHOW_TAG):   Encoding progress: \(Int(progress * 100))%")
            }
        }
        
        // Finish writing
        writerInput.markAsFinished()
        
        await writer.finishWriting()
        
        onProgress(1.0)
        
        if writer.status == .failed {
            throw writer.error ?? NSError(domain: SLIDESHOW_TAG, code: -1, userInfo: [NSLocalizedDescriptionKey: "Writer failed"])
        }
    }
    
    /// Load and scale images to fit video dimensions
    private static func loadImages(slides: [SlideConfig], width: Int, height: Int) async throws -> [UIImage] {
        return try slides.map { slide in
            guard let image = UIImage(contentsOfFile: slide.imagePath) else {
                throw NSError(domain: SLIDESHOW_TAG, code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to load image: \(slide.imagePath)"])
            }
            return scaleAndFitImage(image, targetWidth: width, targetHeight: height)
        }
    }
    
    /// Scale and fit an image to target dimensions with letterboxing/pillarboxing
    private static func scaleAndFitImage(_ image: UIImage, targetWidth: Int, targetHeight: Int) -> UIImage {
        let targetSize = CGSize(width: targetWidth, height: targetHeight)
        let imageSize = image.size
        
        // Calculate scaling to fit
        let widthRatio = targetSize.width / imageSize.width
        let heightRatio = targetSize.height / imageSize.height
        let scaleFactor = min(widthRatio, heightRatio)
        
        let scaledSize = CGSize(
            width: imageSize.width * scaleFactor,
            height: imageSize.height * scaleFactor
        )
        
        let xOffset = (targetSize.width - scaledSize.width) / 2
        let yOffset = (targetSize.height - scaledSize.height) / 2
        
        // Create context with black background
        UIGraphicsBeginImageContextWithOptions(targetSize, true, 1.0)
        defer { UIGraphicsEndImageContext() }
        
        guard let context = UIGraphicsGetCurrentContext() else {
            return image
        }
        
        // Fill with black
        context.setFillColor(UIColor.black.cgColor)
        context.fill(CGRect(origin: .zero, size: targetSize))
        
        // Draw scaled image
        let drawRect = CGRect(x: xOffset, y: yOffset, width: scaledSize.width, height: scaledSize.height)
        image.draw(in: drawRect)
        
        return UIGraphicsGetImageFromCurrentImageContext() ?? image
    }
    
    /// Create a pixel buffer from an image with alpha blending
    private static func createPixelBuffer(
        from image: UIImage,
        width: Int,
        height: Int,
        alpha: CGFloat
    ) -> CVPixelBuffer? {
        var pixelBuffer: CVPixelBuffer?
        let options: [String: Any] = [
            kCVPixelBufferCGImageCompatibilityKey as String: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey as String: true,
        ]
        
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            kCVPixelFormatType_32ARGB,
            options as CFDictionary,
            &pixelBuffer
        )
        
        guard status == kCVReturnSuccess, let buffer = pixelBuffer else {
            return nil
        }
        
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        
        guard let context = CGContext(
            data: CVPixelBufferGetBaseAddress(buffer),
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
        ) else {
            return nil
        }
        
        // Clear to black
        context.setFillColor(UIColor.black.cgColor)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        
        // Draw image with alpha
        context.setAlpha(alpha)
        if let cgImage = image.cgImage {
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        
        return buffer
    }
    
    /// Determine which slide a frame belongs to and the progress within that slide
    private static func getSlideForFrame(
        frameIndex: Int,
        slides: [SlideConfig],
        fps: Int
    ) -> (slideIndex: Int, frameProgress: CGFloat, startFrame: Int) {
        var accumulatedFrames = 0
        
        for (index, slide) in slides.enumerated() {
            let slideTotalMs = Double(slide.durationMs + slide.transitionInDurationMs + slide.transitionOutDurationMs)
            let slideFrames = Int(slideTotalMs / 1000.0 * Double(fps))
            if frameIndex < accumulatedFrames + slideFrames {
                let frameInSlide = frameIndex - accumulatedFrames
                let progress = CGFloat(frameInSlide) / CGFloat(slideFrames)
                return (index, progress, accumulatedFrames)
            }
            accumulatedFrames += slideFrames
        }
        
        // Fallback to last slide
        return (slides.count - 1, 1.0, accumulatedFrames)
    }

    /// Compose two UIImages with given alpha values onto a single image
    private static func composeImages(prev: UIImage, prevAlpha: CGFloat, curr: UIImage, currAlpha: CGFloat, width: Int, height: Int) -> UIImage? {
        let targetSize = CGSize(width: width, height: height)
        UIGraphicsBeginImageContextWithOptions(targetSize, true, 1.0)
        defer { UIGraphicsEndImageContext() }

        guard let context = UIGraphicsGetCurrentContext() else { return nil }
        context.setFillColor(UIColor.black.cgColor)
        context.fill(CGRect(origin: .zero, size: targetSize))

        let drawRect = CGRect(origin: .zero, size: targetSize)
        prev.draw(in: drawRect, blendMode: .normal, alpha: prevAlpha)
        curr.draw(in: drawRect, blendMode: .normal, alpha: currAlpha)

        return UIGraphicsGetImageFromCurrentImageContext()
    }
    
    /// Calculate alpha value based on transition type and progress
    private static func calculateTransitionAlpha(
        frameProgress: CGFloat,
        transitionIn: String,
        transitionOut: String,
        transitionInDurationMs: Int64,
        transitionOutDurationMs: Int64,
        slideTotalDurationMs: Int64
    ) -> CGFloat {
        let fadeInFraction: CGFloat = slideTotalDurationMs > 0 ? CGFloat(transitionInDurationMs) / CGFloat(slideTotalDurationMs) : 0.0
        let fadeOutFraction: CGFloat = slideTotalDurationMs > 0 ? CGFloat(transitionOutDurationMs) / CGFloat(slideTotalDurationMs) : 0.0

        if frameProgress < fadeInFraction && transitionIn == "fade" {
            return frameProgress / fadeInFraction
        } else if frameProgress > (1.0 - fadeOutFraction) && transitionOut == "fade" {
            return (1.0 - frameProgress) / fadeOutFraction
        } else {
            return 1.0
        }
    }

    private static func calculateTransform(
        frameProgress: CGFloat,
        transitionIn: String,
        transitionOut: String,
        transitionInDurationMs: Int64,
        transitionOutDurationMs: Int64,
        slideTotalDurationMs: Int64,
        width: Int,
        height: Int
    ) -> (tx: CGFloat, ty: CGFloat, scale: CGFloat) {
        let inFraction: CGFloat = slideTotalDurationMs > 0 ? CGFloat(transitionInDurationMs) / CGFloat(slideTotalDurationMs) : 0.0
        let outFraction: CGFloat = slideTotalDurationMs > 0 ? CGFloat(transitionOutDurationMs) / CGFloat(slideTotalDurationMs) : 0.0
        var tx: CGFloat = 0.0
        var ty: CGFloat = 0.0
        var scale: CGFloat = 1.0

        if frameProgress < inFraction {
            let t = inFraction > 0 ? frameProgress / inFraction : 1.0
            switch transitionIn {
            case "slideLeft": tx = CGFloat(width) * (1.0 - t)
            case "slideRight": tx = -CGFloat(width) * (1.0 - t)
            case "slideUp": ty = CGFloat(height) * (1.0 - t)
            case "slideDown": ty = -CGFloat(height) * (1.0 - t)
            case "zoomIn": scale = 1.2 - 0.2 * t
            case "zoomOut": scale = 0.8 + 0.2 * t
            default: break
            }
        }

        if frameProgress > (1.0 - outFraction) {
            let t = outFraction > 0 ? (frameProgress - (1.0 - outFraction)) / outFraction : 1.0
            switch transitionOut {
            case "slideLeft": tx = -CGFloat(width) * t
            case "slideRight": tx = CGFloat(width) * t
            case "slideUp": ty = -CGFloat(height) * t
            case "slideDown": ty = CGFloat(height) * t
            case "zoomIn": scale = 1.0 + 0.2 * t
            case "zoomOut": scale = 1.0 - 0.2 * t
            default: break
            }
        }

        return (tx, ty, scale)
    }

    private static func createTransformedImage(from image: UIImage, width: Int, height: Int, tx: CGFloat, ty: CGFloat, scale: CGFloat, alpha: CGFloat) -> UIImage? {
        let targetSize = CGSize(width: width, height: height)
        UIGraphicsBeginImageContextWithOptions(targetSize, true, 1.0)
        defer { UIGraphicsEndImageContext() }

        guard let context = UIGraphicsGetCurrentContext() else { return nil }
        // Fill background with black
        context.setFillColor(UIColor.black.cgColor)
        context.fill(CGRect(origin: .zero, size: targetSize))

        context.saveGState()
        context.translateBy(x: tx, y: ty)
        context.scaleBy(x: scale, y: scale)
        context.setAlpha(alpha)
        image.draw(in: CGRect(origin: .zero, size: targetSize))
        context.restoreGState()

        return UIGraphicsGetImageFromCurrentImageContext()
    }
    
    /// Mix audio with video
    private static func mixAudioWithVideo(
        videoPath: String,
        audioPath: String,
        outputPath: String,
        durationMs: Int64,
        onProgress: @escaping (Double) -> Void
    ) async throws {
        let videoURL = URL(fileURLWithPath: videoPath)
        let audioURL = URL(fileURLWithPath: audioPath)
        let outputURL = URL(fileURLWithPath: outputPath)
        
        // Remove existing output file
        try? FileManager.default.removeItem(at: outputURL)
        
        let composition = AVMutableComposition()
        
        // Add video track (iOS 13 compatible)
        let videoAsset = AVURLAsset(url: videoURL)
        let videoTracks = videoAsset.tracks(withMediaType: .video)
        guard let videoTrack = videoTracks.first else {
            throw NSError(domain: SLIDESHOW_TAG, code: -1, userInfo: [NSLocalizedDescriptionKey: "No video track found"])
        }
        
        let compositionVideoTrack = composition.addMutableTrack(
            withMediaType: .video,
            preferredTrackID: kCMPersistentTrackID_Invalid
        )
        
        let videoDuration = videoAsset.duration
        try compositionVideoTrack?.insertTimeRange(
            CMTimeRange(start: .zero, duration: videoDuration),
            of: videoTrack,
            at: .zero
        )
        
        // Add audio track (iOS 13 compatible)
        let audioAsset = AVURLAsset(url: audioURL)
        let audioTracks = audioAsset.tracks(withMediaType: .audio)
        if let audioTrack = audioTracks.first {
            let compositionAudioTrack = composition.addMutableTrack(
                withMediaType: .audio,
                preferredTrackID: kCMPersistentTrackID_Invalid
            )
            
            // Trim audio to video duration
            let audioDuration = min(videoDuration, audioAsset.duration)
            try compositionAudioTrack?.insertTimeRange(
                CMTimeRange(start: .zero, duration: audioDuration),
                of: audioTrack,
                at: .zero
            )
        }
        
        // Export
        guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality) else {
            throw NSError(domain: SLIDESHOW_TAG, code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to create export session"])
        }
        
        exporter.outputURL = outputURL
        exporter.outputFileType = .mp4
        exporter.shouldOptimizeForNetworkUse = true
        
        // Monitor progress
        let progressTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { _ in
            onProgress(Double(exporter.progress))
        }
        
        await exporter.export()
        
        progressTimer.invalidate()
        
        if exporter.status == .failed {
            throw exporter.error ?? NSError(domain: SLIDESHOW_TAG, code: -1, userInfo: [NSLocalizedDescriptionKey: "Export failed"])
        }
        
        onProgress(1.0)
    }
}
