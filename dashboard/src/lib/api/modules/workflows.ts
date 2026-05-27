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
  WorkflowCatalogResponse,
  WorkflowPreviewRequest,
  WorkflowPreviewResponse,
  WorkflowRequest,
  WorkflowResponse,
  WorkflowRunResponse,
  WorkflowTestMessageResponse,
  WorkflowUpdateRequest,
} from '../types'

export function workflowsMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getWorkflowCatalog: () =>
      core.request<WorkflowCatalogResponse>(`${base}/workflows/catalog`),

    getWorkflows: () =>
      core.request<WorkflowResponse[]>(`${base}/workflows`),

    createWorkflow: (request: WorkflowRequest) =>
      core.request<WorkflowResponse>(`${base}/workflows`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    previewWorkflow: (request: WorkflowPreviewRequest) =>
      core.request<WorkflowPreviewResponse>(`${base}/workflows/preview`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    testWorkflowMessage: (request: WorkflowPreviewRequest) =>
      core.request<WorkflowTestMessageResponse>(`${base}/workflows/test-message`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateWorkflow: (id: number, request: WorkflowUpdateRequest) =>
      core.request<WorkflowResponse>(`${base}/workflows/${id}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteWorkflow: (id: number) =>
      core.request<void>(`${base}/workflows/${id}`, {
        method: 'DELETE',
      }),

    getWorkflowRuns: (id: number) =>
      core.request<WorkflowRunResponse[]>(`${base}/workflows/${id}/runs`),
  }
}
