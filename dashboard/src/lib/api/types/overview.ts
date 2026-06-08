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

export type OverviewTone = 'good' | 'warn' | 'bad' | 'neutral'
export type OverviewSeriesKey = 'errors' | 'latency' | 'throughput' | 'logs'
export type OverviewActivityKind = 'incident' | 'flag' | 'deploy' | 'workflow' | 'replay' | 'feedback'
export type OverviewHeartbeatState = 'up' | 'warn' | 'down'

export interface OverviewResponse {
  systemStatus: OverviewSystemStatus
  kpis: OverviewKpi[]
  serviceHealth: OverviewServiceRow[]
  telemetry: OverviewTelemetryData
  triage: OverviewTriageData
  infra: OverviewInfraData
  uptime: OverviewUptimeData
  deploys: OverviewDeployRow[]
  activity: OverviewActivityItem[]
}

export interface OverviewSystemStatus {
  state: string
  severity: OverviewTone
  counts: OverviewCounts
  ai: OverviewStatusAi
}

export interface OverviewCounts {
  incidents: number
  alerts: number
  degraded: number
  hostsOffline: number
}

export interface OverviewStatusAi {
  summary: string
  incidentId?: string | null
}

export interface OverviewKpi {
  id: string
  label: string
  value: string
  unit?: string | null
  delta: OverviewKpiDelta
  status: OverviewTone
  spark: number[]
}

export interface OverviewKpiDelta {
  value: string
  direction?: 'up' | 'down' | null
  tone: OverviewTone
}

export interface OverviewServiceRow {
  name: string
  env: string
  status: OverviewTone
  reqPerMin: number
  errorPct: number
  p95Ms: number | null
  apdex: number | null
  trend: number[]
  issues: number
  lag?: string | null
  deploy: OverviewServiceDeploy
}

export interface OverviewServiceDeploy {
  version: string
  ageLabel: string
  tone: OverviewTone
}

export interface OverviewTelemetryData {
  errors: number[]
  latency: number[]
  throughput: number[]
  logs: number[]
  deployAtPct: number
  deployLabel: string
}

export interface OverviewTriageData {
  incidents: OverviewIncidentItem[]
  alerts: OverviewAlertItem[]
  issues: OverviewIssueItem[]
  security: OverviewSecurityItem[]
}

export interface OverviewIncidentItem {
  id: string
  title: string
  priority: string
  status: string
  owner: string
  ageLabel: string
}

export interface OverviewAlertItem {
  title: string
  detail: string
  level: 'error' | 'warn'
  ageLabel: string
}

export interface OverviewIssueItem {
  level: 'fatal' | 'error' | 'warn' | 'info'
  title: string
  detail: string
  ageLabel: string
}

export interface OverviewSecurityItem {
  title: string
  detail: string
  level: 'error' | 'warn'
  ageLabel: string
}

export interface OverviewInfraData {
  gauges: OverviewInfraGauge[]
  containers: number
  pods: number
  upLabel: string
  offlineNode?: string | null
}

export interface OverviewInfraGauge {
  label: string
  pct: number
  tone: OverviewTone
}

export interface OverviewUptimeData {
  monitors: OverviewUptimeMonitor[]
  upLabel: string
  syntheticFailing?: string | null
  statusPages: string
}

export interface OverviewUptimeMonitor {
  name: string
  bars: OverviewHeartbeatState[]
  uptimeLabel: string
  down?: boolean
}

export interface OverviewDeployRow {
  version: string
  service: string
  status: OverviewTone
  label: string
  ageLabel: string
}

export interface OverviewActivityItem {
  kind: OverviewActivityKind
  text: string
  meta: string
}
