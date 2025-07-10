import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:workout/workout.dart';
import 'package:flutter_tizen/flutter_tizen.dart' as tizen;

/// Represents a single health metric reading.
class HealthMetricReading {
  final WorkoutFeature feature;
  final double value;
  final int timestamp; // Timestamp en milisegundos desde la época

  HealthMetricReading(this.feature, this.value, this.timestamp);

  @override
  String toString() => 'HealthMetricReading(feature: $feature, value: $value, timestamp: $timestamp)';
}

/// Base class for flutter_workout
class Workout {
  static const _channel = MethodChannel('workout');

  final _streamController = StreamController<Map<String, HealthMetricReading>>.broadcast();

  var _currentFeatures = <WorkoutFeature>[];

  /// A stream of [HealthMetricReading]s collected by the workout session,
  /// delivered as a map of all updated features.
  Stream<Map<String, HealthMetricReading>> get stream => _streamController.stream;

  /// Create a [Workout]
  Workout() {
    _channel.setMethodCallHandler(_handleMessage);
  }

  /// Wear OS: The supported [ExerciseType]s of the device
  ///
  /// Tizen: Always empty
  ///
  /// iOS: Always empty
  Future<List<ExerciseType>> getSupportedExerciseTypes() async {
    if (!Platform.isAndroid) return [];

    final result =
    await _channel.invokeListMethod<int>('getSupportedExerciseTypes');

    final types = <ExerciseType>[];
    for (final id in result!) {
      final type = ExerciseType.fromId(id);
      if (type == null) {
        debugPrint(
          'Unknown ExerciseType id: $id. Please create an issue for this on GitHub.',
        );
      } else {
        types.add(type);
      }
    }

    return types;
  }

  /// Starts a workout session with the specified [features] enabled
  ///
  /// [exerciseType] has no effect on Tizen
  ///
  /// [enableGps] allows location information to be used to estimate
  /// distance/speed instead of steps. Will request location permission.
  /// Only available on Wear OS.
  ///
  /// [locationType], [swimmingLocationType], and [lapLength] are iOS only
  ///
  /// [lapLength] is the length of the pool in meters
  ///
  /// **[updateIntervalMillis]**: The minimum interval in milliseconds between updates
  /// sent from the native platform to Flutter. Defaults to 500ms.
  /// Set to 0 to receive all updates as they come (not recommended for UI).
  ///
  /// iOS: Calls `startWatchApp` with the given configuration. Requires both
  /// apps to have the `HealthKit` entitlement. The watch app must have the
  /// `Workout Processing` background mode enabled.
  Future<WorkoutStartResult> start({
    required ExerciseType exerciseType,
    required List<WorkoutFeature> features,
    bool enableGps = false,
    int updateIntervalMillis = 500,
    WorkoutLocationType? locationType,
    WorkoutSwimmingLocationType? swimmingLocationType,
    double? lapLength,
  }) {
    _currentFeatures = features;

    if (Platform.isAndroid) {
      return _initWearOS(
        exerciseType: exerciseType,
        enableGps: enableGps,
        updateIntervalMillis: updateIntervalMillis,
      );
    } else if (Platform.isIOS) {
      return _initIos(
        exerciseType: exerciseType,
        locationType: locationType,
        swimmingLocationType: swimmingLocationType,
        lapLength: lapLength,
      );
    } else if (tizen.isTizen) {
      return _initTizen();
    } else {
      throw UnsupportedError('Unsupported platform');
    }
  }

  Future<WorkoutStartResult> _initWearOS({
    required ExerciseType exerciseType,
    required bool enableGps,
    required int updateIntervalMillis,
  }) async {
    final sensors = <String>[];

    if (_currentFeatures.contains(WorkoutFeature.heartRate)) {
      final status = await Permission.sensors.request();
      if (status.isGranted) {
        sensors.add(WorkoutFeature.heartRate.name);
      }
    }

    final activityRecognitionFeatures = {
      WorkoutFeature.calories,
      WorkoutFeature.steps,
      WorkoutFeature.distance,
      WorkoutFeature.speed,
    };
    final requestedActivityRecognitionFeatures =
    _currentFeatures.toSet().intersection(activityRecognitionFeatures);

    if (requestedActivityRecognitionFeatures.isNotEmpty) {
      final status = await Permission.activityRecognition.request();
      if (status.isGranted) {
        sensors.addAll(requestedActivityRecognitionFeatures.map((e) => e.name));
      }
    }

    if (enableGps) {
      final status = await Permission.location.request();
      if (!status.isGranted) {
        enableGps = false;
      }
    }

    return _start(
      exerciseType: exerciseType,
      sensors: sensors,
      enableGps: enableGps,
      updateIntervalMillis: updateIntervalMillis,
    );
  }

  Future<WorkoutStartResult> _initIos({
    required ExerciseType exerciseType,
    required WorkoutLocationType? locationType,
    required WorkoutSwimmingLocationType? swimmingLocationType,
    required double? lapLength,
  }) {
    return _start(
      exerciseType: exerciseType,
      locationType: locationType,
      swimmingLocationType: swimmingLocationType,
      lapLength: lapLength,
    );
  }

  Future<WorkoutStartResult> _initTizen() async {
    final sensors = <String>[];
    final status = await Permission.sensors.request();
    if (status.isGranted) {
      if (_currentFeatures.contains(WorkoutFeature.heartRate)) {
        sensors.add(WorkoutFeature.heartRate.name);
      }
      if (_currentFeatures.contains(WorkoutFeature.calories) ||
          _currentFeatures.contains(WorkoutFeature.steps) ||
          _currentFeatures.contains(WorkoutFeature.distance) ||
          _currentFeatures.contains(WorkoutFeature.speed)) {
        sensors.add('pedometer');
      }
    }

    return _start(sensors: sensors);
  }

  Future<WorkoutStartResult> _start({
    ExerciseType? exerciseType,
    List<String> sensors = const [],
    bool enableGps = false,
    int updateIntervalMillis = 500,
    WorkoutLocationType? locationType,
    WorkoutSwimmingLocationType? swimmingLocationType,
    double? lapLength,
  }) async {
    final result = await _channel.invokeMapMethod<String, dynamic>(
      'start',
      {
        'exerciseType': exerciseType?.id,
        'sensors': sensors,
        'enableGps': enableGps,
        'updateIntervalMillis': updateIntervalMillis, // Lo enviamos a Kotlin
        'locationType': locationType?.id,
        'swimmingLocationType': swimmingLocationType?.id,
        'lapLength': lapLength,
      },
    );
    return WorkoutStartResult.fromResult(result);
  }

  /// Stops the workout session and sensor data collection
  Future<void> stop() {
    return _channel.invokeMethod<void>('stop');
  }

  Future<dynamic> _handleMessage(MethodCall call) {
    if (call.method == 'dataReceived') {
      try {
        final Map<String, dynamic> healthDataMap = Map<String, dynamic>.from(call.arguments);

        final Map<String, HealthMetricReading> currentReadings = {};

        healthDataMap.forEach((featureString, data) {
          final Map<String, dynamic> metricData = Map<String, dynamic>.from(data);
          final double value = metricData['value'] as double;
          final int timestamp = metricData['timestamp'] as int;

          if (_currentFeatures.map((e) => e.name).contains(featureString)) {
            final feature = WorkoutFeature.values.byName(featureString);
            currentReadings[featureString] = HealthMetricReading(feature, value, timestamp);
          }
        });

        if (currentReadings.isNotEmpty) {
          _streamController.add(currentReadings);
        }
        return Future.value();
      } catch (e) {
        debugPrint('Error processing dataReceived: $e');
        return Future.error(e);
      }
    }
    return Future.value();
  }
}