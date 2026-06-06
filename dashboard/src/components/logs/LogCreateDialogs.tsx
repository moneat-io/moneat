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

import {useState} from 'react'
import type {ReactNode} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {Activity, Bell, Loader2} from 'lucide-react'

import {api, type CreateWidgetRequest, type DashboardWidget, type LogMonitorCondition} from '@/lib/api'
import type {FacetFilter} from '@/lib/filters/types'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Textarea} from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {useToast} from '@/hooks/useToast'
import {WidgetConfigPanel} from '@/components/dashboards/WidgetConfigPanel'
import {primaryServiceResourceId} from '@/lib/service-facet-scope'
import {buildLogMetricWidget, defaultLogMetricTitle} from '@/components/logs/logWidgetSeed'
import {LOG_MONITOR_CONDITIONS, toGroupByValue} from '@/components/logs/logManagementShared'
import {GroupBySelect, LevelChips} from '@/components/logs/LogManagementControls'

/**
 * Create actions launched from the Log Explorer's Export menu.
 *
 * "Create metric" reuses the real dashboard widget editor: it seeds a `logs`
 * count widget from the current query, lets the user pick (or create) a target
 * dashboard, then hands off to {@link WidgetConfigPanel} for a live preview and
 * refinement before saving. "Create monitor" stays a focused threshold form.
 */

const NEW_DASHBOARD_VALUE = '__new__'
const DEFAULT_MONITOR_THRESHOLD = 10
const MIN_MONITOR_THRESHOLD = 0
const DEFAULT_MONITOR_WINDOW_MINUTES = 5
const MIN_MONITOR_WINDOW_MINUTES = 1
const GROUP_BY_NONE = 'none'

type CreateDialogProps = Readonly<{
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Seed query: the Explorer's raw search plus active facets, flattened. */
  query: string
  /** Seed level filter (empty means "all levels"). */
  levels: string[]
}>

function parseBoundedInt(value: string, fallback: number, min: number): number {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return fallback
  return Math.max(Math.trunc(parsed), min)
}

function DialogField({label, children}: {readonly label: string; readonly children: ReactNode}) {
  return (
    <div className="space-y-1.5">
      <Label className="text-xs text-muted-foreground">{label}</Label>
      {children}
    </div>
  )
}

function toWidgetRequest(widget: DashboardWidget, sortOrder: number): CreateWidgetRequest {
  return {
    ...(widget.id > 0 ? {id: widget.id} : {}),
    title: widget.title,
    widget_type: widget.widget_type,
    grid_x: widget.grid_x,
    grid_y: widget.grid_y,
    grid_w: widget.grid_w,
    grid_h: widget.grid_h,
    query_configs: widget.query_configs,
    display_config: widget.display_config,
    sort_order: sortOrder,
  }
}

function MonitorForm({query, levels, onClose}: {
  readonly query: string
  readonly levels: string[]
  readonly onClose: () => void
}) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [name, setName] = useState('')
  const [queryText, setQueryText] = useState(query)
  const [condition, setCondition] = useState<LogMonitorCondition>('>')
  const [threshold, setThreshold] = useState(DEFAULT_MONITOR_THRESHOLD)
  const [windowMinutes, setWindowMinutes] = useState(DEFAULT_MONITOR_WINDOW_MINUTES)
  const [groupBy, setGroupBy] = useState(GROUP_BY_NONE)

  const createMonitor = useMutation({
    mutationFn: () => api.createLogMonitor({
      name,
      query: queryText,
      levels,
      group_by: toGroupByValue(groupBy),
      condition,
      threshold,
      window_minutes: windowMinutes,
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['log-monitors']})
      toast({title: 'Monitor created'})
      onClose()
    },
  })

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-base">Create log monitor</DialogTitle>
        <DialogDescription>
          Alert on this query when matching log volume crosses a threshold.
        </DialogDescription>
      </DialogHeader>
      <div className="space-y-3 py-1">
        <DialogField label="Search query">
          <Textarea
            value={queryText}
            onChange={(event) => setQueryText(event.target.value)}
            className="h-16 font-mono text-xs"
            placeholder="catch-all"
          />
        </DialogField>
        <DialogField label="Monitor name">
          <Input
            autoFocus
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="High error log volume"
          />
        </DialogField>
        <div className="grid gap-3 sm:grid-cols-2">
          <DialogField label="Condition">
            <Select value={condition} onValueChange={(next) => setCondition(next as LogMonitorCondition)}>
              <SelectTrigger className="h-9">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {LOG_MONITOR_CONDITIONS.map((option) => (
                  <SelectItem key={option} value={option}>
                    {option}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </DialogField>
          <DialogField label="Threshold">
            <Input
              type="number"
              min={MIN_MONITOR_THRESHOLD}
              value={threshold}
              onChange={(event) => {
                setThreshold(parseBoundedInt(event.target.value, threshold, MIN_MONITOR_THRESHOLD))
              }}
            />
          </DialogField>
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          <DialogField label="Window (min)">
            <Input
              type="number"
              min={MIN_MONITOR_WINDOW_MINUTES}
              value={windowMinutes}
              onChange={(event) => {
                setWindowMinutes(parseBoundedInt(event.target.value, windowMinutes, MIN_MONITOR_WINDOW_MINUTES))
              }}
            />
          </DialogField>
          <DialogField label="Group by">
            <GroupBySelect value={groupBy} onChange={setGroupBy} />
          </DialogField>
        </div>
        <div className="flex flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
          <span>Alerts when logs</span>
          <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-foreground">{condition} {threshold}</code>
          <span>over {windowMinutes}m</span>
          <span aria-hidden>·</span>
          <LevelChips levels={levels} />
        </div>
      </div>
      <DialogFooter className="mt-2">
        <Button variant="outline" size="sm" onClick={onClose}>Cancel</Button>
        <Button
          size="sm"
          onClick={() => createMonitor.mutate()}
          disabled={!name.trim() || createMonitor.isPending}
        >
          {createMonitor.isPending ? (
            <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
          ) : (
            <Bell className="mr-1.5 h-3.5 w-3.5" />
          )}
          Create monitor
        </Button>
      </DialogFooter>
    </>
  )
}

type CreateLogMetricDialogProps = Readonly<{
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Raw search text (free-text portion) seeded into the widget query. */
  query: string
  /** Active level filter (empty means "all levels"). */
  levels: string[]
  /** Structured facet filters seeded into the widget's query filters. */
  facetFilters: FacetFilter[]
  /** Active visualization group-by field ('' | level | service | environment). */
  groupByField: string
  /** Current explorer time window. */
  timeRange: {from?: string; to?: string}
}>

/**
 * "Create metric": seed a `logs` count widget from the current query, choose a
 * destination dashboard (existing or new), then open the real widget editor to
 * preview and refine before saving. A freshly created dashboard is discarded if
 * the user backs out of the editor without saving a widget.
 */
export function CreateLogMetricDialog({
  open,
  onOpenChange,
  query,
  levels,
  facetFilters,
  groupByField,
  timeRange,
}: CreateLogMetricDialogProps) {
  const {toast} = useToast()
  const queryClient = useQueryClient()

  const [stage, setStage] = useState<'choose' | 'edit'>('choose')
  const [dashboardId, setDashboardId] = useState<number | null>(null)
  const [createdNew, setCreatedNew] = useState(false)
  const [widget, setWidget] = useState<DashboardWidget | null>(null)
  const [selected, setSelected] = useState('')
  const [newName, setNewName] = useState('')
  const [title, setTitle] = useState('')

  const reset = () => {
    setStage('choose')
    setDashboardId(null)
    setCreatedNew(false)
    setWidget(null)
    setSelected('')
    setNewName('')
    setTitle('')
  }
  const close = () => {
    reset()
    onOpenChange(false)
  }

  const dashboardsQuery = useQuery({
    queryKey: ['custom-dashboards'],
    queryFn: () => api.getDashboards(),
    enabled: open,
  })
  const projectsQuery = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
    enabled: open,
  })

  const dashboards = dashboardsQuery.data ?? []
  const projectId = primaryServiceResourceId(projectsQuery.data ?? [])
  const effectiveSelected =
    selected || (dashboards.length > 0 ? String(dashboards[0].id) : NEW_DASHBOARD_VALUE)
  const isNew = effectiveSelected === NEW_DASHBOARD_VALUE

  const openEditor = useMutation({
    mutationFn: async () => {
      if (isNew) {
        const created = await api.createDashboard({title: newName.trim() || 'Logs', widgets: []})
        return {id: created.id, created: true}
      }
      return {id: Number(effectiveSelected), created: false}
    },
    onSuccess: ({id, created}) => {
      setDashboardId(id)
      setCreatedNew(created)
      setWidget(buildLogMetricWidget({query, levels, facetFilters, groupByField, timeRange, title}))
      setStage('edit')
    },
  })

  const saveWidget = useMutation({
    mutationFn: async (edited: DashboardWidget) => {
      if (dashboardId == null) throw new Error('No target dashboard')
      const dashboard = await api.getDashboard(dashboardId)
      const widgets = [
        ...dashboard.widgets.map((existing, index) => toWidgetRequest(existing, index)),
        toWidgetRequest(edited, dashboard.widgets.length),
      ]
      await api.updateDashboard(dashboardId, {widgets})
      return {id: dashboardId, dashboardTitle: dashboard.title}
    },
    onSuccess: ({id, dashboardTitle}) => {
      queryClient.invalidateQueries({queryKey: ['custom-dashboard', id]})
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
      toast({title: 'Metric added', description: `Added to “${dashboardTitle}”.`})
      close()
    },
  })

  const handleCloseEditor = () => {
    // A dashboard created just for this flow is thrown away if nothing was saved.
    if (createdNew && dashboardId != null) {
      api.deleteDashboard(dashboardId).catch(() => {})
    }
    close()
  }

  if (open && stage === 'edit' && dashboardId != null && widget) {
    return (
      <WidgetConfigPanel
        widget={widget}
        dashboardId={dashboardId}
        projectId={projectId}
        onSave={(edited) => saveWidget.mutate(edited)}
        onClose={handleCloseEditor}
      />
    )
  }

  const continueDisabled = openEditor.isPending || (isNew && !newName.trim())

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next) close() }}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="text-base">Create metric</DialogTitle>
          <DialogDescription>
            Chart this query as a log-count widget. Choose where it lives, then preview and refine it
            in the widget editor.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3 py-1">
          <DialogField label="Widget title">
            <Input
              autoFocus
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder={defaultLogMetricTitle(query)}
            />
          </DialogField>
          <DialogField label="Add to dashboard">
            <Select value={effectiveSelected} onValueChange={setSelected}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {dashboards.map((dashboard) => (
                  <SelectItem key={dashboard.id} value={String(dashboard.id)}>
                    {dashboard.title}
                  </SelectItem>
                ))}
                <SelectItem value={NEW_DASHBOARD_VALUE}>＋ New dashboard…</SelectItem>
              </SelectContent>
            </Select>
          </DialogField>
          {isNew && (
            <DialogField label="New dashboard name">
              <Input
                value={newName}
                onChange={(event) => setNewName(event.target.value)}
                placeholder="Log metrics"
              />
            </DialogField>
          )}
          <div className="flex flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
            <span>Counts matching logs over time</span>
            <span aria-hidden>·</span>
            <LevelChips levels={levels} />
          </div>
        </div>
        <DialogFooter className="mt-2">
          <Button variant="outline" size="sm" onClick={close}>Cancel</Button>
          <Button size="sm" onClick={() => openEditor.mutate()} disabled={continueDisabled}>
            {openEditor.isPending ? (
              <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
            ) : (
              <Activity className="mr-1.5 h-3.5 w-3.5" />
            )}
            Continue
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export function CreateLogMonitorDialog({open, onOpenChange, query, levels}: CreateDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        {open && <MonitorForm query={query} levels={levels} onClose={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}
