# E2E Testing Validation Guide

This guide walks through testing the E2E implementation.

## Prerequisites

1. **Docker and Docker Compose installed**
2. **Android SDK installed** (ANDROID_HOME set)
3. **Java 17+**
4. **Android device/emulator** running

## Step-by-Step Validation

### 1. Start Moneat Services

```bash
cd /Users/aelder/Projects/Moneat
docker-compose up -d
```

Verify services are running:
```bash
docker-compose ps
```

You should see:
- postgres
- clickhouse
- redis
- mailhog

### 2. Set Up E2E Environment

```bash
cd e2e
./setup.sh
```

Expected output:
- ✅ Java found
- ✅ ANDROID_HOME set
- ✅ local.properties files created

### 3. Seed Test Data

```bash
./seed-data.sh
```

**IMPORTANT**: Copy the DSN output from this step!

Expected output should include:
```
=== E2E SETUP COMPLETE ===
Projects:
  - Android E2E App (ID: X)
    DSN: http://PUBLIC_KEY@localhost:8080/PROJECT_ID
  - KMP E2E App (ID: Y)
    DSN: http://PUBLIC_KEY@localhost:8080/PROJECT_ID
```

### 4. Configure DSNs

Edit `Android/local.properties`:
```properties
sentry.dsn=http://PUBLIC_KEY@localhost:8080/PROJECT_ID
sdk.dir=/Users/aelder/Library/Android/sdk
```

Edit `KMP/local.properties`:
```properties
sentry.dsn=http://PUBLIC_KEY@localhost:8080/PROJECT_ID
sdk.dir=/Users/aelder/Library/Android/sdk
```

**For Android Emulator**: Replace `localhost` with `10.0.2.2`

### 5. Test Android App

```bash
./run-android.sh
```

Expected:
- App builds successfully
- App installs on device/emulator
- App launches automatically

In the app:
1. Click "Trigger Crash" - app should crash
2. Restart app
3. Click "Throw Exception" - should see toast "Exception sent to Sentry"
4. Click "Network Error" - should see toast
5. Click "Null Pointer" - should see toast

### 6. Test KMP App

```bash
./run-kmp.sh
```

Expected:
- App builds successfully
- App installs on device/emulator
- App launches automatically

Test the same error buttons as Android app.

### 7. Verify in Dashboard

1. Open http://localhost:3000
2. Login with:
   - Email: `e2e-test@moneat.dev`
   - Password: `e2e-test-password`
3. You should see:
   - E2E Testing Organization
   - Two projects: "Android E2E App" and "KMP E2E App"
4. Click on each project to see captured errors

### 8. Verify Error Tracking

For each project, check:

**Issue List:**
- [ ] Issues are listed
- [ ] Issue count shows number of events
- [ ] Last seen timestamp is recent
- [ ] Error type/message is displayed

**Issue Detail:**
- [ ] Click an issue to view details
- [ ] Stack trace is displayed with file names and line numbers
- [ ] Tags are shown (error_type, platform, etc.)
- [ ] Breadcrumbs are captured (if supported)
- [ ] Context data is attached
- [ ] Multiple events per issue are grouped correctly
- [ ] Affected users are tracked

**Error Grouping:**
- [ ] Clicking the same error button multiple times creates events under the same issue
- [ ] Different error types create separate issues

## Troubleshooting

### DSN Connection Issues

If errors aren't appearing:

1. **Check backend logs**:
   ```bash
   cd ..
   docker-compose logs -f backend
   ```

2. **Verify project exists**:
   ```bash
   docker exec -it moneat-postgres psql -U moneat -d moneat -c "SELECT * FROM projects;"
   ```

3. **Check project keys**:
   ```bash
   docker exec -it moneat-postgres psql -U moneat -d moneat -c "SELECT * FROM project_keys;"
   ```

4. **For Android Emulator**, use `10.0.2.2` instead of `localhost` in DSN

### Build Issues

**Android app won't build:**
```bash
cd Android
./gradlew clean
./gradlew assembleDebug --stacktrace
```

**KMP app won't build:**
```bash
cd KMP
./gradlew clean
./gradlew :composeApp:assembleDebug --stacktrace
```

### App Crashes on Launch

Check logcat:
```bash
adb logcat | grep -i sentry
```

Common issues:
- Missing DSN configuration
- Invalid DSN format
- Network connectivity from emulator

### Data Seeder Fails

1. **Check database is running**:
   ```bash
   docker-compose ps postgres
   ```

2. **Check database connection**:
   ```bash
   docker exec -it moneat-postgres psql -U moneat -d moneat -c "SELECT 1;"
   ```

3. **Check if data already exists**:
   ```bash
   docker exec -it moneat-postgres psql -U moneat -d moneat -c "SELECT * FROM users WHERE email LIKE 'e2e%';"
   ```

## Success Criteria Checklist

- [ ] Both apps build without errors
- [ ] Both apps install and launch successfully
- [ ] Error triggers work and send data to Moneat
- [ ] Dashboard displays captured errors
- [ ] Stack traces are readable
- [ ] Error grouping works (same error = same issue)
- [ ] Tags and context are preserved
- [ ] Multiple events per issue are counted correctly
- [ ] Can login with e2e test credentials
- [ ] Can navigate between projects

## Cleanup

To reset and start fresh:

```bash
# Stop all services
cd /Users/aelder/Projects/Moneat
docker-compose down

# Remove all data (WARNING: destructive)
docker-compose down -v

# Restart
docker-compose up -d

# Re-seed
cd e2e
./seed-data.sh
```

## Performance Testing

To generate load:

```bash
# In Android app, repeatedly click error buttons
# Or write a script to trigger errors programmatically
```

Monitor backend:
```bash
docker-compose logs -f backend
docker stats
```

Check ClickHouse events:
```bash
docker exec -it moneat-clickhouse clickhouse-client --query "SELECT count() FROM events;"
```
