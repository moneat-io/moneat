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
  CustomDashboard,
  DashboardFolder,
  CreateDashboardRequest,
  UpdateDashboardRequest,
  CreateFolderRequest,
  UpdateFolderRequest,
  SearchResponse,
  QueryDsl,
  TimeRangeDef,
  BatchQueryResult,
  DashboardImportResult,
  DashboardWidgetAlert,
  CreateDashboardAlertRequest,
  UpdateDashboardAlertRequest,
  CustomDataSourceResponse,
  CreateCustomDataSourceRequest,
  UpdateCustomDataSourceRequest,
  TestConnectionRequest,
  TestConnectionResult,
  DataSourceField,
  DataSourceInfo,
  CustomDataSourceQueryRequest,
  DashboardTemplateSummary,
  DashboardTemplateDetail,
  InstantiateDashboardTemplateRequest,
} from '../types'

export function dashboardsMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getDashboards: (projectId?: string) => {
      const qs = projectId ? `?projectId=${projectId}` : ''
      return core.request<CustomDashboard[]>(`${base}/dashboards${qs}`)
    },

    getDashboard: (id: string) =>
      core.request<CustomDashboard>(`${base}/dashboards/${id}`),

    createDashboard: (data: CreateDashboardRequest) =>
      core.request<CustomDashboard>(`${base}/dashboards`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      }),

    updateDashboard: (id: string, data: UpdateDashboardRequest) =>
      core.request<CustomDashboard>(`${base}/dashboards/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      }),

    deleteDashboard: (id: string) =>
      core.request<void>(`${base}/dashboards/${id}`, {
        method: 'DELETE',
      }),

    toggleDashboardFavorite: (id: string) =>
      core.request<{ is_favorited: boolean }>(`${base}/dashboards/${id}/favorite`, {
        method: 'POST',
      }),

    duplicateDashboard: (id: string) =>
      core.request<CustomDashboard>(`${base}/dashboards/${id}/duplicate`, {
        method: 'POST',
      }),

    setDefaultDashboard: (id: string) =>
      core.request<{ is_default: boolean }>(`${base}/dashboards/${id}/default`, {
        method: 'POST',
      }),

    moveDashboardToFolder: (id: string, folderId: string | null) =>
      core.request<{ folder_id: string | null }>(`${base}/dashboards/${id}/folder`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ folder_id: folderId }),
      }),

    getDashboardFolders: () =>
      core.request<DashboardFolder[]>(`${base}/dashboards/folders`),

    createDashboardFolder: (data: CreateFolderRequest) =>
      core.request<DashboardFolder>(`${base}/dashboards/folders`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      }),

    updateDashboardFolder: (id: string, data: UpdateFolderRequest) =>
      core.request<DashboardFolder>(`${base}/dashboards/folders/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      }),

    deleteDashboardFolder: (id: string) =>
      core.request<void>(`${base}/dashboards/folders/${id}`, {
        method: 'DELETE',
      }),

    search: (query: string) => {
      const qs = query.trim() ? `?q=${encodeURIComponent(query.trim())}` : ''
      return core.request<SearchResponse>(`${base}/search${qs}`)
    },

    executeWidgetQuery: (
      dashboardId: string,
      queryConfig: QueryDsl,
      projectId: string,
      timeRange?: TimeRangeDef,
      variables?: Record<string, string>
    ) =>
      core.request<Record<string, unknown>[]>(
        `${base}/dashboards/${dashboardId}/query?projectId=${projectId}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            query_config: queryConfig,
            time_range: timeRange,
            variables,
          }),
        }
      ),

    executeBatchQuery: (
      dashboardId: string,
      queries: QueryDsl[],
      projectId: string,
      timeRange?: TimeRangeDef,
      variables?: Record<string, string>
    ) =>
      core.request<BatchQueryResult>(
        `${base}/dashboards/${dashboardId}/query/batch?projectId=${projectId}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            queries,
            time_range: timeRange,
            variables,
          }),
        }
      ),

    resolveVariableOptions: (
      dashboardId: string,
      currentValues: Record<string, string>
    ) =>
      core.request<Record<string, string[]>>(
        `${base}/dashboards/${dashboardId}/variables/resolve`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(currentValues),
        }
      ),

    importDashboard: (format: string, json: string) =>
      core.request<DashboardImportResult>(`${base}/dashboards/import`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ format, json }),
      }),

    exportDashboard: (id: string, format: string) =>
      core.request<unknown>(`${base}/dashboards/${id}/export/${format}`),

    getDataSources: () =>
      core.request<DataSourceInfo[]>(`${base}/dashboards/datasources`),

    getDashboardTemplates: () =>
      core.request<DashboardTemplateSummary[]>(`${base}/dashboards/templates`),

    getDashboardTemplate: (id: string) =>
      core.request<DashboardTemplateDetail>(
        `${base}/dashboards/templates/${encodeURIComponent(id)}`
      ),

    createDashboardFromTemplate: (
      id: string,
      data: InstantiateDashboardTemplateRequest = {}
    ) =>
      core.request<CustomDashboard>(
        `${base}/dashboards/templates/${encodeURIComponent(id)}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(data),
        }
      ),

    listDashboardAlerts: (dashboardId: string) =>
      core.request<DashboardWidgetAlert[]>(
        `${base}/dashboards/${dashboardId}/alerts`
      ),

    createDashboardAlert: (
      dashboardId: string,
      data: CreateDashboardAlertRequest
    ) =>
      core.request<DashboardWidgetAlert>(
        `${base}/dashboards/${dashboardId}/alerts`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(data),
        }
      ),

    updateDashboardAlert: (
      dashboardId: string,
      alertId: string,
      data: UpdateDashboardAlertRequest
    ) =>
      core.request<DashboardWidgetAlert>(
        `${base}/dashboards/${dashboardId}/alerts/${alertId}`,
        {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(data),
        }
      ),

    deleteDashboardAlert: (dashboardId: string, alertId: string) =>
      core.request<void>(
        `${base}/dashboards/${dashboardId}/alerts/${alertId}`,
        {
          method: 'DELETE',
        }
      ),

    listCustomDataSources: () =>
      core.request<CustomDataSourceResponse[]>(`${base}/datasources`),

    getCustomDataSource: (id: string) =>
      core.request<CustomDataSourceResponse>(`${base}/datasources/${id}`),

    createCustomDataSource: (request: CreateCustomDataSourceRequest) =>
      core.request<CustomDataSourceResponse>(`${base}/datasources`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateCustomDataSource: (
      id: string,
      request: UpdateCustomDataSourceRequest
    ) =>
      core.request<CustomDataSourceResponse>(`${base}/datasources/${id}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteCustomDataSource: (id: string) =>
      core.request<void>(`${base}/datasources/${id}`, {
        method: 'DELETE',
      }),

    testDataSourceConnection: (request: TestConnectionRequest) =>
      core.request<TestConnectionResult>(`${base}/datasources/test`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    getDataSourceSchema: (id: string) =>
      core.request<DataSourceField[]>(`${base}/datasources/${id}/schema`),

    queryCustomDataSource: (
      id: string,
      request: CustomDataSourceQueryRequest
    ) => {
      // Strip data_source_id from body; the id is authoritative via the URL
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      const { data_source_id: _, ...body } = request
      return core.request<Record<string, unknown>[]>(
        `${base}/datasources/${id}/query`,
        {
          method: 'POST',
          body: JSON.stringify(body),
        }
      )
    },
  }
}
