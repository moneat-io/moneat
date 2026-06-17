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

import type {ApiClientCore} from '../client'
import type {
  CreateWorkflowConnectionGroupRequest,
  CreateWorkflowConnectionRequest,
  RotateWorkflowConnectionRequest,
  WorkflowConnection,
  WorkflowConnectionGroup,
} from '../types'

export function workflowConnectionsMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    listWorkflowConnections: () =>
      core.request<WorkflowConnection[]>(`${base}/workflows/connections`),

    createWorkflowConnection: (request: CreateWorkflowConnectionRequest) =>
      core.request<WorkflowConnection>(`${base}/workflows/connections`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    rotateWorkflowConnection: (id: string, request: RotateWorkflowConnectionRequest) =>
      core.request<WorkflowConnection>(
        `${base}/workflows/connections/${encodeURIComponent(id)}/rotate`,
        {
        method: 'PUT',
        body: JSON.stringify(request),
        }
      ),

    deleteWorkflowConnection: (id: string) =>
      core.request<void>(`${base}/workflows/connections/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      }),

    listWorkflowConnectionGroups: () =>
      core.request<WorkflowConnectionGroup[]>(`${base}/workflows/connection-groups`),

    createWorkflowConnectionGroup: (request: CreateWorkflowConnectionGroupRequest) =>
      core.request<WorkflowConnectionGroup>(`${base}/workflows/connection-groups`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    deleteWorkflowConnectionGroup: (id: string) =>
      core.request<void>(`${base}/workflows/connection-groups/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      }),
  }
}
