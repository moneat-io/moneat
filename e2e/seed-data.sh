#!/bin/bash
set -e

echo "🌱 Seeding E2E test data..."

# Check if we're in the e2e directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

# Detect docker compose command (v1: docker-compose, v2: docker compose)
if command -v docker-compose &>/dev/null; then
  DOCKER_COMPOSE="docker-compose"
else
  DOCKER_COMPOSE="docker compose"
fi

# Check if database is accessible (whether via docker-compose or service containers)
echo "Checking database connection..."
# First try docker-compose
if $DOCKER_COMPOSE ps postgres 2>/dev/null | grep -q "Up"; then
  echo "✅ PostgreSQL running via docker-compose"
# If not via docker-compose, check if it's accessible directly (e.g., GitHub Actions service container)
elif nc -z localhost 5499 2>/dev/null || pg_isready -h localhost -p 5499 -U moneat 2>/dev/null; then
  echo "✅ PostgreSQL accessible on localhost:5499 (service container)"
else
  echo "❌ PostgreSQL is not running or accessible. Please start database:"
  echo "   $DOCKER_COMPOSE up -d postgres"
  echo "   OR ensure PostgreSQL service container is running (CI environment)"
  exit 1
fi

echo "Database is running. Compiling and running data seeder..."
cd backend

# Run the seeder and capture output
./gradlew seedE2EData --console=plain 2>&1 | tee /tmp/e2e-seed.log

# Extract DSNs from the output
ANDROID_DSN=$(grep "Android E2E App" -A1 /tmp/e2e-seed.log | grep "DSN:" | sed 's/.*DSN: //')
KMP_DSN=$(grep "KMP E2E App" -A1 /tmp/e2e-seed.log | grep "DSN:" | sed 's/.*DSN: //')

# Update local.properties if DSNs were found
if [ -n "$ANDROID_DSN" ]; then
  echo "Updating Android local.properties with DSN..."
  echo "sentry.dsn=$ANDROID_DSN" >../e2e/Android/local.properties
  echo "✅ Updated e2e/Android/local.properties"
fi

if [ -n "$KMP_DSN" ]; then
  echo "Updating KMP local.properties with DSN..."
  echo "sentry.dsn=$KMP_DSN" >../e2e/KMP/local.properties
  echo "✅ Updated e2e/KMP/local.properties"
fi

echo ""
echo "✅ E2E data seeding complete!"
echo ""
echo "You can now:"
echo "1. Login to Moneat dashboard at http://localhost:3000"
echo "2. Use credentials: e2e-test@moneat.dev / e2e-test-password"
echo "3. Run the E2E apps to trigger errors:"
echo "   cd e2e && ./run-android.sh"
echo "   cd e2e && ./run-kmp.sh"
