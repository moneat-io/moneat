# Overview-as-Dashboard — design spec

**Date:** 2026-06-07
**Branch:** `feat/overview-dashboard` (off `develop`)
**Scope:** Dashboard frontend (`dashboard/`). UI only — the data layer is mocked, shaped like
future API responses. Backend wiring is a later, separate effort.

## Goal

Replace the hand-built authenticated Overview with a **command-center Overview that is a real
`CustomDashboard`** rendered by the existing dashboards engine. The mockup's panels become
**widgets**; the page is a customizable widget grid (move / resize / add / remove / reset). Visual
target: `moneat-internal/mockups/moneat-overview.html` (the page body only — the sidebar/topbar
already exist in the app shell and are out of scope).

## Decision (confirmed with user)

The Overview **is** a Dashboard in its entirety, reusing the existing engine. We add a family of
**native (non-query) widget types** so the mockup's composite panels are expressible. Reuse is
maximized at two levels:

- **Engine:** `CustomDashboard` model, `DashboardGrid` (react-grid-layout), `WidgetRenderer`
  dispatch, edit mechanics.
- **Primitives:** `Badge`, `StatusDot`, `Table`, `Card`, recharts, `formatValue`, and the
  compact/dark design tokens the mockup is already built on.

## Existing system (verified)

- `routes/index.tsx` renders `LandingPage` (logged out) and a hand-built Overview under
  `?view=overview` (authenticated landing). The authenticated branch is what we replace.
- `components/dashboards/`: `DashboardGrid` (props-driven grid, edit mode, sections, alert dots) →
  `WidgetRenderer` (query-driven; already **short-circuits** non-query `text`/`section` widgets — the
  precedent we extend) → query types stat/timeseries/table/toplist/bar/gauge/donut/heatmap +
  extended (host_map, topology_map, status, change, flame_graph, …). Extended types are registered in
  `extendedWidgetTypes.ts` and rendered by `ExtendedWidgets.tsx`'s `ExtendedWidgetRenderer`.
- Model (`lib/api/types/dashboards.ts`): `CustomDashboard { widgets: DashboardWidget[] }`,
  `DashboardWidget { widget_type, grid_x/y/w/h, query_configs, display_config, sort_order }`.
- Deps already present: `react-grid-layout@2.2.2`, `recharts@3`.

## Widget taxonomy (9 new native types)

| Type id | Mockup panel | Default size (12-col grid) |
|---|---|---|
| `system_status` | hero status bar + AI summary + actions | w12 × short |
| `kpi` | one KPI tile (×6 seeded) | w2 × short |
| `service_health` | service table (status, req/min, error% bar, p95, apdex, errors, trend, deploy) | w8 × tall |
| `telemetry` | tabbed chart (errors/latency/throughput/logs) + deploy & threshold markers | w8 × med |
| `triage` | "Needs attention": incidents / alerts firing / new issues / security signals | w4 × tall |
| `infra_summary` | CPU/Mem/Disk/Net bar gauges + container/pod counts + offline node | w3 × med |
| `uptime_summary` | per-monitor heartbeat bars + synthetics/status-page chips | w3 × med |
| `deploys` | recent deploy rows with status badges | w3 × med |
| `activity` | event feed | w3 × med |

KPIs are **6 individual draggable widgets** (not one fixed strip) for customizability.

## Integration points (minimal, follows the text/section precedent)

1. **`components/dashboards/extendedWidgetTypes.ts`** — add the 9 ids to `EXTENDED_WIDGET_TYPES`;
   make `isQueryDrivenExtendedWidget` return `false` for them (a new `OVERVIEW_WIDGET_TYPES` set).
   Effect: `WidgetRenderer`'s query is `enabled:false`, the "No data" guard is skipped (extended), and
   the widget renders immediately from its own mock hook.
2. **`components/dashboards/ExtendedWidgets.tsx`** (`ExtendedWidgetRenderer`) — dispatch the 9 ids to
   the overview widget components (lazy import from `components/overview/widgets/`).
3. **`components/dashboards/DashboardGrid.tsx`** —
   - Render overview widgets **chromeless** in view mode (no `WidgetCard` header bar / padding) so each
     panel keeps its own mockup header (title + count + badges + links).
   - In **edit mode**, overlay the existing drag/resize/delete affordances on hover.
   - Add overview short types (`system_status`, `kpi`) to the "short widget" / `minH` sets so the grid
     sizes them correctly.

No changes to query execution, alerts, datasources, import/export, or clipboard.

## Mock data layer

`components/overview/overviewMockData.ts` — typed fixtures + `useXxx()` hooks (one per widget) that
return data shaped like the future API response. Each hook is the single seam to swap for a real
`api.*` call later. Values mirror the mockup (e.g., checkout-api degraded, INC-204, 6 KPIs).

## Overview document + routing

- `components/overview/defaultOverviewLayout.ts` — the seed widget list mapping the mockup 1:1 onto
  grid coordinates; assembled into an in-memory `CustomDashboard` (reserved id `0` / slug `overview`).
- `components/overview/OverviewDashboard.tsx` — holds the widget list in state, persists
  customizations to `localStorage` (key e.g. `moneat.overview.layout.v1`), and renders:
  - `OverviewHeader` (bespoke, matches the mockup: eyebrow "Workspace overview" / title "Overview" /
    environment selector / time range + **Live** / refresh / **Customize** toggle / **Reset** in edit
    mode), reusing primitives.
  - `DashboardGrid` in view/edit modes.
  - `AddOverviewWidgetDialog` — a picker listing the 9 overview widget types (icon + name +
    description) to add to the grid.
- `routes/index.tsx` — the authenticated `?view=overview` branch renders `<OverviewDashboard/>`. The
  now-dead hand-built overview helpers (`DashboardStatsOverview`, `DashboardEventsOverview`,
  `IssueOverviewRow`, `UptimeMonitorOverviewRow`, `InfrastructureHostOverviewRow`,
  `StatusPageOverviewRow`, and their queries) are removed. The logged-out `LandingPage` path and the
  auth/redirect gate are untouched.
- Update `routes/__tests__/overview-alert-incident-coverage.test.tsx` to match the new render.

## Customize scope (v1)

Move / resize / remove / add (picker) / reset to default, persisted to `localStorage`. Per-widget
**query** config stays out of scope (it is the backend-coupled query builder); these panels are
fixed-content for now. The types render engine-wide; making them fully authorable in the generic
`WidgetConfigPanel` is a later step.

## Out of scope

Real query execution / backend persistence / real-time streaming; generic query-builder authoring of
the new types; the app sidebar/topbar (already exist).

## File plan

**New (`dashboard/src/components/overview/`):**
- `overviewWidgetTypes.ts` — registry: id → { label, description, icon, defaultSize, minH, component }.
- `overviewMockData.ts` — typed fixtures + hooks.
- `defaultOverviewLayout.ts` — seed widget list (grid coords).
- `OverviewDashboard.tsx` — page container (state, localStorage, header + grid + add dialog).
- `OverviewHeader.tsx` — bespoke header matching the mockup.
- `AddOverviewWidgetDialog.tsx` — widget picker.
- `widgets/` — `SystemStatusWidget`, `KpiWidget`, `ServiceHealthWidget`, `TelemetryWidget`,
  `TriageWidget`, `InfraSummaryWidget`, `UptimeSummaryWidget`, `DeploysWidget`, `ActivityWidget`.

**Edited:**
- `components/dashboards/extendedWidgetTypes.ts`
- `components/dashboards/ExtendedWidgets.tsx`
- `components/dashboards/DashboardGrid.tsx`
- `routes/index.tsx`
- `routes/__tests__/overview-alert-incident-coverage.test.tsx`

## Testing & verification

Hard FE coverage gate + SonarQube ≥80% new-code on `develop`, so tests ship with the code:
- `overviewWidgetTypes` registry test (every type has a component + sane default size).
- Render smoke test per widget (renders mock data, no crash, key labels present).
- `OverviewDashboard` test: renders default layout; add → widget appears; remove → gone; reset →
  back to default; localStorage round-trip.
- Verification commands: `cd dashboard && npm run lint && npm run build && npm test`.

## Risks / notes

- `DashboardGrid` chromeless mode must not regress normal (query) widgets — gate strictly on
  overview widget type.
- Grid `needsScaling`/`minH` heuristics assume query widgets; verify short overview widgets
  (`system_status`, `kpi`) size correctly.
- Removing the old overview code in `index.tsx` is a large delete; keep the auth gate + landing path
  intact and update the existing overview test.
