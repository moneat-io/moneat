import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useMemo, useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {cn} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Checkbox} from '@/components/ui/checkbox'
import {Label} from '@/components/ui/label'
import {SpanWaterfall} from '@/components/span-waterfall'
import {ChevronLeft, ChevronRight, Clock3, DatabaseZap, Globe, Layers, Tag} from 'lucide-react'

export const Route = createFileRoute('/performance/$transactionId')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
  },
  component: TransactionDetailPage,
})

function statusBadgeVariant(status?: string) {
  const normalized = (status || '').toLowerCase()
  if (normalized === 'ok') return 'default'
  if (!normalized) return 'secondary'
  return 'destructive'
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
    <div className="space-y-2">
      {items.map((item, index) => {
        const crumb = (item || {}) as Record<string, unknown>
        const category = String(crumb.category || crumb.type || 'event')
        const message = formatBreadcrumbMessage(crumb.message)
        const timestamp = formatBreadcrumbTimestamp(crumb.timestamp)

        return (
          <div key={index} className="rounded border px-3 py-2">
            <div className="mb-1 flex items-center justify-between">
              <span className="text-xs font-medium text-muted-foreground">{category}</span>
              <span className="font-mono text-xs text-muted-foreground">{timestamp}</span>
            </div>
            <div className="text-sm">{message || 'No message'}</div>
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
        className="flex w-full items-center justify-between gap-2 rounded-t-lg px-3 py-2.5 text-left text-sm font-medium transition-colors hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
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
                <div key={String(key)} className="flex items-start justify-between gap-4 px-3 py-2">
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

function TransactionDetailPage() {
  const { transactionId } = Route.useParams()
  const [expandByDefault, setExpandByDefault] = useState(true)

  const { data, isLoading } = useQuery({
    queryKey: ['transaction-spans', transactionId],
    queryFn: () => api.getTransactionSpans(transactionId),
  })

  const { data: relatedErrors = [] } = useQuery({
    queryKey: ['related-errors', transactionId],
    queryFn: () => api.getRelatedErrors(transactionId),
  })

  if (isLoading) return <div className="p-8">Loading transaction...</div>
  if (!data) return <div className="p-8">Transaction not found</div>

  const { transaction, spans } = data
  const contextEntries = (() => {
    try {
      const parsed = JSON.parse(transaction.contexts || '{}') as Record<string, unknown>
      return Object.entries(parsed)
    } catch {
      return [] as [string, unknown][]
    }
  })()

  return (
    <div className="min-h-screen bg-background">
      <div className="mx-auto max-w-7xl p-6">
        <nav className="mb-4 flex items-center gap-2 text-sm">
          <Link to="/performance" className="inline-flex items-center gap-1 text-muted-foreground hover:text-foreground">
            <ChevronLeft className="h-4 w-4" />
            Performance
          </Link>
          <span className="text-muted-foreground">/</span>
          <span className="truncate font-medium">{transaction.name || 'Transaction'}</span>
        </nav>

        <Card className="mb-6">
          <CardHeader>
            <CardTitle className="flex flex-wrap items-center gap-2 text-xl">
              <span>{transaction.name || '(unnamed transaction)'}</span>
              {transaction.op && <Badge variant="secondary">{transaction.op}</Badge>}
              <Badge variant={statusBadgeVariant(transaction.status)}>{transaction.status || 'unknown'}</Badge>
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-5">
              <div className="rounded border bg-muted/20 p-3">
                <div className="mb-1 text-xs text-muted-foreground">Duration</div>
                <div className="flex items-center gap-2 font-semibold">
                  <Clock3 className="h-4 w-4 text-muted-foreground" />
                  {transaction.duration.toFixed(2)}ms
                </div>
              </div>
              <div className="rounded border bg-muted/20 p-3">
                <div className="mb-1 text-xs text-muted-foreground">Timestamp</div>
                <div className="font-semibold">{new Date(transaction.timestamp).toLocaleString()}</div>
              </div>
              <div className="rounded border bg-muted/20 p-3">
                <div className="mb-1 text-xs text-muted-foreground">Environment</div>
                <div className="flex items-center gap-2 font-semibold">
                  <Globe className="h-4 w-4 text-muted-foreground" />
                  {transaction.environment || 'unknown'}
                </div>
              </div>
              <div className="rounded border bg-muted/20 p-3">
                <div className="mb-1 text-xs text-muted-foreground">Release</div>
                <div className="font-semibold">{transaction.release || 'n/a'}</div>
              </div>
              <div className="rounded border bg-muted/20 p-3">
                <div className="mb-1 text-xs text-muted-foreground">Trace ID</div>
                <div className="truncate font-mono text-xs">{transaction.traceId || 'n/a'}</div>
              </div>
            </div>
          </CardContent>
        </Card>

        <Card className="mb-6">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <DatabaseZap className="h-5 w-5" />
              Span Waterfall
            </CardTitle>
          </CardHeader>
          <CardContent>
            <SpanWaterfall transaction={transaction} spans={spans} />
          </CardContent>
        </Card>

        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle>Tags & Context</CardTitle>
            </CardHeader>
            <CardContent className="space-y-5 pb-6">
              <div>
                <div className="mb-2.5 flex items-center gap-1.5 text-sm font-medium">
                  <Tag className="h-3.5 w-3.5 text-muted-foreground" />
                  Tags
                </div>
                {Object.keys(transaction.tags).length === 0 ? (
                  <p className="text-sm text-muted-foreground">No tags</p>
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {Object.entries(transaction.tags).map(([key, value]) => (
                      <div
                        key={key}
                        className="inline-flex items-center gap-1.5 rounded-md border bg-muted/30 px-2.5 py-1 text-xs"
                      >
                        <span className="text-muted-foreground">{key}:</span>
                        <span className="font-mono font-medium">{value}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
              <div>
                <div className="mb-2.5 flex items-center justify-between">
                  <div className="flex items-center gap-1.5 text-sm font-medium">
                    <Layers className="h-3.5 w-3.5 text-muted-foreground" />
                    Contexts
                  </div>
                  {contextEntries.length > 0 && (
                    <label className="flex items-center gap-2 cursor-pointer">
                      <Checkbox
                        checked={expandByDefault}
                        onCheckedChange={(checked) => setExpandByDefault(checked === true)}
                      />
                      <Label className="cursor-pointer text-sm font-normal text-muted-foreground">
                        Expand All
                      </Label>
                    </label>
                  )}
                </div>
                {contextEntries.length === 0 ? (
                  <p className="text-sm text-muted-foreground">No context entries</p>
                ) : (
                  <div key={String(expandByDefault)} className="space-y-4">
                    {contextEntries.map(([key, value]) => (
                      <ContextSection key={key} name={key} data={value} defaultOpen={expandByDefault} />
                    ))}
                  </div>
                )}
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Breadcrumbs</CardTitle>
            </CardHeader>
            <CardContent>
              <BreadcrumbsTimeline breadcrumbs={transaction.breadcrumbs} />
            </CardContent>
          </Card>
        </div>

        <Card className="mt-6">
          <CardHeader>
            <CardTitle>Related Errors ({relatedErrors.length})</CardTitle>
          </CardHeader>
          <CardContent>
            {relatedErrors.length === 0 ? (
              <p className="text-sm text-muted-foreground">No related errors were found for this trace.</p>
            ) : (
              <div className="space-y-2">
                {relatedErrors.map((error) => (
                  <div key={error.eventId} className="rounded border p-3">
                    <div className="mb-1 flex items-center justify-between gap-2">
                      <Badge variant="destructive" className="uppercase">{error.level}</Badge>
                      <span className="text-xs text-muted-foreground">{new Date(error.timestamp).toLocaleString()}</span>
                    </div>
                    <div className="text-sm">{error.message || 'No message'}</div>
                    <div className="mt-1 font-mono text-xs text-muted-foreground">{error.eventId}</div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
