#!/bin/bash
set -e

echo "🎬 Complete Screenshot Workflow"
echo "================================"
echo ""
echo "This script will:"
echo "  1. Take screenshots of the dashboard"
echo "  2. Automatically update the landing page to use them"
echo ""

# Check if we're in the scripts directory or project root
if [ -d "scripts" ]; then
    cd scripts
elif [ ! -d "../dashboard" ]; then
    echo "❌ Please run from project root or scripts directory"
    exit 1
fi

# Run screenshot script
echo "Step 1/2: Taking screenshots..."
echo "================================"
./take-screenshots.sh

if [ $? -ne 0 ]; then
    echo "❌ Screenshot generation failed"
    exit 1
fi

echo ""
echo "Step 2/2: Updating landing page..."
echo "================================"
npm run update-landing

echo ""
echo "✅ Complete! Your landing page now uses real screenshots."
echo ""
echo "💡 Next steps:"
echo "   1. Check the landing page at http://localhost:3000"
echo "   2. Review the changes in your editor"
echo "   3. Commit the changes if everything looks good!"
echo ""
