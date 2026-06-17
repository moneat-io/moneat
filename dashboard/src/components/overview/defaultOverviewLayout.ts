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

import type {CustomDashboard, DashboardWidget} from '@/lib/api'
import {OVERVIEW_WIDGETS} from './overviewWidgetTypes'

/** Reserved id for the local default overview dashboard. */
export const OVERVIEW_DASHBOARD_ID = 'overview-default'
const OVERVIEW_ORG_ID = 'overview-default-org'
const OVERVIEW_CREATED_BY_ID = 'overview-default-user'

interface WidgetSpec {
  type: string
  x: number
  y: number
  w: number
  h: number
  kpiId?: string
}

// The default layout maps the design mockup onto the 12-column grid, top to bottom:
// hero status bar, KPI strip, service health + triage rail, telemetry, then the
// infrastructure / uptime / deploys / activity band.
const DEFAULT_SPECS: WidgetSpec[] = [
  {type: 'system_status', x: 0, y: 0, w: 12, h: 1},
  {type: 'kpi', x: 0, y: 1, w: 2, h: 2, kpiId: 'errors'},
  {type: 'kpi', x: 2, y: 1, w: 2, h: 2, kpiId: 'latency'},
  {type: 'kpi', x: 4, y: 1, w: 2, h: 2, kpiId: 'throughput'},
  {type: 'kpi', x: 6, y: 1, w: 2, h: 2, kpiId: 'apdex'},
  {type: 'kpi', x: 8, y: 1, w: 2, h: 2, kpiId: 'issues'},
  {type: 'kpi', x: 10, y: 1, w: 2, h: 2, kpiId: 'uptime'},
  {type: 'service_health', x: 0, y: 3, w: 8, h: 10},
  {type: 'triage', x: 8, y: 3, w: 4, h: 17},
  {type: 'telemetry', x: 0, y: 13, w: 8, h: 7},
  {type: 'infra_summary', x: 0, y: 20, w: 3, h: 5},
  {type: 'uptime_summary', x: 3, y: 20, w: 3, h: 5},
  {type: 'deploys', x: 6, y: 20, w: 3, h: 5},
  {type: 'activity', x: 9, y: 20, w: 3, h: 5},
]

export function buildDefaultOverviewWidgets(): DashboardWidget[] {
  return DEFAULT_SPECS.map((spec, index) => ({
    id: `overview-default-widget-${index + 1}`,
    dashboard_id: OVERVIEW_DASHBOARD_ID,
    title: OVERVIEW_WIDGETS[spec.type]?.label ?? spec.type,
    widget_type: spec.type,
    grid_x: spec.x,
    grid_y: spec.y,
    grid_w: spec.w,
    grid_h: spec.h,
    query_configs: [],
    display_config: spec.kpiId ? {kpiId: spec.kpiId} : ({} as Record<string, string>),
    sort_order: index,
  }))
}

export function buildDefaultOverviewDashboard(): CustomDashboard {
  return {
    id: OVERVIEW_DASHBOARD_ID,
    org_id: OVERVIEW_ORG_ID,
    title: 'Overview',
    description: 'Workspace overview',
    layout_type: 'grid',
    is_default: true,
    created_by: OVERVIEW_CREATED_BY_ID,
    created_at: '',
    updated_at: '',
    widgets: buildDefaultOverviewWidgets(),
  }
}
