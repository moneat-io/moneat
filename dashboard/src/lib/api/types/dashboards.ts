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

export interface DashboardVariable {
  name: string
  label?: string | null
  type: string
  query?: string | null
  default_value?: string | null
  current?: string | null
  options: string[]
  datasource?: string | null
}

export interface MetricDef {
  function: string
  field?: string | null
  alias?: string | null
}

export interface GroupByDef {
  field: string
  type: 'field' | 'time'
  interval?: string | null
}

export interface FilterDef {
  field: string
  op: string
  value?: string | null
  values?: string[] | null
}

export interface OrderByDef {
  field: string
  direction: string
}

export interface TimeRangeDef {
  from: string
  to: string
}

export interface QueryDsl {
  dataSource: string
  metrics: MetricDef[]
  groupBy: GroupByDef[]
  filters: FilterDef[]
  orderBy?: OrderByDef | null
  limit: number
  timeRange: TimeRangeDef
  rawQuery?: string | null
  ref_id?: string | null
}

export interface DashboardWidget {
  id: number
  dashboard_id: number
  title?: string | null
  widget_type: string
  grid_x: number
  grid_y: number
  grid_w: number
  grid_h: number
  query_configs: QueryDsl[]
  display_config: Record<string, string>
  sort_order: number
}

export interface DashboardWidgetAlertNotificationChannels {
  email: boolean
  slack: boolean
  discord: boolean
}

export type DashboardAlertCondition = '>' | '<' | '>=' | '<=' | '=='

export type IncidentSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | null

export interface DashboardWidgetAlert {
  id: number
  widget_id: number
  dashboard_id: number
  name: string
  condition: DashboardAlertCondition
  threshold: number
  metric_index: number
  duration_seconds: number
  incident_severity: IncidentSeverity
  enabled: boolean
  notification_channels: DashboardWidgetAlertNotificationChannels
  last_triggered_at: string | null
  last_value: number | null
  created_at: string
  updated_at: string
}

export interface CreateDashboardAlertRequest {
  widget_id: number
  name: string
  condition: DashboardAlertCondition
  threshold: number
  metric_index?: number
  duration_seconds?: number
  incident_severity?: IncidentSeverity
  enabled?: boolean
  notification_channels?: DashboardWidgetAlertNotificationChannels
}

export interface UpdateDashboardAlertRequest {
  name?: string
  condition?: DashboardAlertCondition
  threshold?: number
  metric_index?: number
  duration_seconds?: number
  incident_severity?: IncidentSeverity
  enabled?: boolean
  notification_channels?: DashboardWidgetAlertNotificationChannels
}

export interface BatchQueryResult {
  results: Record<string, Record<string, unknown>[]>
}

export interface CustomDashboard {
  id: number
  org_id: number
  project_id?: number | null
  folder_id?: number | null
  title: string
  description?: string | null
  layout_type: string
  is_default: boolean
  is_favorited?: boolean
  variables?: DashboardVariable[]
  created_by: number
  created_at: string
  updated_at: string
  widgets: DashboardWidget[]
}

export interface DashboardFolder {
  id: number
  org_id: number
  name: string
  color?: string | null
  sort_order: number
  created_at: string
  updated_at: string
}

export interface CreateWidgetRequest {
  id?: number
  title?: string | null
  widget_type: string
  grid_x: number
  grid_y: number
  grid_w: number
  grid_h: number
  query_configs: QueryDsl[]
  display_config?: Record<string, string>
  sort_order?: number
}

export interface CreateDashboardRequest {
  title: string
  description?: string | null
  project_id?: number | null
  folder_id?: number | null
  layout_type?: string
  is_default?: boolean
  variables?: DashboardVariable[]
  widgets: CreateWidgetRequest[]
}

export interface CreateFolderRequest {
  name: string
  color?: string | null
  sort_order?: number
}

export interface UpdateFolderRequest {
  name?: string | null
  color?: string | null
  sort_order?: number | null
}

export interface UpdateDashboardRequest {
  title?: string | null
  description?: string | null
  folder_id?: number | null
  layout_type?: string | null
  is_default?: boolean | null
  variables?: DashboardVariable[] | null
  widgets?: CreateWidgetRequest[] | null
}

export interface SearchResponse {
  dashboards: CustomDashboard[]
  projects: SearchProjectResponse[]
}

export interface SearchProjectResponse {
  id: number
  name: string
}

export interface ExecuteQueryRequest {
  query_config: QueryDsl
  time_range?: TimeRangeDef | null
  variables?: Record<string, string>
}

export interface DashboardImportResult {
  dashboard: CustomDashboard
  warnings: string[]
  variables?: DashboardVariable[]
}

export interface DataSourceField {
  name: string
  type: string
  description: string
}

export interface DataSourceInfo {
  name: string
  label: string
  fields: DataSourceField[]
}

export interface CustomDataSourceResponse {
  id: number
  org_id: number
  name: string
  description?: string
  source_type: string
  host: string
  port?: number
  database_name?: string
  extra_config: Record<string, string>
  enabled: boolean
  created_by: number
  created_at: string
  updated_at: string
  has_credentials: boolean
}

export interface CreateCustomDataSourceRequest {
  name: string
  description?: string
  source_type: string
  host: string
  port?: number
  database_name?: string
  username?: string
  password?: string
  api_key?: string
  access_key_id?: string
  secret_access_key?: string
  service_account_json?: string
  account_identifier?: string
  connection_string?: string
  project_id?: string
  region?: string
  extra_config?: Record<string, string>
}

export interface UpdateCustomDataSourceRequest {
  name?: string
  description?: string
  host?: string
  port?: number
  database_name?: string
  username?: string
  password?: string
  api_key?: string
  access_key_id?: string
  secret_access_key?: string
  service_account_json?: string
  account_identifier?: string
  connection_string?: string
  project_id?: string
  region?: string
  extra_config?: Record<string, string>
  enabled?: boolean
}

export interface TestConnectionRequest {
  source_type: string
  host: string
  port?: number
  database_name?: string
  username?: string
  password?: string
  api_key?: string
  access_key_id?: string
  secret_access_key?: string
  service_account_json?: string
  account_identifier?: string
  connection_string?: string
  project_id?: string
  region?: string
}

export interface TestConnectionResult {
  success: boolean
  message: string
  tables?: string[]
  metrics?: string[]
  databases?: string[]
  keys?: string[]
}

export interface CustomDataSourceQueryRequest {
  data_source_id: number
  query: string
  limit?: number
  time_range?: TimeRangeDef
}
