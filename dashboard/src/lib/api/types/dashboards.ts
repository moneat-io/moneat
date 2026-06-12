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
  /** Accepts several values at once; the selected value is stored comma-joined. */
  multi?: boolean
  /** Offers an "All" choice (selected value `$__all`). */
  include_all?: boolean
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
  id: string
  dashboard_id: string
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

export type AlertPriority = 'P0' | 'P1' | 'P2' | 'P3' | 'P4' | 'P5' | null
export type DashboardAlertLevel = 'WARNING' | 'ERROR' | null

export interface DashboardWidgetAlert {
  id: string
  widget_id: string
  dashboard_id: string
  name: string
  condition: DashboardAlertCondition
  threshold: number
  warning_threshold: number | null
  metric_index: number
  duration_seconds: number
  alert_priority: AlertPriority
  enabled: boolean
  notification_channels: DashboardWidgetAlertNotificationChannels
  last_triggered_at: string | null
  last_triggered_level: DashboardAlertLevel
  last_value: number | null
  created_at: string
  updated_at: string
}

export interface CreateDashboardAlertRequest {
  widget_id: string
  name: string
  condition: DashboardAlertCondition
  threshold: number
  warning_threshold?: number | null
  metric_index?: number
  duration_seconds?: number
  alert_priority?: AlertPriority
  enabled?: boolean
  notification_channels?: DashboardWidgetAlertNotificationChannels
}

export interface UpdateDashboardAlertRequest {
  name?: string
  condition?: DashboardAlertCondition
  threshold?: number
  warning_threshold?: number | null
  metric_index?: number
  duration_seconds?: number
  alert_priority?: AlertPriority
  enabled?: boolean
  notification_channels?: DashboardWidgetAlertNotificationChannels
}

export interface BatchQueryResult {
  results: Record<string, Record<string, unknown>[]>
  metadata?: Record<string, BatchQueryResultMetadata>
}

export interface BatchQueryResultMetadata {
  original_ref_id?: string | null
  query_index: number
}

export interface CustomDashboard {
  id: string
  org_id: string
  project_id?: string | null
  folder_id?: string | null
  title: string
  description?: string | null
  layout_type: string
  is_default: boolean
  is_favorited?: boolean
  variables?: DashboardVariable[]
  owner_name?: string | null
  created_by: string
  created_at: string
  updated_at: string
  widgets: DashboardWidget[]
}

export interface DashboardFolder {
  id: string
  org_id: string
  name: string
  color?: string | null
  sort_order: number
  created_at: string
  updated_at: string
}

export interface CreateWidgetRequest {
  id?: string
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
  project_id?: string | null
  folder_id?: string | null
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
  folder_id?: string | null
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
  id: string
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

export interface DashboardTemplateSummary {
  id: string
  title: string
  description?: string | null
  category: string
  tags: string[]
  required_sources: string[]
  widget_count: number
  variable_count: number
  resource_path: string
}

export interface DashboardTemplateDetail extends Omit<DashboardTemplateSummary, 'resource_path'> {
  warnings: string[]
  dashboard: CreateDashboardRequest
}

export interface InstantiateDashboardTemplateRequest {
  project_id?: string | null
  folder_id?: string | null
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
  id: string
  org_id: string
  name: string
  description?: string
  source_type: string
  host: string
  port?: number
  database_name?: string
  extra_config: Record<string, string>
  enabled: boolean
  created_by: string
  created_at: string
  updated_at: string
  has_credentials: boolean
  used_by_dashboard_count?: number
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
  header_value?: string
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
  header_value?: string
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
  header_value?: string
  access_key_id?: string
  secret_access_key?: string
  service_account_json?: string
  account_identifier?: string
  connection_string?: string
  project_id?: string
  region?: string
  extra_config?: Record<string, string>
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
  data_source_id: string
  query: string
  limit?: number
  time_range?: TimeRangeDef
}
