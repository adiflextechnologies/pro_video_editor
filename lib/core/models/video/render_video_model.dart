import 'package:flutter/foundation.dart';
import 'package:pro_video_editor/pro_video_editor.dart';

/// A model describing settings for rendering or exporting a video.
///
/// Includes input video data, optional overlays, transformations,
/// color filters, audio options, playback settings, and output format.
class RenderVideoModel {
  /// Creates a [RenderVideoModel] with the given parameters.
  RenderVideoModel({
    required this.outputFormat,
    required this.video,
    this.imageBytes,
    this.transform,
    this.enableAudio = true,
    this.playbackSpeed,
    this.startTime,
    this.endTime,
    this.blur,
    this.bitrate,
    this.colorMatrixList = const [],
    this.qualityConfig,
    this.customAudioPath,
    this.customAudioVolume = 1.0,
    this.customAudioStartTime,
    this.customAudioEndTime,
    this.customAudioFadeInDuration = 0.0,
    this.customAudioFadeOutDuration = 0.0,
    String? id,
    this.preferH264 = false,
  })  : id = id ?? DateTime.now().microsecondsSinceEpoch.toString(),
        assert(
          startTime == null || endTime == null || startTime < endTime,
          'startTime must be before endTime',
        ),
        assert(
          blur == null || blur >= 0,
          '[blur] must be greater than or equal to 0',
        ),
        assert(
          playbackSpeed == null || playbackSpeed > 0,
          '[playbackSpeed] must be greater than 0',
        ),
        assert(
          bitrate == null || bitrate > 0,
          '[bitrate] must be greater than 0',
        ),
        assert(
          customAudioVolume >= 0.0 && customAudioVolume <= 1.0,
          '[customAudioVolume] must be between 0.0 and 1.0',
        ),
        assert(
          customAudioFadeInDuration >= 0.0,
          '[customAudioFadeInDuration] must be >= 0.0',
        ),
        assert(
          customAudioFadeOutDuration >= 0.0,
          '[customAudioFadeOutDuration] must be >= 0.0',
        );

  /// Creates a [RenderVideoModel] with a predefined quality preset.
  ///
  /// This factory constructor simplifies video export by providing common
  /// quality configurations. The preset automatically sets the appropriate
  /// bitrate and resolution.
  ///
  /// Example:
  /// ```dart
  /// var model = RenderVideoModel.withQualityPreset(
  ///   video: EditorVideo.asset('assets/my-video.mp4'),
  ///   qualityPreset: VideoQualityPreset.p1080,
  ///   outputFormat: VideoOutputFormat.mp4,
  /// );
  /// ```
  ///
  /// You can override the preset's resolution by providing a custom
  /// [transform] with scale or crop settings. The bitrate from the preset
  /// will still be used unless explicitly overridden with [bitrateOverride].
  factory RenderVideoModel.withQualityPreset({
    required EditorVideo video,
    required VideoQualityPreset qualityPreset,
    VideoOutputFormat outputFormat = VideoOutputFormat.mp4,
    Uint8List? imageBytes,
    ExportTransform? transform,
    bool enableAudio = true,
    double? playbackSpeed,
    Duration? startTime,
    Duration? endTime,
    double? blur,
    int? bitrateOverride,
    List<List<double>> colorMatrixList = const [],
    String? customAudioPath,
    double customAudioVolume = 1.0,
    Duration? customAudioStartTime,
    Duration? customAudioEndTime,
    double customAudioFadeInDuration = 0.0,
    double customAudioFadeOutDuration = 0.0,
    String? id,
  }) {
    final qualityConfig = VideoQualityConfig.fromPreset(qualityPreset);

    return RenderVideoModel(
      id: id,
      outputFormat: outputFormat,
      video: video,
      imageBytes: imageBytes,
      transform: transform,
      enableAudio: enableAudio,
      playbackSpeed: playbackSpeed,
      startTime: startTime,
      endTime: endTime,
      blur: blur,
      bitrate: bitrateOverride ?? qualityConfig.bitrate,
      colorMatrixList: colorMatrixList,
      qualityConfig: qualityConfig,
      customAudioPath: customAudioPath,
      customAudioVolume: customAudioVolume,
      customAudioStartTime: customAudioStartTime,
      customAudioEndTime: customAudioEndTime,
      customAudioFadeInDuration: customAudioFadeInDuration,
      customAudioFadeOutDuration: customAudioFadeOutDuration,
    );
  }

  /// Unique ID for the task, useful when running multiple tasks at once.
  final String id;

  /// Configuration class that defines video quality parameters.
  final VideoQualityConfig? qualityConfig;

  /// The target format for the exported video.
  final VideoOutputFormat outputFormat;

  /// A model that encapsulates various ways to load and represent a video.
  ///
  /// This class supports videos from in-memory bytes, file system, network,
  /// or asset bundle. It provides convenience methods for identifying the
  /// source type and safely retrieving video bytes.
  final EditorVideo video;

  /// A transparent image which will overlay the video.
  final Uint8List? imageBytes;

  /// Transformation settings like resize, rotation, offset, and flipping.
  ///
  /// Used to control how the video or image is positioned and modified during
  /// export.
  final ExportTransform? transform;

  /// Whether to include audio in the exported video.
  ///
  /// **Default**: `true`
  final bool enableAudio;

  /// Playback speed of the exported video.
  ///
  /// For example, `0.5` for half speed, `2.0` for double speed.
  final double? playbackSpeed;

  /// Optional start time for trimming the video.
  final Duration? startTime;

  /// Optional end time for trimming the video.
  final Duration? endTime;

  /// A 4x5 matrix used to apply color filters (e.g., saturation, brightness).
  final List<List<double>> colorMatrixList;

  /// Amount of blur to apply.
  ///
  /// Higher values result in a stronger blur effect.
  final double? blur;

  /// The bitrate of the video in bits per second.
  ///
  /// This value is optional and may be `null` if the bitrate is not specified.
  ///
  /// **WARNING Android:** Not all devices support CBR (Constant Bitrate) mode.
  /// If unsupported, the encoder may silently fall back to VBR
  /// (Variable Bitrate), and the actual bitrate may be constrained by
  /// device-specific minimum and maximum limits.
  ///
  /// **WARNING macOS iOS** It's not supported to directly set a specific
  /// bitrate, instant it will choose a preset which is the most near to the
  /// applied bitrate.
  final int? bitrate;
  /// If true, prefer H.264 for exports (when applicable).
  /// When not provided, plugin may choose the most appropriate codec.
  final bool preferH264;

  /// Path to custom audio file to replace/mix with video audio.
  ///
  /// If provided, this audio will replace the original video audio.
  /// Use [customAudioVolume], [customAudioStartTime], [customAudioEndTime],
  /// [customAudioFadeInDuration], and [customAudioFadeOutDuration] to control
  /// the audio properties.
  final String? customAudioPath;

  /// Volume level for custom audio (0.0 to 1.0).
  ///
  /// **Default**: `1.0` (100% volume)
  final double customAudioVolume;

  /// Optional start time for trimming custom audio.
  final Duration? customAudioStartTime;

  /// Optional end time for trimming custom audio.
  final Duration? customAudioEndTime;

  /// Fade in duration for custom audio in seconds.
  ///
  /// **Default**: `0.0` (no fade in)
  final double customAudioFadeInDuration;

  /// Fade out duration for custom audio in seconds.
  ///
  /// **Default**: `0.0` (no fade out)
  final double customAudioFadeOutDuration;

  /// Returns a [Stream] of [ProgressModel] objects that provides updates on
  /// the progress of the video rendering process associated with this model's
  /// [id].
  ///
  /// The stream is obtained from the [ProVideoEditor] singleton instance and
  /// is specific to the current video's identifier.
  Stream<ProgressModel> get progressStream {
    return ProVideoEditor.instance.progressStreamById(id);
  }

  /// Converts the model into a serializable map.
  Future<Map<String, dynamic>> toAsyncMap() async {
    var transform = this.transform ?? const ExportTransform();

    double? scaleX = transform.scaleX;
    double? scaleY = transform.scaleY;

    if (qualityConfig != null && scaleX == null && scaleY == null) {
      final meta = await ProVideoEditor.instance.getMetadata(video);
      final originalResolution = meta.resolution;
      final targetResolution = qualityConfig!.resolution ?? originalResolution;
      scaleX = targetResolution.width / originalResolution.width;
      scaleY = targetResolution.height / originalResolution.height;
    }

    String inputPath = await video.safeFilePath();

    return {
      ...transform.toMap(),
      'id': id,
      'inputPath': inputPath,
      'imageBytes': imageBytes,
      'enableAudio': enableAudio,
      'playbackSpeed': playbackSpeed,
      'startTime': startTime?.inMicroseconds,
      'endTime': endTime?.inMicroseconds,
      'colorMatrixList': colorMatrixList,
      'outputFormat': outputFormat.name,
      'blur': blur,
      'bitrate': bitrate,
      'scaleX': scaleX,
      'scaleY': scaleY,
      'customAudioPath': customAudioPath,
      'customAudioVolume': customAudioVolume,
      'customAudioStartTime': customAudioStartTime?.inMicroseconds,
      'customAudioEndTime': customAudioEndTime?.inMicroseconds,
      'customAudioFadeInDuration': customAudioFadeInDuration,
      'customAudioFadeOutDuration': customAudioFadeOutDuration,
      'preferH264': preferH264,
    };
  }

  /// Creates a copy with updated values.
  RenderVideoModel copyWith({
    String? id,
    VideoOutputFormat? outputFormat,
    EditorVideo? video,
    Uint8List? imageBytes,
    ExportTransform? transform,
    bool? enableAudio,
    double? playbackSpeed,
    Duration? startTime,
    Duration? endTime,
    List<List<double>>? colorMatrixList,
    double? blur,
    int? bitrate,
    VideoQualityConfig? qualityConfig,
    String? customAudioPath,
    double? customAudioVolume,
    Duration? customAudioStartTime,
    Duration? customAudioEndTime,
    double? customAudioFadeInDuration,
    double? customAudioFadeOutDuration,
    bool? preferH264,
  }) {
    return RenderVideoModel(
      id: id ?? this.id,
      outputFormat: outputFormat ?? this.outputFormat,
      video: video ?? this.video,
      imageBytes: imageBytes ?? this.imageBytes,
      transform: transform ?? this.transform,
      enableAudio: enableAudio ?? this.enableAudio,
      playbackSpeed: playbackSpeed ?? this.playbackSpeed,
      startTime: startTime ?? this.startTime,
      endTime: endTime ?? this.endTime,
      colorMatrixList: colorMatrixList ?? this.colorMatrixList,
      blur: blur ?? this.blur,
      bitrate: bitrate ?? this.bitrate,
      qualityConfig: qualityConfig ?? this.qualityConfig,
      customAudioPath: customAudioPath ?? this.customAudioPath,
      customAudioVolume: customAudioVolume ?? this.customAudioVolume,
      customAudioStartTime: customAudioStartTime ?? this.customAudioStartTime,
      customAudioEndTime: customAudioEndTime ?? this.customAudioEndTime,
      customAudioFadeInDuration: customAudioFadeInDuration ?? this.customAudioFadeInDuration,
      customAudioFadeOutDuration: customAudioFadeOutDuration ?? this.customAudioFadeOutDuration,
      preferH264: preferH264 ?? this.preferH264,
    );
  }
}

/// Supported video output formats for export.
enum VideoOutputFormat {
  /// MPEG-4 Part 14, widely supported.
  mp4,

  /// mov format.
  ///
  /// Only supported on macos and ios.
  mov,
}
