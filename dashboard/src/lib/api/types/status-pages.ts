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

export interface StatusPage {
  id: string
  organizationId: string
  name: string
  slug: string
  description?: string
  logoUrl?: string
  faviconUrl?: string
  primaryColor: string
  darkMode: boolean
  showUptimeHistory: boolean
  historyDays: number
  isPublic: boolean
  createdAt: string
  updatedAt: string
}

export interface StatusPageMonitor {
  id: number
  monitorId: string
  monitorName: string
  displayName?: string
  sortOrder: number
  url?: string
}

export interface MonitorAssignment {
  monitorId: string
  displayName?: string
  sortOrder: number
}

export interface IncidentUpdate {
  id: string
  status: string
  message: string
  createdAt: string
}

export interface StatusPageIncident {
  id: string
  statusPageId: string
  title: string
  status: string
  type: string
  impact: string
  scheduledStartAt?: string
  scheduledEndAt?: string
  resolvedAt?: string
  createdAt: string
  updatedAt: string
  updates: IncidentUpdate[]
}

export interface CustomDomain {
  id: number
  domain: string
  verificationToken: string
  verified: boolean
  verifiedAt?: string
  sslProvisioned: boolean
  createdAt: string
}

export interface StatusPageDetail extends StatusPage {
  monitors: StatusPageMonitor[]
  customDomains: CustomDomain[]
}

export interface PublicStatusPage {
  name: string
  description?: string
  logoUrl?: string
  faviconUrl?: string
  primaryColor: string
  darkMode: boolean
  showUptimeHistory: boolean
  historyDays: number
  monitors: PublicMonitorStatus[]
  activeIncidents: StatusPageIncident[]
  scheduledMaintenance: StatusPageIncident[]
}

export interface PublicMonitorStatus {
  name: string
  displayName?: string
  status: string
  uptimePercentage: number
  uptimeHistory?: UptimeDataPoint[]
}

export interface UptimeDataPoint {
  date: string
  uptime: number
}

export interface CreateStatusPageRequest {
  name: string
  slug: string
  description?: string
  logoUrl?: string
  faviconUrl?: string
  primaryColor?: string
  darkMode?: boolean
  showUptimeHistory?: boolean
  historyDays?: number
  isPublic?: boolean
}

export interface UpdateStatusPageRequest {
  name?: string
  slug?: string
  description?: string
  logoUrl?: string
  faviconUrl?: string
  primaryColor?: string
  darkMode?: boolean
  showUptimeHistory?: boolean
  historyDays?: number
  isPublic?: boolean
}

export interface CreateIncidentRequest {
  title: string
  status: string
  type?: string
  impact?: string
  message: string
  scheduledStartAt?: string
  scheduledEndAt?: string
}

export interface UpdateIncidentRequest {
  title?: string
  status?: string
  impact?: string
  scheduledStartAt?: string
  scheduledEndAt?: string
}

export interface CreateIncidentUpdateRequest {
  status: string
  message: string
}

export interface AddCustomDomainRequest {
  domain: string
}
