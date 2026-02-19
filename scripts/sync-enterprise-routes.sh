#!/usr/bin/env bash
# Copies enterprise dashboard routes and components into the core dashboard src tree.
# Run before `npm run dev` or `npm run build` to include enterprise UI features.
# The destination files are git-ignored in dashboard/; the source of truth is enterprise/dashboard/.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR/dashboard"

node ./scripts/sync-enterprise.mjs "$@"
