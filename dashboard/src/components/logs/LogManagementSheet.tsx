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

import {useMemo, useState} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {
  api,
  type LogEntry,
  type LogIndex,
  type LogMetricRule,
  type LogPipeline,
  type LogSavedViewState,
} from '@/lib/api'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Textarea} from '@/components/ui/textarea'
import {Badge} from '@/components/ui/badge'
import {Switch} from '@/components/ui/switch'
import {useToast} from '@/hooks/useToast'
import {cn} from '@/lib/utils'
import {
  Activity,
  Bell,
  Database,
  Eye,
  FlaskConical,
  Loader2,
  Plus,
  Save,
  SlidersHorizontal,
  Trash2,
} from 'lucide-react'

interface LogManagementSheetProps {
  trigger: React.ReactNode
  currentQuery: string
  currentLevels: string[]
  currentViewState: LogSavedViewState
  currentLogs: LogEntry[]
  onApplySavedView: (state: LogSavedViewState) => void
}

const DEFAULT_RETENTION_DAYS = 30
const FULL_SAMPLING_RATE = 1
const DEFAULT_PIPELINE_PATTERN = '(?i)(password|token|secret)=([^\\s]+)'
const DEFAULT_PIPELINE_REPLACEMENT = '$1=[redacted]'
const DEFAULT_METRIC_INTERVAL = '5m'
const DEFAULT_MONITOR_THRESHOLD = 10
const BYTES_PER_GB = 1024 * 1024 * 1024
const PREVIEW_LOG_LIMIT = 3

function formatBytes(bytes: number): string {
  if (bytes >= BYTES_PER_GB) return `${(bytes / BYTES_PER_GB).toFixed(2)} GB`
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

function sampleLogsForPreview(logs: LogEntry[]) {
  return logs.slice(0, PREVIEW_LOG_LIMIT).map((log) => ({
    level: log.level,
    message: log.message,
    body: log.body,
    service: log.service,
    environment: log.environment,
    host: log.host,
    tags: log.tags,
    resource_attributes: log.resourceAttributes,
  }))
}

export function LogManagementSheet({
  trigger,
  currentQuery,
  currentLevels,
  currentViewState,
  currentLogs,
  onApplySavedView,
}: LogManagementSheetProps) {
  return (
    <Sheet>
      <SheetTrigger asChild>{trigger}</SheetTrigger>
      <SheetContent side="right" className="flex w-[min(720px,94vw)] max-w-none flex-col p-0 sm:max-w-none">
        <SheetHeader className="border-b px-5 py-4">
          <SheetTitle>Log management</SheetTitle>
          <SheetDescription>
            Manage indexes, pipelines, saved views, metrics, and monitors from the active Explorer context.
          </SheetDescription>
        </SheetHeader>
        <Tabs defaultValue="indexes" className="flex min-h-0 flex-1 flex-col">
          <div className="border-b px-4 py-3">
            <TabsList className="grid h-auto w-full grid-cols-5">
              <TabsTrigger value="indexes" className="gap-1.5 text-xs">
                <Database className="h-3.5 w-3.5" /> Indexes
              </TabsTrigger>
              <TabsTrigger value="pipelines" className="gap-1.5 text-xs">
                <SlidersHorizontal className="h-3.5 w-3.5" /> Pipelines
              </TabsTrigger>
              <TabsTrigger value="views" className="gap-1.5 text-xs">
                <Eye className="h-3.5 w-3.5" /> Views
              </TabsTrigger>
              <TabsTrigger value="metrics" className="gap-1.5 text-xs">
                <Activity className="h-3.5 w-3.5" /> Metrics
              </TabsTrigger>
              <TabsTrigger value="monitors" className="gap-1.5 text-xs">
                <Bell className="h-3.5 w-3.5" /> Monitors
              </TabsTrigger>
            </TabsList>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
            <TabsContent value="indexes" className="mt-0">
              <IndexesPanel currentQuery={currentQuery} />
            </TabsContent>
            <TabsContent value="pipelines" className="mt-0">
              <PipelinesPanel currentQuery={currentQuery} currentLogs={currentLogs} />
            </TabsContent>
            <TabsContent value="views" className="mt-0">
              <SavedViewsPanel currentViewState={currentViewState} onApplySavedView={onApplySavedView} />
            </TabsContent>
            <TabsContent value="metrics" className="mt-0">
              <MetricsPanel currentQuery={currentQuery} currentLevels={currentLevels} />
            </TabsContent>
            <TabsContent value="monitors" className="mt-0">
              <MonitorsPanel currentQuery={currentQuery} currentLevels={currentLevels} />
            </TabsContent>
          </div>
        </Tabs>
      </SheetContent>
    </Sheet>
  )
}

function IndexesPanel({currentQuery}: {currentQuery: string}) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [name, setName] = useState('')
  const [filterQuery, setFilterQuery] = useState(currentQuery)
  const [retentionDays, setRetentionDays] = useState(DEFAULT_RETENTION_DAYS)
  const [samplingRate, setSamplingRate] = useState(FULL_SAMPLING_RATE)
  const [dailyQuotaGb, setDailyQuotaGb] = useState('')

  const {data: indexData, isLoading} = useQuery({
    queryKey: ['log-indexes'],
    queryFn: () => api.getLogIndexes(),
  })
  const {data: usageData} = useQuery({
    queryKey: ['log-index-usage'],
    queryFn: () => api.getLogIndexUsage(),
  })
  const usageByName = useMemo(
    () => new Map((usageData?.usage ?? []).map((usage) => [usage.index_name, usage])),
    [usageData]
  )

  const createIndex = useMutation({
    mutationFn: () => api.createLogIndex({
      name,
      filter_query: filterQuery,
      retention_days: retentionDays,
      sampling_rate: samplingRate,
      daily_quota_gb: dailyQuotaGb === '' ? null : Number(dailyQuotaGb),
    }),
    onSuccess: () => {
      setName('')
      queryClient.invalidateQueries({queryKey: ['log-indexes']})
      queryClient.invalidateQueries({queryKey: ['log-index-usage']})
      toast({title: 'Index created'})
    },
  })
  const updateIndex = useMutation({
    mutationFn: ({id, is_active}: {id: number; is_active: boolean}) => api.updateLogIndex(id, {is_active}),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['log-indexes']}),
  })
  const deleteIndex = useMutation({
    mutationFn: (id: number) => api.deleteLogIndex(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['log-indexes']})
      queryClient.invalidateQueries({queryKey: ['log-index-usage']})
      toast({title: 'Index deleted'})
    },
  })
  const runRetention = useMutation({
    mutationFn: () => api.runLogIndexRetention(),
    onSuccess: (result) => toast({title: 'Retention queued', description: `${result.indexes_processed} indexes processed.`}),
  })

  return (
    <div className="space-y-4">
      <div className="rounded-md border p-3">
        <div className="grid gap-3 md:grid-cols-2">
          <Field label="Name">
            <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="production-errors" />
          </Field>
          <Field label="Daily quota (GB)">
            <Input value={dailyQuotaGb} onChange={(event) => setDailyQuotaGb(event.target.value)} placeholder="optional" />
          </Field>
          <Field label="Retention days">
            <Input
              type="number"
              min={1}
              max={365}
              value={retentionDays}
              onChange={(event) => setRetentionDays(Number(event.target.value))}
            />
          </Field>
          <Field label="Sampling rate">
            <Input
              type="number"
              min={0}
              max={1}
              step={0.05}
              value={samplingRate}
              onChange={(event) => setSamplingRate(Number(event.target.value))}
            />
          </Field>
        </div>
        <Field label="Filter query" className="mt-3">
          <Textarea value={filterQuery} onChange={(event) => setFilterQuery(event.target.value)} />
        </Field>
        <div className="mt-3 flex gap-2">
          <Button
            size="sm"
            onClick={() => createIndex.mutate()}
            disabled={!name.trim() || createIndex.isPending}
          >
            {createIndex.isPending ? <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" /> : <Plus className="mr-1.5 h-3.5 w-3.5" />}
            Create index
          </Button>
          <Button size="sm" variant="outline" onClick={() => setFilterQuery(currentQuery)}>
            Use current search
          </Button>
          <Button size="sm" variant="ghost" onClick={() => runRetention.mutate()}>
            Enforce retention
          </Button>
        </div>
      </div>
      <div className="space-y-2">
        {isLoading ? <LoadingRow /> : (indexData?.indexes ?? []).map((index) => (
          <IndexRow
            key={index.id}
            index={index}
            usage={usageByName.get(index.name)}
            onToggle={(checked) => updateIndex.mutate({id: index.id, is_active: checked})}
            onDelete={() => deleteIndex.mutate(index.id)}
          />
        ))}
      </div>
    </div>
  )
}

function IndexRow({
  index,
  usage,
  onToggle,
  onDelete,
}: {
  index: LogIndex
  usage?: {bytes_today: number; count_today: number; quota_gb?: number | null}
  onToggle: (checked: boolean) => void
  onDelete: () => void
}) {
  const quota = usage?.quota_gb ?? index.daily_quota_gb
  const quotaText = quota ? ` / ${quota} GB` : ''
  return (
    <div className="rounded-md border px-3 py-2">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <p className="truncate text-sm font-medium">{index.name}</p>
            <Badge variant={index.is_active ? 'default' : 'secondary'}>{index.is_active ? 'Active' : 'Paused'}</Badge>
          </div>
          <p className="mt-1 truncate font-mono text-xs text-muted-foreground">
            {index.filter_query || 'catch-all'}
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {formatBytes(usage?.bytes_today ?? 0)}{quotaText} today · {usage?.count_today ?? 0} logs · {index.retention_days}d
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <Switch checked={index.is_active} onCheckedChange={onToggle} />
          <Button size="icon" variant="ghost" className="h-8 w-8 text-destructive" onClick={onDelete}>
            <Trash2 className="h-3.5 w-3.5" />
          </Button>
        </div>
      </div>
    </div>
  )
}

function PipelinesPanel({currentQuery, currentLogs}: {currentQuery: string; currentLogs: LogEntry[]}) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [name, setName] = useState('')
  const [condition, setCondition] = useState(currentQuery)
  const [pattern, setPattern] = useState(DEFAULT_PIPELINE_PATTERN)
  const [replacement, setReplacement] = useState(DEFAULT_PIPELINE_REPLACEMENT)
  const [preview, setPreview] = useState<string>('')
  const {data} = useQuery({queryKey: ['log-pipelines'], queryFn: () => api.getLogPipelines()})
  const steps = useMemo(() => [{
    type: 'redact' as const,
    enabled: true,
    condition,
    source_field: 'message',
    pattern,
    replacement,
  }], [condition, pattern, replacement])
  const createPipeline = useMutation({
    mutationFn: () => api.createLogPipeline({name, steps, is_active: true}),
    onSuccess: () => {
      setName('')
      queryClient.invalidateQueries({queryKey: ['log-pipelines']})
      toast({title: 'Pipeline created'})
    },
  })
  const previewPipeline = useMutation({
    mutationFn: () => api.previewLogPipeline(steps, sampleLogsForPreview(currentLogs)),
    onSuccess: (result) => setPreview(JSON.stringify(result.results, null, 2)),
  })
  const deletePipeline = useMutation({
    mutationFn: (id: number) => api.deleteLogPipeline(id),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['log-pipelines']}),
  })

  return (
    <div className="space-y-4">
      <div className="rounded-md border p-3">
        <div className="grid gap-3 md:grid-cols-2">
          <Field label="Name">
            <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="Redact sensitive keys" />
          </Field>
          <Field label="Condition">
            <Input value={condition} onChange={(event) => setCondition(event.target.value)} placeholder="optional query" />
          </Field>
          <Field label="Pattern">
            <Input value={pattern} onChange={(event) => setPattern(event.target.value)} />
          </Field>
          <Field label="Replacement">
            <Input value={replacement} onChange={(event) => setReplacement(event.target.value)} />
          </Field>
        </div>
        <div className="mt-3 flex gap-2">
          <Button size="sm" onClick={() => createPipeline.mutate()} disabled={!name.trim() || createPipeline.isPending}>
            <Plus className="mr-1.5 h-3.5 w-3.5" /> Create pipeline
          </Button>
          <Button size="sm" variant="outline" onClick={() => previewPipeline.mutate()}>
            <FlaskConical className="mr-1.5 h-3.5 w-3.5" /> Preview
          </Button>
        </div>
        {preview && <pre className="mt-3 max-h-52 overflow-auto rounded-md bg-muted p-2 text-xs">{preview}</pre>}
      </div>
      <ListRows
        rows={data?.pipelines ?? []}
        getTitle={(pipeline: LogPipeline) => pipeline.name}
        getDescription={(pipeline: LogPipeline) => `${pipeline.steps.length} steps · priority ${pipeline.priority}`}
        onDelete={(pipeline: LogPipeline) => deletePipeline.mutate(pipeline.id)}
      />
    </div>
  )
}

function SavedViewsPanel({
  currentViewState,
  onApplySavedView,
}: {
  currentViewState: LogSavedViewState
  onApplySavedView: (state: LogSavedViewState) => void
}) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [name, setName] = useState('')
  const {data} = useQuery({queryKey: ['log-saved-views'], queryFn: () => api.getLogSavedViews()})
  const createView = useMutation({
    mutationFn: () => api.createLogSavedView({name, state: currentViewState, is_shared: true}),
    onSuccess: () => {
      setName('')
      queryClient.invalidateQueries({queryKey: ['log-saved-views']})
      toast({title: 'Saved view created'})
    },
  })
  const deleteView = useMutation({
    mutationFn: (id: number) => api.deleteLogSavedView(id),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['log-saved-views']}),
  })

  return (
    <div className="space-y-4">
      <div className="flex gap-2">
        <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="Name this view" />
        <Button size="sm" onClick={() => createView.mutate()} disabled={!name.trim() || createView.isPending}>
          <Save className="mr-1.5 h-3.5 w-3.5" /> Save
        </Button>
      </div>
      {(data?.views ?? []).map((view) => (
        <div key={view.id} className="rounded-md border px-3 py-2">
          <div className="flex items-center justify-between gap-2">
            <div className="min-w-0">
              <p className="truncate text-sm font-medium">{view.name}</p>
              <p className="truncate text-xs text-muted-foreground">{view.state.query || 'No query'}</p>
            </div>
            <div className="flex shrink-0 gap-1">
              <Button size="sm" variant="outline" onClick={() => onApplySavedView(view.state)}>Apply</Button>
              <Button size="icon" variant="ghost" className="h-8 w-8 text-destructive" onClick={() => deleteView.mutate(view.id)}>
                <Trash2 className="h-3.5 w-3.5" />
              </Button>
            </div>
          </div>
        </div>
      ))}
    </div>
  )
}

function MetricsPanel({currentQuery, currentLevels}: {currentQuery: string; currentLevels: string[]}) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [name, setName] = useState('')
  const [groupBy, setGroupBy] = useState('service')
  const [preview, setPreview] = useState<string>('')
  const {data} = useQuery({queryKey: ['log-metric-rules'], queryFn: () => api.getLogMetricRules()})
  const metricRequest = {
    name,
    query: currentQuery,
    levels: currentLevels,
    group_by: groupBy || null,
    interval: DEFAULT_METRIC_INTERVAL,
  }
  const createRule = useMutation({
    mutationFn: () => api.createLogMetricRule(metricRequest),
    onSuccess: () => {
      setName('')
      queryClient.invalidateQueries({queryKey: ['log-metric-rules']})
      toast({title: 'Metric rule created'})
    },
  })
  const previewRule = useMutation({
    mutationFn: () => api.previewLogMetricRule(metricRequest),
    onSuccess: (result) => setPreview(JSON.stringify(result.buckets.slice(0, 5), null, 2)),
  })
  const rollupRule = useMutation({
    mutationFn: (id: number) => api.rollupLogMetricRule(id),
    onSuccess: (result) => toast({title: 'Metric rollup complete', description: `${result.points_inserted} points written.`}),
  })

  return (
    <div className="space-y-4">
      <div className="rounded-md border p-3">
        <div className="grid gap-3 md:grid-cols-2">
          <Field label="Metric name">
            <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="checkout_error_logs" />
          </Field>
          <Field label="Group by">
            <Input value={groupBy} onChange={(event) => setGroupBy(event.target.value)} placeholder="service" />
          </Field>
        </div>
        <div className="mt-3 flex gap-2">
          <Button size="sm" onClick={() => createRule.mutate()} disabled={!name.trim() || createRule.isPending}>
            <Plus className="mr-1.5 h-3.5 w-3.5" /> Create metric
          </Button>
          <Button size="sm" variant="outline" onClick={() => previewRule.mutate()}>
            Preview
          </Button>
        </div>
        {preview && <pre className="mt-3 max-h-52 overflow-auto rounded-md bg-muted p-2 text-xs">{preview}</pre>}
      </div>
      <ListRows
        rows={data?.rules ?? []}
        getTitle={(rule: LogMetricRule) => rule.name}
        getDescription={(rule: LogMetricRule) => `${rule.query || 'No query'} · ${rule.interval}`}
        onAction={(rule: LogMetricRule) => rollupRule.mutate(rule.id)}
        actionLabel="Roll up"
      />
    </div>
  )
}

function MonitorsPanel({currentQuery, currentLevels}: {currentQuery: string; currentLevels: string[]}) {
  const [name, setName] = useState('')
  const [threshold, setThreshold] = useState(DEFAULT_MONITOR_THRESHOLD)
  const [draft, setDraft] = useState<string>('')
  const createMonitor = useMutation({
    mutationFn: () => api.createLogMonitorFromQuery({
      name,
      query: currentQuery,
      levels: currentLevels,
      condition: '>',
      threshold,
    }),
    onSuccess: (result) => setDraft(JSON.stringify(result, null, 2)),
  })

  return (
    <div className="space-y-4">
      <div className="rounded-md border p-3">
        <div className="grid gap-3 md:grid-cols-2">
          <Field label="Monitor name">
            <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="High error log volume" />
          </Field>
          <Field label="Threshold">
            <Input
              type="number"
              value={threshold}
              onChange={(event) => setThreshold(Number(event.target.value))}
            />
          </Field>
        </div>
        <Button className="mt-3" size="sm" onClick={() => createMonitor.mutate()} disabled={!name.trim()}>
          <Bell className="mr-1.5 h-3.5 w-3.5" /> Create monitor draft
        </Button>
        {draft && <pre className="mt-3 max-h-52 overflow-auto rounded-md bg-muted p-2 text-xs">{draft}</pre>}
      </div>
    </div>
  )
}

function ListRows<T>({
  rows,
  getTitle,
  getDescription,
  onDelete,
  onAction,
  actionLabel,
}: {
  rows: T[]
  getTitle: (row: T) => string
  getDescription: (row: T) => string
  onDelete?: (row: T) => void
  onAction?: (row: T) => void
  actionLabel?: string
}) {
  if (rows.length === 0) {
    return <p className="py-6 text-center text-sm text-muted-foreground">No entries yet.</p>
  }
  return (
    <div className="space-y-2">
      {rows.map((row, index) => (
        <div key={`${getTitle(row)}-${index}`} className="rounded-md border px-3 py-2">
          <div className="flex items-center justify-between gap-3">
            <div className="min-w-0">
              <p className="truncate text-sm font-medium">{getTitle(row)}</p>
              <p className="truncate text-xs text-muted-foreground">{getDescription(row)}</p>
            </div>
            <div className="flex shrink-0 gap-1">
              {onAction && actionLabel && (
                <Button size="sm" variant="outline" onClick={() => onAction(row)}>{actionLabel}</Button>
              )}
              {onDelete && (
                <Button size="icon" variant="ghost" className="h-8 w-8 text-destructive" onClick={() => onDelete(row)}>
                  <Trash2 className="h-3.5 w-3.5" />
                </Button>
              )}
            </div>
          </div>
        </div>
      ))}
    </div>
  )
}

function Field({
  label,
  className,
  children,
}: {
  label: string
  className?: string
  children: React.ReactNode
}) {
  return (
    <div className={cn('space-y-1.5', className)}>
      <Label className="text-xs text-muted-foreground">{label}</Label>
      {children}
    </div>
  )
}

function LoadingRow() {
  return (
    <div className="flex items-center justify-center py-8">
      <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
    </div>
  )
}
