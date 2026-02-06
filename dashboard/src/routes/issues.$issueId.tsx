import { createFileRoute, redirect, Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { formatRelativeTime } from '@/lib/utils'
import { useToast } from '@/hooks/use-toast'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { SpanWaterfall } from '@/components/span-waterfall'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { 
  CheckCircle2, 
  AlertCircle, 
  ChevronLeft,
  Activity,
  ArrowUpRight,
  MousePointer,
  Navigation,
  Zap,
  Info,
  MessageSquare,
  Clock3,
  DatabaseZap,
  Globe,
  Smartphone,
  Battery,
  Circle,
  Play,
} from 'lucide-react'

// Helper function to get level color
function getLevelColor(level: string): string {
  switch (level.toLowerCase()) {
    case 'fatal':
      return 'bg-red-900 text-red-100 hover:bg-red-900'
    case 'error':
      return 'bg-red-600 text-white hover:bg-red-600'
    case 'warning':
      return 'bg-orange-500 text-white hover:bg-orange-500'
    case 'info':
      return 'bg-blue-500 text-white hover:bg-blue-500'
    case 'debug':
      return 'bg-gray-500 text-white hover:bg-gray-500'
    default:
      return 'bg-secondary text-secondary-foreground'
  }
}

function formatDuration(ms: number) {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${ms.toFixed(1)}ms`
}

export const Route = createFileRoute('/issues/$issueId')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
  },
  component: IssueDetailPage,
})

function IssueDetailPage() {
  const { issueId } = Route.useParams()
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const { data: issue, isLoading } = useQuery({
    queryKey: ['issue', issueId],
    queryFn: () => api.getIssue(issueId),
  })

  const { data: events = [] } = useQuery({
    queryKey: ['issue-events', issueId],
    queryFn: () => api.getIssueEvents(issueId),
  })

  const { data: relatedTransactions = [] } = useQuery({
    queryKey: ['issue-transactions', issueId],
    queryFn: () => api.getIssueTransactions(issueId),
  })

  const { data: linkedReplays = [] } = useQuery({
    queryKey: ['issue-replays', issueId],
    queryFn: () => api.getReplaysForIssue(issueId, 10),
  })

  const primaryTransactionId = relatedTransactions[0]?.eventId

  const { data: primaryTransactionSpans } = useQuery({
    queryKey: ['issue-transaction-spans', primaryTransactionId],
    queryFn: () => api.getTransactionSpans(primaryTransactionId!),
    enabled: !!primaryTransactionId,
  })

  const resolveMutation = useMutation({
    mutationFn: (status: string) => api.updateIssue(issueId, { status }),
    onSuccess: (_, status) => {
      queryClient.invalidateQueries({ queryKey: ['issue', issueId] })
      toast({
        variant: 'success',
        title: status === 'resolved' ? 'Issue resolved' : 'Issue unresolved',
        description: status === 'resolved' 
          ? 'The issue has been marked as resolved.' 
          : 'The issue has been marked as unresolved.',
      })
    },
  })

  if (isLoading) return <div className="p-8">Loading...</div>
  if (!issue) return <div className="p-8">Issue not found</div>

  const latestEvent = events[0] || issue.latestEvent

  return (
    <div className="min-h-screen bg-background">
      <div className="p-6 max-w-7xl mx-auto">
        {/* Breadcrumbs */}
        <nav className="mb-6 flex items-center gap-2 rounded-lg border border-amber-200/60 bg-amber-50/80 dark:border-amber-800/50 dark:bg-amber-950/30 px-4 py-2.5">
          <Link
            to="/"
            className="inline-flex items-center gap-1.5 text-sm font-medium text-amber-800 dark:text-amber-200 hover:text-amber-600 dark:hover:text-amber-100 transition-colors"
          >
            <ChevronLeft className="h-4 w-4" />
            Dashboard
          </Link>
          <span className="text-amber-600/70 dark:text-amber-400/60">/</span>
          <span className="text-sm font-medium text-amber-900 dark:text-amber-100 truncate max-w-[200px] sm:max-w-none" title={issue.title}>
            Issue
          </span>
          <span className="text-amber-600/70 dark:text-amber-400/60 text-xs ml-1">({issue.level})</span>
        </nav>

        {/* Issue Header */}
        <div className={`mb-6 bg-card rounded-lg border border-l-4 p-6 ${issue.level.toLowerCase() === 'fatal' || issue.level.toLowerCase() === 'error' ? 'border-l-red-500' : issue.level.toLowerCase() === 'warning' ? 'border-l-amber-500' : issue.level.toLowerCase() === 'info' ? 'border-l-blue-500' : 'border-l-muted-foreground/40'}`}>
          <div className="flex items-start justify-between mb-4">
            <div className="flex-1">
              <div className="flex items-center gap-2 mb-2">
                <Badge className={getLevelColor(issue.level)}>
                  {issue.level.toUpperCase()}
                </Badge>
                <Badge variant="outline">{issue.platform}</Badge>
                {issue.status === 'resolved' && (
                  <Badge variant="default" className="bg-green-500">
                    <CheckCircle2 className="h-3 w-3 mr-1" />
                    Resolved
                  </Badge>
                )}
              </div>
              <h2 className="text-2xl font-bold mb-2">{issue.title}</h2>
              <p className="text-muted-foreground">{issue.culprit}</p>
            </div>
            <div className="flex gap-2">
              {issue.status === 'resolved' ? (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => resolveMutation.mutate('unresolved')}
                  disabled={resolveMutation.isPending}
                >
                  <AlertCircle className="h-4 w-4 mr-2" />
                  Unresolve
                </Button>
              ) : (
                <Button
                  size="sm"
                  onClick={() => resolveMutation.mutate('resolved')}
                  disabled={resolveMutation.isPending}
                >
                  <CheckCircle2 className="h-4 w-4 mr-2" />
                  Resolve
                </Button>
              )}
            </div>
          </div>

          <div className="grid grid-cols-4 gap-4 pt-4 border-t">
            <div className="rounded-lg bg-blue-500/10 dark:bg-blue-500/20 px-3 py-2 border border-blue-200/50 dark:border-blue-800/50">
              <div className="text-xs font-medium text-blue-700 dark:text-blue-300 uppercase mb-1">Events</div>
              <div className="text-2xl font-bold text-blue-900 dark:text-blue-100">{issue.eventCount}</div>
            </div>
            <div className="rounded-lg bg-violet-500/10 dark:bg-violet-500/20 px-3 py-2 border border-violet-200/50 dark:border-violet-800/50">
              <div className="text-xs font-medium text-violet-700 dark:text-violet-300 uppercase mb-1">Users</div>
              <div className="text-2xl font-bold text-violet-900 dark:text-violet-100">{issue.userCount}</div>
            </div>
            <div className="rounded-lg bg-emerald-500/10 dark:bg-emerald-500/20 px-3 py-2 border border-emerald-200/50 dark:border-emerald-800/50">
              <div className="text-xs font-medium text-emerald-700 dark:text-emerald-300 uppercase mb-1">First Seen</div>
              <div className="text-sm font-medium text-emerald-900 dark:text-emerald-100">{formatRelativeTime(issue.firstSeen)}</div>
            </div>
            <div className="rounded-lg bg-amber-500/10 dark:bg-amber-500/20 px-3 py-2 border border-amber-200/50 dark:border-amber-800/50">
              <div className="text-xs font-medium text-amber-700 dark:text-amber-300 uppercase mb-1">Last Seen</div>
              <div className="text-sm font-medium text-amber-900 dark:text-amber-100">{formatRelativeTime(issue.lastSeen)}</div>
            </div>
          </div>
        </div>

        {/* Latest Event Details */}
        {latestEvent && (
          <>
            <Card className="mb-6 border-l-4 border-l-red-400 dark:border-l-red-600">
              <CardHeader className="pb-2">
                <CardTitle className="flex items-center gap-2 text-red-700 dark:text-red-400">
                  <AlertCircle className="h-5 w-5" />
                  Exception
                </CardTitle>
              </CardHeader>
              <CardContent>
                {latestEvent.exception ? (
                  <StackTraceViewer exception={latestEvent.exception} />
                ) : (
                  <p className="text-muted-foreground">No stack trace available</p>
                )}
              </CardContent>
            </Card>

            {latestEvent.breadcrumbs && (
              <Card className="mb-6 border-l-4 border-l-amber-400 dark:border-l-amber-600">
                <CardHeader className="pb-2">
                  <CardTitle className="flex items-center gap-2 text-amber-800 dark:text-amber-300">
                    <Navigation className="h-5 w-5" />
                    Breadcrumbs
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <BreadcrumbsViewer breadcrumbs={latestEvent.breadcrumbs} />
                </CardContent>
              </Card>
            )}

            <div className="grid grid-cols-2 gap-6">
              <Card className="border-l-4 border-l-blue-400 dark:border-l-blue-600">
                <CardHeader className="pb-2">
                  <CardTitle className="flex items-center gap-2 text-blue-700 dark:text-blue-300">
                    <Info className="h-5 w-5" />
                    Tags
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="space-y-2">
                    {Object.entries(latestEvent.tags).map(([key, value]) => (
                      <div key={key} className="flex justify-between text-sm">
                        <span className="text-muted-foreground">{key}</span>
                        <span className="font-mono">{value}</span>
                      </div>
                    ))}
                  </div>
                </CardContent>
              </Card>

              <Card className="border-l-4 border-l-slate-400 dark:border-l-slate-500">
                <CardHeader className="pb-2">
                  <CardTitle className="flex items-center gap-2 text-slate-700 dark:text-slate-300">
                    <Activity className="h-5 w-5" />
                    Event Details
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="space-y-2">
                    <div className="flex justify-between text-sm">
                      <span className="text-muted-foreground">Event ID</span>
                      <span className="font-mono text-xs">{latestEvent.eventId}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-muted-foreground">Timestamp</span>
                      <span>{new Date(latestEvent.timestamp).toLocaleString()}</span>
                    </div>
                    {latestEvent.environment && (
                      <div className="flex justify-between text-sm">
                        <span className="text-muted-foreground">Environment</span>
                        <span>{latestEvent.environment}</span>
                      </div>
                    )}
                    {latestEvent.release && (
                      <div className="flex justify-between text-sm">
                        <span className="text-muted-foreground">Release</span>
                        <span>{latestEvent.release}</span>
                      </div>
                    )}
                    {latestEvent.user && (
                      <div className="flex justify-between text-sm">
                        <span className="text-muted-foreground">User</span>
                        <span>{latestEvent.user.email || latestEvent.user.id}</span>
                      </div>
                    )}
                  </div>
                </CardContent>
              </Card>
            </div>
          </>
        )}

        <Card className="mt-6 border-l-4 border-l-indigo-400 dark:border-l-indigo-600">
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-2 text-indigo-700 dark:text-indigo-300">
              <Activity className="h-5 w-5" />
              Related Transactions ({relatedTransactions.length})
            </CardTitle>
          </CardHeader>
          <CardContent>
            {relatedTransactions.length === 0 ? (
              <p className="text-sm text-muted-foreground">No related transactions were found for this issue.</p>
            ) : (
              <div className="space-y-2">
                {relatedTransactions.map((tx) => (
                  <Link
                    key={tx.eventId}
                    to="/performance/$transactionId"
                    params={{ transactionId: tx.eventId }}
                    className="flex items-center justify-between rounded border p-3 transition-colors hover:bg-accent"
                  >
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="truncate font-medium">{tx.name || '(unnamed transaction)'}</span>
                        {tx.op && <Badge variant="secondary">{tx.op}</Badge>}
                        {tx.status && <Badge variant="outline">{tx.status}</Badge>}
                      </div>
                      <div className="mt-1 flex items-center gap-3 text-xs text-muted-foreground">
                        <span className="inline-flex items-center gap-1">
                          <Clock3 className="h-3.5 w-3.5" />
                          {formatDuration(tx.duration)}
                        </span>
                        <span>{new Date(tx.timestamp).toLocaleString()}</span>
                        {tx.traceId && <span className="truncate font-mono">trace {tx.traceId}</span>}
                      </div>
                    </div>
                    <ArrowUpRight className="h-4 w-4 text-muted-foreground" />
                  </Link>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        {linkedReplays.length > 0 && (
          <Card className="mt-6 border-l-4 border-l-emerald-400 dark:border-l-emerald-600">
            <CardHeader className="pb-2">
              <CardTitle className="flex items-center gap-2 text-emerald-700 dark:text-emerald-300">
                <Play className="h-5 w-5" />
                Session Replays ({linkedReplays.length})
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-2">
                {linkedReplays.map((replay) => (
                  <Link
                    key={replay.replayId}
                    to="/replays/$replayId"
                    params={{ replayId: replay.replayId }}
                    className="flex items-center justify-between rounded border p-3 transition-colors hover:bg-accent"
                  >
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="truncate font-medium">
                          {replay.user?.email || replay.user?.username || replay.user?.id || 'Anonymous'}
                        </span>
                        {replay.errorCount > 0 && (
                          <Badge variant="destructive" className="flex items-center gap-1">
                            <AlertCircle className="h-3 w-3" />
                            {replay.errorCount}
                          </Badge>
                        )}
                      </div>
                      <div className="mt-1 flex items-center gap-3 text-xs text-muted-foreground">
                        <span className="inline-flex items-center gap-1">
                          <Clock3 className="h-3.5 w-3.5" />
                          {formatDuration(replay.durationMs)}
                        </span>
                        <span>{new Date(replay.startedAt).toLocaleString()}</span>
                        {replay.browserName && <span>{replay.browserName}</span>}
                      </div>
                    </div>
                    <ArrowUpRight className="h-4 w-4 text-muted-foreground" />
                  </Link>
                ))}
              </div>
            </CardContent>
          </Card>
        )}

        {primaryTransactionSpans && (
          <Card className="mt-6 border-l-4 border-l-cyan-400 dark:border-l-cyan-600">
            <CardHeader className="pb-2">
              <CardTitle className="flex items-center gap-2 text-cyan-700 dark:text-cyan-300">
                <DatabaseZap className="h-5 w-5" />
                Spans Preview ({primaryTransactionSpans.spans.length})
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <SpanWaterfall
                transaction={primaryTransactionSpans.transaction}
                spans={primaryTransactionSpans.spans}
              />
              <Link
                to="/performance/$transactionId"
                params={{ transactionId: primaryTransactionSpans.transaction.eventId }}
                className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
              >
                Open Full Span Waterfall
                <ArrowUpRight className="h-3.5 w-3.5" />
              </Link>
            </CardContent>
          </Card>
        )}

        {/* Event Timeline */}
        {events.length > 1 && (
          <Card className="mt-6 border-l-4 border-l-violet-400 dark:border-l-violet-600">
            <CardHeader className="pb-2">
              <CardTitle className="flex items-center gap-2 text-violet-700 dark:text-violet-300">
                <Activity className="h-5 w-5" />
                Recent Events ({events.length})
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-2">
                {events.map((event) => (
                  <div
                    key={event.eventId}
                    className="flex items-center justify-between p-3 rounded border hover:bg-accent"
                  >
                    <div className="flex-1">
                      <div className="font-mono text-xs text-muted-foreground">{event.eventId}</div>
                      <div className="text-sm">{event.message}</div>
                    </div>
                    <div className="text-sm text-muted-foreground">
                      {formatRelativeTime(event.timestamp)}
                    </div>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  )
}

function formatAsStackTrace(exception: any): JSX.Element[] {
  try {
    const parsed = typeof exception === 'string' ? JSON.parse(exception) : exception
    const exceptions = parsed.values || [parsed]
    
    return exceptions.map((value: any, exIdx: number) => {
      const frames = value.stacktrace?.frames ? [...value.stacktrace.frames].reverse() : []
      
      return (
        <div key={exIdx}>
          <div className="text-red-600 dark:text-red-400 font-semibold mb-1">
            {value.type}: {value.value}
          </div>
          {frames.map((frame: any, idx: number) => {
            const method = frame.function || '<unknown>'
            const file = frame.filename || frame.module || '<unknown>'
            const line = frame.lineno ? `:${frame.lineno}` : ''
            const module = frame.module ? `${frame.module}.` : ''
            
            return (
              <div key={idx} className="pl-4">
                <span className="text-muted-foreground">at </span>
                <span className="text-blue-600 dark:text-blue-400">{module}{method}</span>
                <span className="text-muted-foreground">(</span>
                <span className="text-amber-600 dark:text-amber-400">{file}</span>
                {line && <span className="text-green-600 dark:text-green-400">{line}</span>}
                <span className="text-muted-foreground">)</span>
              </div>
            )
          })}
          {exIdx < exceptions.length - 1 && (
            <div className="my-2 text-muted-foreground">Caused by:</div>
          )}
        </div>
      )
    })
  } catch (e) {
    const text = typeof exception === 'string' ? exception : JSON.stringify(exception, null, 2)
    return [<div key="error">{text}</div>]
  }
}

function StackTraceViewer({ exception }: { exception: string }) {
  const rawContent = typeof exception === 'string' ? exception : JSON.stringify(exception, null, 2)
  
  try {
    const parsed = typeof exception === 'string' ? JSON.parse(exception) : exception
    const exceptions = parsed.values || [parsed]
    const stackTraceText = formatAsStackTrace(exception)
    
    return (
      <Tabs defaultValue="formatted" className="w-full">
        <TabsList>
          <TabsTrigger value="formatted">Formatted</TabsTrigger>
          <TabsTrigger value="raw">Raw</TabsTrigger>
        </TabsList>
        
        <TabsContent value="formatted" className="mt-4">
          <div className="space-y-6">
            {exceptions.map((value: any, idx: number) => (
              <div key={idx}>
                <div className="font-semibold text-red-600 dark:text-red-400 mb-4 text-lg">
                  {value.type}: {value.value}
                </div>
                {value.stacktrace?.frames && value.stacktrace.frames.length > 0 ? (
                  <div className="space-y-1">
                    {value.stacktrace.frames.map((frame: any, frameIdx: number) => (
                      <StackFrame key={frameIdx} frame={frame} />
                    ))}
                  </div>
                ) : (
                  <p className="text-sm text-muted-foreground italic">No stack frames available</p>
                )}
              </div>
            ))}
          </div>
        </TabsContent>
        
        <TabsContent value="raw" className="mt-4">
          <div className="text-xs bg-muted p-4 rounded overflow-auto max-h-[600px] font-mono leading-relaxed">
            {stackTraceText}
          </div>
        </TabsContent>
      </Tabs>
    )
  } catch (e) {
    console.error('Failed to parse stack trace:', e)
    return (
      <div className="space-y-2">
        <p className="text-sm text-muted-foreground mb-2">Raw stack trace:</p>
        <pre className="text-xs bg-muted p-4 rounded overflow-auto max-h-96 whitespace-pre-wrap break-words font-mono">
          {rawContent}
        </pre>
      </div>
    )
  }
}

function StackFrame({ frame }: { frame: any }) {
  const hasContext = frame.context_line
  const hasVars = frame.vars && Object.keys(frame.vars).length > 0
  
  return (
    <div className="font-mono text-xs border rounded overflow-hidden">
      <div className="flex items-center justify-between p-2 bg-muted/50">
        <div className="flex items-center gap-2 flex-1 min-w-0">
          <span className="font-semibold text-foreground truncate">
            {frame.function || '<anonymous>'}
          </span>
        </div>
        <div className="text-muted-foreground text-right ml-2 flex-shrink-0">
          {frame.filename}
          {frame.lineno && `:${frame.lineno}`}
          {frame.colno && `:${frame.colno}`}
        </div>
      </div>
      
      {(hasContext || hasVars) && (
        <div className="p-3 bg-card border-t">
          {hasContext && (
            <div className="border-l-2 border-muted-foreground/30 pl-3 mb-3">
              {frame.pre_context?.map((line: string, i: number) => (
                <div key={`pre-${i}`} className="text-muted-foreground/60 leading-relaxed">
                  {line}
                </div>
              ))}
              <div className="bg-red-500/10 text-red-600 dark:text-red-400 px-2 py-0.5 -ml-3 pl-3 font-semibold leading-relaxed border-l-2 border-red-500">
                → {frame.context_line}
              </div>
              {frame.post_context?.map((line: string, i: number) => (
                <div key={`post-${i}`} className="text-muted-foreground/60 leading-relaxed">
                  {line}
                </div>
              ))}
            </div>
          )}
          
          {hasVars && (
            <div className="pt-2 border-t border-muted-foreground/20">
              <div className="text-muted-foreground text-[10px] uppercase mb-1">Variables</div>
              <div className="space-y-0.5">
                {Object.entries(frame.vars).map(([key, val]) => (
                  <div key={key} className="flex gap-2">
                    <span className="text-primary">{key}:</span>
                    <span className="text-muted-foreground">{String(val)}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// Helper to get color classes for breadcrumb category
function getBreadcrumbCategoryColor(category: string): { border: string; icon: string; badge: string; dot: string; line: string } {
  if (!category) return { border: 'border-slate-300 dark:border-slate-700', icon: 'text-slate-500', badge: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300', dot: 'bg-slate-400 dark:bg-slate-500 ring-slate-400/20 dark:ring-slate-500/20', line: 'bg-slate-300 dark:bg-slate-700' }
  const cat = category.toLowerCase()
  if (cat.includes('lifecycle')) return { border: 'border-emerald-300 dark:border-emerald-700', icon: 'text-emerald-600 dark:text-emerald-400', badge: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/50 dark:text-emerald-300', dot: 'bg-emerald-500 dark:bg-emerald-400 ring-emerald-500/20 dark:ring-emerald-400/20', line: 'bg-emerald-300 dark:bg-emerald-700' }
  if (cat.includes('click') || cat.includes('touch')) return { border: 'border-violet-300 dark:border-violet-700', icon: 'text-violet-600 dark:text-violet-400', badge: 'bg-violet-100 text-violet-800 dark:bg-violet-900/50 dark:text-violet-300', dot: 'bg-violet-500 dark:bg-violet-400 ring-violet-500/20 dark:ring-violet-400/20', line: 'bg-violet-300 dark:bg-violet-700' }
  if (cat.includes('navigation')) return { border: 'border-blue-300 dark:border-blue-700', icon: 'text-blue-600 dark:text-blue-400', badge: 'bg-blue-100 text-blue-800 dark:bg-blue-900/50 dark:text-blue-300', dot: 'bg-blue-500 dark:bg-blue-400 ring-blue-500/20 dark:ring-blue-400/20', line: 'bg-blue-300 dark:bg-blue-700' }
  if (cat.includes('action')) return { border: 'border-amber-300 dark:border-amber-700', icon: 'text-amber-600 dark:text-amber-400', badge: 'bg-amber-100 text-amber-800 dark:bg-amber-900/50 dark:text-amber-300', dot: 'bg-amber-500 dark:bg-amber-400 ring-amber-500/20 dark:ring-amber-400/20', line: 'bg-amber-300 dark:bg-amber-700' }
  if (cat.includes('http') || cat.includes('network')) return { border: 'border-cyan-300 dark:border-cyan-700', icon: 'text-cyan-600 dark:text-cyan-400', badge: 'bg-cyan-100 text-cyan-800 dark:bg-cyan-900/50 dark:text-cyan-300', dot: 'bg-cyan-500 dark:bg-cyan-400 ring-cyan-500/20 dark:ring-cyan-400/20', line: 'bg-cyan-300 dark:bg-cyan-700' }
  if (cat.includes('device')) return { border: 'border-orange-300 dark:border-orange-700', icon: 'text-orange-600 dark:text-orange-400', badge: 'bg-orange-100 text-orange-800 dark:bg-orange-900/50 dark:text-orange-300', dot: 'bg-orange-500 dark:bg-orange-400 ring-orange-500/20 dark:ring-orange-400/20', line: 'bg-orange-300 dark:bg-orange-700' }
  if (cat.includes('message') || cat.includes('log')) return { border: 'border-pink-300 dark:border-pink-700', icon: 'text-pink-600 dark:text-pink-400', badge: 'bg-pink-100 text-pink-800 dark:bg-pink-900/50 dark:text-pink-300', dot: 'bg-pink-500 dark:bg-pink-400 ring-pink-500/20 dark:ring-pink-400/20', line: 'bg-pink-300 dark:bg-pink-700' }
  return { border: 'border-slate-300 dark:border-slate-700', icon: 'text-slate-500', badge: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300', dot: 'bg-slate-400 dark:bg-slate-500 ring-slate-400/20 dark:ring-slate-500/20', line: 'bg-slate-300 dark:bg-slate-700' }
}

// Helper to get icon for breadcrumb category
function getBreadcrumbIcon(category: string, data?: any) {
  if (!category) return <Circle className="h-4 w-4" />
  
  const cat = category.toLowerCase()
  
  if (cat.includes('lifecycle')) {
    return <Activity className="h-4 w-4" />
  }
  if (cat.includes('click') || cat.includes('touch')) {
    return <MousePointer className="h-4 w-4" />
  }
  if (cat.includes('navigation')) {
    return <Navigation className="h-4 w-4" />
  }
  if (cat.includes('action')) {
    return <Zap className="h-4 w-4" />
  }
  if (cat.includes('http') || cat.includes('network')) {
    return <Globe className="h-4 w-4" />
  }
  if (cat.includes('device')) {
    // Check if it's battery-related
    if (data && (data.action?.includes('BATTERY') || data.level !== undefined)) {
      return <Battery className="h-4 w-4" />
    }
    return <Smartphone className="h-4 w-4" />
  }
  if (cat.includes('message') || cat.includes('log')) {
    return <MessageSquare className="h-4 w-4" />
  }
  
  return <Info className="h-4 w-4" />
}

// Helper to format breadcrumb data nicely
function formatBreadcrumbData(crumb: any): string {
  if (crumb.message) {
    return crumb.message
  }
  
  if (!crumb.data) {
    return ''
  }
  
  const data = crumb.data
  const category = (crumb.category || crumb.type || '').toLowerCase()
  
  if (!category) {
    return JSON.stringify(data)
  }
  
  // UI Lifecycle
  if (category.includes('ui.lifecycle')) {
    return `${data.screen || 'Screen'}: ${data.state || ''}`
  }
  
  // App Lifecycle
  if (category.includes('app.lifecycle')) {
    return `App ${data.state || ''}`
  }
  
  // UI Click
  if (category.includes('ui.click')) {
    const viewClass = data['view.class']?.split('.').pop() || ''
    const viewId = data['view.id'] || ''
    return `Clicked ${viewClass}${viewId ? ` (${viewId})` : ''}`
  }
  
  // Device events
  if (category.includes('device.event')) {
    if (data.action?.includes('BATTERY')) {
      const charging = data.charging ? '⚡' : ''
      return `Battery ${data.level}%${charging ? ' (charging)' : ''}`
    }
    return data.action || 'Device event'
  }
  
  // Navigation
  if (category.includes('navigation')) {
    return `${data.from || ''} → ${data.to || ''}`
  }
  
  // Default: show key data points
  const parts = []
  if (data.url) parts.push(data.url)
  if (data.status_code) parts.push(`${data.status_code}`)
  if (data.method) parts.push(data.method)
  
  return parts.length > 0 ? parts.join(' ') : JSON.stringify(data)
}

function BreadcrumbsViewer({ breadcrumbs }: { breadcrumbs: string }) {
  try {
    const parsed = JSON.parse(breadcrumbs)
    const crumbs = Array.isArray(parsed) ? parsed : parsed.values || []

    return (
      <div className="relative">
        {crumbs.map((crumb: any, idx: number) => {
          // Handle timestamps - they could be in seconds or milliseconds
          let timestamp = crumb.timestamp
          if (timestamp) {
            // If timestamp is a small number (likely seconds), convert to ms
            if (timestamp < 10000000000) {
              timestamp = timestamp * 1000
            }
          }
          
          const category = crumb.category || crumb.type || 'event'
          const formattedData = formatBreadcrumbData(crumb)
          const colors = getBreadcrumbCategoryColor(category)
          const isLast = idx === crumbs.length - 1
          
          return (
            <div key={idx} className="relative flex gap-3 group">
              {/* Timeline track */}
              <div className="flex flex-col items-center flex-shrink-0 w-5">
                {/* Dot */}
                <div className={`relative z-10 mt-1.5 h-2.5 w-2.5 rounded-full ring-4 ${colors.dot}`} />
                {/* Connecting line */}
                {!isLast && (
                  <div className={`w-0.5 flex-1 min-h-[16px] ${colors.line}`} />
                )}
              </div>
              
              {/* Content */}
              <div className={`flex-1 min-w-0 ${isLast ? 'pb-0' : 'pb-4'}`}>
                <div className="flex items-center gap-2">
                  <div className={`flex-shrink-0 ${colors.icon}`}>
                    {getBreadcrumbIcon(category, crumb.data)}
                  </div>
                  <span className="text-muted-foreground font-mono text-xs">
                    {timestamp ? new Date(timestamp).toLocaleTimeString() : '--:--:--'}
                  </span>
                  <Badge className={`text-[10px] leading-tight px-1.5 py-0 border-0 ${colors.badge}`}>
                    {category}
                  </Badge>
                </div>
                {formattedData && (
                  <p className="text-sm text-foreground mt-0.5 ml-6">
                    {formattedData}
                  </p>
                )}
              </div>
            </div>
          )
        })}
      </div>
    )
  } catch (e) {
    return <pre className="text-xs bg-muted p-4 rounded overflow-auto">{breadcrumbs}</pre>
  }
}
