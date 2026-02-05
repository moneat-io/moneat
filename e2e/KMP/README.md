# Moneat KMP E2E Test App

This is a Kotlin Multiplatform (KMP) application for testing Moneat error tracking with various error scenarios.

## Setup

1. **Configure Sentry DSN** (for Android):
   ```bash
   cp local.properties.example local.properties
   # Edit local.properties and add your Moneat DSN
   ```

2. **Build the Android app**:
   ```bash
   ./gradlew :composeApp:assembleDebug
   ```

3. **Install and run Android**:
   ```bash
   ./gradlew :composeApp:installDebug
   adb shell am start -n com.moneat.e2e.kmp/.MainActivity
   ```

4. **Build iOS** (requires Xcode on macOS):
   ```bash
   cd iosApp
   xcodebuild
   ```

## Error Scenarios

The app provides buttons to trigger various error types:

- **Trigger Crash**: Uncaught RuntimeException that crashes the app
- **Throw Exception**: Caught IllegalStateException with context
- **Network Error**: SocketTimeoutException with network context
- **Background Crash**: Crash from a background thread
- **Null Pointer**: NullPointerException with error context

## Configuration

The Android app is configured to:
- Environment: `e2e-testing`
- Release: `kmp-e2e@1.0.0`
- Debug mode: `true`
- Session tracking: `enabled`
- Tags: `platform=kmp-android`, `test_type=e2e`

## Testing

1. Start the app on Android or iOS
2. Click different error buttons
3. Check Moneat dashboard for captured events (Android only currently)
4. Verify error grouping, stack traces, and context data

## Note

Currently only Android has full Sentry integration. iOS implementation is stubbed and would need iOS Sentry SDK configuration.
