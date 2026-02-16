// Moneat - Mobile-First Error Monitoring Platform
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

import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useMemo, useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {cn} from '@/lib/utils'
import {useToast} from '@/hooks/use-toast'
import {Badge} from '@/components/ui/badge'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Checkbox} from '@/components/ui/checkbox'
import {Label} from '@/components/ui/label'
import {SpanWaterfall} from '@/components/span-waterfall'
import {
  AlertCircle,
  AlertTriangle,
  ArrowLeft,
  Calendar,
  CheckCircle2,
  ChevronRight,
  Clock3,
  Copy,
  DatabaseZap,
  ExternalLink,
  Globe,
  Hash,
  Layers,
  Network,
  Package,
  Tag,
  XCircle,
} from 'lucide-react'

export const Route = createFileRoute('/performance/$transactionId')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
  },
  component: TransactionDetailPage,
})

/* ── Helpers ───────────────────────────────────────────────── */

function statusBadgeVariant(status?: string) {
  const normalized = (status || '').toLowerCase()
  if (normalized === 'ok') return 'default'
  if (!normalized) return 'secondary'
  return 'destructive'
}

function statusIcon(status?: string) {
  const normalized = (status || '').toLowerCase()
  if (normalized === 'ok') return CheckCircle2
  if (!normalized) return AlertCircle
  return XCircle
}

function statusColor(status?: string) {
  const normalized = (status || '').toLowerCase()
  if (normalized === 'ok') return 'text-emerald-500'
  if (!normalized) return 'text-muted-foreground'
  return 'text-rose-500'
}

/** Color code duration by severity */
function durationColor(ms: number) {
  if (ms < 300) return 'text-emerald-600 dark:text-emerald-400'
  if (ms < 1000) return 'text-amber-600 dark:text-amber-400'
  return 'text-rose-600 dark:text-rose-400'
}

function durationBgColor(ms: number) {
  if (ms < 300) return 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'
  if (ms < 1000) return 'bg-amber-500/15 text-amber-600 dark:text-amber-400'
  return 'bg-rose-500/15 text-rose-600 dark:text-rose-400'
}

function durationLabel(ms: number) {
  if (ms < 300) return 'Fast'
  if (ms < 1000) return 'Moderate'
  return 'Slow'
}

function formatDuration(ms: number) {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${ms.toFixed(0)}ms`
}

function formatRelativeTime(timestamp: string) {
  const now = Date.now()
  const then = new Date(timestamp).getTime()
  const diffMs = now - then
  const diffSec = Math.floor(diffMs / 1000)
  const diffMin = Math.floor(diffSec / 60)
  const diffHour = Math.floor(diffMin / 60)
  const diffDay = Math.floor(diffHour / 24)
  if (diffDay > 0) return `${diffDay}d ago`
  if (diffHour > 0) return `${diffHour}h ago`
  if (diffMin > 0) return `${diffMin}m ago`
  return 'just now'
}

/** Color for breadcrumb categories */
const BREADCRUMB_CATEGORY_COLORS: Record<string, string> = {
  http: 'bg-blue-500/15 text-blue-700 dark:text-blue-300 border-blue-500/25',
  fetch: 'bg-blue-500/15 text-blue-700 dark:text-blue-300 border-blue-500/25',
  xhr: 'bg-blue-500/15 text-blue-700 dark:text-blue-300 border-blue-500/25',
  navigation: 'bg-cyan-500/15 text-cyan-700 dark:text-cyan-300 border-cyan-500/25',
  ui: 'bg-pink-500/15 text-pink-700 dark:text-pink-300 border-pink-500/25',
  'ui.click': 'bg-pink-500/15 text-pink-700 dark:text-pink-300 border-pink-500/25',
  console: 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/25',
  debug: 'bg-slate-500/15 text-slate-700 dark:text-slate-300 border-slate-500/25',
  error: 'bg-rose-500/15 text-rose-700 dark:text-rose-300 border-rose-500/25',
  warning: 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/25',
  info: 'bg-sky-500/15 text-sky-700 dark:text-sky-300 border-sky-500/25',
  query: 'bg-violet-500/15 text-violet-700 dark:text-violet-300 border-violet-500/25',
  transaction: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/25',
  sentry: 'bg-violet-500/15 text-violet-700 dark:text-violet-300 border-violet-500/25',
}

function getBreadcrumbCategoryColor(category: string) {
  const lower = category.toLowerCase()
  return BREADCRUMB_CATEGORY_COLORS[lower] || 'bg-muted text-muted-foreground border-border'
}

/** Color for operation badge */
const OP_COLORS: Record<string, string> = {
  'http.server': 'bg-blue-500/15 text-blue-700 dark:text-blue-300 border-blue-500/20',
  'http.client': 'bg-sky-500/15 text-sky-700 dark:text-sky-300 border-sky-500/20',
  'db': 'bg-violet-500/15 text-violet-700 dark:text-violet-300 border-violet-500/20',
  'db.query': 'bg-violet-500/15 text-violet-700 dark:text-violet-300 border-violet-500/20',
  'db.sql.query': 'bg-violet-500/15 text-violet-700 dark:text-violet-300 border-violet-500/20',
  'navigation': 'bg-cyan-500/15 text-cyan-700 dark:text-cyan-300 border-cyan-500/20',
  'pageload': 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20',
  'task': 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/20',
  'queue.task': 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/20',
  'function': 'bg-pink-500/15 text-pink-700 dark:text-pink-300 border-pink-500/20',
}

function getOpColor(op: string) {
  return OP_COLORS[op] || 'bg-muted text-muted-foreground border-border'
}

/* ── Sub-components ───────────────────────────────────────── */

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation()
        navigator.clipboard.writeText(text)
        setCopied(true)
        setTimeout(() => setCopied(false), 1500)
      }}
      className="ml-1 inline-flex items-center rounded p-0.5 text-muted-foreground/60 transition-colors hover:bg-muted hover:text-foreground"
      title="Copy to clipboard"
    >
      {copied ? (
        <CheckCircle2 className="h-3 w-3 text-emerald-500" />
      ) : (
        <Copy className="h-3 w-3" />
      )}
    </button>
  )
}

function formatBreadcrumbTimestamp(raw: unknown): string {
  if (typeof raw === 'number') {
    const millis = raw < 10_000_000_000 ? raw * 1000 : raw
    return new Date(millis).toLocaleTimeString()
  }
  if (typeof raw === 'string') {
    const millis = Date.parse(raw)
    if (!Number.isNaN(millis)) return new Date(millis).toLocaleTimeString()
  }
  return '--:--:--'
}

function formatBreadcrumbMessage(raw: unknown): string {
  if (typeof raw === 'string') return raw
  if (raw && typeof raw === 'object') {
    const obj = raw as Record<string, unknown>
    if (typeof obj.formatted === 'string') return obj.formatted
    if (typeof obj.message === 'string') return obj.message
    if (typeof obj.text === 'string') return obj.text
    return JSON.stringify(obj)
  }
  return ''
}

function BreadcrumbsTimeline({ breadcrumbs }: { breadcrumbs?: string }) {
  const items = useMemo(() => {
    if (!breadcrumbs) return []
    try {
      const parsed = JSON.parse(breadcrumbs) as unknown
      if (Array.isArray(parsed)) return parsed
      if (typeof parsed === 'object' && parsed !== null && 'values' in parsed) {
        const values = (parsed as { values?: unknown }).values
        return Array.isArray(values) ? values : []
      }
      return []
    } catch {
      return []
    }
  }, [breadcrumbs])

  if (!breadcrumbs) {
    return <p className="text-sm text-muted-foreground">No breadcrumbs recorded for this transaction.</p>
  }

  if (items.length === 0) {
    return <pre className="max-h-72 overflow-auto rounded bg-muted p-3 text-xs">{breadcrumbs}</pre>
  }

  return (
    <div className="max-h-[630px] space-y-1.5 overflow-y-auto pr-1">
      {items.map((item, index) => {
        const crumb = (item || {}) as Record<string, unknown>
        const category = String(crumb.category || crumb.type || 'event')
        const message = formatBreadcrumbMessage(crumb.message)
        const timestamp = formatBreadcrumbTimestamp(crumb.timestamp)
        const level = String(crumb.level || '')

        return (
          <div
            key={index}
            className={cn(
              'rounded-lg border px-3 py-2 transition-colors hover:bg-muted/30',
              level === 'error' && 'border-rose-500/30 bg-rose-500/5',
              level === 'warning' && 'border-amber-500/30 bg-amber-500/5',
            )}
          >
            <div className="mb-1 flex items-center gap-2">
              <Badge
                variant="outline"
                className={cn('text-[10px] px-1.5 py-0 font-normal', getBreadcrumbCategoryColor(category))}
              >
                {category}
              </Badge>
              <span className="ml-auto font-mono text-[10px] text-muted-foreground">{timestamp}</span>
            </div>
            <div className="text-xs text-foreground/90 break-words">{message || 'No message'}</div>
          </div>
        )
      })}
    </div>
  )
}

function formatContextValue(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (typeof value === 'number') return String(value)
  if (typeof value === 'string') return value
  return JSON.stringify(value)
}

function ContextSection({ name, data, depth = 0, defaultOpen = true }: { name: string; data: unknown; depth?: number; defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen)
  const isNested = depth > 0

  const entries = typeof data === 'object' && data !== null
    ? Object.entries(data as Record<string, unknown>)
    : [['value', data]]

  return (
    <div
      className={cn(
        'rounded-lg border border-border bg-card shadow-sm',
        isNested && 'ml-4 mt-2 border-l-2 border-l-primary/20 first:mt-0 mb-3'
      )}
    >
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="flex w-full items-center justify-between gap-2 rounded-t-lg px-3 py-2 text-left text-sm font-medium transition-colors hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
      >
        <span className="capitalize">{name.replace(/_/g, ' ')}</span>
        <div className="flex items-center gap-1.5">
          <span className="text-xs text-muted-foreground">{entries.length} {entries.length === 1 ? 'field' : 'fields'}</span>
          <ChevronRight className={cn('h-4 w-4 text-muted-foreground transition-transform duration-200', open && 'rotate-90')} />
        </div>
      </button>
      {open && (
        <div className="rounded-b-lg border-t border-b border-border bg-muted/10 overflow-hidden">
          <div className="divide-y pb-2">
            {entries.map(([key, value]) => {
              const isNestedObject = typeof value === 'object' && value !== null && !Array.isArray(value)
              const isArray = Array.isArray(value)

              if (isNestedObject) {
                return (
                  <ContextSection key={String(key)} name={String(key)} data={value as Record<string, unknown>} depth={depth + 1} defaultOpen={defaultOpen} />
                )
              }

              return (
                <div key={String(key)} className="flex items-start justify-between gap-4 px-3 py-1.5">
                  <span className="shrink-0 text-xs text-muted-foreground">{String(key)}</span>
                  <span className="text-right font-mono text-xs break-all">
                    {isArray
                      ? (value as unknown[]).map(formatContextValue).join(', ')
                      : formatContextValue(value)}
                  </span>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}

function RequestInfoCard({ request }: { request?: string }) {
  const parsed = useMemo(() => {
    if (!request) return null
    try {
      return JSON.parse(request) as Record<string, unknown>
    } catch {
      return null
    }
  }, [request])

  if (!parsed) return null

  const method = String(parsed.method || '').toUpperCase()
  const url = String(parsed.url || parsed.path || '')
  const headers = parsed.headers as Record<string, string> | undefined

  if (!method && !url) return null

  const methodColor: Record<string, string> = {
    GET: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300',
    POST: 'bg-blue-500/15 text-blue-700 dark:text-blue-300',
    PUT: 'bg-amber-500/15 text-amber-700 dark:text-amber-300',
    PATCH: 'bg-amber-500/15 text-amber-700 dark:text-amber-300',
    DELETE: 'bg-rose-500/15 text-rose-700 dark:text-rose-300',
  }

  return (
    <div className="rounded-lg border bg-card p-3">
      <div className="mb-2 flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <ExternalLink className="h-3 w-3" />
        Request
      </div>
      <div className="flex items-center gap-2">
        {method && (
          <span className={cn('rounded px-1.5 py-0.5 text-[11px] font-bold', methodColor[method] || 'bg-muted text-muted-foreground')}>
            {method}
          </span>
        )}
        {url && (
          <span className="truncate font-mono text-xs text-foreground/80">{url}</span>
        )}
      </div>
      {headers && Object.keys(headers).length > 0 && (
        <div className="mt-2 space-y-0.5">
          {Object.entries(headers).slice(0, 5).map(([key, value]) => (
            <div key={key} className="flex items-center gap-2 text-[11px]">
              <span className="text-muted-foreground shrink-0">{key}:</span>
              <span className="truncate font-mono text-foreground/70">{value}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/* ── Main Page ────────────────────────────────────────────── */

function TransactionDetailPage() {
  const { transactionId } = Route.useParams()
  const [expandByDefault, setExpandByDefault] = useState(true)
  const { toast } = useToast()

  const { data, isLoading } = useQuery({
    queryKey: ['transaction-spans', transactionId],
    queryFn: () => api.getTransactionSpans(transactionId),
  })

  const { data: relatedErrors = [] } = useQuery({
    queryKey: ['related-errors', transactionId],
    queryFn: () => api.getRelatedErrors(transactionId),
  })

  if (isLoading) return <div className="p-8 text-muted-foreground">Loading transaction...</div>
  if (!data) return <div className="p-8 text-muted-foreground">Transaction not found</div>

  const { transaction, spans } = data
  const contextEntries = (() => {
    try {
      const parsed = JSON.parse(transaction.contexts || '{}') as Record<string, unknown>
      return Object.entries(parsed)
    } catch {
      return [] as [string, unknown][]
    }
  })()

  const StatusIcon = statusIcon(transaction.status)
  const spanCount = spans.length

  return (
    <div>
      <div className="mx-auto max-w-[1600px] px-4 py-4 sm:px-6 lg:px-8">
        {/* ── Compact Header ──────────────────────────────── */}
        <div className="mb-4">
          {/* Breadcrumb nav */}
          <Link
            to="/performance"
            className="mb-2 inline-flex items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
          >
            <ArrowLeft className="h-3 w-3" />
            Back to Performance
          </Link>

          {/* Title row */}
          <div className="flex flex-wrap items-start gap-x-3 gap-y-1">
            <h1 className="text-lg font-semibold leading-tight sm:text-xl">{transaction.name || '(unnamed transaction)'}</h1>
            <div className="flex items-center gap-1.5 py-0.5">
              {transaction.op && (
                <Badge variant="outline" className={cn('text-[11px] px-1.5 py-0 font-normal', getOpColor(transaction.op))}>
                  {transaction.op}
                </Badge>
              )}
              <Badge variant={statusBadgeVariant(transaction.status)} className="gap-1 text-[11px] px-1.5 py-0">
                <StatusIcon className={cn('h-3 w-3', statusColor(transaction.status))} />
                {transaction.status || 'unknown'}
              </Badge>
            </div>
          </div>
        </div>

        {/* ── Stats Strip ─────────────────────────────────── */}
        <div className="mb-4 grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
          {/* Duration */}
          <div className={cn('rounded-lg border p-2.5 flex items-center gap-2.5', durationBgColor(transaction.duration).includes('rose') ? 'border-rose-500/20' : durationBgColor(transaction.duration).includes('amber') ? 'border-amber-500/20' : 'border-emerald-500/20')}>
            <div className={cn('h-8 w-8 shrink-0 rounded-lg flex items-center justify-center', durationBgColor(transaction.duration))}>
              <Clock3 className="h-4 w-4" />
            </div>
            <div className="min-w-0">
              <div className="text-[10px] font-medium text-muted-foreground">Duration</div>
              <div className={cn('text-sm font-bold leading-tight tabular-nums', durationColor(transaction.duration))}>{formatDuration(transaction.duration)}</div>
              <div className="text-[10px] text-muted-foreground">{durationLabel(transaction.duration)}</div>
            </div>
          </div>

          {/* Timestamp */}
          <div className="rounded-lg border border-sky-500/20 p-2.5 flex items-center gap-2.5">
            <div className="h-8 w-8 shrink-0 rounded-lg flex items-center justify-center bg-sky-500/15 text-sky-600 dark:text-sky-400">
              <Calendar className="h-4 w-4" />
            </div>
            <div className="min-w-0">
              <div className="text-[10px] font-medium text-muted-foreground">Timestamp</div>
              <div className="text-sm font-bold leading-tight">{new Date(transaction.timestamp).toLocaleTimeString()}</div>
              <div className="text-[10px] text-muted-foreground">{formatRelativeTime(transaction.timestamp)}</div>
            </div>
          </div>

          {/* Environment */}
          <div className="rounded-lg border border-cyan-500/20 p-2.5 flex items-center gap-2.5">
            <div className="h-8 w-8 shrink-0 rounded-lg flex items-center justify-center bg-cyan-500/15 text-cyan-600 dark:text-cyan-400">
              <Globe className="h-4 w-4" />
            </div>
            <div className="min-w-0">
              <div className="text-[10px] font-medium text-muted-foreground">Environment</div>
              <div className="text-sm font-bold leading-tight truncate">{transaction.environment || 'unknown'}</div>
            </div>
          </div>

          {/* Release */}
          <div className="rounded-lg border border-violet-500/20 p-2.5 flex items-center gap-2.5">
            <div className="h-8 w-8 shrink-0 rounded-lg flex items-center justify-center bg-violet-500/15 text-violet-600 dark:text-violet-400">
              <Package className="h-4 w-4" />
            </div>
            <div className="min-w-0">
              <div className="text-[10px] font-medium text-muted-foreground">Release</div>
              <div className="text-sm font-bold leading-tight truncate">{transaction.release || 'n/a'}</div>
            </div>
          </div>

          {/* Span Count */}
          <div className="rounded-lg border border-blue-500/20 p-2.5 flex items-center gap-2.5">
            <div className="h-8 w-8 shrink-0 rounded-lg flex items-center justify-center bg-blue-500/15 text-blue-600 dark:text-blue-400">
              <Network className="h-4 w-4" />
            </div>
            <div className="min-w-0">
              <div className="text-[10px] font-medium text-muted-foreground">Spans</div>
              <div className="text-sm font-bold leading-tight">{spanCount}</div>
              <div className="text-[10px] text-muted-foreground">{spanCount === 1 ? 'span' : 'spans'} recorded</div>
            </div>
          </div>

          {/* Related Errors */}
          <div className={cn(
            'rounded-lg border p-2.5 flex items-center gap-2.5',
            relatedErrors.length > 0 ? 'border-rose-500/20' : 'border-emerald-500/20',
          )}>
            <div className={cn(
              'h-8 w-8 shrink-0 rounded-lg flex items-center justify-center',
              relatedErrors.length > 0 ? 'bg-rose-500/15 text-rose-600 dark:text-rose-400' : 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400',
            )}>
              <AlertTriangle className="h-4 w-4" />
            </div>
            <div className="min-w-0">
              <div className="text-[10px] font-medium text-muted-foreground">Errors</div>
              <div className={cn('text-sm font-bold leading-tight', relatedErrors.length > 0 ? 'text-rose-600 dark:text-rose-400' : 'text-emerald-600 dark:text-emerald-400')}>
                {relatedErrors.length}
              </div>
              <div className="text-[10px] text-muted-foreground">{relatedErrors.length === 0 ? 'No errors' : 'related'}</div>
            </div>
          </div>
        </div>

        {/* ── Trace ID + Request Info Row ─────────────────── */}
        <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2">
          <div className="flex items-center gap-1.5 rounded-md bg-muted/50 px-2.5 py-1.5 text-xs">
            <Hash className="h-3 w-3 text-muted-foreground" />
            <span className="text-muted-foreground">Trace:</span>
            <span className="font-mono text-foreground/80 truncate max-w-[200px] sm:max-w-none">{transaction.traceId || 'n/a'}</span>
            {transaction.traceId && <CopyButton text={transaction.traceId} />}
          </div>
          <div className="flex items-center gap-1.5 rounded-md bg-muted/50 px-2.5 py-1.5 text-xs">
            <Hash className="h-3 w-3 text-muted-foreground" />
            <span className="text-muted-foreground">Event:</span>
            <span className="font-mono text-foreground/80 truncate max-w-[200px] sm:max-w-none">{transaction.eventId}</span>
            <CopyButton text={transaction.eventId} />
          </div>
        </div>

        {/* ── Request Info (if available) ──────────────────── */}
        {transaction.request && (
          <div className="mb-4">
            <RequestInfoCard request={transaction.request} />
          </div>
        )}

        {/* ── Span Waterfall ──────────────────────────────── */}
        <Card className="mb-4">
          <CardHeader className="px-4 py-3">
            <CardTitle className="flex items-center gap-2 text-sm">
              <DatabaseZap className="h-4 w-4 text-blue-500" />
              Span Waterfall
              <Badge variant="secondary" className="ml-1 text-[10px] px-1.5 py-0">{spanCount} spans</Badge>
            </CardTitle>
          </CardHeader>
          <CardContent className="px-4 pb-4 pt-0">
            <SpanWaterfall transaction={transaction} spans={spans} />
          </CardContent>
        </Card>

        {/* ── Bottom Sections: 3-col on desktop ───────────── */}
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
          {/* Tags & Context */}
          <Card className="lg:col-span-1 flex min-h-0 flex-col">
            <CardHeader className="px-4 py-3">
              <CardTitle className="flex items-center gap-2 text-sm">
                <Tag className="h-4 w-4 text-violet-500" />
                Tags & Context
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4 px-4 pb-4 pt-0">
              {/* Tags */}
              <div>
                <div className="mb-2 flex items-center justify-between">
                  <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
                    <Tag className="h-3 w-3" />
                    Tags
                    {Object.keys(transaction.tags).length > 0 && (
                      <Badge variant="secondary" className="ml-1 text-[10px] px-1 py-0">{Object.keys(transaction.tags).length}</Badge>
                    )}
                  </div>
                  {Object.keys(transaction.tags).length > 0 && (
                    <button
                      type="button"
                      onClick={() => {
                        navigator.clipboard.writeText(JSON.stringify(transaction.tags, null, 2))
                        toast({ title: 'Copied', description: 'Tags copied to clipboard.' })
                      }}
                      className="inline-flex items-center gap-1 rounded p-1 text-muted-foreground/60 hover:bg-muted hover:text-foreground transition-colors"
                    >
                      <Copy className="h-3 w-3" />
                      <span className="text-[10px]">Copy</span>
                    </button>
                  )}
                </div>
                {Object.keys(transaction.tags).length === 0 ? (
                  <p className="text-xs text-muted-foreground">No tags</p>
                ) : (
                  <div className="flex flex-wrap gap-1.5">
                    {Object.entries(transaction.tags).map(([key, value]) => (
                      <div
                        key={key}
                        className="inline-flex items-center gap-1 rounded-md border bg-muted/30 px-2 py-0.5 text-[11px]"
                      >
                        <span className="text-muted-foreground">{key}:</span>
                        <span className="font-mono font-medium">{value}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Contexts */}
              <div>
                <div className="mb-2 flex items-center justify-between">
                  <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
                    <Layers className="h-3 w-3" />
                    Contexts
                    {contextEntries.length > 0 && (
                      <Badge variant="secondary" className="ml-1 text-[10px] px-1 py-0">{contextEntries.length}</Badge>
                    )}
                  </div>
                  <div className="flex items-center gap-2">
                    {contextEntries.length > 0 && (
                      <button
                        type="button"
                        onClick={() => {
                          const contextsObj = Object.fromEntries(contextEntries)
                          navigator.clipboard.writeText(JSON.stringify(contextsObj, null, 2))
                          toast({ title: 'Copied', description: 'Contexts copied to clipboard.' })
                        }}
                        className="inline-flex items-center gap-1 rounded p-1 text-muted-foreground/60 hover:bg-muted hover:text-foreground transition-colors"
                      >
                        <Copy className="h-3 w-3" />
                        <span className="text-[10px]">Copy</span>
                      </button>
                    )}
                    {contextEntries.length > 0 && (
                      <label className="flex items-center gap-1.5 cursor-pointer">
                        <Checkbox
                          checked={expandByDefault}
                          onCheckedChange={(checked) => setExpandByDefault(checked === true)}
                          className="h-3.5 w-3.5"
                        />
                        <Label className="cursor-pointer text-[11px] font-normal text-muted-foreground">
                          Expand
                        </Label>
                      </label>
                    )}
                  </div>
                </div>
                {contextEntries.length === 0 ? (
                  <p className="text-xs text-muted-foreground">No context entries</p>
                ) : (
                  <div key={String(expandByDefault)} className="space-y-2 max-h-[500px] overflow-y-auto pr-1">
                    {contextEntries.map(([key, value]) => (
                      <ContextSection key={key} name={key} data={value} defaultOpen={expandByDefault} />
                    ))}
                  </div>
                )}
              </div>
            </CardContent>
          </Card>

          {/* Breadcrumbs */}
          <Card className="lg:col-span-1">
            <CardHeader className="px-4 py-3">
              <CardTitle className="flex items-center gap-2 text-sm">
                <Layers className="h-4 w-4 text-amber-500" />
                Breadcrumbs
              </CardTitle>
            </CardHeader>
            <CardContent className="px-4 pb-4 pt-0">
              <BreadcrumbsTimeline breadcrumbs={transaction.breadcrumbs} />
            </CardContent>
          </Card>

          {/* Related Errors */}
          <Card className={cn('lg:col-span-1', relatedErrors.length > 0 && 'border-rose-500/20')}>
            <CardHeader className="px-4 py-3">
              <CardTitle className="flex items-center gap-2 text-sm">
                <AlertTriangle className={cn('h-4 w-4', relatedErrors.length > 0 ? 'text-rose-500' : 'text-muted-foreground')} />
                Related Errors
                {relatedErrors.length > 0 && (
                  <Badge variant="destructive" className="ml-1 text-[10px] px-1.5 py-0">{relatedErrors.length}</Badge>
                )}
              </CardTitle>
            </CardHeader>
            <CardContent className="px-4 pb-4 pt-0">
              {relatedErrors.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-6 text-center">
                  <div className="mb-2 rounded-full bg-emerald-500/10 p-2.5">
                    <CheckCircle2 className="h-5 w-5 text-emerald-500" />
                  </div>
                  <p className="text-sm font-medium text-foreground/80">No errors</p>
                  <p className="text-xs text-muted-foreground">This transaction completed without any related errors.</p>
                </div>
              ) : (
                <div className="space-y-2 max-h-[500px] overflow-y-auto pr-1">
                  {relatedErrors.map((error) => (
                    <div key={error.eventId} className="rounded-lg border border-rose-500/20 bg-rose-500/5 p-3 transition-colors hover:bg-rose-500/10">
                      <div className="mb-1.5 flex items-center justify-between gap-2">
                        <Badge variant="destructive" className="text-[10px] px-1.5 py-0 uppercase">{error.level}</Badge>
                        <span className="text-[10px] text-muted-foreground">{new Date(error.timestamp).toLocaleString()}</span>
                      </div>
                      <div className="text-xs font-medium text-foreground/90">{error.message || 'No message'}</div>
                      <div className="mt-1.5 flex items-center gap-1 font-mono text-[10px] text-muted-foreground">
                        <span className="truncate">{error.eventId}</span>
                        <CopyButton text={error.eventId} />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  )
}
