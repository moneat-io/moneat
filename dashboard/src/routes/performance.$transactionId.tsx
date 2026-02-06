import { createFileRoute, redirect, Link } from '@tanstack/react-router'
import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { SpanWaterfall } from '@/components/span-waterfall'
import { ChevronLeft, Clock3, DatabaseZap, Globe } from 'lucide-react'

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
        const message = String(crumb.message || '')
        const timestamp = typeof crumb.timestamp === 'number'
          ? new Date((crumb.timestamp < 10_000_000_000 ? crumb.timestamp * 1000 : crumb.timestamp)).toLocaleTimeString()
          : '--:--:--'

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

function TransactionDetailPage() {
  const { transactionId } = Route.useParams()

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
            <CardContent className="space-y-4">
              <div>
                <div className="mb-2 text-sm font-medium">Tags</div>
                {Object.keys(transaction.tags).length === 0 ? (
                  <p className="text-sm text-muted-foreground">No tags</p>
                ) : (
                  <div className="space-y-1">
                    {Object.entries(transaction.tags).map(([key, value]) => (
                      <div key={key} className="flex justify-between text-sm">
                        <span className="text-muted-foreground">{key}</span>
                        <span className="font-mono">{value}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
              <div>
                <div className="mb-2 text-sm font-medium">Contexts</div>
                {contextEntries.length === 0 ? (
                  <p className="text-sm text-muted-foreground">No context entries</p>
                ) : (
                  <div className="max-h-64 space-y-2 overflow-auto">
                    {contextEntries.map(([key, value]) => (
                      <div key={key} className="rounded border p-2">
                        <div className="text-xs font-medium text-muted-foreground">{key}</div>
                        <pre className="mt-1 overflow-auto text-xs">{JSON.stringify(value, null, 2)}</pre>
                      </div>
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
