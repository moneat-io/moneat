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

import {createFileRoute, useNavigate} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, type CreateWidgetRequest, type DashboardVariable, type DashboardWidget} from '@/lib/api'
import {DashboardGrid} from '@/components/dashboards/DashboardGrid'
import {DashboardToolbar} from '@/components/dashboards/DashboardToolbar'
import {WidgetConfigPanel} from '@/components/dashboards/WidgetConfigPanel'
import {ImportExportModal} from '@/components/dashboards/ImportExportModal'
import {DataSourceMapperModal} from '@/components/dashboards/DataSourceMapperModal'
import {VariableSettingsDialog} from '@/components/dashboards/VariableSettingsDialog'
import {parseDashboardLink} from '@/components/dashboards/dashboardShareLink'
import {useWidgetClipboard} from '@/components/dashboards/useWidgetClipboard'
import {useCallback, useEffect, useRef, useState} from 'react'
import {isDemo} from '@/lib/demo'
import {EmptyState} from '@/components/ui/empty-state'
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import {LayoutDashboard} from 'lucide-react'
import {primaryServiceResourceId} from '@/lib/service-facet-scope'

interface DashboardSearch {
  edit?: boolean
  from?: string
  to?: string
  vars?: string
}

export const Route = createFileRoute('/dashboards/$dashboardId')({
  component: DashboardViewPage,
  validateSearch: (search: Record<string, unknown>): DashboardSearch => ({
    edit: search.edit === true || search.edit === 'true',
    from: typeof search.from === 'string' ? search.from : undefined,
    to: typeof search.to === 'string' ? search.to : undefined,
    vars: typeof search.vars === 'string' ? search.vars : undefined,
  }),
})

export function DashboardViewPage() {
  const {dashboardId} = Route.useParams()
  const {edit, from, to, vars} = Route.useSearch()
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  // Restore time range + variable selections from a shared deep link, if present.
  const linked = parseDashboardLink({from, to, vars})

  const [isEditing, setIsEditing] = useState(edit ?? false)
  const [selectedWidget, setSelectedWidget] = useState<DashboardWidget | null>(null)
  const [selectedWidgetId, setSelectedWidgetId] = useState<string | null>(null)
  const [showExport, setShowExport] = useState(false)
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)
  const [timeRange, setTimeRange] = useState(
    linked.timeRange ?? {from: isDemo() ? 'now-7d' : 'now-24h', to: 'now'},
  )
  const [refreshMs, setRefreshMs] = useState(0)
  const [variableValues, setVariableValues] = useState<Record<string, string>>(
    linked.variableValues ?? {},
  )
  const [resolvedOptions, setResolvedOptions] = useState<Record<string, string[]>>({})
  const [showVariableSettings, setShowVariableSettings] = useState(false)
  const [mapperState, setMapperState] = useState<{
    widget: CreateWidgetRequest
    sources: string[]
  } | null>(null)

  const id = dashboardId
  const toWidgetUpdateRequest = useCallback((w: DashboardWidget): CreateWidgetRequest => ({
    ...(w.id ? {id: w.id} : {}),
    title: w.title,
    widget_type: w.widget_type,
    grid_x: w.grid_x,
    grid_y: w.grid_y,
    grid_w: w.grid_w,
    grid_h: w.grid_h,
    query_configs: w.query_configs,
    display_config: w.display_config,
    sort_order: w.sort_order,
  }), [])

  const {data: dashboard, isLoading} = useQuery({
    queryKey: ['custom-dashboard', id],
    queryFn: () => api.getDashboard(id),
    enabled: id.length > 0,
  })
  const {data: projects = []} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })
  const primaryServiceId = primaryServiceResourceId(projects)

  // Initialize variable values from dashboard variable defaults on first load
  useEffect(() => {
    const vars = dashboard?.variables
    if (!vars?.length) return
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setVariableValues((prev) => {
      const next = {...prev}
      let changed = false
      for (const v of vars) {
        if (!(v.name in next)) {
          let defaultVal = v.current ?? v.default_value ?? (v.options.length > 0 ? v.options[0] : '')
          // Grafana's $__all means "match all" — keep it so the backend can handle it
          if (defaultVal === '$__all' && v.options.length > 0) {
            // Prefer first real option; keep $__all if no alternatives
            const realOption = v.options.find((o: string) => o !== '$__all')
            defaultVal = realOption ?? '$__all'
          }
          if (defaultVal) {
            next[v.name] = defaultVal
            changed = true
          }
        }
      }
      return changed ? next : prev
    })
  }, [dashboard?.variables])

  // Resolve dynamic variable options (e.g., Grafana label_values queries) from Prometheus
  const [resolveKey, setResolveKey] = useState(0)
  useEffect(() => {
    if (!dashboard?.variables?.length) return
    const hasQueryVars = dashboard.variables.some(v => v.query?.startsWith('label_values('))
    if (!hasQueryVars) return
    // Wait until variable values are populated before resolving
    if (Object.keys(variableValues).length === 0) return

    api.resolveVariableOptions(id, variableValues).then(resolved => {
      if (Object.keys(resolved).length > 0) {
        setResolvedOptions(prev => {
          const same = Object.keys(resolved).every(k => JSON.stringify(prev[k]) === JSON.stringify(resolved[k]))
          return same ? prev : resolved
        })
      }
    }).catch(() => {})
  }, [dashboard?.variables, id, resolveKey, variableValues])

  const {mutate: updateDashboard} = useMutation({
    mutationFn: (data: Parameters<typeof api.updateDashboard>[1]) => api.updateDashboard(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['custom-dashboard', id]})
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
    },
  })
  const favoriteMutation = useMutation({
    mutationFn: () => api.toggleDashboardFavorite(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['custom-dashboard', id]})
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
    },
  })

  const duplicateMutation = useMutation({
    mutationFn: () => api.duplicateDashboard(id),
    onSuccess: (created) => {
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
      navigate({to: '/dashboards/$dashboardId', params: {dashboardId: String(created.id)}, search: {edit: true}})
    },
  })

  const deleteMutation = useMutation({
    mutationFn: () => api.deleteDashboard(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
      navigate({to: '/dashboards'})
    },
  })

  const setDefaultMutation = useMutation({
    mutationFn: () => api.setDefaultDashboard(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['custom-dashboard', id]})
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
    },
  })

  // Manual + interval refresh re-run every mounted widget query.
  const handleRefreshNow = useCallback(() => {
    queryClient.invalidateQueries({queryKey: ['widget-data']})
  }, [queryClient])

  useEffect(() => {
    if (refreshMs <= 0) return
    const timer = globalThis.setInterval(() => {
      queryClient.invalidateQueries({queryKey: ['widget-data']})
    }, refreshMs)
    return () => globalThis.clearInterval(timer)
  }, [refreshMs, queryClient])

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
      prePasteWidgetsRef.current = dashboard.widgets.map((w) => toWidgetUpdateRequest(w))
      const widgets: CreateWidgetRequest[] = [
        ...prePasteWidgetsRef.current,
        {...widget, sort_order: dashboard.widgets.length},
      ]
      updateDashboard({widgets})
    },
    [dashboard, updateDashboard, toWidgetUpdateRequest]
  )

  const handleUndoPaste = useCallback(() => {
    if (!prePasteWidgetsRef.current) return
    updateDashboard({widgets: prePasteWidgetsRef.current})
    prePasteWidgetsRef.current = null
  }, [updateDashboard])

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
      updateDashboard({widgets})
    },
    [updateDashboard]
  )

  const handleTitleChange = useCallback(
    (title: string) => {
      updateDashboard({title})
    },
    [updateDashboard]
  )

  const handleVariablesSave = useCallback(
    (variables: DashboardVariable[]) => {
      updateDashboard({variables})
    },
    [updateDashboard]
  )

  const handleAddWidget = useCallback(() => {
    if (!dashboard) return
    const newWidget: DashboardWidget = {
      id: `draft-widget-${Date.now()}`,
      dashboard_id: id,
      title: 'New Widget',
      widget_type: 'timeseries',
      grid_x: 0,
      grid_y: (dashboard.widgets.length > 0
        ? Math.max(...dashboard.widgets.map((w) => w.grid_y + w.grid_h))
        : 0),
      grid_w: 6,
      grid_h: 8,
      query_configs: [{
        dataSource: 'events',
        metrics: [{function: 'count', alias: 'count'}],
        groupBy: [{field: 'timestamp', type: 'time', interval: 'auto'}],
        filters: [],
        limit: 100,
        timeRange: {from: 'now-24h', to: 'now'},
      }],
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
                ...(widget.id ? {id: widget.id} : {}),
                title: widget.title,
                widget_type: widget.widget_type,
                grid_x: widget.grid_x,
                grid_y: widget.grid_y,
                grid_w: widget.grid_w,
                grid_h: widget.grid_h,
                query_configs: widget.query_configs,
                display_config: widget.display_config,
                sort_order: widget.sort_order,
              }
            : toWidgetUpdateRequest(w)
        )
      } else {
        widgets = [
          ...dashboard.widgets.map((w) => toWidgetUpdateRequest(w)),
          {
            title: widget.title,
            widget_type: widget.widget_type,
            grid_x: widget.grid_x,
            grid_y: widget.grid_y,
            grid_w: widget.grid_w,
            grid_h: widget.grid_h,
            query_configs: widget.query_configs,
            display_config: widget.display_config,
            sort_order: widget.sort_order,
          },
        ]
      }
      updateDashboard({widgets})
      setSelectedWidget(null)
    },
    [dashboard, updateDashboard, toWidgetUpdateRequest]
  )

  const handleDeleteWidget = useCallback(
    (widgetId: string) => {
      if (!dashboard) return
      const widgets = dashboard.widgets
        .filter((w) => w.id !== widgetId)
        .map((w) => toWidgetUpdateRequest(w))
      updateDashboard({widgets})
    },
    [dashboard, updateDashboard, toWidgetUpdateRequest]
  )

  if (isLoading) {
    return (
      <div className="p-4 space-y-4">
        <div className="h-10 bg-muted/30 rounded animate-pulse" />
        <div className="h-96 bg-muted/30 rounded animate-pulse" />
      </div>
    )
  }

  if (!dashboard) {
    return (
      <div className="p-6">
        <EmptyState
          icon={LayoutDashboard}
          title="Dashboard not found"
          description="This dashboard may have been deleted or you may not have access to it."
        />
      </div>
    )
  }

  return (
    <div className="p-4 space-y-4">
      <DashboardToolbar
        title={dashboard.title}
        updatedAt={dashboard.updated_at}
        isEditing={isEditing}
        isFavorited={dashboard.is_favorited}
        isDefault={dashboard.is_default}
        onToggleEdit={() => setIsEditing(!isEditing)}
        onSave={handleSave}
        onTitleChange={handleTitleChange}
        onAddWidget={handleAddWidget}
        onExport={() => setShowExport(true)}
        onDuplicate={() => duplicateMutation.mutate()}
        onDelete={() => setShowDeleteConfirm(true)}
        onToggleFavorite={() => favoriteMutation.mutate()}
        onSetDefault={() => setDefaultMutation.mutate()}
        timeRange={timeRange}
        onTimeRangeChange={setTimeRange}
        refreshMs={refreshMs}
        onRefreshMsChange={setRefreshMs}
        onRefreshNow={handleRefreshNow}
        variables={dashboard.variables?.map(v => ({
          ...v,
          options: resolvedOptions[v.name]?.length ? resolvedOptions[v.name] : v.options,
        }))}
        variableValues={variableValues}
        onVariableChange={(name, value) => {
          setVariableValues(prev => ({...prev, [name]: value}))
          setResolveKey(k => k + 1)
        }}
        onVariableSettings={() => setShowVariableSettings(true)}
      />

      <DashboardGrid
        widgets={dashboard.widgets}
        isEditing={isEditing}
        dashboardId={id}
        projectId={primaryServiceId}
        timeRange={timeRange}
        autoRefresh={false}
        variableValues={variableValues}
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
          projectId={primaryServiceId}
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

      <VariableSettingsDialog
        open={showVariableSettings}
        onOpenChange={setShowVariableSettings}
        variables={dashboard.variables ?? []}
        onSave={handleVariablesSave}
      />

      <AlertDialog open={showDeleteConfirm} onOpenChange={setShowDeleteConfirm}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete this dashboard?</AlertDialogTitle>
            <AlertDialogDescription>
              &ldquo;{dashboard.title}&rdquo; and its widgets will be permanently removed. This cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => deleteMutation.mutate()}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
