# Android Manual Validation Checklist

This checklist validates Android SDK integration and event ingestion correctness. Run before each release.

## Prerequisites

- [ ] Moneat backend services running (`docker-compose up -d`)
- [ ] E2E data seeded (`./seed-data.sh`)
- [ ] Android emulator or physical device ready

## Setup

1. Start the Android E2E app:
   ```bash
   cd e2e
   ./run-android.sh
   ```

2. Wait for app to launch on device/emulator

3. Login to Moneat dashboard:
   - URL: http://localhost:5173 (or production URL)
   - Email: `e2e-test@moneat.dev`
   - Password: `e2e-test-password`

4. Navigate to "Android E2E App" project

## Test Scenarios

### 1. Uncaught Exception (Crash)
- [ ] **Action**: Tap "Trigger Uncaught Exception" button in app
- [ ] **Expected**: App crashes and restarts
- [ ] **Verify in Dashboard**:
  - [ ] New issue appears in issues list within 10 seconds
  - [ ] Issue title contains exception type (e.g., `RuntimeException`)
  - [ ] Stack trace shows correct file/line number
  - [ ] Event count increments if triggered multiple times
  - [ ] Issue is grouped correctly (same error = same issue)

**Pass/Fail**: _____ | **Notes**: _________________________________

---

### 2. Caught Exception with Context
- [ ] **Action**: Tap "Trigger Caught Exception" button
- [ ] **Expected**: App logs error but does not crash
- [ ] **Verify in Dashboard**:
  - [ ] Issue appears with severity level "error"
  - [ ] Tags include custom metadata (e.g., `test-scenario: caught-exception`)
  - [ ] User context is attached (user ID, email if available)
  - [ ] Breadcrumbs show events leading up to error

**Pass/Fail**: _____ | **Notes**: _________________________________

---

### 3. Network Error
- [ ] **Action**: Tap "Trigger Network Error" button
- [ ] **Expected**: App simulates failed HTTP request
- [ ] **Verify in Dashboard**:
  - [ ] Issue appears with network-related metadata
  - [ ] Extra data includes URL, status code, request headers
  - [ ] Error message clearly indicates network failure

**Pass/Fail**: _____ | **Notes**: _________________________________

---

### 4. ANR (Application Not Responding)
- [ ] **Action**: Tap "Simulate ANR" button
- [ ] **Expected**: App freezes for several seconds
- [ ] **Verify in Dashboard**:
  - [ ] ANR event is captured (may take up to 30s to appear)
  - [ ] Stack trace shows blocking call location
  - [ ] Event type is marked as ANR/performance issue

**Pass/Fail**: _____ | **Notes**: _________________________________

---

### 5. Background Thread Crash
- [ ] **Action**: Tap "Background Thread Crash" button
- [ ] **Expected**: App may or may not visibly crash (background thread)
- [ ] **Verify in Dashboard**:
  - [ ] Issue appears with stack trace from background thread
  - [ ] Thread name is captured in metadata

**Pass/Fail**: _____ | **Notes**: _________________________________

---

### 6. Null Pointer Exception
- [ ] **Action**: Tap "Null Pointer Exception" button
- [ ] **Expected**: App crashes with NPE
- [ ] **Verify in Dashboard**:
  - [ ] Issue type is `NullPointerException`
  - [ ] Stack trace points to exact line causing NPE
  - [ ] Grouping works (multiple NPE triggers = same issue)

**Pass/Fail**: _____ | **Notes**: _________________________________

---

### 7. Performance Transaction (Success)
- [ ] **Action**: Tap "Successful Transaction" button
- [ ] **Expected**: App completes simulated operation
- [ ] **Verify in Dashboard**:
  - [ ] Navigate to "Performance" tab
  - [ ] Transaction appears in transaction list
  - [ ] Child spans are visible in waterfall view
  - [ ] Duration is reasonable (e.g., 100-500ms)

**Pass/Fail**: _____ | **Notes**: _________________________________

---

### 8. Slow Transaction Profile
- [ ] **Action**: Tap "Slow Transaction" button
- [ ] **Expected**: App performs slow operation
- [ ] **Verify in Dashboard**:
  - [ ] Transaction marked as slow (>1s)
  - [ ] Span waterfall shows which operation was slow
  - [ ] Performance metrics are accurate

**Pass/Fail**: _____ | **Notes**: _________________________________

---

### 9. Failed Transaction with Related Error
- [ ] **Action**: Tap "Failed Transaction" button
- [ ] **Expected**: App simulates operation failure
- [ ] **Verify in Dashboard**:
  - [ ] Transaction appears in Performance tab with failed status
  - [ ] Related error is linked in trace context
  - [ ] Error event shows same trace ID

**Pass/Fail**: _____ | **Notes**: _________________________________

---

## Additional Checks

### Event Grouping
- [ ] Multiple triggers of same error type group into one issue
- [ ] Event count increments correctly
- [ ] "Last seen" timestamp updates

### User Tracking
- [ ] User ID is captured across events
- [ ] Multiple events from same user are linked
- [ ] "Affected Users" count is accurate

### Metadata Accuracy
- [ ] OS version is correct (Android version)
- [ ] Device model is captured
- [ ] App version matches E2E app version
- [ ] Environment tag is set to expected value

### Dashboard Performance
- [ ] Issues load in <2 seconds
- [ ] Stack traces render correctly (no Unicode issues)
- [ ] Filtering/sorting works
- [ ] Issue detail page loads without errors

## Sign-Off

**Tested By**: _____________________ | **Date**: ___________

**Backend Version**: ___________ | **Dashboard Version**: ___________

**Overall Status**: PASS / FAIL / PARTIAL

**Critical Issues Found**:
1. _____________________________________________________________
2. _____________________________________________________________
3. _____________________________________________________________

**Notes**:
___________________________________________________________________
___________________________________________________________________
___________________________________________________________________

## Troubleshooting

**Events not appearing in dashboard?**
- Check backend logs: `docker-compose logs backend | grep ingest`
- Verify project ID matches DSN in `Android/local.properties`
- Check ClickHouse is running: `docker-compose ps clickhouse`

**App won't build?**
- Ensure Android SDK installed: `echo $ANDROID_HOME`
- Clean and rebuild: `cd Android && ./gradlew clean build`

**DSN not configured?**
- Run `./seed-data.sh` and copy DSN to `Android/local.properties`

## Automation Future

This manual checklist is a release gate. Future work:
- Automate crash detection via `adb logcat`
- Headless Android emulator in CI
- Automated validation of dashboard API responses
