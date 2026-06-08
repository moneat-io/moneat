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
  type LogAggregateResponse,
  type LogEntry,
  type LogIndex,
  type LogIndexUsage,
  type LogMetricRule,
  type LogMonitor,
  type LogMonitorCondition,
  type LogPipeline,
  type LogPipelinePreviewEntry,
  type LogPipelinePreviewResult,
  type LogSavedView,
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
import {Checkbox} from '@/components/ui/checkbox'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {StatusDot} from '@/components/ui/status-dot'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {useToast} from '@/hooks/useToast'
import {cn} from '@/lib/utils'
import {
  GROUP_BY_NONE,
  LOG_MONITOR_CONDITIONS,
  toGroupByValue,
} from '@/components/logs/logManagementShared'
import {GroupBySelect, LevelChips} from '@/components/logs/LogManagementControls'
import {
  Activity,
  ArrowRight,
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

export type LogManagementTab = 'indexes' | 'pipelines' | 'views' | 'metrics' | 'monitors'

type LogManagementSheetProps = Readonly<{
  trigger?: React.ReactNode
  open?: boolean
  onOpenChange?: (open: boolean) => void
  defaultTab?: LogManagementTab
  currentQuery: string
  currentLevels: string[]
  currentViewState: LogSavedViewState
  currentLogs: LogEntry[]
  onApplySavedView: (state: LogSavedViewState) => void
}>

type IndexesPanelProps = Readonly<{currentQuery: string}>

type IndexRowProps = Readonly<{
  index: LogIndex
  usage?: LogIndexUsage
  onToggle: (checked: boolean) => void
  onDelete: () => void
}>

type PipelinesPanelProps = Readonly<{
  currentQuery: string
  currentLogs: LogEntry[]
}>

type SavedViewsPanelProps = Readonly<{
  currentViewState: LogSavedViewState
  onApplySavedView: (state: LogSavedViewState) => void
}>

type QueryLevelsPanelProps = Readonly<{
  currentQuery: string
  currentLevels: string[]
}>

type ContextStripProps = Readonly<{
  currentQuery: string
  currentLevels: string[]
}>

type FieldProps = Readonly<{
  label: string
  className?: string
  children: React.ReactNode
}>

const DEFAULT_RETENTION_DAYS = 30
const MIN_RETENTION_DAYS = 1
const MAX_RETENTION_DAYS = 365
const FULL_SAMPLING_RATE = 1
const MIN_SAMPLING_RATE = 0
const MIN_DAILY_QUOTA_GB = 0
const DEFAULT_PIPELINE_PATTERN = String.raw`(?i)(password|token|secret)=([^\s]+)`
const DEFAULT_PIPELINE_REPLACEMENT = '$1=[redacted]'
const DEFAULT_METRIC_INTERVAL = '5m'
const DEFAULT_MONITOR_THRESHOLD = 10
const MIN_MONITOR_THRESHOLD = 0
const BYTES_PER_GB = 1024 * 1024 * 1024
const PREVIEW_LOG_LIMIT = 3
const PREVIEW_BUCKET_LIMIT = 6
const SAMPLE_LINE_PLACEHOLDER = 'request failed credential=[sample] value=[sample]'
const DEFAULT_MONITOR_WINDOW_MINUTES = 5
const MIN_MONITOR_WINDOW_MINUTES = 1

function entryText(entry: LogPipelinePreviewEntry | null | undefined): string {
  if (!entry) return ''
  return entry.message ?? entry.body ?? ''
}

/** A redaction/drop only counts as a change when the masked field actually
 * differs from the source, or the log would be dropped before indexing. */
function isPipelineResultChanged(result: LogPipelinePreviewResult): boolean {
  if (result.dropped) return true
  const {before, after} = result
  if (!after) return false
  return (
    (before.message ?? '') !== (after.message ?? '') ||
    (before.body ?? '') !== (after.body ?? '')
  )
}

function pipelinePreviewResultKey(result: LogPipelinePreviewResult): string {
  return [
    result.dropped ? 'dropped' : 'kept',
    entryText(result.before),
    entryText(result.after),
    result.before.service ?? '',
    result.before.environment ?? '',
    result.before.host ?? '',
  ].join('|')
}

function keyedPipelinePreviewResults(results: LogPipelinePreviewResult[]) {
  const seenKeys = new Map<string, number>()
  return results.map((result) => {
    const baseKey = pipelinePreviewResultKey(result)
    const duplicateCount = seenKeys.get(baseKey) ?? 0
    seenKeys.set(baseKey, duplicateCount + 1)
    return {
      result,
      key: duplicateCount === 0 ? baseKey : `${baseKey}|duplicate-${duplicateCount}`,
    }
  })
}

function formatBytes(bytes: number): string {
  if (bytes >= BYTES_PER_GB) return `${(bytes / BYTES_PER_GB).toFixed(2)} GB`
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

function formatSamplingRate(rate: number): string {
  const percent = rate * 100
  const rounded = Number.isInteger(percent) ? percent : Math.round(percent * 10) / 10
  return `${rounded}%`
}

function parseFiniteInput(value: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null

  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : null
}

function optionalNumberAtLeast(value: string, min: number): number | null {
  const parsed = parseFiniteInput(value)
  if (parsed === null || parsed < min) return null
  return parsed
}

function clampNumber(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max)
}

function updateClampedNumber(
  value: string,
  min: number,
  max: number,
  onChange: (nextValue: number) => void
) {
  const parsed = parseFiniteInput(value)
  if (parsed !== null) onChange(clampNumber(parsed, min, max))
}

function updateMinimumNumber(
  value: string,
  min: number,
  onChange: (nextValue: number) => void
) {
  const parsed = parseFiniteInput(value)
  if (parsed !== null) onChange(Math.max(parsed, min))
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
  open,
  onOpenChange,
  defaultTab,
  currentQuery,
  currentLevels,
  currentViewState,
  currentLogs,
  onApplySavedView,
}: LogManagementSheetProps) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      {trigger && <SheetTrigger asChild>{trigger}</SheetTrigger>}
      <SheetContent side="right" className="flex w-[min(760px,94vw)] max-w-none flex-col p-0 sm:max-w-none">
        <SheetHeader className="space-y-1 border-b px-5 py-4">
          <SheetTitle>Log management</SheetTitle>
          <SheetDescription>
            Indexes, pipelines, saved views, log metrics, and monitors, scoped to the active Explorer query.
          </SheetDescription>
        </SheetHeader>
        <ContextStrip currentQuery={currentQuery} currentLevels={currentLevels} />
        <Tabs defaultValue={defaultTab ?? 'indexes'} className="flex min-h-0 flex-1 flex-col">
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
          <div className="min-h-0 flex-1 overflow-y-auto bg-muted/20 px-5 py-4">
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

function ContextStrip({currentQuery, currentLevels}: ContextStripProps) {
  return (
    <div className="border-b bg-muted/30 px-5 py-2.5">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5">
        <span className="text-[11px] font-semibold uppercase text-muted-foreground">
          Explorer context
        </span>
        <div className="flex min-w-0 items-center gap-1.5">
          <span className="text-[11px] text-muted-foreground">Query</span>
          <code
            className={cn(
              'max-w-[280px] truncate rounded bg-background px-1.5 py-0.5 font-mono text-[11px]',
              'text-foreground ring-1 ring-border'
            )}
          >
            {currentQuery.trim() || 'All logs'}
          </code>
        </div>
        <div className="flex items-center gap-1.5">
          <span className="text-[11px] text-muted-foreground">Levels</span>
          <LevelChips levels={currentLevels} />
        </div>
      </div>
      <p className="mt-1.5 text-[11px] text-muted-foreground">
        New indexes, log metrics, and monitors inherit this query and level filter.
      </p>
    </div>
  )
}

function IndexesPanel({currentQuery}: IndexesPanelProps) {
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
  const dailyQuotaGbValue = optionalNumberAtLeast(dailyQuotaGb, MIN_DAILY_QUOTA_GB)
  const hasInvalidDailyQuota = dailyQuotaGb.trim() !== '' && dailyQuotaGbValue === null

  const createIndex = useMutation({
    mutationFn: () => api.createLogIndex({
      name,
      filter_query: filterQuery,
      retention_days: retentionDays,
      sampling_rate: samplingRate,
      daily_quota_gb: dailyQuotaGbValue,
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
    onSuccess: (result) => {
      toast({
        title: 'Retention queued',
        description: `${result.indexes_processed} indexes processed.`,
      })
    },
  })

  const indexes = indexData?.indexes ?? []
  let indexListContent: React.ReactNode
  if (isLoading) {
    indexListContent = <LoadingRow />
  } else if (indexes.length === 0) {
    indexListContent = (
      <div className="p-3">
        <EmptyState
          icon={Database}
          title="No indexes yet"
          description="Create an index to route, sample, and retain a slice of incoming logs."
        />
      </div>
    )
  } else {
    indexListContent = (
      <Table className="text-xs">
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead className="h-8 px-3 text-[11px] uppercase">Index</TableHead>
            <TableHead className="h-8 px-3 text-[11px] uppercase">Usage</TableHead>
            <TableHead className="h-8 px-3 text-right text-[11px] uppercase">Retention</TableHead>
            <TableHead className="h-8 px-3 text-right text-[11px] uppercase">Sampling</TableHead>
            <TableHead className="h-8 px-3 text-right text-[11px] uppercase">Priority</TableHead>
            <TableHead className="h-8 px-3 text-right text-[11px] uppercase">
              <span className="sr-only">Actions</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {indexes.map((index) => (
            <IndexRow
              key={index.id}
              index={index}
              usage={usageByName.get(index.name)}
              onToggle={(checked) => updateIndex.mutate({id: index.id, is_active: checked})}
              onDelete={() => deleteIndex.mutate(index.id)}
            />
          ))}
        </TableBody>
      </Table>
    )
  }

  return (
    <div className="space-y-4">
      <SectionCard title="New index" icon={Database}>
        <div className="grid gap-3 md:grid-cols-2">
          <Field label="Name">
            <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="production-errors" />
          </Field>
          <Field label="Daily quota (GB)">
            <Input
              type="number"
              min={MIN_DAILY_QUOTA_GB}
              value={dailyQuotaGb}
              onChange={(event) => setDailyQuotaGb(event.target.value)}
              placeholder="optional"
              aria-invalid={hasInvalidDailyQuota}
            />
          </Field>
          <Field label="Retention days">
            <Input
              type="number"
              min={MIN_RETENTION_DAYS}
              max={MAX_RETENTION_DAYS}
              value={retentionDays}
              onChange={(event) => {
                updateClampedNumber(event.target.value, MIN_RETENTION_DAYS, MAX_RETENTION_DAYS, setRetentionDays)
              }}
            />
          </Field>
          <Field label="Sampling rate">
            <Input
              type="number"
              min={MIN_SAMPLING_RATE}
              max={FULL_SAMPLING_RATE}
              step={0.05}
              value={samplingRate}
              onChange={(event) => {
                updateClampedNumber(event.target.value, MIN_SAMPLING_RATE, FULL_SAMPLING_RATE, setSamplingRate)
              }}
            />
          </Field>
        </div>
        <Field label="Filter query" className="mt-3">
          <Textarea
            value={filterQuery}
            onChange={(event) => setFilterQuery(event.target.value)}
            className="font-mono text-xs"
            placeholder="catch-all"
          />
        </Field>
        {hasInvalidDailyQuota && (
          <p className="mt-2 text-xs text-danger-fg">Daily quota must be zero or a positive number.</p>
        )}
        <div className="mt-3 flex flex-wrap gap-2">
          <Button
            size="sm"
            onClick={() => createIndex.mutate()}
            disabled={!name.trim() || hasInvalidDailyQuota || createIndex.isPending}
          >
            {createIndex.isPending ? (
              <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
            ) : (
              <Plus className="mr-1.5 h-3.5 w-3.5" />
            )}
            Create index
          </Button>
          <Button size="sm" variant="outline" onClick={() => setFilterQuery(currentQuery)}>
            Use current search
          </Button>
          <Button
            size="sm"
            variant="ghost"
            className="ml-auto"
            onClick={() => runRetention.mutate()}
            disabled={runRetention.isPending}
          >
            Enforce retention
          </Button>
        </div>
      </SectionCard>

      <SectionCard title="Indexes" icon={Database} count={indexes.length} flushBody>
        {indexListContent}
      </SectionCard>
    </div>
  )
}

function IndexRow({index, usage, onToggle, onDelete}: IndexRowProps) {
  const quota = usage?.quota_gb ?? index.daily_quota_gb
  const quotaText = typeof quota === 'number' ? ` / ${quota} GB` : ''
  return (
    <TableRow>
      <TableCell className="px-3 py-2">
        <div className="flex items-start gap-2">
          <StatusDot tone={index.is_active ? 'success' : 'neutral'} className="mt-1" />
          <div className="min-w-0">
            <div className="flex items-center gap-1.5">
              <span className="truncate font-medium text-foreground">{index.name}</span>
              <Badge
                variant={index.is_active ? 'success' : 'neutral'}
                className="px-1.5 py-0 text-[10px] leading-4"
              >
                {index.is_active ? 'Active' : 'Paused'}
              </Badge>
            </div>
            <p className="mt-0.5 max-w-[220px] truncate font-mono text-[11px] text-muted-foreground">
              {index.filter_query || 'All logs'}
            </p>
          </div>
        </div>
      </TableCell>
      <TableCell className="px-3 py-2">
        <div className="font-mono tabular-nums text-foreground">
          {formatBytes(usage?.bytes_today ?? 0)}{quotaText} today
        </div>
        <p className="mt-0.5 text-[11px] text-muted-foreground">{usage?.count_today ?? 0} logs</p>
      </TableCell>
      <TableCell className="px-3 py-2 text-right tabular-nums text-muted-foreground">
        {index.retention_days}d
      </TableCell>
      <TableCell className="px-3 py-2 text-right tabular-nums text-muted-foreground">
        {formatSamplingRate(index.sampling_rate)}
      </TableCell>
      <TableCell className="px-3 py-2 text-right tabular-nums text-muted-foreground">
        {index.priority}
      </TableCell>
      <TableCell className="px-3 py-2">
        <div className="flex items-center justify-end gap-1">
          <Switch checked={index.is_active} onCheckedChange={onToggle} aria-label={`Toggle ${index.name}`} />
          <Button
            size="icon"
            variant="ghost"
            className="h-7 w-7 text-muted-foreground hover:text-destructive"
            onClick={onDelete}
            aria-label={`Delete ${index.name}`}
          >
            <Trash2 className="h-3.5 w-3.5" />
          </Button>
        </div>
      </TableCell>
    </TableRow>
  )
}

function PipelinesPanel({currentQuery, currentLogs}: PipelinesPanelProps) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [name, setName] = useState('')
  const [condition, setCondition] = useState(currentQuery)
  const [pattern, setPattern] = useState(DEFAULT_PIPELINE_PATTERN)
  const [replacement, setReplacement] = useState(DEFAULT_PIPELINE_REPLACEMENT)
  const [preview, setPreview] = useState<LogPipelinePreviewResult[] | null>(null)
  const [sampleLine, setSampleLine] = useState('')
  const [showUnchanged, setShowUnchanged] = useState(false)
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
    mutationFn: (samples: LogPipelinePreviewEntry[]) => api.previewLogPipeline(steps, samples),
    onSuccess: (result) => {
      setShowUnchanged(false)
      setPreview(result.results)
    },
  })
  const deletePipeline = useMutation({
    mutationFn: (id: number) => api.deleteLogPipeline(id),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['log-pipelines']}),
  })

  const pipelines = data?.pipelines ?? []
  const hasSampleLogs = currentLogs.length > 0

  return (
    <div className="space-y-4">
      <SectionCard title="New pipeline" icon={SlidersHorizontal}>
        <div className="grid gap-3 md:grid-cols-2">
          <Field label="Name">
            <Input
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="Redact sensitive keys"
            />
          </Field>
          <Field label="Condition">
            <Input
              value={condition}
              onChange={(event) => setCondition(event.target.value)}
              placeholder="optional query"
            />
          </Field>
          <Field label="Pattern">
            <Input value={pattern} onChange={(event) => setPattern(event.target.value)} className="font-mono text-xs" />
          </Field>
          <Field label="Replacement">
            <Input
              value={replacement}
              onChange={(event) => setReplacement(event.target.value)}
              className="font-mono text-xs"
            />
          </Field>
        </div>
        <div className="mt-3 flex flex-wrap gap-2">
          <Button size="sm" onClick={() => createPipeline.mutate()} disabled={!name.trim() || createPipeline.isPending}>
            <Plus className="mr-1.5 h-3.5 w-3.5" /> Create pipeline
          </Button>
          <Button
            size="sm"
            variant="outline"
            onClick={() => previewPipeline.mutate(sampleLogsForPreview(currentLogs))}
            disabled={!hasSampleLogs || previewPipeline.isPending}
          >
            <FlaskConical className="mr-1.5 h-3.5 w-3.5" /> Test on samples
          </Button>
        </div>
        {!hasSampleLogs && (
          <p className="mt-2 text-xs text-muted-foreground">
            Load logs in the Explorer to test this redaction step against real samples.
          </p>
        )}
        <div className="mt-4 border-t pt-3">
          <Field label="Test a sample line">
            <div className="flex flex-wrap gap-2">
              <Input
                value={sampleLine}
                onChange={(event) => setSampleLine(event.target.value)}
                placeholder={SAMPLE_LINE_PLACEHOLDER}
                className="min-w-[220px] flex-1 font-mono text-xs"
              />
              <Button
                size="sm"
                variant="outline"
                onClick={() => previewPipeline.mutate([{message: sampleLine}])}
                disabled={!sampleLine.trim() || previewPipeline.isPending}
              >
                Run sample
              </Button>
            </div>
          </Field>
          <p className="mt-1.5 text-[11px] text-muted-foreground">
            Runs the pattern against one line so you can confirm what it masks.
          </p>
        </div>
      </SectionCard>

      {preview && (
        <PipelineTestResults
          results={preview}
          showUnchanged={showUnchanged}
          onShowUnchangedChange={setShowUnchanged}
        />
      )}

      <SectionCard title="Pipelines" icon={SlidersHorizontal} count={pipelines.length}>
        {pipelines.length === 0 ? (
          <EmptyState
            icon={SlidersHorizontal}
            title="No pipelines yet"
            description="Pipelines remap, enrich, redact, or drop logs before they are indexed."
          />
        ) : (
          <div className="divide-y">
            {pipelines.map((pipeline) => (
              <PipelineRow
                key={pipeline.id}
                pipeline={pipeline}
                onDelete={() => deletePipeline.mutate(pipeline.id)}
              />
            ))}
          </div>
        )}
      </SectionCard>
    </div>
  )
}

function PipelineTestResults({
  results,
  showUnchanged,
  onShowUnchangedChange,
}: {
  readonly results: LogPipelinePreviewResult[]
  readonly showUnchanged: boolean
  readonly onShowUnchangedChange: (next: boolean) => void
}) {
  const changed = results.filter(isPipelineResultChanged)
  const unchanged = results.filter((result) => !isPipelineResultChanged(result))
  const visible = showUnchanged ? [...changed, ...unchanged] : changed
  const visibleRows = keyedPipelinePreviewResults(visible)
  let resultContent: React.ReactNode
  if (results.length === 0) {
    resultContent = (
      <p className="text-xs text-muted-foreground">
        No sample lines to test yet. Load logs in the Explorer or run a sample line above.
      </p>
    )
  } else if (changed.length === 0) {
    resultContent = (
      <p className="text-xs text-muted-foreground">
        None of the sampled logs matched this pattern. They would pass through unchanged.
      </p>
    )
  } else {
    resultContent = (
      <div className="space-y-2">
        {visibleRows.map(({key, result}) => (
          <PipelinePreviewRow key={key} result={result} />
        ))}
        {unchanged.length > 0 && (
          <label className="flex cursor-pointer items-center gap-2 pt-1 text-[11px] text-muted-foreground">
            <Checkbox
              checked={showUnchanged}
              onCheckedChange={(checked) => onShowUnchangedChange(checked === true)}
              className="h-3.5 w-3.5"
            />
            Show unchanged ({unchanged.length})
          </label>
        )}
      </div>
    )
  }

  return (
    <SectionCard title="Test results" icon={FlaskConical} count={`${changed.length} changed`}>
      {resultContent}
    </SectionCard>
  )
}

function PipelinePreviewRow({result}: {readonly result: LogPipelinePreviewResult}) {
  const beforeText = entryText(result.before)
  const afterText = entryText(result.after)

  if (!isPipelineResultChanged(result)) {
    return (
      <div className="flex items-center justify-between gap-2 rounded-md border bg-background p-2.5">
        <code className="min-w-0 break-all font-mono text-[11px] text-muted-foreground">{beforeText}</code>
        <Badge variant="neutral" className="shrink-0 px-1.5 py-0 text-[10px] leading-4">Unchanged</Badge>
      </div>
    )
  }

  return (
    <div className="rounded-md border bg-background p-2.5">
      <div className="mb-1.5">
        {result.dropped ? (
          <Badge variant="danger" className="px-1.5 py-0 text-[10px] leading-4">Dropped</Badge>
        ) : (
          <Badge variant="warning" className="px-1.5 py-0 text-[10px] leading-4">Redacted</Badge>
        )}
      </div>
      <div className="flex items-start gap-2">
        <span className="mt-0.5 w-10 shrink-0 text-[10px] font-semibold uppercase text-muted-foreground">
          Before
        </span>
        <code className="min-w-0 break-all font-mono text-[11px] text-muted-foreground">{beforeText}</code>
      </div>
      <div className="mt-1.5 flex items-start gap-2">
        <span
          className="mt-0.5 flex w-10 shrink-0 items-center gap-0.5 text-[10px] font-semibold uppercase text-foreground"
        >
          <ArrowRight className="h-3 w-3 text-muted-foreground" />
          After
        </span>
        {result.dropped ? (
          <span className="text-[11px] text-muted-foreground">Log dropped before indexing</span>
        ) : (
          <code className="min-w-0 break-all font-mono text-[11px] text-foreground">{afterText}</code>
        )}
      </div>
    </div>
  )
}

function PipelineRow({pipeline, onDelete}: {readonly pipeline: LogPipeline; readonly onDelete: () => void}) {
  const enabledSteps = pipeline.steps.filter((step) => step.enabled).length
  const enabledStepsText = enabledSteps < pipeline.steps.length ? ` / ${enabledSteps} enabled` : ''
  return (
    <div className="flex items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
      <div className="flex min-w-0 items-center gap-2">
        <StatusDot tone={pipeline.is_active ? 'success' : 'neutral'} />
        <div className="min-w-0">
          <p className="truncate text-sm font-medium">{pipeline.name}</p>
          <p className="truncate text-[11px] text-muted-foreground">
            {pipeline.steps.length} {pipeline.steps.length === 1 ? 'step' : 'steps'}
            {enabledStepsText} / priority {pipeline.priority}
          </p>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <Badge
          variant={pipeline.is_active ? 'success' : 'neutral'}
          className="px-1.5 py-0 text-[10px] leading-4"
        >
          {pipeline.is_active ? 'Active' : 'Paused'}
        </Badge>
        <Button
          size="icon"
          variant="ghost"
          className="h-7 w-7 text-muted-foreground hover:text-destructive"
          onClick={onDelete}
          aria-label={`Delete ${pipeline.name}`}
        >
          <Trash2 className="h-3.5 w-3.5" />
        </Button>
      </div>
    </div>
  )
}

function SavedViewsPanel({currentViewState, onApplySavedView}: SavedViewsPanelProps) {
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

  const views = data?.views ?? []

  return (
    <div className="space-y-4">
      <SectionCard title="Save current view" icon={Eye}>
        <div className="flex gap-2">
          <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="Name this view" />
          <Button size="sm" onClick={() => createView.mutate()} disabled={!name.trim() || createView.isPending}>
            <Save className="mr-1.5 h-3.5 w-3.5" /> Save
          </Button>
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
          <span>Captures</span>
          <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-foreground">
            {currentViewState.query.trim() || 'All logs'}
          </code>
          <LevelChips levels={currentViewState.levels} />
          <span>/ {currentViewState.visualization}</span>
        </div>
      </SectionCard>

      <SectionCard title="Saved views" icon={Eye} count={views.length}>
        {views.length === 0 ? (
          <EmptyState
            icon={Eye}
            title="No saved views yet"
            description="Save a query, level filter, and visualization to return to it in one click."
          />
        ) : (
          <div className="divide-y">
            {views.map((view) => (
              <SavedViewRow
                key={view.id}
                view={view}
                onApply={() => onApplySavedView(view.state)}
                onDelete={() => deleteView.mutate(view.id)}
              />
            ))}
          </div>
        )}
      </SectionCard>
    </div>
  )
}

function SavedViewRow({
  view,
  onApply,
  onDelete,
}: {
  readonly view: LogSavedView
  readonly onApply: () => void
  readonly onDelete: () => void
}) {
  return (
    <div className="flex items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
      <div className="min-w-0">
        <div className="flex items-center gap-1.5">
          <p className="truncate text-sm font-medium">{view.name}</p>
          {view.is_shared && (
            <Badge variant="neutral" className="px-1.5 py-0 text-[10px] leading-4">Shared</Badge>
          )}
        </div>
        <div className="mt-0.5 flex min-w-0 flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
          <code className="max-w-[260px] truncate font-mono text-foreground">
            {view.state.query.trim() || 'All logs'}
          </code>
          <LevelChips levels={view.state.levels} />
          <span>/ {view.state.visualization}</span>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <Button size="sm" variant="outline" onClick={onApply}>Apply</Button>
        <Button
          size="icon"
          variant="ghost"
          className="h-7 w-7 text-muted-foreground hover:text-destructive"
          onClick={onDelete}
          aria-label={`Delete ${view.name}`}
        >
          <Trash2 className="h-3.5 w-3.5" />
        </Button>
      </div>
    </div>
  )
}

function MetricsPanel({currentQuery, currentLevels}: QueryLevelsPanelProps) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [name, setName] = useState('')
  const [query, setQuery] = useState(currentQuery)
  const [groupBy, setGroupBy] = useState('service')
  const [preview, setPreview] = useState<LogAggregateResponse | null>(null)
  const {data} = useQuery({queryKey: ['log-metric-rules'], queryFn: () => api.getLogMetricRules()})
  const metricRequest = {
    name,
    query,
    levels: currentLevels,
    group_by: toGroupByValue(groupBy),
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
    onSuccess: (result) => setPreview(result),
  })
  const rollupRule = useMutation({
    mutationFn: (id: number) => api.rollupLogMetricRule(id),
    onSuccess: (result) => {
      toast({
        title: 'Metric rollup complete',
        description: `${result.points_inserted} points written.`,
      })
    },
  })

  const rules = data?.rules ?? []

  return (
    <div className="space-y-4">
      <SectionCard title="New log metric" icon={Activity}>
        <Field label="Search query">
          <Textarea
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            className="font-mono text-xs"
            placeholder="catch-all"
          />
        </Field>
        <div className="mt-2">
          <Button size="sm" variant="outline" onClick={() => setQuery(currentQuery)}>
            Use current search
          </Button>
        </div>
        <div className="mt-3 grid gap-3 md:grid-cols-2">
          <Field label="Metric name">
            <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="checkout_error_logs" />
          </Field>
          <Field label="Group by">
            <GroupBySelect value={groupBy} onChange={setGroupBy} />
            <p className="text-[11px] text-muted-foreground">Creates one metric series per selected value.</p>
          </Field>
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
          <span>Count of matching logs</span>
          <LevelChips levels={currentLevels} />
          <span>/ every {DEFAULT_METRIC_INTERVAL}</span>
        </div>
        <div className="mt-3 flex flex-wrap gap-2">
          <Button size="sm" onClick={() => createRule.mutate()} disabled={!name.trim() || createRule.isPending}>
            <Plus className="mr-1.5 h-3.5 w-3.5" /> Create metric
          </Button>
          <Button size="sm" variant="outline" onClick={() => previewRule.mutate()} disabled={previewRule.isPending}>
            <FlaskConical className="mr-1.5 h-3.5 w-3.5" /> Estimate volume
          </Button>
        </div>
      </SectionCard>

      {preview && <MetricPreview preview={preview} />}

      <SectionCard title="Log metrics" icon={Activity} count={rules.length}>
        {rules.length === 0 ? (
          <EmptyState
            icon={Activity}
            title="No log metrics yet"
            description="Roll matching logs into a counter you can chart and alert on."
          />
        ) : (
          <div className="divide-y">
            {rules.map((rule) => (
              <MetricRuleRow
                key={rule.id}
                rule={rule}
                onRollup={() => rollupRule.mutate(rule.id)}
              />
            ))}
          </div>
        )}
      </SectionCard>
    </div>
  )
}

function MetricPreview({preview}: {readonly preview: LogAggregateResponse}) {
  const buckets = preview.buckets.slice(0, PREVIEW_BUCKET_LIMIT)
  const maxCount = buckets.reduce((max, bucket) => Math.max(max, bucket.count), 0)
  return (
    <SectionCard
      title="Estimated volume"
      icon={FlaskConical}
      count={`${preview.totalCount} total`}
    >
      {buckets.length === 0 ? (
        <p className="text-xs text-muted-foreground">No matching logs in this window.</p>
      ) : (
        <div className="space-y-1">
          {buckets.map((bucket) => (
            <div key={bucket.timestamp} className="flex items-center gap-2">
              <span className="w-[150px] shrink-0 truncate font-mono text-[11px] text-muted-foreground">
                {bucket.timestamp}
              </span>
              <div className="h-2 flex-1 overflow-hidden rounded-full bg-muted">
                <div
                  className="h-full rounded-full bg-primary/70"
                  style={{width: `${maxCount > 0 ? (bucket.count / maxCount) * 100 : 0}%`}}
                />
              </div>
              <span className="w-10 shrink-0 text-right font-mono tabular-nums text-[11px] text-foreground">
                {bucket.count}
              </span>
            </div>
          ))}
        </div>
      )}
    </SectionCard>
  )
}

function MetricRuleRow({rule, onRollup}: {readonly rule: LogMetricRule; readonly onRollup: () => void}) {
  return (
    <div className="flex items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
      <div className="min-w-0">
        <div className="flex items-center gap-1.5">
          <p className="truncate text-sm font-medium">{rule.name}</p>
          <Badge variant="neutral" className="px-1.5 py-0 text-[10px] leading-4">{rule.interval}</Badge>
        </div>
        <div className="mt-0.5 flex min-w-0 flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
          <code className="max-w-[240px] truncate font-mono text-foreground">{rule.query.trim() || 'All logs'}</code>
          {rule.group_by && <span>/ by {rule.group_by}</span>}
          <LevelChips levels={rule.levels} />
        </div>
      </div>
      <div className="shrink-0">
        <Button size="sm" variant="outline" onClick={onRollup}>Roll up</Button>
      </div>
    </div>
  )
}

function MonitorsPanel({currentQuery, currentLevels}: QueryLevelsPanelProps) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [name, setName] = useState('')
  const [query, setQuery] = useState(currentQuery)
  const [condition, setCondition] = useState<LogMonitorCondition>('>')
  const [threshold, setThreshold] = useState(DEFAULT_MONITOR_THRESHOLD)
  const [windowMinutes, setWindowMinutes] = useState(DEFAULT_MONITOR_WINDOW_MINUTES)
  const [groupBy, setGroupBy] = useState(GROUP_BY_NONE)
  const {data} = useQuery({queryKey: ['log-monitors'], queryFn: () => api.getLogMonitors()})
  const createMonitor = useMutation({
    mutationFn: () => api.createLogMonitor({
      name,
      query,
      levels: currentLevels,
      group_by: toGroupByValue(groupBy),
      condition,
      threshold,
      window_minutes: windowMinutes,
    }),
    onSuccess: () => {
      setName('')
      queryClient.invalidateQueries({queryKey: ['log-monitors']})
      toast({title: 'Monitor created'})
    },
  })
  const deleteMonitor = useMutation({
    mutationFn: (id: number) => api.deleteLogMonitor(id),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['log-monitors']}),
  })

  const monitors = data?.monitors ?? []

  return (
    <div className="space-y-4">
      <SectionCard title="New log monitor" icon={Bell}>
        <Field label="Search query">
          <Textarea
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            className="font-mono text-xs"
            placeholder="catch-all"
          />
        </Field>
        <div className="mt-2">
          <Button size="sm" variant="outline" onClick={() => setQuery(currentQuery)}>
            Use current search
          </Button>
        </div>
        <div className="mt-3 grid gap-3 md:grid-cols-2">
          <Field label="Monitor name">
            <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="High error log volume" />
          </Field>
          <Field label="Group by">
            <GroupBySelect value={groupBy} onChange={setGroupBy} />
            <p className="text-[11px] text-muted-foreground">Evaluates the threshold per selected value.</p>
          </Field>
        </div>
        <div className="mt-3 grid gap-3 md:grid-cols-3">
          <Field label="Condition">
            <ConditionSelect value={condition} onChange={setCondition} />
          </Field>
          <Field label="Threshold">
            <Input
              type="number"
              min={MIN_MONITOR_THRESHOLD}
              value={threshold}
              onChange={(event) => {
                updateMinimumNumber(event.target.value, MIN_MONITOR_THRESHOLD, setThreshold)
              }}
            />
          </Field>
          <Field label="Window (min)">
            <Input
              type="number"
              min={MIN_MONITOR_WINDOW_MINUTES}
              value={windowMinutes}
              onChange={(event) => {
                updateMinimumNumber(event.target.value, MIN_MONITOR_WINDOW_MINUTES, setWindowMinutes)
              }}
            />
          </Field>
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
          <span>Alerts when matching logs</span>
          <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-foreground">{condition} {threshold}</code>
          <span>over {windowMinutes}m for</span>
          <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-foreground">
            {query.trim() || 'All logs'}
          </code>
          <LevelChips levels={currentLevels} />
        </div>
        <Button
          className="mt-3"
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
      </SectionCard>

      <SectionCard title="Log monitors" icon={Bell} count={monitors.length}>
        {monitors.length === 0 ? (
          <EmptyState
            icon={Bell}
            title="No log monitors yet"
            description="Alert on a saved query when matching log volume crosses a threshold."
          />
        ) : (
          <div className="divide-y">
            {monitors.map((monitor) => (
              <MonitorRow
                key={monitor.id}
                monitor={monitor}
                onDelete={() => deleteMonitor.mutate(monitor.id)}
              />
            ))}
          </div>
        )}
      </SectionCard>
    </div>
  )
}

function ConditionSelect({
  value,
  onChange,
}: {
  readonly value: LogMonitorCondition
  readonly onChange: (next: LogMonitorCondition) => void
}) {
  return (
    <Select value={value} onValueChange={(next) => onChange(next as LogMonitorCondition)}>
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
  )
}

function MonitorRow({monitor, onDelete}: {readonly monitor: LogMonitor; readonly onDelete: () => void}) {
  return (
    <div className="flex items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
      <div className="min-w-0">
        <div className="flex items-center gap-1.5">
          <StatusDot tone={monitor.is_active ? 'success' : 'neutral'} />
          <p className="truncate text-sm font-medium">{monitor.name}</p>
          <Badge variant="neutral" className="px-1.5 py-0 font-mono text-[10px] leading-4">
            {monitor.condition} {monitor.threshold}
          </Badge>
        </div>
        <div className="mt-0.5 flex min-w-0 flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
          <code className="max-w-[240px] truncate font-mono text-foreground">{monitor.query.trim() || 'All logs'}</code>
          {monitor.group_by && <span>/ by {monitor.group_by}</span>}
          <span>/ {monitor.window_minutes}m</span>
          <LevelChips levels={monitor.levels} />
        </div>
      </div>
      <Button
        size="icon"
        variant="ghost"
        className="h-7 w-7 shrink-0 text-muted-foreground hover:text-destructive"
        onClick={onDelete}
        aria-label={`Delete ${monitor.name}`}
      >
        <Trash2 className="h-3.5 w-3.5" />
      </Button>
    </div>
  )
}

function Field({label, className, children}: FieldProps) {
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
