import Flutter
import UIKit

public class ProVideoEditorPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
  var eventSink: FlutterEventSink?
  // Buffer last known progress by id so that listeners that attach later can receive the most
  // recent update instead of starting at 0.0.
  // Store progress and optional stage for buffered events
  var lastProgress: [String: (progress: Double, stage: String?)] = [:]

  public static func register(with registrar: FlutterPluginRegistrar) {
    let methodChannel = FlutterMethodChannel(
      name: "pro_video_editor", binaryMessenger: registrar.messenger())
    let eventChannel = FlutterEventChannel(
      name: "pro_video_editor_progress", binaryMessenger: registrar.messenger())

    let instance = ProVideoEditorPlugin()
    registrar.addMethodCallDelegate(instance, channel: methodChannel)
    eventChannel.setStreamHandler(instance)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "getPlatformVersion":
      result("iOS " + UIDevice.current.systemVersion)

    case "getMetadata":
      guard let args = call.arguments as? [String: Any],
        let inputPath = args["inputPath"] as? String,
        let extensionStr = args["extension"] as? String
      else {
        result(
          FlutterError(
            code: "INVALID_ARGUMENTS", message: "Expected arguments missing", details: nil))
        return
      }

      Task {
        do {
          let meta = try await VideoMetadata.processVideo(inputPath: inputPath, ext: extensionStr)
          result(meta)
        } catch {
          result(
            FlutterError(code: "METADATA_ERROR", message: error.localizedDescription, details: nil))
        }
      }

    case "getThumbnails":
      guard let args = call.arguments as? [String: Any],
        let id = args["id"] as? String,
        let inputPath = args["inputPath"] as? String,
        let extensionStr = args["extension"] as? String,
        let boxFit = args["boxFit"] as? String,
        let outputFormat = args["outputFormat"] as? String,
        let outputWidth = args["outputWidth"] as? Int,
        let outputHeight = args["outputHeight"] as? Int
      else {
        result(FlutterError(code: "INVALID_ARGUMENTS", message: "Missing parameters", details: nil))
        return
      }

      let timestampsUs = (args["timestamps"] as? [NSNumber])?.map { $0.int64Value } ?? []
      let maxOutputFrames = args["maxOutputFrames"] as? Int

      postProgress(id: id, progress: 0.0)

      Task {
        let thumbnails = await ThumbnailGenerator.getThumbnails(
          inputPath: inputPath,
          extension: extensionStr,
          outputFormat: outputFormat,
          boxFit: boxFit,
          outputWidth: outputWidth,
          outputHeight: outputHeight,
          timestampsUs: timestampsUs,
          maxOutputFrames: maxOutputFrames,
          onProgress: { progress, stage in
            self.postProgress(id: id, progress: progress, stage: stage)
          }
        )
        self.postProgress(id: id, progress: 1.0)
        result(thumbnails)
      }

    case "renderVideo":
      guard let args = call.arguments as? [String: Any],
        let id = args["id"] as? String,
        let inputPath = args["inputPath"] as? String
      else {
        result(
          FlutterError(
            code: "INVALID_ARGUMENTS", message: "Missing parameters", details: nil))
        return
      }
      
      let inputFormat = args["inputFormat"] as? String ?? "mp4"
      let outputFormat = args["outputFormat"] as? String ?? "mp4"
      let outputPath = args["outputPath"] as? String
      let imageBytes = (args["imageBytes"] as? FlutterStandardTypedData)?.data
      let rotateTurns = args["rotateTurns"] as? Int
      let cropWidth = args["cropWidth"] as? Int
      let cropHeight = args["cropHeight"] as? Int
      let cropX = args["cropX"] as? Int
      let cropY = args["cropY"] as? Int
      let scaleX = (args["scaleX"] as? NSNumber)?.floatValue
      let scaleY = (args["scaleY"] as? NSNumber)?.floatValue
      let flipX = args["flipX"] as? Bool ?? false
      let flipY = args["flipY"] as? Bool ?? false
      let blur = args["blur"] as? Double
      let bitrate = args["bitrate"] as? Int
      let enableAudio = args["enableAudio"] as? Bool ?? true
      let playbackSpeed = (args["playbackSpeed"] as? NSNumber)?.floatValue
      let startUs = args["startTime"] as? Int64
      let endUs = args["endTime"] as? Int64
      let colorMatrixList = args["colorMatrixList"] as? [[Double]] ?? []
      
      // Custom audio parameters
      let customAudioPath = args["customAudioPath"] as? String
      let customAudioVolume = (args["customAudioVolume"] as? NSNumber)?.doubleValue ?? 1.0
      let customAudioStartTime = args["customAudioStartTime"] as? Int64
      let customAudioEndTime = args["customAudioEndTime"] as? Int64
      let customAudioFadeInDuration = args["customAudioFadeInDuration"] as? Int64 ?? 0
      let customAudioFadeOutDuration = args["customAudioFadeOutDuration"] as? Int64 ?? 0

      postProgress(id: id, progress: 0.0)

      let preferH264 = args["preferH264"] as? Bool ?? false

      RenderVideo.render(
        id: id,
        inputPath: inputPath,
        imageData: imageBytes,
        inputFormat: inputFormat,
        outputFormat: outputFormat,
        outputPath: outputPath,
        rotateTurns: rotateTurns,
        flipX: flipX,
        flipY: flipY,
        cropWidth: cropWidth,
        cropHeight: cropHeight,
        cropX: cropX,
        cropY: cropY,
        scaleX: scaleX,
        scaleY: scaleY,
        bitrate: bitrate,
        enableAudio: enableAudio,
        playbackSpeed: playbackSpeed,
        startUs: startUs,
        endUs: endUs,
        colorMatrixList: colorMatrixList,
        blur: blur,
        customAudioPath: customAudioPath,
        customAudioVolume: customAudioVolume,
        customAudioStartTime: customAudioStartTime,
        customAudioEndTime: customAudioEndTime,
        customAudioFadeInDuration: customAudioFadeInDuration,
        customAudioFadeOutDuration: customAudioFadeOutDuration,
        preferH264: preferH264,
        onProgress: { progress, stage in
          self.postProgress(id: id, progress: progress, stage: stage)
        },
        onComplete: { outputData in
          self.postProgress(id: id, progress: 1.0)
          result(outputData)
        },
        onError: { error in
          result(
            FlutterError(code: "RENDER_ERROR", message: error.localizedDescription, details: nil))
        }
      )

    case "concatenateVideos":
      guard let args = call.arguments as? [String: Any],
        let id = args["id"] as? String,
        let inputPaths = args["inputPaths"] as? [String],
        let outputPath = args["outputPath"] as? String
      else {
        result(
          FlutterError(
            code: "INVALID_ARGUMENTS", message: "Missing parameters for concatenateVideos", details: nil))
        return
      }
      
      postProgress(id: id, progress: 0.0)
      
      ConcatenateVideos.concatenate(
        inputPaths: inputPaths,
        outputPath: outputPath,
        onProgress: { progress, stage in
          self.postProgress(id: id, progress: progress, stage: stage)
        },
        onComplete: { outputPath in
          self.postProgress(id: id, progress: 1.0)
          result(outputPath)
        },
        onError: { error in
          result(
            FlutterError(code: "CONCATENATE_ERROR", message: error.localizedDescription, details: nil))
        }
      )

    case "generateSlideshow":
      guard let args = call.arguments as? [String: Any],
        let id = args["id"] as? String,
        let slidesData = args["slides"] as? [[String: Any]],
        let outputPath = args["outputPath"] as? String
      else {
        result(
          FlutterError(
            code: "INVALID_ARGUMENTS", message: "Missing parameters for generateSlideshow", details: nil))
        return
      }
      
      let width = args["width"] as? Int ?? 1920
      let height = args["height"] as? Int ?? 1080
      let fps = args["fps"] as? Int ?? 30
      let audioPath = args["audioPath"] as? String
      
      // Audio parameters
      let audioTrimStartMs = (args["audioTrimStartMs"] as? NSNumber)?.int64Value
      let audioTrimEndMs = (args["audioTrimEndMs"] as? NSNumber)?.int64Value
      let audioVolume = (args["audioVolume"] as? NSNumber)?.doubleValue
      let audioFadeInMs = (args["audioFadeInMs"] as? NSNumber)?.int64Value
      let audioFadeOutMs = (args["audioFadeOutMs"] as? NSNumber)?.int64Value
      
      // Parse slides data
      let slides: [SlideshowGenerator.SlideConfig] = slidesData.compactMap { slideMap in
        guard let imagePath = slideMap["imagePath"] as? String,
              let durationMs = (slideMap["durationMs"] as? NSNumber)?.int64Value
        else {
          return nil
        }
        
        let transitionInType = slideMap["transitionInType"] as? String ?? "fade"
        let transitionOutType = slideMap["transitionOutType"] as? String ?? "fade"
        let transitionInDurationMs = (slideMap["transitionInDurationMs"] as? NSNumber)?.int64Value ?? 500
        let transitionOutDurationMs = (slideMap["transitionOutDurationMs"] as? NSNumber)?.int64Value ?? 500
        
        return SlideshowGenerator.SlideConfig(
          imagePath: imagePath,
          durationMs: durationMs,
          transitionInType: transitionInType,
          transitionOutType: transitionOutType,
          transitionInDurationMs: transitionInDurationMs,
          transitionOutDurationMs: transitionOutDurationMs
        )
      }
      
      guard !slides.isEmpty else {
        result(
          FlutterError(
            code: "INVALID_ARGUMENTS", message: "No valid slides provided", details: nil))
        return
      }
      
      postProgress(id: id, progress: 0.0)
      
      SlideshowGenerator.generateSlideshow(
        slides: slides,
        outputPath: outputPath,
        width: width,
        height: height,
        fps: fps,
        audioPath: audioPath,
        audioTrimStartMs: audioTrimStartMs,
        audioTrimEndMs: audioTrimEndMs,
        audioVolume: audioVolume,
        audioFadeInMs: audioFadeInMs,
        audioFadeOutMs: audioFadeOutMs,
        onProgress: { progress, stage in
          self.postProgress(id: id, progress: progress, stage: stage)
        },
        onComplete: { resultPath in
          self.postProgress(id: id, progress: 1.0)
          result(resultPath)
        },
        onError: { error in
          result(
            FlutterError(code: "SLIDESHOW_ERROR", message: error.localizedDescription, details: nil))
        }
      )

    case "cancelRender":
      guard let args = call.arguments as? [String: Any], let id = args["id"] as? String else {
        result(FlutterError(code: "INVALID_ARGUMENTS", message: "Missing id", details: nil))
        return
      }
      // Attempt to cancel and return result
      let cancelled = RenderVideo.cancel(id: id)
      // Clear any buffered progress for this id to avoid stale events
      lastProgress.removeValue(forKey: id)
      result(cancelled)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private func postProgress(id: String, progress: Double, stage: String? = nil) {
    DispatchQueue.main.async {
      // store buffered value
      self.lastProgress[id] = (progress: progress, stage: stage)
      print("ProVideoEditorPlugin.postProgress(\(id)) -> \(progress) sink=\(self.eventSink != nil)")
      self.eventSink?([
        "id": id,
        "progress": progress,
        "stage": stage as Any,
      ])
      if progress >= 1.0 {
        self.lastProgress.removeValue(forKey: id)
      }
    }
  }

  @objc public func onListen(
    withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink
  ) -> FlutterError? {
    self.eventSink = events
    print("ProVideoEditorPlugin.onListen - sink set")
    // Emit buffered progress values for all tasks so UI has a fast snapshot
    for (id, p) in lastProgress {
      self.eventSink?([
        "id": id,
        "progress": p.progress,
        "stage": p.stage as Any,
      ])
    }
    return nil
  }

  @objc public func onCancel(withArguments arguments: Any?) -> FlutterError? {
    print("ProVideoEditorPlugin.onCancel - sink cleared")
    self.eventSink = nil
    return nil
  }
}
