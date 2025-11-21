import AVFoundation

public func applyBitrate(requestedBitrate: Int?, targetWidth: Int? = nil, targetHeight: Int? = nil, presetHint: String? = nil) -> String {
    if let bitrate = requestedBitrate {
        print("[Render] Requested Bitrate: \(bitrate) bps")
        print("[Render] ⚠️ AVAssetExportSession does not support custom bitrate directly.")

        if bitrate >= 50_000_000 {
            if #available(iOS 11.0, *) {
                return AVAssetExportPresetHEVC3840x2160  // Use 4K HEVC as max on iOS
            } else {
                return AVAssetExportPreset3840x2160
            }
        } else if bitrate >= 40_000_000 {
            if #available(iOS 11.0, *) {
                return AVAssetExportPresetHEVC3840x2160
            } else {
                return AVAssetExportPreset3840x2160
            }
        } else if bitrate >= 30_000_000 {
            if #available(iOS 11.0, *) {
                return AVAssetExportPresetHEVC1920x1080
            } else {
                return AVAssetExportPreset1920x1080
            }
        } else if bitrate >= 20_000_000 {
            if #available(iOS 11.0, *) {
                return AVAssetExportPresetHEVCHighestQuality
            } else {
                return AVAssetExportPresetHighestQuality
            }
        } else if bitrate >= 10_000_000 {
            return AVAssetExportPresetHighestQuality
        } else if bitrate >= 7_000_000 {
            // Prefer a 1080p preset when the target render size is >= 1280x720
            if let w = targetWidth, let h = targetHeight {
                if (w >= 1280 || h >= 720) {
                    return AVAssetExportPreset1920x1080
                }
            }
            return AVAssetExportPreset1280x720
        } else if bitrate >= 5_000_000 {
            if let w = targetWidth, let h = targetHeight {
                if (w >= 1280 || h >= 720) {
                    return AVAssetExportPreset1920x1080
                }
            }
            return AVAssetExportPreset1280x720
        } else if bitrate >= 3_000_000 {
            return AVAssetExportPreset960x540
        } else if bitrate >= 2_000_000 {
            return AVAssetExportPreset640x480
        } else if bitrate >= 1_000_000 {
            return AVAssetExportPresetMediumQuality
        } else {
            return AVAssetExportPresetLowQuality
        }
    }

    return presetHint ?? AVAssetExportPresetHighestQuality
}
