#!/bin/bash
set -e

# DSN Configuration
MONEAT_DSN="https://414ee8498b8b4765a4a346e9d6342e11@moneat-backend.bandapella.com/4"
SENTRY_SAAS_DSN="https://371e1ef6e7919c2c59c330db8adbc068@o4507459978133504.ingest.us.sentry.io/4510856838250496"

# Parse arguments
USE_SENTRY_SAAS=false
if [[ "$1" == "--sentry" ]]; then
  USE_SENTRY_SAAS=true
fi

echo "📱 Building and running Android E2E app..."

# Check if we're in the e2e directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/Android"

# Check for local.properties
if [ ! -f "local.properties" ]; then
  echo "❌ local.properties not found. Run ../setup.sh first."
  exit 1
fi

# Configure DSN based on flag
if [ "$USE_SENTRY_SAAS" = true ]; then
  echo "🔧 Configuring for Sentry SaaS..."
  SENTRY_DSN="$SENTRY_SAAS_DSN"
  echo "✅ Using Sentry SaaS DSN"
else
  echo "🔧 Configuring for Moneat..."
  SENTRY_DSN="$MONEAT_DSN"
  echo "✅ Using Moneat DSN"
fi

# Update or add sentry.dsn in local.properties
if grep -q "^sentry.dsn=" local.properties; then
  sed -i '' "s|^sentry.dsn=.*|sentry.dsn=$SENTRY_DSN|" local.properties
else
  echo "sentry.dsn=$SENTRY_DSN" >> local.properties
fi

echo "Building Android app..."
./gradlew assembleDebug

echo "Installing on device/emulator..."
./gradlew installDebug

echo "Starting app..."
adb shell am start -n com.moneat.e2e.android/.MainActivity

echo ""
echo "✅ Android E2E app launched!"
if [ "$USE_SENTRY_SAAS" = true ]; then
  echo "📊 Events will be sent to Sentry SaaS"
else
  echo "📊 Events will be sent to Moneat instance"
fi
