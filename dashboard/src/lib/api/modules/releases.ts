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
import type { Release, ReleaseStats } from '../types'
import { urlWithQuery } from '../utils'
import { serviceScopeQuery, type ServiceScopeParams } from './service-scope'

export function releasesMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getReleases: (projectId: string | number) =>
      core.request<Release[]>(`${base}/projects/${projectId}/releases`),

    getOrganizationReleases: (params: ServiceScopeParams = {}) =>
      core.request<Release[]>(
        urlWithQuery(`${base}/releases`, serviceScopeQuery(params))
      ),

    getReleaseStats: (projectId: string | number, version: string) =>
      core.request<ReleaseStats>(
        `${base}/projects/${projectId}/releases/${encodeURIComponent(version)}/stats`
      ),

    getOrganizationReleaseStats: (version: string, params: ServiceScopeParams = {}) =>
      core.request<ReleaseStats>(
        urlWithQuery(`${base}/releases/${encodeURIComponent(version)}/stats`, serviceScopeQuery(params))
      ),
  }
}
