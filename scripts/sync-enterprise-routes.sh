#!/usr/bin/env bash
# Copies enterprise dashboard routes into the core dashboard src tree.
# Run before `npm run dev` or `npm run build` to include enterprise UI features.
# The destination files are git-ignored in dashboard/; the source of truth is enterprise/dashboard/.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="$REPO_ROOT/enterprise/dashboard/src/routes"
DST="$REPO_ROOT/dashboard/src/routes"

if [[ ! -d "$SRC" ]]; then
  echo "⚠️  Enterprise routes not found at $SRC — skipping sync (community build)"
  exit 0
fi

echo "🔄 Syncing enterprise routes → dashboard/src/routes/"
cp "$SRC"/analytics.tsx           "$DST/analytics.tsx"
cp "$SRC"/analytics.index.tsx     "$DST/analytics.index.tsx"
cp "$SRC"/on-call.tsx             "$DST/on-call.tsx"
cp "$SRC"/on-call.index.tsx       "$DST/on-call.index.tsx"
cp "$SRC"/on-call.schedules.tsx   "$DST/on-call.schedules.tsx"
cp "$SRC"/on-call.incidents.tsx   "$DST/on-call.incidents.tsx"
cp "$SRC"/"on-call.incidents.\$incidentId.tsx" "$DST/on-call.incidents.\$incidentId.tsx"
cp "$SRC"/on-call.escalation-policies.tsx "$DST/on-call.escalation-policies.tsx"
cp "$SRC"/on-call.declared-incidents.tsx  "$DST/on-call.declared-incidents.tsx"
cp "$SRC"/"on-call.declared-incidents.\$incidentId.tsx" "$DST/on-call.declared-incidents.\$incidentId.tsx"
cp "$SRC"/auth.oauth.callback.tsx  "$DST/auth.oauth.callback.tsx"
cp "$SRC"/auth.sso.callback.tsx    "$DST/auth.sso.callback.tsx"
echo "✅ Enterprise routes synced"
