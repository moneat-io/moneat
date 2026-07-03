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

import {useCallback, useMemo, useState} from 'react'
import type {CreateWidgetRequest, DashboardWidget} from '@/lib/api'
import {DashboardGrid} from '@/components/dashboards/DashboardGrid'
import {OverviewHeader} from './OverviewHeader'
import {AddOverviewWidgetDialog} from './AddOverviewWidgetDialog'
import {buildDefaultOverviewWidgets, OVERVIEW_DASHBOARD_ID} from './defaultOverviewLayout'
import {OVERVIEW_WIDGETS} from './overviewWidgetTypes'
import {OverviewDataProvider} from './OverviewDataProvider'

const STORAGE_KEY = 'moneat.overview.layout.v3'

function loadWidgets(): DashboardWidget[] {
  try {
    const raw = globalThis.localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as DashboardWidget[]
      if (Array.isArray(parsed) && parsed.length > 0) return parsed
    }
  } catch {
    // corrupted — fall through to defaults
  }
  return buildDefaultOverviewWidgets()
}

function saveWidgets(widgets: DashboardWidget[]) {
  try {
    globalThis.localStorage.setItem(STORAGE_KEY, JSON.stringify(widgets))
  } catch {
    // storage full — silently drop
  }
}

function nextLocalId(widgets: DashboardWidget[]): string {
  return `overview-local-widget-${widgets.length + 1}-${Date.now()}`
}

function maxGridY(widgets: DashboardWidget[]): number {
  return widgets.reduce((max, w) => Math.max(max, w.grid_y + w.grid_h), 0)
}

export function OverviewDashboard() {
  const [widgets, setWidgets] = useState<DashboardWidget[]>(loadWidgets)
  const [isEditing, setIsEditing] = useState(false)
  const [addDialogOpen, setAddDialogOpen] = useState(false)

  const timeRange = useMemo(() => ({from: 'now-24h', to: 'now'}), [])

  const handleToggleEdit = useCallback(() => {
    setIsEditing((prev) => !prev)
  }, [])

  const handleReset = useCallback(() => {
    const defaults = buildDefaultOverviewWidgets()
    setWidgets(defaults)
    saveWidgets(defaults)
  }, [])

  const handleAddWidget = useCallback(
    (widgetType: string) => {
      const def = OVERVIEW_WIDGETS[widgetType]
      if (!def) return

      const newWidget: DashboardWidget = {
        id: nextLocalId(widgets),
        dashboard_id: OVERVIEW_DASHBOARD_ID,
        title: def.label,
        widget_type: widgetType,
        grid_x: 0,
        grid_y: maxGridY(widgets),
        grid_w: def.defaultSize.w,
        grid_h: def.defaultSize.h,
        query_configs: [],
        display_config: {},
        sort_order: widgets.length,
      }

      const next = [...widgets, newWidget]
      setWidgets(next)
      saveWidgets(next)
    },
    [widgets],
  )

  const handleDeleteWidget = useCallback(
    (widgetId: string) => {
      const next = widgets.filter((w) => w.id !== widgetId)
      setWidgets(next)
      saveWidgets(next)
    },
    [widgets],
  )

  const handleLayoutChange = useCallback(
    (updated: CreateWidgetRequest[]) => {
      const next: DashboardWidget[] = updated.map((req) => {
        const reqId = req.id
        const existing = widgets.find((w) => w.id === reqId)
        return {
          id: reqId ?? existing?.id ?? nextLocalId(widgets),
          dashboard_id: OVERVIEW_DASHBOARD_ID,
          title: req.title ?? existing?.title ?? '',
          widget_type: req.widget_type,
          grid_x: req.grid_x,
          grid_y: req.grid_y,
          grid_w: req.grid_w,
          grid_h: req.grid_h,
          query_configs: req.query_configs,
          display_config: req.display_config ?? existing?.display_config ?? {},
          sort_order: req.sort_order ?? existing?.sort_order ?? 0,
        }
      })
      setWidgets(next)
      saveWidgets(next)
    },
    [widgets],
  )

  const noop = useCallback(() => {}, [])

  return (
    <OverviewDataProvider>
      <div className="min-h-screen">
        <div className="px-3 py-3 sm:px-6">
          <OverviewHeader
            isEditing={isEditing}
            onToggleEdit={handleToggleEdit}
            onAddWidget={() => setAddDialogOpen(true)}
            onReset={handleReset}
          />
          <DashboardGrid
            widgets={widgets}
            isEditing={isEditing}
            dashboardId={OVERVIEW_DASHBOARD_ID}
            timeRange={timeRange}
            autoRefresh={false}
            onLayoutChange={handleLayoutChange}
            onWidgetClick={noop}
            onWidgetDelete={handleDeleteWidget}
          />
        </div>
        <AddOverviewWidgetDialog open={addDialogOpen} onOpenChange={setAddDialogOpen} onAdd={handleAddWidget} />
      </div>
    </OverviewDataProvider>
  )
}
