# workout

Run a workout session and get live health data from Wear OS and Tizen. Also start a watchOS app from iOS.

## Getting Started

### Wear OS

Health Services for Wear OS are currently in beta

android/app/build.gradle:

`minSdkVersion 30`

The plugin contributes the required permissions to the merged Android manifest,
including `READ_HEART_RATE` for apps targeting API 36 or newer.

Request access from the host app after explaining why it is needed:

```dart
final healthGranted = await WorkoutPermissions.requestHealthPermissions();
final locationGranted = await WorkoutPermissions.requestFineLocationPermission();
```

Permission checks are also available without showing a system dialog:

```dart
final hasHealth = await WorkoutPermissions.hasHealthPermissions();
final hasFineLocation = await WorkoutPermissions.hasFineLocationPermission();
```

The permissions merged by the plugin are:

```xml
<uses-permission android:name="android.permission.BODY_SENSORS" android:maxSdkVersion="35" />
<uses-permission android:name="android.permission.health.READ_HEART_RATE" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

### Tizen

This plugin requires Tizen 4.0+.

Make the following changes to `tizen/tizen-manifest.xml`:

```
<manifest api-version="4.0" ...>
    <privileges>
        <privilege>http://tizen.org/privilege/healthinfo</privilege>
    </privileges>
    <feature name="http://tizen.org/feature/sensor.heart_rate_monitor">true</feature>
    <feature name="http://tizen.org/feature/sensor.pedometer">true</feature>
</manifest>
```

### iOS

Flutter cannot run on watchOS, but there is a method on iOS to start the watch app. Calling the `start` method on iOS will call `startWatchApp` with the given parameters. The following requirements must be met for this to function:

- Both the phone and watch apps must have the `HealthKit` entitlement
- The watch app must have the `Workout Processing` background mode enabled

## Supported data types

| Feature    | Wear OS | Tizen |
| ---------- | ------- | ----- |
| Heart rate | Yes     | Yes   |
| Calories   | Yes     | Yes   |
| Step count | Yes     | Yes   |
| Speed      | Yes     | Yes   |
| Distance   | Yes     | Yes   |
