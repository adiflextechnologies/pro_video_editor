import AVFoundation
import UIKit

class ThumbnailGenerator {

    static func getThumbnails(
        inputPath: String,
        extension ext: String,
        outputFormat: String,
        boxFit: String,
        outputWidth: Int,
        outputHeight: Int,
        timestampsUs: [Int64],
        maxOutputFrames: Int? = 10,
        onProgress: @escaping (Double, String?) -> Void
    ) async -> [Data] {
        let videoURL = URL(fileURLWithPath: inputPath)
        let asset = AVURLAsset(url: videoURL)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero

        let times: [NSValue]
        if !timestampsUs.isEmpty {
            times = timestampsUs.map {
                NSValue(time: CMTime(value: $0, timescale: 1_000_000))
            }
        } else if let maxFrames = maxOutputFrames {
            times = await extractKeyframeTimestamps(asset: asset, maxFrames: maxFrames)
        } else {
            return []
        }

        let timeIndexMap: [Double: Int] = Dictionary(
            uniqueKeysWithValues:
                times.enumerated().map { (index, time) in
                    (time.timeValue.seconds, index)
                }
        )

        let results = await withCheckedContinuation { continuation in
            var resultData = [Data?](repeating: nil, count: times.count)
            var completed = 0
            let start = Date().timeIntervalSince1970
            let totalCount = times.count

            generator.generateCGImagesAsynchronously(forTimes: times) {
                requestedTime, cgImage, actualTime, result, error in

                let key = requestedTime.seconds
                guard let index = timeIndexMap[key] else {
                    print("⚠️ Unexpected time: \(Int(key * 1000)) ms")
                    return
                }

                if let cgImage = cgImage {
                    let resized = resizeCGImageKeepingAspect(
                        cgImage: cgImage,
                        targetWidth: outputWidth,
                        targetHeight: outputHeight,
                        boxFit: boxFit
                    )
                    let data = compressCGImage(resized, format: outputFormat)
                    resultData[index] = data

                    let elapsed = Int((Date().timeIntervalSince1970 - start) * 1000)
                    print("[\(index)] ✅ \(Int(key * 1000)) ms in \(elapsed) ms (\(data.count) bytes)")
                } else {
                    let message = error?.localizedDescription ?? "Unknown error"
                    print("[\(index)] ❌ Failed at \(Int(key * 1000)) ms: \(message)")
                }

                completed += 1
                onProgress(Double(completed) / Double(totalCount), "thumbnail")

                if completed == totalCount {
                    continuation.resume(returning: resultData.compactMap { $0 })
                }
            }
        }

        return results.filter { !$0.isEmpty }
    }

    private static func resizeCGImageKeepingAspect(
        cgImage: CGImage,
        targetWidth: Int,
        targetHeight: Int,
        boxFit: String
    ) -> CGImage {
        let originalWidth = CGFloat(cgImage.width)
        let originalHeight = CGFloat(cgImage.height)

        let widthRatio = CGFloat(targetWidth) / originalWidth
        let heightRatio = CGFloat(targetHeight) / originalHeight

        let scale: CGFloat = {
            switch boxFit.lowercased() {
            case "cover": return max(widthRatio, heightRatio)
            default: return min(widthRatio, heightRatio)
            }
        }()

        let newWidth = Int(originalWidth * scale)
        let newHeight = Int(originalHeight * scale)

        let context = CGContext(
            data: nil,
            width: newWidth,
            height: newHeight,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!

        context.interpolationQuality = .high
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: newWidth, height: newHeight))
        return context.makeImage()!
    }

    private static func compressCGImage(_ cgImage: CGImage, format: String) -> Data {
        let image = UIImage(cgImage: cgImage)
        switch format.lowercased() {
        case "png":
            return image.pngData() ?? Data()
        case "jpeg", "jpg":
            return image.jpegData(compressionQuality: 1) ?? Data()
        default:
            print("⚠️ Format \(format) not supported, falling back to JPEG")
            return image.jpegData(compressionQuality: 1) ?? Data()
        }
    }

    private static func extractKeyframeTimestamps(asset: AVAsset, maxFrames: Int) async -> [NSValue] {
        let duration: CMTime
        if #available(iOS 15.0, *) {
            do {
                duration = try await asset.load(.duration)
            } catch {
                print("❌ Failed to load duration: \(error.localizedDescription)")
                return []
            }
        } else {
            duration = asset.duration
        }

        guard duration.seconds.isFinite && duration.seconds > 0 else { return [] }

        let step = duration.seconds / Double(maxFrames)
        return (0..<maxFrames).map {
            let time = CMTime(seconds: Double($0) * step, preferredTimescale: 1_000_000)
            return NSValue(time: time)
        }
    }
}
