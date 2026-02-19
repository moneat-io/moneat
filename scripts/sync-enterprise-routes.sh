#!/usr/bin/env bash
# Copies enterprise dashboard routes and components into the core dashboard src tree.
# Run before `npm run dev` or `npm run build` to include enterprise UI features.
# The destination files are git-ignored in dashboard/; the source of truth is enterprise/dashboard/.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="$REPO_ROOT/enterprise/dashboard/src"
DST="$REPO_ROOT/dashboard/src"

if [[ ! -d "$SRC" ]]; then
  echo "⚠️  Enterprise src not found at $SRC — skipping sync (community build)"
  exit 0
fi

echo "🔄 Syncing enterprise routes → dashboard/src/routes/"
cp "$SRC/routes/analytics.tsx"           "$DST/routes/analytics.tsx"
cp "$SRC/routes/analytics.index.tsx"     "$DST/routes/analytics.index.tsx"
cp "$SRC/routes/on-call.tsx"             "$DST/routes/on-call.tsx"
cp "$SRC/routes/on-call.index.tsx"       "$DST/routes/on-call.index.tsx"
cp "$SRC/routes/on-call.schedules.tsx"   "$DST/routes/on-call.schedules.tsx"
cp "$SRC/routes/on-call.incidents.tsx"   "$DST/routes/on-call.incidents.tsx"
cp "$SRC/routes/on-call.incidents.\$incidentId.tsx" "$DST/routes/on-call.incidents.\$incidentId.tsx"
cp "$SRC/routes/on-call.escalation-policies.tsx" "$DST/routes/on-call.escalation-policies.tsx"
cp "$SRC/routes/on-call.declared-incidents.tsx"  "$DST/routes/on-call.declared-incidents.tsx"
cp "$SRC/routes/on-call.declared-incidents.\$incidentId.tsx" "$DST/routes/on-call.declared-incidents.\$incidentId.tsx"
cp "$SRC/routes/auth.oauth.callback.tsx"  "$DST/routes/auth.oauth.callback.tsx"
cp "$SRC/routes/auth.sso.callback.tsx"    "$DST/routes/auth.sso.callback.tsx"

echo "🔄 Syncing enterprise components → dashboard/src/components/"
mkdir -p "$DST/components/on-call"
cp "$SRC/components/analytics/AnalyticsBreakdownTable.tsx" "$DST/components/analytics/AnalyticsBreakdownTable.tsx"
cp "$SRC/components/analytics/AnalyticsChart.tsx"          "$DST/components/analytics/AnalyticsChart.tsx"
cp "$SRC/components/analytics/AnalyticsDatePicker.tsx"     "$DST/components/analytics/AnalyticsDatePicker.tsx"
cp "$SRC/components/analytics/AnalyticsFilterBar.tsx"      "$DST/components/analytics/AnalyticsFilterBar.tsx"
cp "$SRC/components/analytics/AnalyticsKpiCards.tsx"       "$DST/components/analytics/AnalyticsKpiCards.tsx"
cp "$SRC/components/analytics/AnalyticsRealtimeBadge.tsx"  "$DST/components/analytics/AnalyticsRealtimeBadge.tsx"
cp "$SRC/components/on-call/EscalationPolicyEditor.tsx"    "$DST/components/on-call/EscalationPolicyEditor.tsx"
cp "$SRC/components/on-call/IncidentTimeline.tsx"          "$DST/components/on-call/IncidentTimeline.tsx"
cp "$SRC/components/on-call/ScheduleEditor.tsx"            "$DST/components/on-call/ScheduleEditor.tsx"
cp "$SRC/components/sso-settings.tsx"                      "$DST/components/sso-settings.tsx"

echo "✅ Enterprise routes and components synced"
