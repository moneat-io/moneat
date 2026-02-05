#!/bin/bash
set -e

echo "📱 Building and running KMP E2E app (Android)..."

# Check if we're in the e2e directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/KMP"

# Check for local.properties
if [ ! -f "local.properties" ]; then
  echo "❌ local.properties not found. Run ../setup.sh first."
  exit 1
fi

# Check if DSN is configured
if ! grep -q "sentry.dsn=http" local.properties 2>/dev/null; then
  echo "⚠️  Warning: Sentry DSN not configured in local.properties"
  echo "   Errors won't be sent to Moneat until you configure the DSN."
  echo ""
fi

echo "Building KMP Android app..."
./gradlew :composeApp:assembleDebug

echo "Installing on device/emulator..."
./gradlew :composeApp:installDebug

echo "Starting app..."
adb shell am start -n com.moneat.e2e.kmp/.MainActivity

echo ""
echo "✅ KMP E2E app launched!"
echo "Trigger errors and check the Moneat dashboard."
