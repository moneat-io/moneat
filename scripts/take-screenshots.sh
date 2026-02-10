#!/bin/bash
set -e

echo "🎬 Moneat Screenshot Automation"
echo "================================"
echo ""

# Check if we're in the scripts directory or project root
if [ -d "scripts" ]; then
    SCRIPTS_DIR="./scripts"
elif [ -d "../dashboard" ]; then
    SCRIPTS_DIR="."
else
    echo "❌ Please run from project root or scripts directory"
    exit 1
fi

cd "$SCRIPTS_DIR"

# Check if playwright is installed
if [ ! -d "node_modules/playwright" ]; then
    echo "📦 Installing dependencies..."
    npm install
    echo ""
    echo "🌐 Installing Playwright browsers..."
    npx playwright install chromium
    echo ""
fi

# Check if dashboard is running
echo "🔍 Checking if dashboard is running..."
if ! curl -s http://localhost:3000 > /dev/null 2>&1; then
    echo ""
    echo "❌ Dashboard is not running at http://localhost:3000"
    echo ""
    echo "Please start the dashboard in another terminal:"
    echo "  cd dashboard && npm run dev"
    echo ""
    exit 1
fi

echo "✅ Dashboard is running"
echo ""

# Check if demo data exists
echo "🔍 Checking for demo data..."
echo "💡 Make sure demo data is seeded. Run this if needed:"
echo "  ./seed-demo-data.sh"
echo ""

# Run screenshot script
if [ "$1" = "--debug" ] || [ "$1" = "-d" ]; then
    echo "🐛 Running in debug mode (browser visible)..."
    npm run screenshots:debug
else
    echo "📸 Running screenshot automation (headless)..."
    npm run screenshots
fi
