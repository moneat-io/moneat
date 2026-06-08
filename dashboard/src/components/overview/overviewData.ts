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

import {createContext, useContext} from 'react'
import type {
  OverviewActivityItem,
  OverviewActivityKind,
  OverviewDeployRow,
  OverviewInfraData,
  OverviewKpi,
  OverviewResponse,
  OverviewSeriesKey,
  OverviewServiceRow,
  OverviewSystemStatus,
  OverviewTelemetryData,
  OverviewTone,
  OverviewTriageData,
  OverviewUptimeData,
} from '@/lib/api/types'

export type Tone = OverviewTone
export type ActivityKind = OverviewActivityKind
export type TelemetrySeriesKey = OverviewSeriesKey
export type ServiceRow = OverviewServiceRow

export interface OverviewDataState {
  overview: OverviewResponse
  isLoading: boolean
  isError: boolean
}

export const EMPTY_OVERVIEW: OverviewResponse = {
  systemStatus: {
    state: 'Healthy',
    severity: 'good',
    counts: {incidents: 0, alerts: 0, degraded: 0, hostsOffline: 0},
    ai: {summary: ''},
  },
  kpis: [],
  serviceHealth: [],
  telemetry: {errors: [], latency: [], throughput: [], logs: [], deployAtPct: 0, deployLabel: ''},
  triage: {incidents: [], alerts: [], issues: [], security: []},
  infra: {gauges: [], containers: 0, pods: 0, upLabel: '0/0 up'},
  uptime: {monitors: [], upLabel: '0/0 up', statusPages: '0 status pages'},
  deploys: [],
  activity: [],
}

export const OverviewDataContext = createContext<OverviewDataState>({
  overview: EMPTY_OVERVIEW,
  isLoading: false,
  isError: false,
})

export function useOverviewData(): OverviewDataState {
  return useContext(OverviewDataContext)
}

export function useSystemStatus(): OverviewSystemStatus {
  return useOverviewData().overview.systemStatus
}

export function useKpis(): OverviewKpi[] {
  return useOverviewData().overview.kpis
}

export function useServiceHealth(): OverviewServiceRow[] {
  return useOverviewData().overview.serviceHealth
}

export function useTelemetry(): OverviewTelemetryData {
  return useOverviewData().overview.telemetry
}

export function useTriage(): OverviewTriageData {
  return useOverviewData().overview.triage
}

export function useInfraSummary(): OverviewInfraData {
  return useOverviewData().overview.infra
}

export function useUptimeSummary(): OverviewUptimeData {
  return useOverviewData().overview.uptime
}

export function useDeploys(): OverviewDeployRow[] {
  return useOverviewData().overview.deploys
}

export function useActivity(): OverviewActivityItem[] {
  return useOverviewData().overview.activity
}
