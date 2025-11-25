import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:mime/mime.dart';

import '/core/models/video/editor_video_model.dart';
import 'core/models/thumbnail/key_frames_configs.model.dart';
import 'core/models/thumbnail/thumbnail_base.abstract.dart';
import 'core/models/thumbnail/thumbnail_configs.model.dart';
import 'core/models/video/progress_model.dart';
import 'core/models/video/render_video_model.dart';
import 'core/models/video/video_metadata_model.dart';
import 'core/platform/io/io_helper.dart';
import 'pro_video_editor_platform_interface.dart';

/// An implementation of [ProVideoEditor] that uses method channels.
class MethodChannelProVideoEditor extends ProVideoEditor {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('pro_video_editor');
  final _progressChannel = const EventChannel('pro_video_editor_progress');

  @override
  Future<String?> getPlatformVersion() async {
    final version =
        await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }

  @override
  Future<VideoMetadata> getMetadata(EditorVideo value) async {
    var inputPath = await value.safeFilePath();

    var extension = _getFileExtension(inputPath);

    final response =
        await methodChannel.invokeMethod<Map<dynamic, dynamic>>('getMetadata', {
              'inputPath': inputPath,
              'extension': extension,
            }) ??
            {};

    return VideoMetadata.fromMap(response, extension);
  }

  Future<List<Uint8List>> _extractThumbnails(ThumbnailBase value) async {
    var inputPath = await value.video.safeFilePath();

    final response = await methodChannel.invokeMethod<List<dynamic>>(
      'getThumbnails',
      {
        'inputPath': inputPath,
        'extension': _getFileExtension(inputPath),
        ...value.toMap(),
      },
    );
    final List<Uint8List> result = response?.cast<Uint8List>() ?? [];

    return result;
  }

  @override
  Future<List<Uint8List>> getThumbnails(ThumbnailConfigs value) async {
    return await _extractThumbnails(value);
  }

  @override
  Future<List<Uint8List>> getKeyFrames(KeyFramesConfigs value) async {
    return await _extractThumbnails(value);
  }

  @override
  Future<Uint8List> renderVideo(RenderVideoModel value) async {
    final renderData = await value.toAsyncMap();

    var extension = _getFileExtension(renderData['inputPath']);

    final Uint8List? result = await methodChannel.invokeMethod<Uint8List>(
      'renderVideo',
      {
        ...renderData,
        'inputFormat': extension,
      },
    );

    if (result == null) {
      throw ArgumentError('Failed to export the video');
    }

    return result;
  }

  @override
  Future<bool> cancelRender(String taskId) async {
    try {
      final result = await methodChannel.invokeMethod<bool>('cancelRender', {'id': taskId});
      return result == true;
    } catch (e) {
      if (kDebugMode) debugPrint('cancelRender failed: $e');
      return false;
    }
  }

  @override
  Future<String> renderVideoToFile(
    String filePath,
    RenderVideoModel value,
  ) async {
    final renderData = await value.toAsyncMap();
    final inputPath = await value.video.safeFilePath();

    var extension = _getFileExtension(renderData['inputPath']);

    await methodChannel.invokeMethod<String>(
      'renderVideo',
      {
        ...renderData,
        'inputFormat': extension,
        'inputPath': inputPath,
        'outputPath': filePath,
      },
    );

    return filePath;
  }

  @override
  Future<String> concatenateVideos({
    required List<String> inputPaths,
    required String outputPath,
    String? taskId,
  }) async {
    final result = await methodChannel.invokeMethod<String>(
      'concatenateVideos',
      {
        'id': taskId ?? 'concatenate_${DateTime.now().millisecondsSinceEpoch}',
        'inputPaths': inputPaths,
        'outputPath': outputPath,
      },
    );

    if (result == null) {
      throw ArgumentError('Failed to concatenate videos');
    }

    return result;
  }

  @override
  Future<String> generateSlideshow({
    required List<Map<String, dynamic>> slides,
    required String outputPath,
    int width = 1920,
    int height = 1080,
    int fps = 30,
    String? audioPath,
    String? taskId,
  }) async {
    final result = await methodChannel.invokeMethod<String>(
      'generateSlideshow',
      {
        'id': taskId ?? 'slideshow_${DateTime.now().millisecondsSinceEpoch}',
        'slides': slides,
        'outputPath': outputPath,
        'width': width,
        'height': height,
        'fps': fps,
        'audioPath': audioPath,
      },
    );

    if (result == null) {
      throw ArgumentError('Failed to generate slideshow');
    }

    return result;
  }

  @override
  void initializeStream() {
    if (!kIsWeb && (Platform.isWindows || Platform.isLinux)) return;
    _progressChannel.receiveBroadcastStream().map((event) {
      try {
        return ProgressModel.fromMap(event);
      } catch (e, stack) {
        debugPrint('Error in fromMap: $e\n$stack');
        return const ProgressModel(id: 'error', progress: 0);
      }
    }).listen(progressCtrl.add);
  }

  String _getFileExtension(String inputPath) {
    var mimeType = lookupMimeType(inputPath);
    var mimeSp = mimeType?.split('/') ?? [];
    var extension = mimeSp.length == 2 ? mimeSp[1] : 'mp4';

    return extension;
  }
}
