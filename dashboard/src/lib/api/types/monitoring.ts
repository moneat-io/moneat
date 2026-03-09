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

export interface LatestMetrics {
  cpu_percent: number
  mem_total: number
  mem_used: number
  mem_percent: number
  disk_total: number
  disk_used: number
  disk_percent: number
  net_recv_bytes: number
  net_sent_bytes: number
  net_recv_mbps?: number | null
  net_sent_mbps?: number | null
  load_1: number
  temp_max?: number | null
  gpu_percent?: number | null
  battery_percent?: number | null
}

export interface MonitorHostResponse {
  id: number
  project_id?: number
  name: string
  hostname: string
  status: string
  last_seen_at?: number | string | null
  first_seen_at?: number | string | null
  agent_version?: string | null
  os?: string | null
  arch?: string | null
  platform?: string | null
  processor?: string | null
  cpu_cores?: number | null
  memory_total_kb?: number | null
  created_at: number
  latest_metrics?: LatestMetrics | null
}

export interface HistoricalDataPoint {
  timestamp: number
  cpu_percent?: number
  mem_percent?: number
  disk_percent?: number
  net_recv_bytes?: number
  net_sent_bytes?: number
  load_1?: number
  load_5?: number
  load_15?: number
  temp_max?: number
  gpu_percent?: number
  battery_percent?: number
}

export interface SystemMetricsHistory {
  data_points: HistoricalDataPoint[]
}

export interface ContainerStats {
  name: string
  id: string
  image: string
  status: string
  cpuPercent?: number
  memUsed?: number
  memLimit?: number
  netRecvBytes?: number
  netSentBytes?: number
}

export interface RawContainerStats {
  name: string
  id: string
  image: string
  status: string
  cpuPercent?: number
  cpu_percent?: number
  memUsed?: number
  mem_used?: number
  memLimit?: number
  mem_limit?: number
  netRecvBytes?: number
  net_recv_bytes?: number
  netSentBytes?: number
  net_sent_bytes?: number
}

export interface ContainerHistoricalDataPoint {
  timestamp: number
  cpu_percent: number
  mem_used: number
  mem_limit: number
  net_recv_bytes: number
  net_sent_bytes: number
}

export interface ContainerMetricsHistory {
  data_points: ContainerHistoricalDataPoint[]
}

export interface HostAlert {
  id: number
  hostId?: number
  scope: 'global' | 'host'
  metric: string
  condition: string
  threshold: number
  durationSeconds: number
  enabled: boolean
  incidentSeverity?: string | null
  lastTriggeredAt?: number
  createdAt: number
}

export interface HostAlertConfig {
  scope: 'global' | 'host'
  globalAlerts: HostAlert[]
  hostAlerts: HostAlert[]
  effectiveAlerts: HostAlert[]
}

export interface SilencePeriod {
  id: number
  organizationId: number
  reason: string | null
  startsAt: number
  endsAt: number
  createdBy: number
  createdAt: number
}

export interface CreateSilencePeriodRequest {
  reason?: string
  starts_at: number
  ends_at: number
}
