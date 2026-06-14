#!/bin/bash
set -e

echo "🚀 Setting up E2E Testing Environment..."

# Check if we're in the e2e directory
if [ ! -f "README.md" ]; then
  echo "❌ Please run this script from the e2e directory"
  exit 1
fi

# Check for required tools
echo "Checking for required tools..."

if ! command -v java &>/dev/null; then
  echo "❌ Java not found. Please install Java 11 or later."
  exit 1
fi

echo "✅ Java found: $(java -version 2>&1 | head -n 1)"

# Check Android SDK (optional but recommended)
if [ -z "$ANDROID_HOME" ]; then
  echo "⚠️  ANDROID_HOME not set. Android builds may fail."
  echo "   Please install Android SDK and set ANDROID_HOME environment variable."
else
  echo "✅ ANDROID_HOME: $ANDROID_HOME"
fi

# Create local.properties files if they don't exist
echo ""
echo "Setting up local.properties files..."

if [ ! -f "Android/local.properties" ]; then
  echo "Creating Android/local.properties..."
  cp Android/local.properties.example Android/local.properties
  if [ -n "$ANDROID_HOME" ]; then
    echo "sdk.dir=$ANDROID_HOME" >>Android/local.properties
  fi
  echo "✅ Created Android/local.properties (needs DSN configuration)"
else
  echo "✅ Android/local.properties already exists"
fi

if [ ! -f "KMP/local.properties" ]; then
  echo "Creating KMP/local.properties..."
  cp KMP/local.properties.example KMP/local.properties
  if [ -n "$ANDROID_HOME" ]; then
    echo "sdk.dir=$ANDROID_HOME" >>KMP/local.properties
  fi
  echo "✅ Created KMP/local.properties (needs DSN configuration)"
else
  echo "✅ KMP/local.properties already exists"
fi

echo ""
echo "✅ E2E setup complete!"
echo ""
echo "Next steps:"
echo "1. Start Moneat services: cd .. && docker compose up -d (or docker-compose up -d)"
echo "2. Seed test data: ./seed-data.sh"
echo "3. Copy the DSNs from seed output to Android/local.properties and KMP/local.properties"
echo "4. Run the apps: ./run-android.sh or ./run-kmp.sh"
