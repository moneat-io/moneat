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
  CreateLogMetricRuleRequest,
  CreateLogMonitorRequest,
  CreateLogPipelineRequest,
  CreateLogSavedViewRequest,
  LogAggregateResponse,
  LogIndexUsage,
  LogMetricRule,
  LogMonitor,
  LogMonitorDraft,
  LogMonitorDraftRequest,
  LogPermissions,
  LogPipeline,
  LogPipelinePreviewEntry,
  LogPipelinePreviewResult,
  LogPipelineStep,
  LogSavedView,
  UpdateLogMetricRuleRequest,
  UpdateLogMonitorRequest,
  UpdateLogPipelineRequest,
  UpdateLogSavedViewRequest,
} from '../types'

export function logManagementMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getLogPermissions: () =>
      core.request<LogPermissions>(`${base}/logs/permissions`),

    getLogIndexUsage: () =>
      core.request<{usage: LogIndexUsage[]}>(`${base}/logs/indexes/usage`),

    runLogIndexRetention: () =>
      core.request<{indexes_processed: number}>(`${base}/logs/indexes/retention/run`, {
        method: 'POST',
      }),

    getLogPipelines: () =>
      core.request<{pipelines: LogPipeline[]}>(`${base}/logs/pipelines`),

    createLogPipeline: (request: CreateLogPipelineRequest) =>
      core.request<LogPipeline>(`${base}/logs/pipelines`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateLogPipeline: (id: string, request: UpdateLogPipelineRequest) =>
      core.request<LogPipeline>(`${base}/logs/pipelines/${id}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteLogPipeline: (id: string) =>
      core.request<void>(`${base}/logs/pipelines/${id}`, {
        method: 'DELETE',
      }),

    previewLogPipeline: (steps: LogPipelineStep[], sampleLogs: LogPipelinePreviewEntry[]) =>
      core.request<{results: LogPipelinePreviewResult[]}>(`${base}/logs/pipelines/preview`, {
        method: 'POST',
        body: JSON.stringify({steps, sample_logs: sampleLogs}),
      }),

    getLogSavedViews: () =>
      core.request<{views: LogSavedView[]}>(`${base}/logs/saved-views`),

    createLogSavedView: (request: CreateLogSavedViewRequest) =>
      core.request<LogSavedView>(`${base}/logs/saved-views`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateLogSavedView: (id: string, request: UpdateLogSavedViewRequest) =>
      core.request<LogSavedView>(`${base}/logs/saved-views/${id}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteLogSavedView: (id: string) =>
      core.request<void>(`${base}/logs/saved-views/${id}`, {
        method: 'DELETE',
      }),

    getLogMetricRules: () =>
      core.request<{rules: LogMetricRule[]}>(`${base}/logs/metrics/rules`),

    createLogMetricRule: (request: CreateLogMetricRuleRequest) =>
      core.request<LogMetricRule>(`${base}/logs/metrics/rules`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateLogMetricRule: (id: string, request: UpdateLogMetricRuleRequest) =>
      core.request<LogMetricRule>(`${base}/logs/metrics/rules/${id}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteLogMetricRule: (id: string) =>
      core.request<void>(`${base}/logs/metrics/rules/${id}`, {
        method: 'DELETE',
      }),

    previewLogMetricRule: (request: CreateLogMetricRuleRequest) =>
      core.request<LogAggregateResponse>(`${base}/logs/metrics/preview`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    rollupLogMetricRule: (id: string) =>
      core.request<{points_inserted: number}>(`${base}/logs/metrics/rules/${id}/rollup`, {
        method: 'POST',
      }),

    createLogMonitorFromQuery: (request: LogMonitorDraftRequest) =>
      core.request<LogMonitorDraft>(`${base}/logs/monitors/from-query`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    getLogMonitors: () =>
      core.request<{monitors: LogMonitor[]}>(`${base}/logs/monitors`),

    createLogMonitor: (request: CreateLogMonitorRequest) =>
      core.request<LogMonitor>(`${base}/logs/monitors`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateLogMonitor: (id: string, request: UpdateLogMonitorRequest) =>
      core.request<LogMonitor>(`${base}/logs/monitors/${id}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteLogMonitor: (id: string) =>
      core.request<void>(`${base}/logs/monitors/${id}`, {
        method: 'DELETE',
      }),
  }
}
