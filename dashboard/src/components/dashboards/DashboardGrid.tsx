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

import {useMemo, useCallback} from 'react'
import {Responsive as ResponsiveGridLayout, type Layout} from 'react-grid-layout'
import type {DashboardWidget, CreateWidgetRequest, TimeRangeDef} from '@/lib/api'
import {WidgetRenderer} from './WidgetRenderer'
import {Trash2, GripVertical} from 'lucide-react'
import 'react-grid-layout/css/styles.css'
import 'react-resizable/css/styles.css'

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
  const layout = useMemo<Layout[]>(
    () =>
      widgets.map((w) => ({
        i: String(w.id),
        x: w.grid_x,
        y: w.grid_y,
        w: w.grid_w,
        h: w.grid_h,
        static: !isEditing,
        minW: 2,
        minH: 2,
      })),
    [widgets, isEditing]
  )

  const handleLayoutChange = useCallback(
    (newLayout: Layout[]) => {
      if (!isEditing) return
      const updated: CreateWidgetRequest[] = widgets.map((widget) => {
        const layoutItem = newLayout.find((l) => l.i === String(widget.id))
        return {
          title: widget.title,
          widget_type: widget.widget_type,
          grid_x: layoutItem?.x ?? widget.grid_x,
          grid_y: layoutItem?.y ?? widget.grid_y,
          grid_w: layoutItem?.w ?? widget.grid_w,
          grid_h: layoutItem?.h ?? widget.grid_h,
          query_config: widget.query_config,
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
    <ResponsiveGridLayout
      className="layout"
      layouts={{lg: layout}}
      breakpoints={{lg: 1200, md: 996, sm: 768, xs: 480}}
      cols={{lg: 12, md: 12, sm: 6, xs: 4}}
      rowHeight={80}
      isDraggable={isEditing}
      isResizable={isEditing}
      onLayoutChange={handleLayoutChange}
      draggableHandle=".drag-handle"
      compactType="vertical"
      margin={[12, 12]}
    >
      {widgets.map((widget) => (
        <div key={String(widget.id)} className="group">
          <div
            className={`h-full rounded-lg border bg-card overflow-hidden flex flex-col ${
              isEditing ? 'ring-1 ring-transparent hover:ring-primary/30 cursor-pointer' : ''
            }`}
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
              className="flex-1 p-2 overflow-hidden"
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
  )
}
