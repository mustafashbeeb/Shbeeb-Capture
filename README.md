# Shbeeb-Capture
Professional Android screen recorder and screenshot app
# Shbeeb Capture

Shbeeb Capture is an Android screen recorder and screenshot app prototype.

## Current MVP

- Screen recording with Android MediaProjection
- Screenshot capture
- Microphone audio option
- Recording stop notification and foreground service
- Arabic RTL interface
- Debug APK build through GitHub Actions

## Build

Open the project in Android Studio with JDK 17 and Android SDK 35, then run:

```shell
./gradlew assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Audio

Android limits system audio capture. The prototype records microphone audio. Full system/internal audio or combined microphone and system audio requires a device-compatible implementation and may vary by Android version and OEM.
