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

import {useMemo, useCallback, useRef, useState, useEffect} from 'react'
import {useQuery} from '@tanstack/react-query'
import {Responsive as ResponsiveGridLayout, type Layout} from 'react-grid-layout'
import type {DashboardWidget, CreateWidgetRequest, TimeRangeDef} from '@/lib/api'
import {api} from '@/lib/api'
import {WidgetRenderer} from './WidgetRenderer'
import {Trash2, GripVertical, Bell} from 'lucide-react'
import 'react-grid-layout/css/styles.css'
import 'react-resizable/css/styles.css'

const GRID_BREAKPOINTS = {lg: 1200, md: 996, sm: 768, xs: 480}
const GRID_COLS = {lg: 12, md: 12, sm: 6, xs: 4}
const GRID_MARGIN: [number, number] = [12, 12]

interface DashboardGridProps {
  widgets: DashboardWidget[]
  isEditing: boolean
  dashboardId: number
  projectId?: number
  timeRange: TimeRangeDef
  autoRefresh: boolean
  onLayoutChange: (widgets: CreateWidgetRequest[]) => void
  onWidgetClick: (widget: DashboardWidget) => void
  onWidgetDelete: (widgetId: number) => void
}

export function DashboardGrid({
  widgets,
  isEditing,
  dashboardId,
  projectId,
  timeRange,
  autoRefresh,
  onLayoutChange,
  onWidgetClick,
  onWidgetDelete,
}: DashboardGridProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(1200)

  const {data: alerts = []} = useQuery({
    queryKey: ['dashboard-alerts', dashboardId],
    queryFn: () => api.listDashboardAlerts(dashboardId),
    refetchInterval: 60000,
  })

  useEffect(() => {
    const el = containerRef.current
    if (!el) return

    let rafId: number | null = null
    const observer = new ResizeObserver(() => {
      if (rafId != null) return
      rafId = requestAnimationFrame(() => {
        rafId = null
        if (containerRef.current) {
          setWidth(containerRef.current.offsetWidth)
        }
      })
    })

    setWidth(el.offsetWidth)
    observer.observe(el)
    return () => {
      observer.disconnect()
      if (rafId != null) cancelAnimationFrame(rafId)
    }
  }, [])

  const layout = useMemo<Layout[]>(
    () =>
      widgets.map((w) => ({
        i: String(w.id),
        x: w.grid_x,
        y: w.grid_y,
        w: w.grid_w,
        h: w.grid_h,
        isDraggable: isEditing,
        isResizable: isEditing,
        minW: 2,
        minH: 2,
      })),
    [widgets, isEditing]
  )

  const handleLayoutChange = useCallback(
    (newLayout: Layout[]) => {
      if (!isEditing) return
      
      // Check if layout actually changed to avoid infinite loops
      const hasChanges = newLayout.some((layoutItem) => {
        const widget = widgets.find((w) => String(w.id) === layoutItem.i)
        if (!widget) return false
        return (
          layoutItem.x !== widget.grid_x ||
          layoutItem.y !== widget.grid_y ||
          layoutItem.w !== widget.grid_w ||
          layoutItem.h !== widget.grid_h
        )
      })
      
      if (!hasChanges) return
      
      const updated: CreateWidgetRequest[] = widgets.map((widget) => {
        const layoutItem = newLayout.find((l) => l.i === String(widget.id))
        return {
          ...(widget.id > 0 ? {id: widget.id} : {}),
          title: widget.title,
          widget_type: widget.widget_type,
          grid_x: layoutItem?.x ?? widget.grid_x,
          grid_y: layoutItem?.y ?? widget.grid_y,
          grid_w: layoutItem?.w ?? widget.grid_w,
          grid_h: layoutItem?.h ?? widget.grid_h,
          query_configs: widget.query_configs,
          display_config: widget.display_config,
          sort_order: widget.sort_order,
        }
      })
      onLayoutChange(updated)
    },
    [isEditing, widgets, onLayoutChange]
  )

  if (widgets.length === 0 && !isEditing) {
    return (
      <div className="flex items-center justify-center py-16 text-muted-foreground text-sm">
        This dashboard has no widgets yet. Click Edit to add some.
      </div>
    )
  }

  return (
    <div ref={containerRef} className="w-full">
      <ResponsiveGridLayout
        className="layout"
        layouts={{lg: layout}}
        breakpoints={GRID_BREAKPOINTS}
        cols={GRID_COLS}
        rowHeight={80}
        width={width}
        isDraggable={isEditing}
        isResizable={isEditing}
        onLayoutChange={handleLayoutChange}
        draggableHandle=".drag-handle"
        compactType="vertical"
        margin={GRID_MARGIN}
      >
      {widgets.map((widget) => (
        <div
          key={String(widget.id)}
          className="group"
          style={{contentVisibility: 'auto', containIntrinsicSize: 'auto 300px'}}
        >
          <div
            className={`h-full rounded-lg border bg-card overflow-visible flex flex-col ${
              isEditing ? 'ring-1 ring-transparent hover:ring-primary/30 cursor-pointer' : ''
            }`}
            style={{contain: 'layout style'}}
          >
            {/* Widget header */}
            <div className="flex items-center justify-between px-3 py-2 border-b bg-muted/30 min-h-[36px]">
              <div className="flex items-center gap-1.5 min-w-0 flex-1">
                {isEditing && (
                  <div className="drag-handle cursor-grab active:cursor-grabbing">
                    <GripVertical className="h-3.5 w-3.5 text-muted-foreground" />
                  </div>
                )}
                <span className="text-xs font-medium truncate">{widget.title || 'Untitled'}</span>
                {(() => {
                  const widgetAlerts = alerts.filter((a) => a.widget_id === widget.id && a.enabled)
                  if (widgetAlerts.length === 0) return null
                  const firing = widgetAlerts.some((a) => a.last_triggered_at)
                  const severity = widgetAlerts.find((a) => a.last_triggered_at)?.incident_severity
                  const dotColor = firing
                    ? severity === 'CRITICAL' || severity === 'HIGH' ? 'bg-red-500' : 'bg-orange-500'
                    : 'bg-muted-foreground/40'
                  return (
                    <span className="relative shrink-0" title={firing ? `Alert firing` : `${widgetAlerts.length} alert(s) configured`}>
                      <Bell className="h-3 w-3 text-muted-foreground" />
                      <span className={`absolute -top-0.5 -right-0.5 h-1.5 w-1.5 rounded-full ${dotColor}`} />
                    </span>
                  )
                })()}
              </div>
              {isEditing && (
                <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                  <button
                    onClick={(e) => {
                      e.stopPropagation()
                      onWidgetClick(widget)
                    }}
                    className="p-1 rounded text-xs text-muted-foreground hover:text-foreground hover:bg-muted"
                  >
                    Edit
                  </button>
                  <button
                    onClick={(e) => {
                      e.stopPropagation()
                      onWidgetDelete(widget.id)
                    }}
                    className="p-1 rounded text-destructive hover:bg-destructive/10"
                  >
                    <Trash2 className="h-3 w-3" />
                  </button>
                </div>
              )}
            </div>

            {/* Widget body */}
            <div
              className="flex-1 p-2 overflow-visible min-h-0"
              onClick={() => isEditing && onWidgetClick(widget)}
            >
              <WidgetRenderer
                widget={widget}
                dashboardId={dashboardId}
                projectId={projectId}
                timeRange={timeRange}
                autoRefresh={autoRefresh}
              />
            </div>
          </div>
        </div>
      ))}
    </ResponsiveGridLayout>
    </div>
  )
}
