import {useState} from 'react'
import {ChevronRight} from 'lucide-react'
import {type ApmSpanUsageDebugGroup, type ApmSpanUsageDebugResponse} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {formatDateTime as formatDateTimeUtil} from '@/lib/date-format'

const TRACE_ID_PREVIEW_LENGTH = 12
const FULL_PERCENT = 100
const CLICKHOUSE_DATETIME_PATTERN = /^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})(?:\.(\d+))?$/

function formatOptionalText(value: string | null | undefined, fallback = 'None'): string {
  const trimmed = value?.trim()
  return trimmed ? trimmed : fallback
}

function formatApmSource(source: string): string {
  const normalized = formatOptionalText(source, 'datadog')
  if (normalized.toLowerCase() === 'otlp') return 'OTLP'
  if (normalized.toLowerCase() === 'sentry') return 'Sentry'
  if (normalized.toLowerCase() === 'datadog') return 'Datadog'
  return normalized
}

function formatSpanDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '0 ms'
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)} s`
  return `${ms.toFixed(ms >= 10 ? 0 : 1)} ms`
}

function normalizeClickHouseDateTime(value: string): string {
  const match = CLICKHOUSE_DATETIME_PATTERN.exec(value)
  if (!match) return value
  const millis = (match[3] ?? '').padEnd(3, '0').slice(0, 3)
  return `${match[1]}T${match[2]}.${millis}Z`
}

function formatSpanDebugDate(value: string, timezone: string): string {
  if (!value) return 'None'
  return formatDateTimeUtil(normalizeClickHouseDateTime(value), timezone)
}

function formatTracePreview(traceId: string): string {
  if (!traceId) return 'None'
  if (traceId.length <= TRACE_ID_PREVIEW_LENGTH) return traceId
  return `${traceId.slice(0, TRACE_ID_PREVIEW_LENGTH)}...`
}

function spanDebugRowKey(row: ApmSpanUsageDebugGroup): string {
  return [
    row.source,
    row.service,
    row.operation,
    row.resource,
    row.spanType,
    row.env,
    row.kind,
    row.scopeName,
    row.scopeVersion,
    row.projectId ?? 'org',
  ].join('|')
}

function boundedPercent(value: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.max(0, Math.min(FULL_PERCENT, value))
}

function formatScope(row: ApmSpanUsageDebugGroup): string {
  const scopeName = row.scopeName.trim()
  const scopeVersion = row.scopeVersion.trim()
  if (scopeName && scopeVersion) return `${scopeName}@${scopeVersion}`
  if (scopeName) return scopeName
  return formatOptionalText(row.kind, 'No scope')
}

interface ApmSpanUsageBreakdownProps {
  readonly debug?: ApmSpanUsageDebugResponse
  readonly isLoading: boolean
  readonly error?: unknown
  readonly expanded?: boolean
  readonly onExpandedChange?: (expanded: boolean) => void
  readonly retentionDays: number
  readonly timezone: string
}

export function ApmSpanUsageBreakdown({
  debug,
  isLoading,
  error,
  expanded,
  onExpandedChange,
  retentionDays,
  timezone,
}: ApmSpanUsageBreakdownProps) {
  const [internalExpanded, setInternalExpanded] = useState(false)
  const isExpanded = expanded ?? internalExpanded
  const setIsExpanded = onExpandedChange ?? setInternalExpanded
  const groups = debug?.groups ?? []

  return (
    <div className="mt-2 space-y-3">
      <div className="flex flex-wrap items-center justify-end gap-2">
        <span className="text-xs text-muted-foreground">
          Sources use retained {retentionDays}d traces.
        </span>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="h-7 gap-1 px-2 text-xs"
          aria-expanded={isExpanded}
          aria-controls="apm-span-source-breakdown"
          onClick={() => setIsExpanded(!isExpanded)}
        >
          <ChevronRight
            className={`h-3.5 w-3.5 transition-transform ${isExpanded ? 'rotate-90' : ''}`}
          />
          {isExpanded ? 'Hide sources' : 'Show sources'}
        </Button>
      </div>
      {isExpanded && (isLoading ? (
        <p className="text-sm text-muted-foreground">Loading APM span sources...</p>
      ) : error ? (
        <div
          id="apm-span-source-breakdown"
          className="rounded-md border border-destructive/40 p-4 text-center text-sm text-destructive"
        >
          Unable to load APM span sources.
        </div>
      ) : groups.length > 0 ? (
        <div id="apm-span-source-breakdown" className="overflow-x-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>Source</TableHead>
                <TableHead>Service</TableHead>
                <TableHead>Operation</TableHead>
                <TableHead>Resource</TableHead>
                <TableHead>Scope</TableHead>
                <TableHead className="text-right">Spans</TableHead>
                <TableHead className="text-right">Share</TableHead>
                <TableHead className="text-right">Traces</TableHead>
                <TableHead className="text-right">Errors</TableHead>
                <TableHead className="text-right">Latency</TableHead>
                <TableHead className="text-right">Latest</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {groups.map((row) => {
                const share = boundedPercent(row.percentage)
                const projectLabel = row.projectName ?? row.projectSlug

                return (
                  <TableRow key={spanDebugRowKey(row)}>
                    <TableCell>
                      <div className="space-y-1">
                        <Badge variant="outline" className="capitalize">
                          {formatApmSource(row.source)}
                        </Badge>
                        {projectLabel && (
                          <p className="max-w-[160px] truncate text-xs text-muted-foreground" title={projectLabel}>
                            {projectLabel}
                          </p>
                        )}
                      </div>
                    </TableCell>
                    <TableCell>
                      <div
                        className="max-w-[180px] truncate font-medium"
                        title={formatOptionalText(row.service, 'Unknown service')}
                      >
                        {formatOptionalText(row.service, 'Unknown service')}
                      </div>
                      <p className="text-xs text-muted-foreground">{formatOptionalText(row.env, 'No env')}</p>
                    </TableCell>
                    <TableCell>
                      <div
                        className="max-w-[220px] truncate font-medium"
                        title={formatOptionalText(row.operation, 'Unknown operation')}
                      >
                        {formatOptionalText(row.operation, 'Unknown operation')}
                      </div>
                      <p className="text-xs text-muted-foreground">
                        {formatOptionalText(row.spanType, formatOptionalText(row.kind, 'No type'))}
                      </p>
                    </TableCell>
                    <TableCell>
                      <div
                        className="max-w-[260px] truncate font-mono text-xs"
                        title={formatOptionalText(row.resource)}
                      >
                        {formatOptionalText(row.resource)}
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="max-w-[220px] truncate text-xs" title={formatScope(row)}>
                        {formatScope(row)}
                      </div>
                      <p className="text-xs text-muted-foreground" title={row.sampleTraceId}>
                        {formatTracePreview(row.sampleTraceId)}
                      </p>
                    </TableCell>
                    <TableCell className="text-right font-semibold tabular-nums">
                      {row.spanCount.toLocaleString()}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex items-center justify-end gap-2">
                        <div className="h-1.5 w-16 rounded-full bg-secondary overflow-hidden">
                          <div
                            className="h-full rounded-full bg-purple-500"
                            style={{width: `${share}%`}}
                          />
                        </div>
                        <span className="min-w-[3rem] text-xs tabular-nums">{share.toFixed(1)}%</span>
                      </div>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{row.traceCount.toLocaleString()}</TableCell>
                    <TableCell className="text-right tabular-nums">{row.errorCount.toLocaleString()}</TableCell>
                    <TableCell className="text-right text-xs tabular-nums">
                      <div>{formatSpanDuration(row.avgDurationMs)} avg</div>
                      <div className="text-muted-foreground">{formatSpanDuration(row.maxDurationMs)} max</div>
                    </TableCell>
                    <TableCell className="text-right text-xs text-muted-foreground whitespace-nowrap">
                      {formatSpanDebugDate(row.latestSpanAt, timezone)}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>
      ) : (
        <div
          id="apm-span-source-breakdown"
          className="rounded-md border border-dashed p-4 text-center text-sm text-muted-foreground"
        >
          No APM spans stored for the retained {retentionDays}-day window.
        </div>
      ))}
    </div>
  )
}
