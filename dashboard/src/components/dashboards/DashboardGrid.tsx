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

import {useCallback, useEffect, useMemo, useRef, useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {type Layout, type LayoutItem, Responsive as ResponsiveGridLayout} from 'react-grid-layout/legacy'
import type {CreateWidgetRequest, DashboardWidget, TimeRangeDef} from '@/lib/api'
import {api} from '@/lib/api'
import {WidgetRenderer} from './WidgetRenderer'
import {isOverviewWidgetType} from './extendedWidgetTypes'
import {overviewWidgetDef} from '@/components/overview/overviewWidgetTypes'
import {Bell, ChevronDown, ChevronRight, GripVertical, Trash2} from 'lucide-react'
import 'react-grid-layout/css/styles.css'
import 'react-resizable/css/styles.css'

const GRID_BREAKPOINTS = {lg: 1200, md: 996, sm: 768, xs: 480}
const GRID_COLS = {lg: 12, md: 12, sm: 12, xs: 6}
const GRID_MARGIN: [number, number] = [12, 12]

interface DashboardGridProps {
  widgets: DashboardWidget[]
  isEditing: boolean
  dashboardId: number
  projectId?: string | number
  timeRange: TimeRangeDef
  autoRefresh: boolean
  variableValues?: Record<string, string>
  onLayoutChange: (widgets: CreateWidgetRequest[]) => void
  onWidgetClick: (widget: DashboardWidget) => void
  onWidgetDelete: (widgetId: number) => void
}

type WidgetCardAlert = {
  widget_id: number
  enabled: boolean
  last_triggered_at: string | null
  last_triggered_level?: string | null
  alert_priority: string | null
}

type WidgetCardProps = Readonly<{
  widget: DashboardWidget
  isEditing: boolean
  dashboardId: number
  projectId?: string | number
  timeRange: TimeRangeDef
  autoRefresh: boolean
  variableValues?: Record<string, string>
  alerts: WidgetCardAlert[]
  onWidgetClick: (widget: DashboardWidget) => void
  onWidgetDelete: (widgetId: number) => void
}>

function getSectionMembership(widgets: DashboardWidget[]): Map<number, number> {
  const sorted = [...widgets].sort((a, b) => a.grid_y - b.grid_y || a.sort_order - b.sort_order)
  const membership = new Map<number, number>()
  let currentSectionId: number | null = null
  for (const w of sorted) {
    if (w.widget_type === 'section') {
      currentSectionId = w.id
    } else if (currentSectionId !== null) {
      membership.set(w.id, currentSectionId)
    }
  }
  return membership
}

function alertDotColor(
  firing: boolean,
  level: string | null | undefined,
  priority: string | null | undefined,
): string {
  if (!firing) return 'bg-muted-foreground/40'
  if (level === 'ERROR') return 'bg-danger-solid'
  if (level === 'WARNING') return 'bg-warning-solid'
  return priority === 'P0' || priority === 'P1' ? 'bg-danger-solid' : 'bg-warning-solid'
}

export function DashboardGrid({
  widgets,
  isEditing,
  dashboardId,
  projectId,
  timeRange,
  autoRefresh,
  variableValues,
  onLayoutChange,
  onWidgetClick,
  onWidgetDelete,
}: DashboardGridProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(1200)
  const [collapsedSections, setCollapsedSections] = useState<Set<number>>(() => {
    const initial = new Set<number>()
    for (const w of widgets) {
      if (w.widget_type === 'section' && w.display_config?.collapsed === 'true') {
        initial.add(w.id)
      }
    }
    return initial
  })

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

  const sectionMembership = useMemo(() => getSectionMembership(widgets), [widgets])

  const visibleWidgets = useMemo(() => {
    if (collapsedSections.size === 0) return widgets
    return widgets.filter((w) => {
      const parentSection = sectionMembership.get(w.id)
      return !parentSection || !collapsedSections.has(parentSection)
    })
  }, [widgets, collapsedSections, sectionMembership])

  const toggleSection = useCallback((sectionId: number) => {
    setCollapsedSections((prev) => {
      const next = new Set(prev)
      if (next.has(sectionId)) {
        next.delete(sectionId)
      } else {
        next.add(sectionId)
      }
      return next
    })
  }, [])

  const needsScaling = useMemo(
    () => visibleWidgets.some(w =>
      w.widget_type !== 'section' &&
      w.widget_type !== 'stat' &&
      w.widget_type !== 'gauge' &&
      w.widget_type !== 'bargauge' &&
      w.widget_type !== 'text' &&
      w.widget_type !== 'system_status' &&
      w.widget_type !== 'kpi' &&
      w.grid_h <= 4
    ),
    [visibleWidgets]
  )

  const layout = useMemo<Layout>(
    () => {
      return visibleWidgets.map((w): LayoutItem => {
        const h = needsScaling
          ? (w.widget_type === 'section' ? 1 : w.grid_h * 2)
          : w.grid_h
          
        const y = needsScaling ? w.grid_y * 2 : w.grid_y

        return {
          i: String(w.id),
          x: w.grid_x,
          y: y,
          w: w.grid_w,
          h: h,
          isDraggable: isEditing,
          isResizable: isEditing && w.widget_type !== 'section',
          minW: w.widget_type === 'section' ? 12 : 2,
          minH: w.widget_type === 'section'
            ? 1
            : isOverviewWidgetType(w.widget_type)
              ? (overviewWidgetDef(w.widget_type)?.minH ?? 4)
              : (['stat', 'gauge', 'bargauge'].includes(w.widget_type) ? 4 : 6),
          maxH: w.widget_type === 'section' ? 1 : undefined,
        }
      })
    },
    [visibleWidgets, isEditing, needsScaling]
  )

  const handleLayoutChange = useCallback(
    (newLayout: Layout) => {
      if (!isEditing) return
      const scale = needsScaling ? 2 : 1

      const hasChanges = newLayout.some((layoutItem) => {
        const widget = widgets.find((w) => String(w.id) === layoutItem.i)
        if (!widget) return false
        // Compare scaled layout values against scaled canonical values
        return (
          layoutItem.x !== widget.grid_x ||
          layoutItem.y !== widget.grid_y * scale ||
          layoutItem.w !== widget.grid_w ||
          layoutItem.h !== (widget.widget_type === 'section' ? 1 : widget.grid_h * scale)
        )
      })
      
      if (!hasChanges) return
      
      const updated: CreateWidgetRequest[] = widgets.map((widget) => {
        const layoutItem = newLayout.find((l) => l.i === String(widget.id))
        // Reverse the scaling transform before persisting canonical coordinates
        return {
          id: widget.id,
          title: widget.title,
          widget_type: widget.widget_type,
          grid_x: layoutItem?.x ?? widget.grid_x,
          grid_y: layoutItem != null ? Math.round(layoutItem.y / scale) : widget.grid_y,
          grid_w: layoutItem?.w ?? widget.grid_w,
          grid_h: layoutItem != null
            ? (widget.widget_type === 'section' ? 1 : Math.round(layoutItem.h / scale))
            : widget.grid_h,
          query_configs: widget.query_configs,
          display_config: widget.display_config,
          sort_order: widget.sort_order,
        }
      })
      onLayoutChange(updated)
    },
    [isEditing, needsScaling, widgets, onLayoutChange]
  )

  if (widgets.length === 0 && !isEditing) {
    return (
      <div className="flex items-center justify-center py-16 text-muted-foreground text-sm">
        This dashboard has no widgets yet. Click Edit to add some.
      </div>
    )
  }

  return (
    <div ref={containerRef} className="relative z-0 isolate w-full">
      <ResponsiveGridLayout
        className="layout"
        layouts={{lg: layout}}
        breakpoints={GRID_BREAKPOINTS}
        cols={GRID_COLS}
        rowHeight={40}
        width={width}
        isDraggable={isEditing}
        isResizable={isEditing}
        onLayoutChange={handleLayoutChange}
        draggableHandle=".drag-handle"
        compactType="vertical"
        margin={GRID_MARGIN}
      >
      {visibleWidgets.map((widget) => (
        <div
          key={String(widget.id)}
          className="group [&:hover]:z-50"
          style={widget.widget_type === 'section'
            ? undefined
            : {overflow: 'visible'}
          }
        >
          {widget.widget_type === 'section' ? (
            <SectionHeader
              widget={widget}
              isEditing={isEditing}
              isCollapsed={collapsedSections.has(widget.id)}
              onToggle={() => toggleSection(widget.id)}
              onDelete={() => onWidgetDelete(widget.id)}
            />
          ) : (
            <WidgetCard
              widget={widget}
              isEditing={isEditing}
              dashboardId={dashboardId}
              projectId={projectId}
              timeRange={timeRange}
              autoRefresh={autoRefresh}
              variableValues={variableValues}
              alerts={alerts}
              onWidgetClick={onWidgetClick}
              onWidgetDelete={onWidgetDelete}
            />
          )}
        </div>
      ))}
    </ResponsiveGridLayout>
    </div>
  )
}

function SectionHeader({
  widget,
  isEditing,
  isCollapsed,
  onToggle,
  onDelete,
}: {
  widget: DashboardWidget
  isEditing: boolean
  isCollapsed: boolean
  onToggle: () => void
  onDelete: () => void
}) {
  return (
    <div className="h-full flex items-center gap-2 px-1">
      {isEditing && (
        <div className="drag-handle cursor-grab active:cursor-grabbing shrink-0">
          <GripVertical className="h-3.5 w-3.5 text-muted-foreground" />
        </div>
      )}
      <button
        onClick={onToggle}
        className="flex items-center gap-1.5 min-w-0 hover:text-foreground transition-colors"
      >
        {isCollapsed
          ? <ChevronRight className="h-4 w-4 text-muted-foreground shrink-0" />
          : <ChevronDown className="h-4 w-4 text-muted-foreground shrink-0" />
        }
        <span className="text-sm font-semibold truncate">{widget.title || 'Section'}</span>
      </button>
      <div className="flex-1 border-b border-border/60 ml-2" />
      {isEditing && (
        <button
          onClick={(e) => {
            e.stopPropagation()
            onDelete()
          }}
          className="p-1 rounded text-destructive hover:bg-destructive/10 opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
        >
          <Trash2 className="h-3 w-3" />
        </button>
      )}
    </div>
  )
}

function WidgetCard({
  widget,
  isEditing,
  dashboardId,
  projectId,
  timeRange,
  autoRefresh,
  variableValues,
  alerts,
  onWidgetClick,
  onWidgetDelete,
}: WidgetCardProps) {
  // Overview widgets are full-bleed: they render their own panel chrome, so the
  // grid card stays chromeless and only overlays drag/delete affordances in edit mode.
  if (isOverviewWidgetType(widget.widget_type)) {
    return (
      <div className="relative h-full" style={{contain: 'style'}}>
        {isEditing && (
          <div className="absolute right-1 top-1 z-10 flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100">
            <div className="drag-handle cursor-grab rounded border bg-background/90 p-1 shadow-sm active:cursor-grabbing">
              <GripVertical className="h-3.5 w-3.5 text-muted-foreground" />
            </div>
            <button
              type="button"
              aria-label={`Delete ${widget.title ?? 'widget'}`}
              title={`Delete ${widget.title ?? 'widget'}`}
              onClick={(e) => {
                e.stopPropagation()
                onWidgetDelete(widget.id)
              }}
              className="rounded border bg-background/90 p-1 text-destructive shadow-sm hover:bg-destructive/10"
            >
              <Trash2 className="h-3 w-3" />
            </button>
          </div>
        )}
        <WidgetRenderer
          widget={widget}
          dashboardId={dashboardId}
          projectId={projectId}
          timeRange={timeRange}
          autoRefresh={autoRefresh}
          variables={variableValues}
        />
      </div>
    )
  }
  return (
    <div
      className={`h-full rounded-lg border bg-card overflow-visible flex flex-col ${
        isEditing ? 'ring-1 ring-transparent hover:ring-primary/30 cursor-pointer' : ''
      }`}
      style={{contain: 'style'}}
    >
      <div className="flex items-center justify-between px-3 py-2 border-b bg-muted/30 min-h-[36px]">
        <div className="flex items-center gap-1.5 min-w-0 flex-1">
          {isEditing && (
            <div className="drag-handle cursor-grab active:cursor-grabbing">
              <GripVertical className="h-3.5 w-3.5 text-muted-foreground" />
            </div>
          )}
          <span className="text-xs font-medium truncate">{
            variableValues
              ? Object.entries(variableValues).reduce(
                  (t, [k, v]) => {
                    const display = v === '$__all' ? 'All' : v
                    return t.replace(`$${k}`, display).replace(`\${${k}}`, display)
                  },
                  widget.title || 'Untitled'
                )
              : widget.title || 'Untitled'
          }</span>
          {(() => {
            const widgetAlerts = alerts.filter((a) => a.widget_id === widget.id && a.enabled)
            if (widgetAlerts.length === 0) return null
            const firingAlert = widgetAlerts.reduce<(typeof widgetAlerts)[number] | null>((latest, alert) => {
              if (!alert.last_triggered_at) return latest
              if (!latest?.last_triggered_at) return alert
              return Date.parse(alert.last_triggered_at) > Date.parse(latest.last_triggered_at) ? alert : latest
            }, null)
            const firing = firingAlert != null
            const priority = firingAlert?.alert_priority
            const dotColor = alertDotColor(firing, firingAlert?.last_triggered_level, priority)
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
          variables={variableValues}
        />
      </div>
    </div>
  )
}
