#!/usr/bin/env bash
set -euo pipefail

# Prepares the build context for Docker builds, then runs docker compose build.
# When --enterprise is passed, copies the enterprise source into a temporary
# enterprise/ directory so the existing Dockerfile COPY instructions work unchanged.
# When --enterprise is NOT passed, creates empty stubs so COPY doesn't fail.
#
# Usage:
#   ./scripts/docker-build.sh                     # Core-only build
#   ./scripts/docker-build.sh --enterprise         # Enterprise build
#   ENTERPRISE_PATH=/custom/path ./scripts/docker-build.sh --enterprise

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENTERPRISE_DIR="$REPO_ROOT/enterprise"
ENTERPRISE_PATH="${ENTERPRISE_PATH:-$(cd "$REPO_ROOT/.." && pwd)/moneat-enterprise}"
BUILD_ENTERPRISE=false
EXTRA_ARGS=()

for arg in "$@"; do
  case "$arg" in
    --enterprise) BUILD_ENTERPRISE=true ;;
    *) EXTRA_ARGS+=("$arg") ;;
  esac
done

cleanup() {
  if [ -d "$ENTERPRISE_DIR" ]; then
    echo "Cleaning up temporary enterprise/ directory..."
    rm -rf "$ENTERPRISE_DIR"
  fi
}
trap cleanup EXIT

# Always start clean
rm -rf "$ENTERPRISE_DIR"

if [ "$BUILD_ENTERPRISE" = true ]; then
  # Validate enterprise source exists
  if [ ! -d "$ENTERPRISE_PATH/backend/src" ]; then
    echo "ERROR: Enterprise source not found at $ENTERPRISE_PATH"
    echo "Clone the enterprise repo or set ENTERPRISE_PATH."
    exit 1
  fi

  echo "Preparing enterprise build context from $ENTERPRISE_PATH..."
  mkdir -p "$ENTERPRISE_DIR"
  cp -r "$ENTERPRISE_PATH/backend" "$ENTERPRISE_DIR/backend"
  # Dashboard files now in open source - no copy needed

  echo "Building with enterprise modules..."
  cd "$REPO_ROOT"
  docker compose build --build-arg ENTERPRISE=true "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"
else
  # Create empty stubs so Dockerfile COPY instructions succeed
  echo "Creating enterprise stubs for core-only build..."
  mkdir -p "$ENTERPRISE_DIR/backend/src"
  # Dashboard stubs no longer needed

  echo "Building core-only..."
  cd "$REPO_ROOT"
  docker compose build "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"
fi

echo "Build complete."
