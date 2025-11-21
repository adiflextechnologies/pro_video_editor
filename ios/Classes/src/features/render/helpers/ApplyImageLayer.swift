import AVFoundation
import CoreImage

func applyImageLayer(
    config: inout VideoCompositorConfig,
    imageData: Data?
) {
    config.overlayImage = imageData
    guard imageData != nil else {
        print("[Render] No overlay image provided")
        return
    }

    print("[Render] Applying overlay image, size: \(imageData?.count ?? 0) bytes")
}
