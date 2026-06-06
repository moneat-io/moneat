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

export interface DdProcessResponse {
  processId: string
  host: string
  pid: number
  name: string
  command: string
  user: string
  cpuPercent: number
  memRss: number
  memVms: number
  state: string
  threadCount: number
  openFdCount: number
  tags: Record<string, string>
  timestamp: string
}

export interface DdProcessListResponse {
  processes: DdProcessResponse[]
  totalCount: number
}

export type InfrastructureResourceKind = 'hosts' | 'containers'
export type InfrastructureGroupBy =
  | 'status'
  | 'host'
  | 'platform'
  | 'os'
  | 'agent'
  | 'image'
  | 'tag:env'
  | 'tag:service'
  | 'tag:region'
export type InfrastructureFillBy = 'health' | 'cpu' | 'memory' | 'network' | 'lastSeen'
export type InfrastructureSizeBy = 'uniform' | 'cpu' | 'memory' | 'network'

export interface InfrastructureMapViewState {
  resourceKind: InfrastructureResourceKind
  groupBy: InfrastructureGroupBy
  fillBy: InfrastructureFillBy
  sizeBy: InfrastructureSizeBy
  searchQuery: string
}

export interface SavedInfrastructureMapView extends InfrastructureMapViewState {
  id: string
  name: string
  createdAt: string
  updatedAt: string
  schemaVersion: number
}

export interface InfrastructureMapSavedViewsResponse {
  views: SavedInfrastructureMapView[]
}

export interface SaveInfrastructureMapViewRequest extends InfrastructureMapViewState {
  name: string
}

export interface DdContainerResponse {
  id: string
  host: string
  containerId: string
  name: string
  image: string
  state: string
  cpuPercent: number
  memUsage: number
  memLimit: number
  netRxBytes: number
  netTxBytes: number
  source?: string | null
  tags: Record<string, string>
  timestamp: string
}

export interface DdContainerListResponse {
  containers: DdContainerResponse[]
  totalCount: number
}

export interface DdConnectionResponse {
  connectionId: string
  host: string
  pid: number
  localAddr: string
  localPort: number
  remoteAddr: string
  remotePort: number
  protocol: string
  family: string
  direction: string
  bytesSent: number
  bytesRecv: number
  tags: Record<string, string>
  timestamp: string
}

export interface DdConnectionListResponse {
  connections: DdConnectionResponse[]
  totalCount: number
}
