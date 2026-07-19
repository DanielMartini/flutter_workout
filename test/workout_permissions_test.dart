import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:workout/workout.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('workout');
  late MethodCall lastCall;

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      channel,
      (call) async {
        lastCall = call;
        return true;
      },
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('health permission checks forward the requested data categories',
      () async {
    final granted = await WorkoutPermissions.hasHealthPermissions(
      heartRate: false,
      activityRecognition: true,
    );

    expect(granted, isTrue);
    expect(lastCall.method, 'hasHealthPermissions');
    expect(lastCall.arguments, {
      'heartRate': false,
      'activityRecognition': true,
    });
  });

  test('fine location requests use the native permission API', () async {
    final granted = await WorkoutPermissions.requestFineLocationPermission();

    expect(granted, isTrue);
    expect(lastCall.method, 'requestFineLocationPermission');
    expect(lastCall.arguments, isNull);
  });
}
