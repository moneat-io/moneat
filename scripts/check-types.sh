#!/bin/bash
cd "$(dirname "$0")/../dashboard"
echo "Running TypeScript check..."
npx tsc --noEmit
