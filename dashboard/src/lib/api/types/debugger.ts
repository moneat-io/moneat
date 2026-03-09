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

export type DebuggerProbeType =
  | 'log_probe'
  | 'snapshot'
  | 'span_decoration'
  | 'metric_probe'
export type DebuggerProbeWhereType = 'method' | 'line'
export type DebuggerMetricKind = 'count' | 'gauge' | 'histogram'

export interface DebuggerProbe {
  id: string
  organizationId: number
  probeType: DebuggerProbeType
  service: string
  environment: string
  language: string
  active: boolean
  whereType: DebuggerProbeWhereType
  typeName?: string | null
  methodName?: string | null
  sourceFile?: string | null
  sourceLines?: string | null
  template?: string | null
  metricName?: string | null
  metricKind?: DebuggerMetricKind | null
  tags?: string | null
  captureConfig?: string | null
  createdBy?: number | null
  createdAt: string
  updatedAt: string
}

export interface CreateDebuggerProbeRequest {
  probeType: DebuggerProbeType
  service: string
  environment?: string
  language?: string
  active?: boolean
  whereType?: DebuggerProbeWhereType
  typeName?: string
  methodName?: string
  sourceFile?: string
  sourceLines?: string
  template?: string
  metricName?: string
  metricKind?: DebuggerMetricKind
  tags?: string
  captureConfig?: string
}

export interface UpdateDebuggerProbeRequest {
  probeType?: DebuggerProbeType
  service?: string
  environment?: string
  language?: string
  active?: boolean
  whereType?: DebuggerProbeWhereType
  typeName?: string
  methodName?: string
  sourceFile?: string
  sourceLines?: string
  template?: string
  metricName?: string
  metricKind?: DebuggerMetricKind
  tags?: string
  captureConfig?: string
}
