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
import {urlWithQuery} from '../utils'
import type {
  InstantiateBlueprintRequest,
  WorkflowAuditEntry,
  WorkflowBlueprintDetail,
  WorkflowBlueprintSummary,
  WorkflowCatalogResponse,
  WorkflowExportResponse,
  WorkflowImportRequest,
  WorkflowJsonValue,
  WorkflowOverviewResponse,
  WorkflowPreviewRequest,
  WorkflowPreviewResponse,
  WorkflowRequest,
  WorkflowRunCancelResponse,
  WorkflowRunInstanceRequest,
  WorkflowResponse,
  WorkflowRunResponse,
  WorkflowTestMessageResponse,
  WorkflowUpdateRequest,
  WorkflowUsageResponse,
  WorkflowWebhookSigningResponse,
} from '../types'

function auditQuery(limit?: number): string {
  return limit === undefined ? '' : new URLSearchParams({limit: String(limit)}).toString()
}

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

    updateWorkflow: (id: string, request: WorkflowUpdateRequest) =>
      core.request<WorkflowResponse>(`${base}/workflows/${id}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    publishWorkflow: (id: string) =>
      core.request<WorkflowResponse>(`${base}/workflows/${id}/publish`, {
        method: 'POST',
      }),

    unpublishWorkflow: (id: string) =>
      core.request<WorkflowResponse>(`${base}/workflows/${id}/unpublish`, {
        method: 'POST',
      }),

    runWorkflow: (id: string, scope: Record<string, WorkflowJsonValue> = {}) =>
      core.request<WorkflowRunResponse>(`${base}/workflows/${id}/run`, {
        method: 'POST',
        body: JSON.stringify({scope}),
      }),

    deleteWorkflow: (id: string) =>
      core.request<void>(`${base}/workflows/${id}`, {
        method: 'DELETE',
      }),

    getWorkflowRuns: (id: string) =>
      core.request<WorkflowRunResponse[]>(`${base}/workflows/${id}/runs`),

    getWorkflowInstances: (id: string) =>
      core.request<WorkflowRunResponse[]>(`${base}/workflows/${id}/instances`),

    getWorkflowRun: (id: string, runId: string) =>
      core.request<WorkflowRunResponse>(`${base}/workflows/${id}/instances/${runId}`),

    createWorkflowInstance: (
      id: string,
      request: WorkflowRunInstanceRequest = {scope: {}}
    ) =>
      core.request<WorkflowRunResponse>(`${base}/workflows/${id}/instances`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    cancelWorkflowRun: (id: string, runId: string) =>
      core.request<WorkflowRunCancelResponse>(`${base}/workflows/${id}/instances/${runId}/cancel`, {
        method: 'PUT',
      }),

    getWorkflowWebhookSigning: (id: string) =>
      core.request<WorkflowWebhookSigningResponse>(`${base}/workflows/${id}/webhook-signing`),

    getWorkflowBlueprints: () =>
      core.request<WorkflowBlueprintSummary[]>(`${base}/workflows/blueprints`),

    getWorkflowBlueprint: (key: string) =>
      core.request<WorkflowBlueprintDetail>(
        `${base}/workflows/blueprints/${encodeURIComponent(key)}`
      ),

    instantiateBlueprint: (key: string, request: InstantiateBlueprintRequest = {}) =>
      core.request<WorkflowResponse>(
        `${base}/workflows/blueprints/${encodeURIComponent(key)}/instantiate`,
        {
          method: 'POST',
          body: JSON.stringify(request),
        }
      ),

    getWorkflowOverview: () =>
      core.request<WorkflowOverviewResponse>(`${base}/workflows/overview`),

    getWorkflowUsage: () =>
      core.request<WorkflowUsageResponse>(`${base}/workflows/usage`),

    getWorkflowAudit: (limit?: number) =>
      core.request<WorkflowAuditEntry[]>(
        urlWithQuery(`${base}/workflows/audit`, auditQuery(limit))
      ),

    getWorkflowAuditForWorkflow: (id: string, limit?: number) =>
      core.request<WorkflowAuditEntry[]>(
        urlWithQuery(`${base}/workflows/${id}/audit`, auditQuery(limit))
      ),

    exportWorkflow: (id: string) =>
      core.request<WorkflowExportResponse>(`${base}/workflows/${id}/export`),

    importWorkflow: (request: WorkflowImportRequest) =>
      core.request<WorkflowResponse>(`${base}/workflows/import`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),
  }
}
