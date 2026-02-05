# Moneat Android E2E Test App

This is an Android application for testing Moneat error tracking with various error scenarios.

## Setup

1. **Configure Sentry DSN**:
   ```bash
   cp local.properties.example local.properties
   # Edit local.properties and add your Moneat DSN
   ```

2. **Build the app**:
   ```bash
   ./gradlew assembleDebug
   ```

3. **Install and run**:
   ```bash
   ./gradlew installDebug
   adb shell am start -n com.moneat.e2e.android/.MainActivity
   ```

## Error Scenarios

The app provides buttons to trigger various error types:

- **Trigger Crash**: Uncaught RuntimeException that crashes the app
- **Throw Exception**: Caught IllegalStateException with context
- **Network Error**: SocketTimeoutException with network context
- **Simulate ANR**: Freezes UI thread for 10 seconds
- **Background Crash**: Crash from a background thread
- **Null Pointer**: NullPointerException with error context

## Configuration

The app is configured to:
- Environment: `e2e-testing`
- Release: `android-e2e@1.0.0`
- Debug mode: `true`
- Session tracking: `enabled`
- Tags: `platform=android`, `test_type=e2e`

## Testing

1. Start the app
2. Click different error buttons
3. Check Moneat dashboard for captured events
4. Verify error grouping, stack traces, and context data
