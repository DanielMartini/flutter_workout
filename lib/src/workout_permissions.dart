import 'package:flutter/services.dart';

/// Android runtime permissions required by Health Services on Wear OS.
///
/// Permission prompts are intentionally separate from [Workout.start] so the
/// host app can explain why access is needed before showing the system dialog.
class WorkoutPermissions {
  static const MethodChannel _channel = MethodChannel('workout');

  static Future<bool> hasHealthPermissions({
    bool heartRate = true,
    bool activityRecognition = true,
  }) {
    return _invokeBool('hasHealthPermissions', {
      'heartRate': heartRate,
      'activityRecognition': activityRecognition,
    });
  }

  static Future<bool> requestHealthPermissions({
    bool heartRate = true,
    bool activityRecognition = true,
  }) {
    return _invokeBool('requestHealthPermissions', {
      'heartRate': heartRate,
      'activityRecognition': activityRecognition,
    });
  }

  /// Must be checked after a denied request, when Android can distinguish a
  /// normal denial from a permission that can only be changed in settings.
  static Future<bool> isAnyHealthPermissionPermanentlyDenied({
    bool heartRate = true,
    bool activityRecognition = true,
  }) {
    return _invokeBool('isAnyHealthPermissionPermanentlyDenied', {
      'heartRate': heartRate,
      'activityRecognition': activityRecognition,
    });
  }

  static Future<bool> hasFineLocationPermission() {
    return _invokeBool('hasFineLocationPermission');
  }

  static Future<bool> requestFineLocationPermission() {
    return _invokeBool('requestFineLocationPermission');
  }

  static Future<bool> _invokeBool(String method,
      [Map<String, Object?>? arguments]) async {
    return await _channel.invokeMethod<bool>(method, arguments) ?? false;
  }
}
