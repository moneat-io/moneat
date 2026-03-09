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
  Project,
  ProjectKey,
  ProjectStats,
  SdkVersionsResponse,
} from '../types'

export function projectsMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getProjects: () => core.request<Project[]>(`${base}/projects`),

    getProject: (projectId: number) =>
      core.request<Project>(`${base}/projects/${projectId}`),

    getSdkVersions: () =>
      core.request<SdkVersionsResponse>(`${base}/sdk-versions`),

    createProject: (
      name: string,
      framework?: string,
      targets?: string[]
    ) =>
      core.request<Project>(`${base}/projects`, {
        method: 'POST',
        body: JSON.stringify({ name, framework, targets }),
      }),

    addProjectTarget: (projectId: number, target: string) =>
      core.request<ProjectKey>(`${base}/projects/${projectId}/targets`, {
        method: 'POST',
        body: JSON.stringify({ target }),
      }),

    updateProject: (
      projectId: number,
      updates: { name?: string; framework?: string }
    ) =>
      core.request(`${base}/projects/${projectId}`, {
        method: 'PUT',
        body: JSON.stringify(updates),
      }),

    deleteProject: (projectId: number) =>
      core.request(`${base}/projects/${projectId}`, { method: 'DELETE' }),

    getProjectStats: (
      projectId: number,
      period: '24h' | '7d' | '30d' | '90d' = '7d'
    ) =>
      core.request<ProjectStats>(
        `${base}/projects/${projectId}/stats?period=${period}`
      ),
  }
}
