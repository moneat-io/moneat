# Overview-as-Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the authenticated Overview a real, customizable `CustomDashboard` rendered by the existing dashboards engine, with 9 new native (non-query) widget types reproducing the mockup, backed by a mock data layer.

**Architecture:** New native widget types are registered in the dashboards engine (`extendedWidgetTypes.ts`), dispatched by `ExtendedWidgets.tsx`, and rendered **chromeless** by `DashboardGrid` so each panel keeps its own header. A new `components/overview/` module provides the widget components, a typed mock-data layer, a seed layout, the page container (`OverviewDashboard`), a bespoke header, and an add-widget picker. `routes/index.tsx` renders `<OverviewDashboard/>` for the authenticated `?view=overview` landing.

**Tech Stack:** React + TypeScript, TanStack Router/Query, `react-grid-layout@2.2.2`, `recharts@3`, Vitest + React Testing Library, Tailwind + internal design-system primitives (`components/ui/*`) and tokens.

**Canonical visual source:** `moneat-internal/mockups/moneat-overview.html` (read at `/Users/aelder/.codex/worktrees/5a26/moneat-internal/mockups/moneat-overview.html`). Each widget reproduces its panel's markup using `components/ui/*` primitives and CSS tokens (`var(--accent)`, `var(--danger-solid)`, `--scale-*`, `--row-h`, etc.) — never raw hex. Default density is compact.

**Conventions:** Follow existing patterns in `components/dashboards/` and tests in `components/dashboards/__tests__/`. Every source file starts with the AGPL license header used throughout the repo. Commits use Conventional Commits; **no Claude attribution** in commit messages.

---

## File Structure

**New — `dashboard/src/components/overview/`:**
- `overviewWidgetTypes.ts` — `OVERVIEW_WIDGET_IDS`, registry `OVERVIEW_WIDGETS: Record<id, {label, description, icon, defaultSize:{w,h}, minH, component}>`, `isOverviewWidgetType(t)`.
- `overviewMockData.ts` — typed fixtures + `useXxx()` hooks (one per widget). Single seam for future real API.
- `defaultOverviewLayout.ts` — `buildDefaultOverviewDashboard(): CustomDashboard` (seed widgets w/ grid coords).
- `OverviewHeader.tsx` — bespoke page header matching the mockup.
- `AddOverviewWidgetDialog.tsx` — picker over the 9 types.
- `OverviewDashboard.tsx` — container: state + localStorage + header + `DashboardGrid` + add dialog.
- `widgets/SystemStatusWidget.tsx`, `KpiWidget.tsx`, `ServiceHealthWidget.tsx`, `TelemetryWidget.tsx`, `TriageWidget.tsx`, `InfraSummaryWidget.tsx`, `UptimeSummaryWidget.tsx`, `DeploysWidget.tsx`, `ActivityWidget.tsx`.
- `__tests__/` — colocated tests.

**Edited:**
- `components/dashboards/extendedWidgetTypes.ts`
- `components/dashboards/ExtendedWidgets.tsx`
- `components/dashboards/DashboardGrid.tsx`
- `routes/index.tsx`
- `routes/__tests__/overview-alert-incident-coverage.test.tsx`

**Widget type ids:** `system_status`, `kpi`, `service_health`, `telemetry`, `triage`, `infra_summary`, `uptime_summary`, `deploys`, `activity`.

---

## Task 1: Register native overview widget types in the engine

**Files:**
- Modify: `dashboard/src/components/dashboards/extendedWidgetTypes.ts`
- Test: `dashboard/src/components/dashboards/__tests__/extendedWidgetTypes.test.ts` (create)

- [ ] **Step 1: Write failing test**

```ts
import {describe, expect, it} from 'vitest'
import {
  EXTENDED_WIDGET_TYPES,
  OVERVIEW_WIDGET_TYPES,
  isExtendedWidgetType,
  isOverviewWidgetType,
  isQueryDrivenExtendedWidget,
} from '../extendedWidgetTypes'

describe('overview widget types', () => {
  const ids = ['system_status','kpi','service_health','telemetry','triage','infra_summary','uptime_summary','deploys','activity']
  it('are extended, non-query, and flagged as overview', () => {
    for (const id of ids) {
      expect(isExtendedWidgetType(id)).toBe(true)
      expect(OVERVIEW_WIDGET_TYPES.has(id)).toBe(true)
      expect(isOverviewWidgetType(id)).toBe(true)
      expect(isQueryDrivenExtendedWidget(id)).toBe(false)
    }
  })
  it('keeps existing extended types query-driven (except iframe)', () => {
    expect(isQueryDrivenExtendedWidget('host_map')).toBe(true)
    expect(isQueryDrivenExtendedWidget('iframe')).toBe(false)
    expect(EXTENDED_WIDGET_TYPES.has('host_map')).toBe(true)
  })
})
```

- [ ] **Step 2: Run test, expect FAIL** — `cd dashboard && npx vitest run src/components/dashboards/__tests__/extendedWidgetTypes.test.ts` → fails (no `OVERVIEW_WIDGET_TYPES`).

- [ ] **Step 3: Implement** — edit `extendedWidgetTypes.ts`:

```ts
export const OVERVIEW_WIDGET_TYPES = new Set([
  'system_status','kpi','service_health','telemetry','triage',
  'infra_summary','uptime_summary','deploys','activity',
])

export const EXTENDED_WIDGET_TYPES = new Set([
  'stream','timeline','geo_map','host_map','topology_map','sankey','treemap',
  'scatter','status','change','custom','flame_graph','cost_summary','iframe',
  ...OVERVIEW_WIDGET_TYPES,
])

export function isExtendedWidgetType(widgetType: string): boolean {
  return EXTENDED_WIDGET_TYPES.has(widgetType)
}

export function isOverviewWidgetType(widgetType: string): boolean {
  return OVERVIEW_WIDGET_TYPES.has(widgetType)
}

// Overview widgets render from their own data hooks, not a query.
export function isQueryDrivenExtendedWidget(widgetType: string): boolean {
  return widgetType !== 'iframe' && !OVERVIEW_WIDGET_TYPES.has(widgetType)
}

export function extendedWidgetTestId(widgetType: string): string {
  return `widget-${widgetType}`
}
```

- [ ] **Step 4: Run test, expect PASS.** Also run the existing `ExtendedWidgets.test.tsx` to confirm no regression: `npx vitest run src/components/dashboards/__tests__/ExtendedWidgets.test.tsx`.

- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(overview): register native overview widget types"`

---

## Task 2: Mock data layer

**Files:**
- Create: `dashboard/src/components/overview/overviewMockData.ts`
- Test: `dashboard/src/components/overview/__tests__/overviewMockData.test.ts`

Defines exported types + fixtures + hooks. Types (shape mirrors the mockup; these become the future API contract):

```ts
export type Health = 'good' | 'warn' | 'bad' | 'neutral'
export interface SystemStatusData {
  state: string; severity: Health
  counts: {incidents: number; alerts: number; degraded: number; hostsOffline: number}
  ai: {summary: string; incidentId?: string}
}
export interface Kpi {
  id: string; label: string; value: string; unit?: string
  delta?: {value: string; direction: 'up'|'down'; tone: 'good'|'bad'|'neutral'}
  status: Health; spark: number[]
}
export interface ServiceRow {
  name: string; env: string; status: Health; reqPerMin: number; errorPct: number
  p95Ms: number | null; apdex: number | null; errors24h: number; issues: number
  trend: number[]; deploy: {version: string; ageLabel: string; tone: 'good'|'bad'|'neutral'}
}
export interface TelemetrySeries { errors: number[]; latency: number[]; throughput: number[]; logs: number[]; deployAtPct?: number }
export interface TriageData {
  incidents: {id: string; title: string; priority: string; status: string; owner: string; ageLabel: string}[]
  alerts: {title: string; detail: string; level: 'error'|'warn'; ageLabel: string}[]
  issues: {level: 'fatal'|'error'|'warn'|'info'; title: string; detail: string; ageLabel: string}[]
  security: {title: string; detail: string; level: 'error'|'warn'; ageLabel: string}[]
}
export interface InfraData { gauges: {label: string; pct: number; tone: Health}[]; containers: number; pods: number; offlineNode?: string }
export interface UptimeMonitor { name: string; bars: ('up'|'warn'|'down')[]; uptimeLabel: string; down?: boolean }
export interface UptimeData { monitors: UptimeMonitor[]; syntheticFailing?: string; statusPages: string }
export interface DeployRow { version: string; service: string; status: 'good'|'bad'|'neutral'; label: string; ageLabel: string }
export interface ActivityItem { kind: 'incident'|'flag'|'deploy'|'workflow'|'replay'|'feedback'; text: string; meta: string }
```

Fixtures mirror the mockup (checkout-api degraded, INC-204, 6 KPIs: Errors 48.3k +312%, p95 412ms +38%, Throughput 18.2k req/min −4.1%, Apdex 0.86 −0.08, Open issues 37, Uptime 99.95%). Hooks: `useSystemStatus()`, `useKpis()`, `useServiceHealth()`, `useTelemetry()`, `useTriage()`, `useInfraSummary()`, `useUptimeSummary()`, `useDeploys()`, `useActivity()`. For now each returns its fixture synchronously: `return OVERVIEW_FIXTURES.kpis` etc. (Later: swap body for `useQuery`/`api.*`.)

- [ ] **Step 1: Test (assert fixture shapes via hooks rendered through a host component or by calling the plain fixture getters).**

```ts
import {describe, expect, it} from 'vitest'
import {OVERVIEW_FIXTURES} from '../overviewMockData'
describe('overview fixtures', () => {
  it('has 6 KPIs and a degraded service', () => {
    expect(OVERVIEW_FIXTURES.kpis).toHaveLength(6)
    expect(OVERVIEW_FIXTURES.serviceHealth.some(s => s.status === 'bad')).toBe(true)
  })
  it('telemetry series are equal length', () => {
    const t = OVERVIEW_FIXTURES.telemetry
    expect(t.errors.length).toBe(t.latency.length)
  })
})
```

- [ ] **Step 2: FAIL** (`npx vitest run src/components/overview/__tests__/overviewMockData.test.ts`).
- [ ] **Step 3: Implement** the types, `OVERVIEW_FIXTURES`, and the hooks (hooks return fixtures).
- [ ] **Step 4: PASS.**
- [ ] **Step 5: Commit** — `git commit -m "feat(overview): add typed mock data layer"`

---

## Tasks 3–11: Widget components (one task each)

Each widget is a presentational component: `export function XxxWidget()` reading its mock hook, reproducing the corresponding mockup panel (its own `panel`/`panel__head` header with title + count/badges + link, then body) using `components/ui/*` (`Card`, `Badge`, `StatusDot`, `Table`, `Separator`) and CSS tokens. Root element sets `data-testid={extendedWidgetTestId(type)}` (e.g., `widget-service_health`). Fill height: `h-full flex flex-col`.

Per widget, the **steps are identical in shape**:
1. Write render smoke test (below) — assert testid present + 2–3 key strings from the mockup.
2. Run → FAIL.
3. Implement the component from the mockup panel (cited above) with primitives/tokens.
4. Run → PASS.
5. Commit `feat(overview): add <Name>Widget`.

**Test template** (adjust name/testid/strings):

```tsx
import {render, screen} from '@testing-library/react'
import {describe, expect, it} from 'vitest'
import {ServiceHealthWidget} from '../widgets/ServiceHealthWidget'
describe('ServiceHealthWidget', () => {
  it('renders services from mock data', () => {
    render(<ServiceHealthWidget />)
    expect(screen.getByTestId('widget-service_health')).toBeInTheDocument()
    expect(screen.getByText('checkout-api')).toBeInTheDocument()
    expect(screen.getByText(/View all/i)).toBeInTheDocument()
  })
})
```

- [ ] **Task 3 — `SystemStatusWidget`** (`system_status`): mockup `section.sysbar`. Severity rail + icon, state ("Action needed"), counts (incidents/alerts/degraded/hosts), AI summary line with sparkle icon + `INC-204`, actions "Ask AI" / "View incident". Test asserts testid + "Action needed" + "INC-204".
- [ ] **Task 4 — `KpiWidget`** (`kpi`): single tile from `section.kpis .kpi`. Props: `kpiId?: string` (from `display_config.kpiId`); default to first. Label (caps), big mono value + unit, delta with arrow + tone color, status dot, inline SVG sparkline from `spark` numbers. Test renders with `display_config={{kpiId:'errors'}}` → asserts label + value.
- [ ] **Task 5 — `ServiceHealthWidget`** (`service_health`): `panel` "Service health" + count "8 services" + badges + "View all"; `Table` with columns Service/Req·min/Error %/p95/Apdex/Errors(24h)/Issues/Last deploy; error% mini-bar (`--scale-*`), trend sparkline, deploy badge. Test asserts "checkout-api" + "View all".
- [ ] **Task 6 — `TelemetryWidget`** (`telemetry`): `panel` "Telemetry" with `Tabs` (Errors/Latency/Throughput/Logs); recharts `AreaChart` (errors/latency/throughput) and `BarChart` (logs) from `useTelemetry()`; vertical deploy marker (`ReferenceLine` at `deployAtPct`) + threshold line on errors. Test asserts testid + tab "Errors" + that switching to "Logs" updates legend text. Use `ResponsiveContainer`.
- [ ] **Task 7 — `TriageWidget`** (`triage`): `panel` "Needs attention" + count; four sections (Active incidents / Alerts firing / New issues / Security signals) with left-border severity rows (`trow--*`), `level` chips for issue severities, incident card with P1/TRIGGERED badges. Test asserts "Needs attention" + "checkout-api".
- [ ] **Task 8 — `InfraSummaryWidget`** (`infra_summary`): `panel` "Infrastructure" + "23/24 up" + "Map"; four bar-gauges (CPU/Mem/Disk/Net) with tone fills; statline containers/pods + offline-node danger badge. Test asserts "Infrastructure" + "82%".
- [ ] **Task 9 — `UptimeSummaryWidget`** (`uptime_summary`): `panel` "Uptime & Synthetics" + "18/19 up"; heartbeat rows (`hb i` up/warn/bad) per monitor with uptime %; statline synthetic-failing + status-pages chip. Test asserts "checkout flow" + "DOWN".
- [ ] **Task 10 — `DeploysWidget`** (`deploys`): `panel` "Deploys" + "last 24h" + "Releases"; deploy rows (dot + version + service + status badge + age). Test asserts "v2.4.1" + "regressing".
- [ ] **Task 11 — `ActivityWidget`** (`activity`): `panel` "Activity" + "View log"; feed rows (kind icon tile + text w/ `code` chips + meta). Test asserts testid + "INC-204".

---

## Task 12: Overview widget registry

**Files:**
- Create: `dashboard/src/components/overview/overviewWidgetTypes.ts`
- Test: `dashboard/src/components/overview/__tests__/overviewWidgetTypes.test.tsx`

Registry maps each id to metadata + component (for the renderer dispatch + add-dialog + grid sizing). Icons from `lucide-react`.

```ts
import type {ComponentType} from 'react'
import {Activity, AlertTriangle, BarChart3, Boxes, GitCommitHorizontal, Layers, ListChecks, Rocket, Server} from 'lucide-react'
import {SystemStatusWidget} from './widgets/SystemStatusWidget'
// ...imports
export interface OverviewWidgetDef {
  label: string; description: string; icon: ComponentType<{className?: string}>
  defaultSize: {w: number; h: number}; minH: number; component: ComponentType<{displayConfig?: Record<string,string>}>
}
export const OVERVIEW_WIDGETS: Record<string, OverviewWidgetDef> = {
  system_status: {label:'System status', description:'Hero triage line + AI summary', icon:AlertTriangle, defaultSize:{w:12,h:3}, minH:2, component:SystemStatusWidget},
  kpi: {label:'KPI', description:'A single metric tile', icon:BarChart3, defaultSize:{w:2,h:3}, minH:2, component:KpiWidget},
  service_health: {label:'Service health', description:'Per-service health table', icon:Server, defaultSize:{w:8,h:10}, minH:6, component:ServiceHealthWidget},
  telemetry: {label:'Telemetry', description:'Errors/latency/throughput/logs', icon:BarChart3, defaultSize:{w:8,h:7}, minH:5, component:TelemetryWidget},
  triage: {label:'Needs attention', description:'Incidents, alerts, issues, security', icon:ListChecks, defaultSize:{w:4,h:17}, minH:6, component:TriageWidget},
  infra_summary: {label:'Infrastructure', description:'Resource gauges + counts', icon:Boxes, defaultSize:{w:3,h:7}, minH:5, component:InfraSummaryWidget},
  uptime_summary: {label:'Uptime & Synthetics', description:'Heartbeats + synthetics', icon:Activity, defaultSize:{w:3,h:7}, minH:5, component:UptimeSummaryWidget},
  deploys: {label:'Deploys', description:'Recent deploys', icon:Rocket, defaultSize:{w:3,h:7}, minH:5, component:DeploysWidget},
  activity: {label:'Activity', description:'Recent events', icon:GitCommitHorizontal, defaultSize:{w:3,h:7}, minH:5, component:ActivityWidget},
}
export function overviewWidgetDef(t: string): OverviewWidgetDef | undefined { return OVERVIEW_WIDGETS[t] }
```

- [ ] Test: every id in `OVERVIEW_WIDGET_TYPES` (Task 1) has an entry in `OVERVIEW_WIDGETS` with a component and `defaultSize.w` in 1..12. → FAIL → implement → PASS → commit `feat(overview): add overview widget registry`.

---

## Task 13: Renderer dispatch for overview widgets

**Files:**
- Modify: `dashboard/src/components/dashboards/ExtendedWidgets.tsx` (in `ExtendedWidgetRenderer`)
- Test: `dashboard/src/components/dashboards/__tests__/overviewWidgetRender.test.tsx`

In `ExtendedWidgetRenderer`, before the existing switch, dispatch overview types via the registry:

```tsx
import {isOverviewWidgetType} from './extendedWidgetTypes'
import {overviewWidgetDef} from '@/components/overview/overviewWidgetTypes'
// inside ExtendedWidgetRenderer({widget, widgetType, ...}):
if (isOverviewWidgetType(widgetType)) {
  const def = overviewWidgetDef(widgetType)
  if (def) {
    const C = def.component
    return <C displayConfig={widget.display_config} />
  }
}
```

- [ ] Test: render `<WidgetRenderer>` with a `service_health` widget (no query) and assert `widget-service_health` testid appears and no query fires (`projectId` undefined). → FAIL → implement → PASS → commit `feat(overview): dispatch overview widgets in renderer`.

---

## Task 14: Chromeless render + edit affordances in DashboardGrid

**Files:**
- Modify: `dashboard/src/components/dashboards/DashboardGrid.tsx`
- Test: `dashboard/src/components/dashboards/__tests__/DashboardGridOverview.test.tsx`

Changes:
1. Import `isOverviewWidgetType`.
2. In `WidgetCard`, when `isOverviewWidgetType(widget.widget_type)`: render a chromeless container (`h-full` rounded border bg-card, **no** header bar, **no** `p-2`) containing `WidgetRenderer`; in `isEditing`, overlay a top-right hover bar with a `.drag-handle` (GripVertical) and delete button (reuse existing handlers). View mode: no chrome.
3. In `needsScaling` memo: also exclude `system_status` and `kpi` (treat like `stat`).
4. In `layout` memo `minH`: use `overviewWidgetDef(type)?.minH` when overview type, else existing logic.

```tsx
// needsScaling exclusion:
const SHORT_TYPES = new Set(['section','stat','gauge','bargauge','text','system_status','kpi'])
// ...some(w => !SHORT_TYPES.has(w.widget_type) && w.grid_h <= 4)

// minH in layout item:
minH: w.widget_type === 'section' ? 1
  : isOverviewWidgetType(w.widget_type) ? (overviewWidgetDef(w.widget_type)?.minH ?? 4)
  : (['stat','gauge','bargauge'].includes(w.widget_type) ? 4 : 6),
```

- [ ] Test: render `DashboardGrid` (isEditing=false) with one `kpi` overview widget + one normal `stat` widget; assert the overview widget has **no** widget header (query `getByText` for its title is absent as a header) while rendering its content, and the normal widget keeps its header. Render again with isEditing=true and assert a `.drag-handle` exists for the overview widget. → FAIL → implement → PASS → commit `feat(overview): render overview widgets chromeless in grid`.

---

## Task 15: Default Overview layout

**Files:**
- Create: `dashboard/src/components/overview/defaultOverviewLayout.ts`
- Test: `dashboard/src/components/overview/__tests__/defaultOverviewLayout.test.ts`

`buildDefaultOverviewDashboard(): CustomDashboard` returns an in-memory dashboard (id `0`, title `'Overview'`, `layout_type:'grid'`, `is_default:true`, `widgets:[...]`). Widgets (12-col grid) mirror the mockup order:
- `system_status` x0 y0 w12 h3
- 6× `kpi` at y3, w2 each (x 0/2/4/6/8/10), h3, `display_config:{kpiId:'errors'|'latency'|'throughput'|'apdex'|'issues'|'uptime'}`
- `service_health` x0 y6 w8 h10
- `triage` x8 y6 w4 h17
- `telemetry` x0 y16 w8 h7
- `infra_summary` x0 y23 w3 h7; `uptime_summary` x3 y23 w3 h7; `deploys` x6 y23 w3 h7; `activity` x9 y23 w3 h7

Each widget: `id` = negative/sequential sentinel (e.g. `-1..-N` so they're stable keys without backend ids), `dashboard_id:0`, `query_configs:[]`, `display_config` as noted, `sort_order` = index, `title` = registry label.

- [ ] Test: dashboard has 14 widgets; all `widget_type`s are in `OVERVIEW_WIDGET_TYPES`; exactly 6 `kpi` with distinct `kpiId`; no two widgets share an `id`; max `grid_x+grid_w` ≤ 12. → FAIL → implement → PASS → commit `feat(overview): add default overview layout`.

---

## Task 16: OverviewHeader

**Files:**
- Create: `dashboard/src/components/overview/OverviewHeader.tsx`
- Test: `dashboard/src/components/overview/__tests__/OverviewHeader.test.tsx`

Props: `{isEditing, onToggleEdit, onReset, onAddWidget, timeRange, onTimeRangeChange, live, onToggleLive, onRefresh}`. Matches mockup `.pagehead`: eyebrow "Workspace overview", title "Overview"; right side: environment `Select` (static "All environments" for now), time-range presets (reuse the preset list shape from `DashboardToolbar`), **Live** toggle (pulsing dot), refresh icon button, and **Customize** button (→ `onToggleEdit`). In edit mode show **Add widget** + **Reset** + **Done**.

- [ ] Test: renders "Overview" + "Customize"; clicking Customize calls `onToggleEdit`; in editing mode shows "Add widget"/"Done" and clicking Reset calls `onReset`. → FAIL → implement → PASS → commit `feat(overview): add overview header`.

---

## Task 17: AddOverviewWidgetDialog

**Files:**
- Create: `dashboard/src/components/overview/AddOverviewWidgetDialog.tsx`
- Test: `dashboard/src/components/overview/__tests__/AddOverviewWidgetDialog.test.tsx`

Props: `{open, onOpenChange, onAdd:(type:string)=>void}`. Uses `Dialog`; lists `OVERVIEW_WIDGETS` entries (icon + label + description) as buttons; clicking calls `onAdd(type)` then closes.

- [ ] Test: when open, shows "Service health" option; clicking it calls `onAdd('service_health')`. → FAIL → implement → PASS → commit `feat(overview): add widget picker dialog`.

---

## Task 18: OverviewDashboard container

**Files:**
- Create: `dashboard/src/components/overview/OverviewDashboard.tsx`
- Test: `dashboard/src/components/overview/__tests__/OverviewDashboard.test.tsx`

Behavior:
- State `widgets: DashboardWidget[]`, initialized from `localStorage['moneat.overview.layout.v1']` if present, else `buildDefaultOverviewDashboard().widgets`.
- Persist widgets to localStorage on change (effect).
- `timeRange` state (default `now-24h`), `live` state, `isEditing` state.
- Render `OverviewHeader` + `DashboardGrid` (dashboardId=0, projectId=undefined, autoRefresh=live) + `AddOverviewWidgetDialog`.
- Handlers: `onLayoutChange` (map `CreateWidgetRequest[]` back to `DashboardWidget[]`, preserving ids), `onWidgetDelete` (filter), `onAdd` (append widget with `overviewWidgetDef(type).defaultSize`, fresh negative id, placed at max-y), `onReset` (restore default + clear localStorage).
- Guard against SSR/prerender: only touch `localStorage` inside effects/handlers (the app prerenders — see `src/prerender`).

- [ ] Test (jsdom): renders default → "checkout-api" present; click "Customize" → "Add widget" appears; open dialog + add "Deploys" → a second deploys testid appears; delete is exercised via handler; click Reset → localStorage key cleared and default restored. Mock `localStorage` via jsdom. → FAIL → implement → PASS → commit `feat(overview): add OverviewDashboard container`.

---

## Task 19: Wire into the authenticated route

**Files:**
- Modify: `dashboard/src/routes/index.tsx` (authenticated `?view=overview` branch)
- Modify: `dashboard/src/routes/__tests__/overview-alert-incident-coverage.test.tsx`

Steps:
- [ ] Read `index.tsx` fully; identify the authenticated overview JSX (from the `isOverviewView` render through the overview body) and the helper components/queries used **only** there (`DashboardStatsOverview`, `DashboardEventsOverview`, `IssueOverviewRow`, `UptimeMonitorOverviewRow`, `InfrastructureHostOverviewRow`, `StatusPageOverviewRow`, and their `useQuery`s).
- [ ] Replace the authenticated overview render with `return <OverviewDashboard />` (keep the auth gate, `isChecking`, `LandingPage`, and `Navigate` redirects intact).
- [ ] Remove the now-unused helpers + imports flagged by lint/tsc.
- [ ] Update `overview-alert-incident-coverage.test.tsx` to assert the new overview renders (e.g., renders `OverviewDashboard` content like "Workspace overview"/"checkout-api") — or, if the test's intent (alert/incident coverage) no longer applies, retarget it to the triage widget. Keep coverage meaningful.
- [ ] Run `npx tsc --noEmit` (catch dead-code/type breakage) and the route test → PASS.
- [ ] Commit `feat(overview): render overview dashboard on the app landing`.

---

## Task 20: Full verification

- [ ] `cd dashboard && npm run lint` → 0 errors.
- [ ] `cd dashboard && npm run build` → success (includes tsc + prerender).
- [ ] `cd dashboard && npm test` → all pass (new + existing).
- [ ] Manual sanity (optional): `moneat dev` and open `/?view=overview`.
- [ ] Final commit if any lint/build fixups: `chore(overview): lint/build fixups`.

---

## Self-Review

**Spec coverage:** shape (Task 13/14/18/19) ✓; 9 widget types (Tasks 3–11) ✓; integration extendedWidgetTypes/ExtendedWidgets/DashboardGrid (Tasks 1/13/14) ✓; mock data layer (Task 2) ✓; default layout + routing (Tasks 15/18/19) ✓; customize+localStorage (Tasks 16/17/18) ✓; tests + verification (every task + Task 20) ✓; out-of-scope (no query/back-end/author-in-config) respected ✓.

**Placeholder scan:** widget JSX intentionally references the cited mockup panel as canonical markup (not a TODO) — each widget task names exact elements + test assertions. Integration/registry/data tasks contain complete code.

**Type consistency:** `OVERVIEW_WIDGET_TYPES`/`isOverviewWidgetType` (Task 1) used in Tasks 13/14/15; `OVERVIEW_WIDGETS`/`overviewWidgetDef`/`OverviewWidgetDef.minH/defaultSize` (Task 12) used in Tasks 14/17/18; `buildDefaultOverviewDashboard` (Task 15) used in Task 18; hook names in Task 2 match widget tasks; `display_config.kpiId` set in Task 15 and read in Task 4. Consistent.
