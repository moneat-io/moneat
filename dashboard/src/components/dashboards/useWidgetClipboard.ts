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

import {useCallback, useEffect, useRef, useState} from 'react'
import type {CreateWidgetRequest, DashboardWidget} from '@/lib/api'

// Grafana panel type → Moneat widget type
const GRAFANA_TYPE_MAP: Record<string, string> = {
  timeseries: 'timeseries',
  graph: 'timeseries',
  barchart: 'bar',
  bargauge: 'bar',
  piechart: 'donut',
  stat: 'stat',
  gauge: 'stat',
  table: 'table',
  heatmap: 'heatmap',
  text: 'text',
  row: 'section',
}

interface GrafanaPanel {
  type?: string
  title?: string
  gridPos?: {x?: number; y?: number; w?: number; h?: number}
  targets?: Array<{
    expr?: string
    rawSql?: string
    query?: string
    datasource?: {type?: string; uid?: string} | string
  }>
  datasource?: {type?: string; uid?: string} | string
  options?: Record<string, unknown>
  fieldConfig?: Record<string, unknown>
}

export interface PastedWidget {
  widget: CreateWidgetRequest
  unknownDatasources: string[]
  warnings: string[]
}

/**
 * Detect if pasted JSON is a Grafana panel, a Moneat widget, or unknown.
 */
function detectFormat(data: unknown): 'grafana-panel' | 'moneat-widget' | 'unknown' {
  if (!data || typeof data !== 'object') return 'unknown'
  const obj = data as Record<string, unknown>

  // Grafana panel: has "type" + "gridPos" or "targets" or "datasource"
  if (obj.type && typeof obj.type === 'string' &&
      (obj.gridPos || obj.targets || obj.datasource || obj.fieldConfig)) {
    return 'grafana-panel'
  }

  // Moneat widget: has "widget_type" + "query_configs" (or legacy "query_config")
  if (obj.widget_type && (obj.query_configs || obj.query_config)) {
    return 'moneat-widget'
  }

  return 'unknown'
}

/**
 * Convert a Grafana panel JSON to a Moneat CreateWidgetRequest.
 */
function convertGrafanaPanel(panel: GrafanaPanel, yOffset: number): PastedWidget {
  const warnings: string[] = []
  const unknownDatasources: string[] = []

  // Map widget type
  const grafanaType = panel.type || 'timeseries'
  let widgetType = GRAFANA_TYPE_MAP[grafanaType]
  if (widgetType === 'section') {
    return {
      widget: {
        title: panel.title || 'Section',
        widget_type: 'section',
        grid_x: 0,
        grid_y: yOffset,
        grid_w: 12,
        grid_h: 1,
        query_configs: [],
        display_config: {collapsed: 'false'},
      },
      unknownDatasources: [],
      warnings: [],
    }
  }
  if (!widgetType) {
    warnings.push(`Unknown Grafana panel type '${grafanaType}', using 'timeseries'`)
    widgetType = 'timeseries'
  }

  // Map grid position (Grafana 24-col → Moneat 12-col)
  const gp = panel.gridPos || {}
  const gridX = Math.round((gp.x || 0) / 2)
  const gridW = Math.max(2, Math.round((gp.w || 12) / 2))
  const gridH = Math.max(2, gp.h || 4)

  // Parse datasource
  let dataSource = 'events'
  const panelDs = panel.datasource
  const targetDs = panel.targets?.[0]?.datasource
  const dsRef = targetDs || panelDs
  if (dsRef) {
    const dsType = typeof dsRef === 'string' ? dsRef : dsRef.type || dsRef.uid || ''
    if (dsType) {
      // Map known Grafana datasource types to Moneat
      if (dsType.includes('prometheus') || dsType === 'prometheus') {
        dataSource = 'system_metrics'
      } else if (dsType.includes('loki') || dsType === 'loki') {
        dataSource = 'logs'
      } else if (dsType.includes('tempo') || dsType === 'tempo') {
        dataSource = 'spans'
      } else if (dsType.includes('clickhouse') || dsType === 'clickhouse') {
        dataSource = 'events'
      } else {
        unknownDatasources.push(dsType)
        dataSource = `__unmapped:${dsType}`
      }
    }
  }

  // Parse query from targets
  let rawQuery: string | undefined
  const target = panel.targets?.[0]
  if (target) {
    if (target.expr) rawQuery = target.expr
    else if (target.rawSql) rawQuery = target.rawSql
    else if (target.query) rawQuery = target.query
  }

  const queryConfig = {
    dataSource,
    metrics: [{function: 'count' as const, alias: 'count'}],
    groupBy: [{field: 'timestamp', type: 'time' as const, interval: 'auto'}],
    filters: [],
    limit: 100,
    timeRange: {from: 'now-24h', to: 'now'},
    ...(rawQuery ? {rawQuery} : {}),
  }

  return {
    widget: {
      title: panel.title || 'Pasted Widget',
      widget_type: widgetType,
      grid_x: Math.min(gridX, 10),
      grid_y: yOffset,
      grid_w: Math.min(gridW, 12),
      grid_h: gridH,
      query_configs: [queryConfig],
      display_config: {},
    },
    unknownDatasources,
    warnings,
  }
}

/**
 * Duplicate a Moneat widget, offsetting position.
 */
function duplicateWidget(widget: DashboardWidget, yOffset: number): CreateWidgetRequest {
  return {
    title: `${widget.title || 'Widget'} (copy)`,
    widget_type: widget.widget_type,
    grid_x: widget.grid_x,
    grid_y: yOffset,
    grid_w: widget.grid_w,
    grid_h: widget.grid_h,
    query_configs: widget.query_configs.map(q => ({...q})),
    display_config: {...widget.display_config},
  }
}

export interface UseWidgetClipboardOptions {
  isEditing: boolean
  widgets: DashboardWidget[]
  selectedWidgetId: number | null
  onPasteWidget: (widget: CreateWidgetRequest) => void
  onDatasourceMapping: (widget: CreateWidgetRequest, unknownSources: string[]) => void
  onUndo: () => void
}

export function useWidgetClipboard({
  isEditing,
  widgets,
  selectedWidgetId,
  onPasteWidget,
  onDatasourceMapping,
  onUndo,
}: UseWidgetClipboardOptions) {
  const copiedWidgetRef = useRef<DashboardWidget | null>(null)
  const [copiedWidget, setCopiedWidget] = useState<DashboardWidget | null>(null)
  const canUndoRef = useRef(false)

  const getNextY = useCallback(() => {
    if (widgets.length === 0) return 0
    return Math.max(...widgets.map((w) => w.grid_y + w.grid_h))
  }, [widgets])

  const handleCopy = useCallback(() => {
    if (!isEditing || selectedWidgetId === null) return
    const widget = widgets.find((w) => w.id === selectedWidgetId)
    if (!widget) return

    copiedWidgetRef.current = widget
    setCopiedWidget(widget)
    // Also put Moneat JSON in clipboard for cross-tab paste
    const json = JSON.stringify({
      _moneat_widget: true,
      ...widget,
    })
    navigator.clipboard.writeText(json).catch(() => {
      // Clipboard API may fail in non-secure contexts; internal copy still works
    })
  }, [isEditing, selectedWidgetId, widgets])

  const handlePaste = useCallback(async () => {
    if (!isEditing) return

    const yOffset = getNextY()
    canUndoRef.current = true

    // Try reading from system clipboard first (may contain Grafana/external JSON)
    try {
      const text = await navigator.clipboard.readText()
      if (text.trim().startsWith('{')) {
        const data = JSON.parse(text)
        const format = detectFormat(data)

        if (format === 'grafana-panel') {
          const result = convertGrafanaPanel(data as GrafanaPanel, yOffset)
          if (result.unknownDatasources.length > 0) {
            onDatasourceMapping(result.widget, result.unknownDatasources)
          } else {
            onPasteWidget(result.widget)
          }
          return
        }

        if (format === 'moneat-widget') {
          // Pasted Moneat widget (from another tab/dashboard)
          const w = data as DashboardWidget & {_moneat_widget?: boolean}
          const pasted: CreateWidgetRequest = {
            title: `${w.title || 'Widget'} (copy)`,
            widget_type: w.widget_type,
            grid_x: w.grid_x ?? 0,
            grid_y: yOffset,
            grid_w: w.grid_w ?? 6,
            grid_h: w.grid_h ?? 4,
            query_configs: w.query_configs,
            display_config: w.display_config ?? {},
          }
          onPasteWidget(pasted)
          return
        }
      }
    } catch {
      // Clipboard read failed, fall through to internal copy
    }

    // Fallback: duplicate internally copied widget
    if (copiedWidgetRef.current) {
      onPasteWidget(duplicateWidget(copiedWidgetRef.current, yOffset))
    }
  }, [isEditing, getNextY, onPasteWidget, onDatasourceMapping])

  useEffect(() => {
    if (!isEditing) return

    const handler = (e: KeyboardEvent) => {
      // Ignore if user is typing in an input/textarea
      const tag = (e.target as HTMLElement)?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return

      if ((e.ctrlKey || e.metaKey) && e.key === 'c') {
        handleCopy()
      }
      if ((e.ctrlKey || e.metaKey) && e.key === 'v') {
        e.preventDefault()
        handlePaste()
      }
      if ((e.ctrlKey || e.metaKey) && e.key === 'z') {
        if (canUndoRef.current) {
          e.preventDefault()
          canUndoRef.current = false
          onUndo()
        }
      }
    }

    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [isEditing, handleCopy, handlePaste, onUndo])

  return {
    copiedWidget,
    copy: handleCopy,
    paste: handlePaste,
  }
}
