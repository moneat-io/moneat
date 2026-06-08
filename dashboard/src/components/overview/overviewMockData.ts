// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

// Mock data layer for the Overview dashboard widgets.
//
// These types are the contract the backend will fulfil later. Each `useXxx()`
// hook is the single seam to swap for a real `api.*` / `useQuery` call — for now
// they return static fixtures (mirroring the design mockup) so the UI is fully
// functional without a backend.

export type Tone = 'good' | 'warn' | 'bad' | 'neutral'

export interface SystemStatusData {
  state: string
  severity: Tone
  counts: {incidents: number; alerts: number; degraded: number; hostsOffline: number}
  ai: {summary: string; incidentId?: string}
}

export interface KpiDelta {
  value: string
  direction?: 'up' | 'down'
  tone: Tone
}

export interface Kpi {
  id: string
  label: string
  value: string
  unit?: string
  delta: KpiDelta
  status: Tone
  spark: number[]
}

export interface ServiceRow {
  name: string
  env: string
  status: Tone
  reqPerMin: number
  errorPct: number
  p95Ms: number | null
  apdex: number | null
  /** errors-over-24h sparkline */
  trend: number[]
  issues: number
  /** optional badge shown in place of apdex (e.g. queue lag) */
  lag?: string
  deploy: {version: string; ageLabel: string; tone: Tone}
}

export type TelemetrySeriesKey = 'errors' | 'latency' | 'throughput' | 'logs'

export interface TelemetryData {
  errors: number[]
  latency: number[]
  throughput: number[]
  logs: number[]
  /** horizontal position (0-100) of the deploy marker */
  deployAtPct: number
  deployLabel: string
}

export interface IncidentItem {
  id: string
  title: string
  priority: string
  status: string
  owner: string
  ageLabel: string
}

export interface AlertItem {
  title: string
  detail: string
  level: 'error' | 'warn'
  ageLabel: string
}

export interface IssueItem {
  level: 'fatal' | 'error' | 'warn' | 'info'
  title: string
  detail: string
  ageLabel: string
}

export interface SecurityItem {
  title: string
  detail: string
  level: 'error' | 'warn'
  ageLabel: string
}

export interface TriageData {
  incidents: IncidentItem[]
  alerts: AlertItem[]
  issues: IssueItem[]
  security: SecurityItem[]
}

export interface InfraGauge {
  label: string
  pct: number
  tone: Tone
}

export interface InfraData {
  gauges: InfraGauge[]
  containers: number
  pods: number
  upLabel: string
  offlineNode?: string
}

export interface UptimeMonitor {
  name: string
  bars: ('up' | 'warn' | 'down')[]
  uptimeLabel: string
  down?: boolean
}

export interface UptimeData {
  monitors: UptimeMonitor[]
  upLabel: string
  syntheticFailing?: string
  statusPages: string
}

export interface DeployRow {
  version: string
  service: string
  status: Tone
  label: string
  ageLabel: string
}

export type ActivityKind = 'incident' | 'flag' | 'deploy' | 'workflow' | 'replay' | 'feedback'

export interface ActivityItem {
  kind: ActivityKind
  text: string
  meta: string
}

export interface OverviewFixtures {
  systemStatus: SystemStatusData
  kpis: Kpi[]
  serviceHealth: ServiceRow[]
  telemetry: TelemetryData
  triage: TriageData
  infra: InfraData
  uptime: UptimeData
  deploys: DeployRow[]
  activity: ActivityItem[]
}

export const OVERVIEW_FIXTURES: OverviewFixtures = {
  systemStatus: {
    state: 'Action needed',
    severity: 'bad',
    counts: {incidents: 1, alerts: 3, degraded: 2, hostsOffline: 1},
    ai: {
      summary:
        'error rate on checkout-api is up +312% with p95 +38%, beginning ~2 min after deploy v2.4.1. Likely root cause of INC-204.',
      incidentId: 'INC-204',
    },
  },
  kpis: [
    {
      id: 'errors',
      label: 'Errors · 24h',
      value: '48.3k',
      delta: {value: '312%', direction: 'up', tone: 'bad'},
      status: 'bad',
      spark: [6, 7, 5, 8, 6, 7, 9, 12, 18, 25, 27],
    },
    {
      id: 'latency',
      label: 'p95 latency',
      value: '412',
      unit: 'ms',
      delta: {value: '38%', direction: 'up', tone: 'bad'},
      status: 'warn',
      spark: [9, 8, 10, 9, 11, 10, 12, 14, 17, 21, 23],
    },
    {
      id: 'throughput',
      label: 'Throughput',
      value: '18.2k',
      unit: 'req/min',
      delta: {value: '4.1%', direction: 'down', tone: 'bad'},
      status: 'neutral',
      spark: [18, 19, 17, 20, 18, 19, 17, 18, 15, 13, 11],
    },
    {
      id: 'apdex',
      label: 'Apdex',
      value: '0.86',
      delta: {value: '0.08', direction: 'down', tone: 'bad'},
      status: 'warn',
      spark: [23, 22, 23, 21, 22, 20, 19, 17, 14, 12, 11],
    },
    {
      id: 'issues',
      label: 'Open issues',
      value: '37',
      unit: '+6 new',
      delta: {value: '19%', direction: 'up', tone: 'bad'},
      status: 'bad',
      spark: [10, 11, 10, 12, 11, 13, 14, 16, 19, 22, 24],
    },
    {
      id: 'uptime',
      label: 'Uptime · 24h',
      value: '99.95',
      unit: '%',
      delta: {value: 'SLO 99.9', tone: 'neutral'},
      status: 'good',
      spark: [24, 24, 23, 24, 24, 22, 24, 24, 23, 24, 24],
    },
  ],
  serviceHealth: [
    {
      name: 'checkout-api', env: 'prod', status: 'bad', reqPerMin: 3108, errorPct: 6.8,
      p95Ms: 920, apdex: 0.71, trend: [4, 5, 4, 6, 7, 9, 14, 19, 24, 30],
      issues: 14, deploy: {version: 'v2.4.1', ageLabel: '14m', tone: 'bad'},
    },
    {
      name: 'payments-api', env: 'prod', status: 'warn', reqPerMin: 2440, errorPct: 1.2,
      p95Ms: 480, apdex: 0.88, trend: [9, 8, 10, 9, 11, 10, 12, 13, 14, 15],
      issues: 5, deploy: {version: 'v1.9.0', ageLabel: '2h', tone: 'neutral'},
    },
    {
      name: 'web-frontend', env: 'prod', status: 'good', reqPerMin: 9820, errorPct: 0.3,
      p95Ms: 210, apdex: 0.97, trend: [16, 15, 16, 15, 16, 15, 16, 15, 16, 15],
      issues: 6, deploy: {version: 'v3.4.2', ageLabel: '5h', tone: 'neutral'},
    },
    {
      name: 'auth-service', env: 'prod', status: 'good', reqPerMin: 4210, errorPct: 0.1,
      p95Ms: 95, apdex: 0.99, trend: [7, 6, 7, 7, 6, 7, 7, 6, 7, 7],
      issues: 1, deploy: {version: 'v2.1.0', ageLabel: '1d', tone: 'neutral'},
    },
    {
      name: 'search-api', env: 'prod', status: 'good', reqPerMin: 1604, errorPct: 0.4,
      p95Ms: 180, apdex: 0.96, trend: [15, 16, 14, 16, 15, 14, 16, 15, 14, 15],
      issues: 3, deploy: {version: 'v0.8.4', ageLabel: '3h', tone: 'neutral'},
    },
    {
      name: 'inventory-svc', env: 'prod', status: 'good', reqPerMin: 1120, errorPct: 0.2,
      p95Ms: 140, apdex: 0.98, trend: [16, 15, 16, 15, 16, 16, 15, 16, 16, 15],
      issues: 0, deploy: {version: 'v4.0.0', ageLabel: '2d', tone: 'neutral'},
    },
    {
      name: 'notifications-worker', env: 'prod', status: 'neutral', reqPerMin: 820, errorPct: 0.6,
      p95Ms: null, apdex: null, trend: [15, 16, 15, 17, 15, 16, 15, 15, 16, 15],
      issues: 2, deploy: {version: 'v1.2.1', ageLabel: '6h', tone: 'neutral'},
    },
    {
      name: 'analytics-pipeline', env: 'prod', status: 'warn', reqPerMin: 240, errorPct: 0.9,
      p95Ms: null, apdex: null, lag: 'lag 4m', trend: [16, 15, 17, 14, 16, 13, 15, 12, 14, 13],
      issues: 6, deploy: {version: 'v2.7.3', ageLabel: '8h', tone: 'neutral'},
    },
  ],
  telemetry: {
    errors: [12, 14, 11, 13, 12, 14, 11, 13, 12, 14, 11, 13, 12, 14, 11, 13, 16, 22, 38, 64, 92, 108, 116, 120],
    latency: [104, 100, 106, 102, 98, 104, 99, 102, 97, 100, 96, 99, 95, 98, 93, 96, 110, 140, 180, 260, 320, 360, 395, 412],
    throughput: [60, 54, 58, 50, 56, 48, 54, 46, 52, 48, 50, 46, 52, 48, 54, 50, 58, 62, 64, 72, 80, 86, 84, 82],
    logs: [36, 40, 34, 42, 38, 44, 36, 40, 42, 38, 44, 40, 46, 42, 44, 48, 52, 60, 72, 92, 104, 108, 112, 116],
    deployAtPct: 84,
    deployLabel: 'v2.4.1',
  },
  triage: {
    incidents: [
      {id: 'INC-204', title: 'Elevated 5xx on checkout-api', priority: 'P1', status: 'TRIGGERED', owner: 'M. Chen', ageLabel: '12m'},
    ],
    alerts: [
      {title: '5xx error rate > 2%', detail: 'checkout-api · now 6.8%', level: 'error', ageLabel: '2m'},
      {title: 'p95 latency > 400ms', detail: 'payments-api · now 480ms', level: 'warn', ageLabel: '6m'},
      {title: 'Disk usage > 85%', detail: 'db-prod-2 · now 91%', level: 'warn', ageLabel: '22m'},
    ],
    issues: [
      {level: 'fatal', title: "TypeError: cannot read 'token'", detail: 'checkout-api · 1,204 events · 318 users', ageLabel: '9m'},
      {level: 'error', title: 'Timeout in PaymentGateway.charge', detail: 'payments-api · 642 events · 96 users', ageLabel: '17m'},
    ],
    security: [
      {title: 'Credential brute-force', detail: '/login · 412 attempts · 1 source IP', level: 'error', ageLabel: '8m'},
      {title: 'New admin API key created', detail: 'svc-deploy · off-hours', level: 'warn', ageLabel: '40m'},
    ],
  },
  infra: {
    gauges: [
      {label: 'CPU', pct: 71, tone: 'warn'},
      {label: 'Mem', pct: 64, tone: 'good'},
      {label: 'Disk', pct: 82, tone: 'warn'},
      {label: 'Net', pct: 38, tone: 'good'},
    ],
    containers: 412,
    pods: 318,
    upLabel: '23/24 up',
    offlineNode: 'worker-node-7',
  },
  uptime: {
    monitors: [
      {name: 'app.acme.io', bars: ['up', 'up', 'up', 'up', 'up', 'up', 'up', 'up', 'up', 'up', 'up', 'up'], uptimeLabel: '100%'},
      {name: 'api.acme.io', bars: ['up', 'up', 'up', 'up', 'up', 'up', 'up', 'up', 'up', 'warn', 'up', 'up'], uptimeLabel: '99.8%'},
      {name: 'checkout flow', bars: ['up', 'up', 'up', 'up', 'up', 'up', 'up', 'up', 'down', 'down', 'down', 'down'], uptimeLabel: 'DOWN', down: true},
      {name: 'cdn.acme.io', bars: ['up', 'up', 'up', 'up', 'up', 'up', 'up', 'up', 'up', 'up', 'up', 'up'], uptimeLabel: '100%'},
    ],
    upLabel: '18/19 up',
    syntheticFailing: 'eu-west',
    statusPages: '2 status pages · operational',
  },
  deploys: [
    {version: 'v2.4.1', service: 'checkout-api', status: 'bad', label: 'regressing', ageLabel: '14m'},
    {version: 'v1.9.0', service: 'payments-api', status: 'good', label: '99.4% cf', ageLabel: '2h'},
    {version: 'v0.8.4', service: 'search-api', status: 'good', label: '99.9% cf', ageLabel: '3h'},
    {version: 'v3.4.2', service: 'web-frontend', status: 'good', label: '99.9% cf', ageLabel: '5h'},
    {version: 'v1.2.1', service: 'notifications-worker', status: 'neutral', label: 'stable', ageLabel: '6h'},
  ],
  activity: [
    {kind: 'incident', text: 'Alert 5xx>2% triggered INC-204', meta: 'automation · 2m ago'},
    {kind: 'flag', text: 'Flag new-checkout-ui rolled to 100%', meta: 'j.rivera · 22m ago'},
    {kind: 'deploy', text: 'Deployed checkout-api v2.4.1', meta: 'a.elder · 14m ago'},
    {kind: 'workflow', text: 'Workflow nightly-etl failed at step 3', meta: 'scheduler · 38m ago'},
    {kind: 'replay', text: 'Replay with 8 errors · checkout', meta: 'user j.doe · 1h ago'},
    {kind: 'feedback', text: 'Feedback: “Payment button not working”', meta: 'support · 2h ago'},
  ],
}

export function useSystemStatus(): SystemStatusData {
  return OVERVIEW_FIXTURES.systemStatus
}
export function useKpis(): Kpi[] {
  return OVERVIEW_FIXTURES.kpis
}
export function useServiceHealth(): ServiceRow[] {
  return OVERVIEW_FIXTURES.serviceHealth
}
export function useTelemetry(): TelemetryData {
  return OVERVIEW_FIXTURES.telemetry
}
export function useTriage(): TriageData {
  return OVERVIEW_FIXTURES.triage
}
export function useInfraSummary(): InfraData {
  return OVERVIEW_FIXTURES.infra
}
export function useUptimeSummary(): UptimeData {
  return OVERVIEW_FIXTURES.uptime
}
export function useDeploys(): DeployRow[] {
  return OVERVIEW_FIXTURES.deploys
}
export function useActivity(): ActivityItem[] {
  return OVERVIEW_FIXTURES.activity
}
