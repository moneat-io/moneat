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

import type { ApiClientCore } from '../client'
import type {
  StatusPage,
  StatusPageDetail,
  StatusPageMonitor,
  MonitorAssignment,
  StatusPageIncident,
  CustomDomain,
  PublicStatusPage,
  CreateStatusPageRequest,
  UpdateStatusPageRequest,
  CreateIncidentRequest,
  UpdateIncidentRequest,
  CreateIncidentUpdateRequest,
} from '../types'

export function statusPagesMethods(core: ApiClientCore) {
  const base = core.API_BASE
  const backendUrl = base.replace(/\/v1$/, '') || 'https://api.moneat.io'

  return {
    getStatusPages: () =>
      core.request<StatusPage[]>(`${base}/status-pages`),

    getStatusPage: (pageId: string) =>
      core.request<StatusPageDetail>(`${base}/status-pages/${pageId}`),

    createStatusPage: (data: CreateStatusPageRequest) =>
      core.request<StatusPage>(`${base}/status-pages`, {
        method: 'POST',
        body: JSON.stringify(data),
      }),

    updateStatusPage: (
      pageId: string,
      data: UpdateStatusPageRequest
    ) =>
      core.request<StatusPage>(`${base}/status-pages/${pageId}`, {
        method: 'PUT',
        body: JSON.stringify(data),
      }),

    deleteStatusPage: (pageId: string) =>
      core.request<void>(`${base}/status-pages/${pageId}`, {
        method: 'DELETE',
      }),

    addMonitorsToStatusPage: (
      pageId: string,
      monitors: MonitorAssignment[]
    ) =>
      core.request<StatusPageMonitor[]>(
        `${base}/status-pages/${pageId}/monitors`,
        {
          method: 'POST',
          body: JSON.stringify({ monitors }),
        }
      ),

    removeMonitorFromStatusPage: (
      pageId: string,
      monitorId: string
    ) =>
      core.request<void>(
        `${base}/status-pages/${pageId}/monitors/${monitorId}`,
        {
          method: 'DELETE',
        }
      ),

    getStatusPageIncidents: (pageId: string) =>
      core.request<StatusPageIncident[]>(
        `${base}/status-pages/${pageId}/incidents`
      ),

    createIncident: (
      pageId: string,
      data: CreateIncidentRequest
    ) =>
      core.request<StatusPageIncident>(
        `${base}/status-pages/${pageId}/incidents`,
        {
          method: 'POST',
          body: JSON.stringify(data),
        }
      ),

    updateIncident: (
      pageId: string,
      incidentId: string,
      data: UpdateIncidentRequest
    ) =>
      core.request<StatusPageIncident>(
        `${base}/status-pages/${pageId}/incidents/${incidentId}`,
        {
          method: 'PUT',
          body: JSON.stringify(data),
        }
      ),

    createIncidentUpdate: (
      pageId: string,
      incidentId: string,
      data: CreateIncidentUpdateRequest
    ) =>
      core.request<StatusPageIncident>(
        `${base}/status-pages/${pageId}/incidents/${incidentId}/updates`,
        {
          method: 'POST',
          body: JSON.stringify(data),
        }
      ),

    addCustomDomain: (pageId: string, domain: string) =>
      core.request<CustomDomain>(
        `${base}/status-pages/${pageId}/domains`,
        {
          method: 'POST',
          body: JSON.stringify({ domain }),
        }
      ),

    verifyCustomDomain: (pageId: string, domainId: number) =>
      core.request<CustomDomain>(
        `${base}/status-pages/${pageId}/domains/${domainId}/verify`,
        {
          method: 'POST',
        }
      ),

    removeCustomDomain: (pageId: string, domainId: number) =>
      core.request<void>(
        `${base}/status-pages/${pageId}/domains/${domainId}`,
        {
          method: 'DELETE',
        }
      ),

    getPublicStatusPage: async (slug: string) => {
      const publicUrl = `${backendUrl}/public/status/${encodeURIComponent(slug)}`
      const response = await fetch(publicUrl)
      if (!response.ok) {
        throw new Error('Failed to fetch public status page')
      }
      return response.json() as Promise<PublicStatusPage>
    },

    getPublicStatusPageByDomain: async (domain: string) => {
      const publicUrl = `${backendUrl}/public/status/domain/${encodeURIComponent(domain)}`
      const response = await fetch(publicUrl)
      if (!response.ok) {
        throw new Error('Failed to fetch public status page')
      }
      return response.json() as Promise<PublicStatusPage>
    },
  }
}
