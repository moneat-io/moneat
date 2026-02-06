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

## Performance Scenarios

The app also provides deterministic transaction/span scenarios for performance testing:

- **Transaction (Successful)**: Sends a normal transaction with nested `db.query`, `http.client`, and `ui.render` spans
- **Transaction (Slow)**: Sends a long-running transaction (~1.9s) for p95/slowest-transaction validation
- **Transaction (Failed + Related Error)**: Sends a failed transaction and captures an error on the same trace

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
5. Open the Performance page and verify:
   - transaction groups are listed
   - slow transaction appears in slowest list
   - transaction detail shows span waterfall
   - failed transaction shows related error in the same trace
