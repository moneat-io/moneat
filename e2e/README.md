# Moneat E2E Testing Environment

End-to-end testing setup for Moneat with sample Android and KMP applications.

## Overview

This directory contains:
- **Android**: Native Android app with error testing scenarios
- **KMP**: Kotlin Multiplatform app with shared error testing code
- Sample data seeder to populate realistic test data

## Quick Start

1. **Start Moneat services**:
   ```bash
   cd ..
   docker-compose up -d
   ```

2. **Seed test data**:
   ```bash
   ./seed-data.sh
   ```
   This creates:
   - Test users (e2e-test@moneat.dev, etc.)
   - E2E Testing Organization
   - Two projects: "Android E2E App" and "KMP E2E App"
   - Project keys with DSNs

3. **Configure apps with DSNs**:
   Copy the DSNs from the seed output to:
   - `Android/local.properties`
   - `KMP/local.properties`

4. **Run Android app**:
   ```bash
   ./run-android.sh
   ```

5. **Run KMP app** (Android):
   ```bash
   ./run-kmp.sh
   ```

## Test Scenarios

Both apps include buttons to trigger:
- **Uncaught exceptions** (crashes)
- **Caught exceptions** with context
- **Network errors** with metadata
- **ANR simulation** (Android only)
- **Background thread crashes**
- **Null pointer exceptions**

Android app also includes:
- **Successful transaction + child spans**
- **Slow transaction profile**
- **Failed transaction with related error on same trace**

## Test Users

After seeding, you can login with:
- Email: `e2e-test@moneat.dev`
- Password: `e2e-test-password`

## Project Structure

```
e2e/
├── Android/                      # Native Android E2E app
├── KMP/                          # Kotlin Multiplatform E2E app
├── web/                          # Playwright web E2E tests
├── setup.sh                      # Initialize E2E environment
├── seed-data.sh                  # Seed database with test data
├── run-android.sh                # Build and run Android app
├── run-kmp.sh                    # Build and run KMP app (Android)
├── ANDROID_MANUAL_CHECKLIST.md  # Release validation checklist
└── README.md                     # This file
```

## Testing Workflow

### Web E2E Tests (Automated)
```bash
cd web
npm install
npm run test:smoke  # Run critical-path tests
```
See `web/README.md` for details.

### Mobile Manual Testing
1. Start the apps
2. Click different error trigger buttons
3. Check the Moneat dashboard at http://localhost:5173
4. Login with e2e-test@moneat.dev
5. Navigate to projects and view captured errors

**For release validation**, use the comprehensive checklist:
- `ANDROID_MANUAL_CHECKLIST.md` - Step-by-step validation guide

Verify:
   - Error grouping (same error type = same issue)
   - Stack traces are captured
   - Context data (tags, breadcrumbs) is attached
   - Affected users are tracked
   - Multiple events per issue are counted
   - Performance page (`/performance`) lists transaction groups and throughput
   - Transaction detail (`/performance/$transactionId`) renders span waterfall
   - Failed transaction scenario shows related errors in trace context

## Troubleshooting

**DSN not working?**
- Make sure Moneat backend is running (check logs)
- Verify DSN format: `http://PUBLIC_KEY@localhost:8080/PROJECT_ID`
- Check network connectivity from emulator (use 10.0.2.2 instead of localhost on Android emulator)

**Apps won't build?**
- Run `./setup.sh` to ensure all dependencies are configured
- Check that Android SDK is installed and ANDROID_HOME is set
- For KMP, ensure you have Java 11+ installed

**Errors not appearing in dashboard?**
- Check backend logs: `docker-compose logs -f backend`
- Verify ClickHouse is running: `docker-compose ps`
- Check that project ID in DSN matches the actual project ID

## Clean Up

To remove E2E data and start fresh:
```bash
# Stop services
cd ..
docker-compose down

# Remove volumes (WARNING: deletes all data)
docker-compose down -v

# Restart
docker-compose up -d
./e2e/seed-data.sh
```
