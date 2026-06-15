#!/bin/bash
cd "$(dirname "$0")/../dashboard" || exit
echo "Running TypeScript check..."
npx tsc --noEmit
