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
import { urlWithQuery } from '../utils'
import type {
  AlertGroup,
  AlertGroupListFilters,
  AlertRoute,
  AlertRoutePreviewRequest,
  AlertRoutePreviewResponse,
  AlertRouteRequest,
  AlertRouteRevision,
  AttachAlertGroupInput,
  CreateAlertGroupTriageInput,
  ReorderAlertRoutesRequest,
} from '../types'

type MessageResponse = { message: string }

// Enterprise Alert Route and Alert Group APIs. These live under the on-call
// namespace and share the native incident rollout + entitlement gate, so callers
// should hold their queries behind `useNativeIncidentRollout().enabled`.
export function alertRoutesMethods(core: ApiClientCore) {
  const base = core.API_BASE
  const routesBase = `${base}/on-call/alert-routes`
  const groupsBase = `${base}/on-call/alert-groups`
  const routeId = (id: string) => `${routesBase}/${encodeURIComponent(id)}`
  const groupId = (id: string) => `${groupsBase}/${encodeURIComponent(id)}`

  return {
    getAlertRoutes: () => core.request<AlertRoute[]>(routesBase),

    getAlertRoute: (id: string) => core.request<AlertRoute>(routeId(id)),

    getAlertRouteRevisions: (id: string) =>
      core.request<AlertRouteRevision[]>(`${routeId(id)}/revisions`),

    createAlertRoute: (request: AlertRouteRequest) =>
      core.request<AlertRoute>(routesBase, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateAlertRoute: (id: string, request: AlertRouteRequest) =>
      core.request<AlertRoute>(routeId(id), {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteAlertRoute: (id: string, expectedRevision: number) =>
      core.request<MessageResponse>(
        urlWithQuery(routeId(id), `expectedRevision=${expectedRevision}`),
        { method: 'DELETE' }
      ),

    reorderAlertRoutes: (request: ReorderAlertRoutesRequest) =>
      core.request<AlertRoute[]>(`${routesBase}/reorder`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    previewAlertRoutes: (request: AlertRoutePreviewRequest) =>
      core.request<AlertRoutePreviewResponse>(`${routesBase}/preview`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    // ──── Alert Groups ────

    getAlertGroups: (filters: AlertGroupListFilters = {}) => {
      const params = new URLSearchParams()
      if (filters.limit !== undefined) params.append('limit', String(filters.limit))
      if (filters.offset !== undefined) params.append('offset', String(filters.offset))
      return core.request<AlertGroup[]>(urlWithQuery(groupsBase, params.toString()))
    },

    getAlertGroup: (id: string) => core.request<AlertGroup>(groupId(id)),

    markAlertGroupEpisodeUnrelated: (
      group: string,
      episodeId: string,
      expectedVersion: number
    ) =>
      core.request<AlertGroup>(
        `${groupId(group)}/episodes/${encodeURIComponent(episodeId)}/unrelated`,
        { method: 'POST', body: JSON.stringify({ expectedVersion }) }
      ),

    removeAlertGroupEpisode: (
      group: string,
      episodeId: string,
      expectedVersion: number
    ) =>
      core.request<AlertGroup>(
        urlWithQuery(
          `${groupId(group)}/episodes/${encodeURIComponent(episodeId)}`,
          `expectedVersion=${expectedVersion}`
        ),
        { method: 'DELETE' }
      ),

    attachAlertGroup: (group: string, input: AttachAlertGroupInput) =>
      core.request<AlertGroup>(`${groupId(group)}/attach`, {
        method: 'POST',
        body: JSON.stringify(input),
      }),

    createAlertGroupTriage: (group: string, input: CreateAlertGroupTriageInput) =>
      core.request<AlertGroup>(`${groupId(group)}/create-triage`, {
        method: 'POST',
        body: JSON.stringify(input),
      }),
  }
}
