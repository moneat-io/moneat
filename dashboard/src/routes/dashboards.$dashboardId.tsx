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

import {createFileRoute} from '@tanstack/react-router'
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {api, type DashboardWidget, type CreateWidgetRequest} from '@/lib/api'
import {DashboardGrid} from '@/components/dashboards/DashboardGrid'
import {DashboardToolbar} from '@/components/dashboards/DashboardToolbar'
import {WidgetConfigPanel} from '@/components/dashboards/WidgetConfigPanel'
import {ImportExportModal} from '@/components/dashboards/ImportExportModal'
import {DataSourceMapperModal} from '@/components/dashboards/DataSourceMapperModal'
import {useWidgetClipboard} from '@/components/dashboards/useWidgetClipboard'
import {useState, useCallback, useRef} from 'react'
import {useProject} from '@/contexts/project-context'

interface DashboardSearch {
  edit?: boolean
}

export const Route = createFileRoute('/dashboards/$dashboardId')({
  component: DashboardViewPage,
  validateSearch: (search: Record<string, unknown>): DashboardSearch => ({
    edit: search.edit === true || search.edit === 'true',
  }),
})

function DashboardViewPage() {
  const {dashboardId} = Route.useParams()
  const {edit} = Route.useSearch()
  const queryClient = useQueryClient()
  const {selectedProjectId} = useProject()

  const [isEditing, setIsEditing] = useState(edit ?? false)
  const [selectedWidget, setSelectedWidget] = useState<DashboardWidget | null>(null)
  const [selectedWidgetId, setSelectedWidgetId] = useState<number | null>(null)
  const [showExport, setShowExport] = useState(false)
  const [timeRange, setTimeRange] = useState({from: 'now-24h', to: 'now'})
  const [autoRefresh, setAutoRefresh] = useState(false)
  const [mapperState, setMapperState] = useState<{
    widget: CreateWidgetRequest
    sources: string[]
  } | null>(null)

  const id = parseInt(dashboardId, 10)

  const {data: dashboard, isLoading} = useQuery({
    queryKey: ['custom-dashboard', id],
    queryFn: () => api.getDashboard(id),
    enabled: !isNaN(id),
  })

  const updateMutation = useMutation({
    mutationFn: (data: Parameters<typeof api.updateDashboard>[1]) => api.updateDashboard(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['custom-dashboard', id]})
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
    },
  })

  const {data: availableDataSources} = useQuery({
    queryKey: ['datasources'],
    queryFn: () => api.getDataSources(),
    staleTime: 60000,
  })

  const prePasteWidgetsRef = useRef<CreateWidgetRequest[] | null>(null)

  const handlePasteWidget = useCallback(
    (widget: CreateWidgetRequest) => {
      if (!dashboard) return
      // Save current state for undo
      prePasteWidgetsRef.current = dashboard.widgets.map((w) => ({
        title: w.title,
        widget_type: w.widget_type,
        grid_x: w.grid_x,
        grid_y: w.grid_y,
        grid_w: w.grid_w,
        grid_h: w.grid_h,
        query_config: w.query_config,
        display_config: w.display_config,
        sort_order: w.sort_order,
      }))
      const widgets: CreateWidgetRequest[] = [
        ...prePasteWidgetsRef.current,
        {...widget, sort_order: dashboard.widgets.length},
      ]
      updateMutation.mutate({widgets})
    },
    [dashboard, updateMutation]
  )

  const handleUndoPaste = useCallback(() => {
    if (!prePasteWidgetsRef.current) return
    updateMutation.mutate({widgets: prePasteWidgetsRef.current})
    prePasteWidgetsRef.current = null
  }, [updateMutation])

  const handleWidgetClick = useCallback(
    (widget: DashboardWidget) => {
      if (isEditing) {
        setSelectedWidgetId(widget.id)
        setSelectedWidget(widget)
      }
    },
    [isEditing]
  )

  useWidgetClipboard({
    isEditing,
    widgets: dashboard?.widgets ?? [],
    selectedWidgetId,
    onPasteWidget: handlePasteWidget,
    onDatasourceMapping: (widget, sources) => setMapperState({widget, sources}),
    onUndo: handleUndoPaste,
  })

  const handleSave = useCallback(() => {
    if (!dashboard) return
    setIsEditing(false)
  }, [dashboard])

  const handleLayoutChange = useCallback(
    (widgets: CreateWidgetRequest[]) => {
      updateMutation.mutate({widgets})
    },
    [updateMutation]
  )

  const handleTitleChange = useCallback(
    (title: string) => {
      updateMutation.mutate({title})
    },
    [updateMutation]
  )

  const handleAddWidget = useCallback(() => {
    if (!dashboard) return
    const newWidget: DashboardWidget = {
      id: 0,
      dashboard_id: id,
      title: 'New Widget',
      widget_type: 'timeseries',
      grid_x: 0,
      grid_y: (dashboard.widgets.length > 0
        ? Math.max(...dashboard.widgets.map((w) => w.grid_y + w.grid_h))
        : 0),
      grid_w: 6,
      grid_h: 4,
      query_config: {
        dataSource: 'events',
        metrics: [{function: 'count', alias: 'count'}],
        groupBy: [{field: 'timestamp', type: 'time', interval: 'auto'}],
        filters: [],
        limit: 100,
        timeRange: {from: 'now-24h', to: 'now'},
      },
      display_config: {},
      sort_order: dashboard.widgets.length,
    }
    setSelectedWidget(newWidget)
  }, [dashboard, id])

  const handleWidgetSave = useCallback(
    (widget: DashboardWidget) => {
      if (!dashboard) return
      const existingIndex = dashboard.widgets.findIndex((w) => w.id === widget.id)
      let widgets: CreateWidgetRequest[]
      if (existingIndex >= 0) {
        widgets = dashboard.widgets.map((w, i) =>
          i === existingIndex
            ? {
                title: widget.title,
                widget_type: widget.widget_type,
                grid_x: widget.grid_x,
                grid_y: widget.grid_y,
                grid_w: widget.grid_w,
                grid_h: widget.grid_h,
                query_config: widget.query_config,
                display_config: widget.display_config,
                sort_order: widget.sort_order,
              }
            : {
                title: w.title,
                widget_type: w.widget_type,
                grid_x: w.grid_x,
                grid_y: w.grid_y,
                grid_w: w.grid_w,
                grid_h: w.grid_h,
                query_config: w.query_config,
                display_config: w.display_config,
                sort_order: w.sort_order,
              }
        )
      } else {
        widgets = [
          ...dashboard.widgets.map((w) => ({
            title: w.title,
            widget_type: w.widget_type,
            grid_x: w.grid_x,
            grid_y: w.grid_y,
            grid_w: w.grid_w,
            grid_h: w.grid_h,
            query_config: w.query_config,
            display_config: w.display_config,
            sort_order: w.sort_order,
          })),
          {
            title: widget.title,
            widget_type: widget.widget_type,
            grid_x: widget.grid_x,
            grid_y: widget.grid_y,
            grid_w: widget.grid_w,
            grid_h: widget.grid_h,
            query_config: widget.query_config,
            display_config: widget.display_config,
            sort_order: widget.sort_order,
          },
        ]
      }
      updateMutation.mutate({widgets})
      setSelectedWidget(null)
    },
    [dashboard, updateMutation]
  )

  const handleDeleteWidget = useCallback(
    (widgetId: number) => {
      if (!dashboard) return
      const widgets = dashboard.widgets
        .filter((w) => w.id !== widgetId)
        .map((w) => ({
          title: w.title,
          widget_type: w.widget_type,
          grid_x: w.grid_x,
          grid_y: w.grid_y,
          grid_w: w.grid_w,
          grid_h: w.grid_h,
          query_config: w.query_config,
          display_config: w.display_config,
          sort_order: w.sort_order,
        }))
      updateMutation.mutate({widgets})
    },
    [dashboard, updateMutation]
  )

  if (isLoading) {
    return (
      <div className="space-y-4">
        <div className="h-10 bg-muted/30 rounded animate-pulse" />
        <div className="h-96 bg-muted/30 rounded animate-pulse" />
      </div>
    )
  }

  if (!dashboard) {
    return (
      <div className="flex items-center justify-center py-16 text-muted-foreground">
        Dashboard not found
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <DashboardToolbar
        title={dashboard.title}
        isEditing={isEditing}
        onToggleEdit={() => setIsEditing(!isEditing)}
        onSave={handleSave}
        onTitleChange={handleTitleChange}
        onAddWidget={handleAddWidget}
        onExport={() => setShowExport(true)}
        timeRange={timeRange}
        onTimeRangeChange={setTimeRange}
        autoRefresh={autoRefresh}
        onAutoRefreshChange={setAutoRefresh}
      />

      <DashboardGrid
        widgets={dashboard.widgets}
        isEditing={isEditing}
        dashboardId={id}
        projectId={selectedProjectId ?? undefined}
        timeRange={timeRange}
        autoRefresh={autoRefresh}
        onLayoutChange={handleLayoutChange}
        onWidgetClick={handleWidgetClick}
        onWidgetDelete={handleDeleteWidget}
      />

      {selectedWidget && (
        <WidgetConfigPanel
          widget={selectedWidget}
          onSave={handleWidgetSave}
          onClose={() => setSelectedWidget(null)}
          dashboardId={id}
          projectId={selectedProjectId ?? undefined}
        />
      )}

      <ImportExportModal
        open={showExport}
        onOpenChange={setShowExport}
        mode="export"
        dashboardId={id}
      />

      <DataSourceMapperModal
        open={mapperState !== null}
        widget={mapperState?.widget ?? null}
        unknownSources={mapperState?.sources ?? []}
        dataSources={availableDataSources ?? []}
        onConfirm={(widget) => {
          handlePasteWidget(widget)
          setMapperState(null)
        }}
        onCancel={() => setMapperState(null)}
      />
    </div>
  )
}
