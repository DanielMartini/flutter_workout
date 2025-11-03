import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:workout/workout.dart';
import 'package:flutter_tizen/flutter_tizen.dart' as tizen;

/// Representa una lectura de métrica de salud.
class HealthMetricReading {
  final WorkoutFeature feature;
  final double value;
  final int timestamp;

  HealthMetricReading(this.feature, this.value, this.timestamp);

  @override
  String toString() => 'HealthMetricReading($feature: $value @ $timestamp)';

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
          other is HealthMetricReading &&
              runtimeType == other.runtimeType &&
              feature == other.feature &&
              value == other.value &&
              timestamp == other.timestamp;

  @override
  int get hashCode => feature.hashCode ^ value.hashCode ^ timestamp.hashCode;
}

/// Clase base para flutter_workout - Ultra optimizada
class Workout {
  static const _channel = MethodChannel('workout');

  // Stream optimizado: broadcast + buffer de 1
  final _streamController = StreamController<Map<String, HealthMetricReading>>.broadcast(
    onListen: () => debugPrint('Workout stream: listener added'),
    onCancel: () => debugPrint('Workout stream: listener removed'),
  );

  var _currentFeatures = <WorkoutFeature>[];

  // Cache del último estado enviado (evitar duplicados)
  Map<String, HealthMetricReading>? _lastEmittedReadings;

  // Throttling adicional en Flutter
  int _lastEmitTime = 0;
  final int _minEmitIntervalMs = 500; // Mínimo 500ms entre emisiones en Flutter

  // Estadísticas
  int _receivedUpdates = 0;
  int _emittedUpdates = 0;

  Stream<Map<String, HealthMetricReading>> get stream => _streamController.stream;

  Workout() {
    _channel.setMethodCallHandler(_handleMessage);
  }

  Future<List<ExerciseType>> getSupportedExerciseTypes() async {
    if (!Platform.isAndroid) return [];

    final result = await _channel.invokeListMethod<int>('getSupportedExerciseTypes');

    final types = <ExerciseType>[];
    for (final id in result!) {
      final type = ExerciseType.fromId(id);
      if (type == null) {
        debugPrint('Unknown ExerciseType id: $id');
      } else {
        types.add(type);
      }
    }

    return types;
  }

  /// Inicia una sesión de entrenamiento optimizada
  ///
  /// [updateIntervalMillis]: Intervalo mínimo entre actualizaciones (default: 2000ms = 2s)
  /// Valores recomendados:
  /// - Monitoreo normal: 2000-3000ms
  /// - Bajo consumo: 5000ms o más
  /// - Alta precisión: 1000ms (no recomendado para batería)
  Future<WorkoutStartResult> start({
    required ExerciseType exerciseType,
    required List<WorkoutFeature> features,
    bool enableGps = false,
    int updateIntervalMillis = 2000, // Default aumentado a 2s
    WorkoutLocationType? locationType,
    WorkoutSwimmingLocationType? swimmingLocationType,
    double? lapLength,
  }) {
    _currentFeatures = features;

    // Reset de estadísticas
    _receivedUpdates = 0;
    _emittedUpdates = 0;
    _lastEmittedReadings = null;
    _lastEmitTime = 0;

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
    int updateIntervalMillis = 2000,
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
        'updateIntervalMillis': updateIntervalMillis,
        'locationType': locationType?.id,
        'swimmingLocationType': swimmingLocationType?.id,
        'lapLength': lapLength,
      },
    );
    return WorkoutStartResult.fromResult(result);
  }

  Future<void> stop() async {
    await _channel.invokeMethod<void>('stop');

    // Log de estadísticas finales
    if (_receivedUpdates > 0) {
      final efficiency = (_receivedUpdates - _emittedUpdates) * 100.0 / _receivedUpdates;
      debugPrint('Workout stats - Received: $_receivedUpdates, Emitted: $_emittedUpdates, '
          'Filtered: ${efficiency.toStringAsFixed(1)}%');
    }
  }

  /// Obtiene estadísticas del plugin nativo (solo Android)
  Future<Map<String, dynamic>?> getStats() async {
    if (!Platform.isAndroid) return null;
    try {
      return await _channel.invokeMapMethod<String, dynamic>('getStats');
    } catch (e) {
      return null;
    }
  }

  Future<dynamic> _handleMessage(MethodCall call) async {
    if (call.method == 'dataReceived') {
      _receivedUpdates++;

      try {
        final currentTime = DateTime.now().millisecondsSinceEpoch;

        // FILTRO #1: Throttling adicional en Flutter (capa de seguridad)
        if (_lastEmitTime != 0 && currentTime - _lastEmitTime < _minEmitIntervalMs) {
          return; // Silenciosamente ignorar
        }

        final Map<String, dynamic> healthDataMap = Map<String, dynamic>.from(call.arguments);

        // FILTRO #2: Verificar que hay datos relevantes
        if (healthDataMap.isEmpty) {
          return;
        }

        final Map<String, HealthMetricReading> currentReadings = {};
        final requestedFeatures = _currentFeatures.map((e) => e.name).toSet();

        for (final entry in healthDataMap.entries) {
          final featureString = entry.key;

          // Solo procesar features solicitadas
          if (!requestedFeatures.contains(featureString)) {
            continue;
          }

          final Map<String, dynamic> metricData = Map<String, dynamic>.from(entry.value);
          final double value = (metricData['value'] as num).toDouble();
          final int timestamp = metricData['timestamp'] as int;

          final feature = WorkoutFeature.values.byName(featureString);
          currentReadings[featureString] = HealthMetricReading(feature, value, timestamp);
        }

        // FILTRO #3: Verificar cambios significativos vs último emitido
        if (currentReadings.isEmpty || !_hasSignificantChanges(currentReadings)) {
          return;
        }

        // Actualizar cache y timestamp
        _lastEmittedReadings = Map.from(currentReadings);
        _lastEmitTime = currentTime;
        _emittedUpdates++;

        // Emitir al stream
        _streamController.add(currentReadings);

      } catch (e, stack) {
        debugPrint('Error processing dataReceived: $e');
        debugPrint('Stack: $stack');
      }
    }
  }

  /// Verifica si hay cambios significativos vs la última emisión
  bool _hasSignificantChanges(Map<String, HealthMetricReading> newReadings) {
    final lastReadings = _lastEmittedReadings;

    // Primera emisión siempre se envía
    if (lastReadings == null || lastReadings.isEmpty) {
      return true;
    }

    // Verificar si algún valor cambió significativamente
    for (final entry in newReadings.entries) {
      final feature = entry.key;
      final newReading = entry.value;
      final lastReading = lastReadings[feature];

      // Nueva feature
      if (lastReading == null) {
        return true;
      }

      // Verificar cambio significativo
      final threshold = _getThreshold(newReading.feature);
      if ((newReading.value - lastReading.value).abs() >= threshold) {
        return true;
      }
    }

    return false;
  }

  /// Umbrales de cambio significativo por feature
  double _getThreshold(WorkoutFeature feature) {
    switch (feature) {
      case WorkoutFeature.heartRate:
        return 2.0; // ±2 BPM
      case WorkoutFeature.calories:
        return 2.0; // ±2 kcal
      case WorkoutFeature.steps:
        return 2.0; // ±2 pasos
      case WorkoutFeature.distance:
        return 5.0; // ±10 metros
      case WorkoutFeature.speed:
        return 0.1; // ±0.3 m/s
      default:
        return 0.5;
    }
  }

  void dispose() {
    _streamController.close();
  }
}