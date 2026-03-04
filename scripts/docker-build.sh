#!/usr/bin/env bash
set -euo pipefail

# Builds all Docker images for Moneat.
# Enterprise modules (SSO, On-Call) are always compiled in but gated by
# MONEAT_LICENSE_KEY at runtime.
#
# Usage:
#   ./scripts/docker-build.sh
#   ./scripts/docker-build.sh --no-cache

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Building Moneat..."
cd "$REPO_ROOT"
docker compose build "$@"
echo "Build complete."
