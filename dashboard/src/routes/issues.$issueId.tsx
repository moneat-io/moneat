import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useState} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {formatRelativeTime} from '@/lib/utils'
import {useToast} from '@/hooks/use-toast'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Checkbox} from '@/components/ui/checkbox'
import {Label} from '@/components/ui/label'
import {SpanWaterfall} from '@/components/span-waterfall'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {EmbeddedLogs} from '@/components/logs/EmbeddedLogs'
import {
    Activity,
    AlertCircle,
    ArrowUpRight,
    Battery,
    CheckCircle2,
    ChevronLeft,
    ChevronRight,
    Circle,
    Clock3,
    DatabaseZap,
    Globe,
    Info,
    Layers,
    MessageSquare,
    MousePointer,
    Navigation,
    Play,
    Smartphone,
    Tag,
    TerminalSquare,
    Zap,
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

function parseContextEntries(rawContexts: unknown): [string, unknown][] {
  if (!rawContexts) return []

  try {
    const parsed = typeof rawContexts === 'string' ? JSON.parse(rawContexts || '{}') : rawContexts
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return []
    return Object.entries(parsed as Record<string, unknown>)
  } catch {
    return []
  }
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
  const [expandContextsByDefault, setExpandContextsByDefault] = useState(true)

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
  const latestEventTags = latestEvent?.tags ?? {}
  const contextEntries = parseContextEntries(latestEvent?.contexts)

  return (
    <div className="min-h-screen bg-background">
      <div className="px-3 py-3 lg:px-5 lg:py-4">
        {/* Breadcrumb nav */}
        <nav className="mb-3 flex items-center gap-2 text-sm">
          <Link
            to="/"
            className="inline-flex items-center gap-1 text-muted-foreground hover:text-foreground transition-colors"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
            Dashboard
          </Link>
          <span className="text-muted-foreground/50">/</span>
          <span className="text-foreground font-medium truncate max-w-[200px] sm:max-w-none" title={issue.title}>
            Issue
          </span>
        </nav>

        {/* Issue Header - compact */}
        <div className={`mb-3 bg-card rounded-lg border border-t-2 px-4 py-3 sm:px-5 sm:py-3.5 ${issue.level.toLowerCase() === 'fatal' || issue.level.toLowerCase() === 'error' ? 'border-t-red-500' : issue.level.toLowerCase() === 'warning' ? 'border-t-amber-500' : issue.level.toLowerCase() === 'info' ? 'border-t-blue-500' : 'border-t-muted-foreground/40'}`}>
          <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-1.5 flex-wrap">
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
              <h2 className="text-lg sm:text-xl font-bold leading-tight mb-1 break-words [overflow-wrap:anywhere]">
                {issue.title}
              </h2>
              <p className="text-sm text-muted-foreground break-words [overflow-wrap:anywhere]">{issue.culprit}</p>
            </div>
            <div className="flex gap-2 flex-shrink-0">
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

          {/* Compact inline stats */}
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5 mt-2.5 pt-2.5 border-t text-sm">
            <span className="inline-flex items-center gap-1.5">
              <span className="font-semibold text-blue-600 dark:text-blue-400">{issue.eventCount}</span>
              <span className="text-muted-foreground">events</span>
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="font-semibold text-violet-600 dark:text-violet-400">{issue.userCount}</span>
              <span className="text-muted-foreground">users</span>
            </span>
            <span className="text-muted-foreground/40 hidden sm:inline">|</span>
            <span className="inline-flex items-center gap-1.5 text-muted-foreground">
              First <span className="text-foreground font-medium">{formatRelativeTime(issue.firstSeen)}</span>
            </span>
            <span className="inline-flex items-center gap-1.5 text-muted-foreground">
              Last <span className="text-foreground font-medium">{formatRelativeTime(issue.lastSeen)}</span>
            </span>
          </div>
        </div>

        {/* Two-column layout: main content + sidebar */}
        <div className="grid grid-cols-1 lg:grid-cols-5 gap-3">
          {/* Main column */}
          <div className="lg:col-span-3 space-y-3">
            {/* Exception */}
            {latestEvent && (
              <>
                <Card>
                  <CardHeader className="pb-2 px-3 pt-3">
                    <CardTitle className="flex items-center gap-2 text-sm font-medium">
                      <AlertCircle className="h-4 w-4 text-red-500 dark:text-red-400" />
                      Exception
                    </CardTitle>
                  </CardHeader>
                  <CardContent className="px-3 pb-3">
                    {latestEvent.exception ? (
                      <div className="max-h-[500px] overflow-auto">
                        <StackTraceViewer exception={latestEvent.exception} />
                      </div>
                    ) : (
                      <p className="text-muted-foreground text-sm">No stack trace available</p>
                    )}
                  </CardContent>
                </Card>

                {latestEvent.breadcrumbs && (
                  <Card>
                    <CardHeader className="pb-2 px-3 pt-3">
                      <CardTitle className="flex items-center gap-2 text-sm font-medium">
                        <Navigation className="h-4 w-4 text-amber-500 dark:text-amber-400" />
                        Breadcrumbs
                      </CardTitle>
                    </CardHeader>
                    <CardContent className="px-3 pb-3">
                      <BreadcrumbsViewer breadcrumbs={latestEvent.breadcrumbs} />
                    </CardContent>
                  </Card>
                )}

                {/* Logs Context */}
                <Card>
                  <CardHeader className="pb-2 px-3 pt-3">
                    <CardTitle className="flex items-center gap-2 text-sm font-medium">
                      <TerminalSquare className="h-4 w-4 text-indigo-500 dark:text-indigo-400" />
                      Logs Context
                    </CardTitle>
                  </CardHeader>
                  <CardContent className="px-0 pb-0">
                    <EmbeddedLogs
                      projectId={issue.projectId}
                      centerTimestamp={latestEvent.timestamp}
                      contextMinutes={5}
                      environment={latestEvent.environment}
                      service={latestEventTags.service}
                      maxHeight="500px"
                      showHeader={false}
                      className="border-0 rounded-none"
                    />
                  </CardContent>
                </Card>
              </>
            )}

            {/* Spans Preview - wide content, keep in main column */}
            {primaryTransactionSpans && (
              <Card>
                <CardHeader className="pb-2 px-3 pt-3">
                  <CardTitle className="flex items-center gap-2 text-sm font-medium">
                    <DatabaseZap className="h-4 w-4 text-cyan-500 dark:text-cyan-400" />
                    Spans Preview ({primaryTransactionSpans.spans.length})
                  </CardTitle>
                </CardHeader>
                <CardContent className="px-3 pb-3 space-y-3">
                  <div className="max-h-[350px] overflow-auto">
                    <SpanWaterfall
                      transaction={primaryTransactionSpans.transaction}
                      spans={primaryTransactionSpans.spans}
                    />
                  </div>
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
          </div>

          {/* Sidebar */}
          <div className="lg:col-span-2 space-y-3">
            {/* Event Details */}
            {latestEvent && (
              <Card>
                <CardHeader className="pb-2 px-3 pt-3">
                  <CardTitle className="flex items-center gap-2 text-sm font-medium">
                    <Activity className="h-4 w-4 text-slate-500 dark:text-slate-400" />
                    Event Details
                  </CardTitle>
                </CardHeader>
                <CardContent className="px-3 pb-3">
                  <div className="space-y-1.5 text-sm">
                    <div className="flex items-start justify-between gap-2">
                      <span className="text-muted-foreground flex-shrink-0">Event ID</span>
                      <span className="font-mono text-xs text-right min-w-0 max-w-[65%] break-all">{latestEvent.eventId}</span>
                    </div>
                    <div className="flex justify-between gap-2">
                      <span className="text-muted-foreground flex-shrink-0">Timestamp</span>
                      <span className="text-xs">{new Date(latestEvent.timestamp).toLocaleString()}</span>
                    </div>
                    {latestEvent.environment && (
                      <div className="flex justify-between gap-2">
                        <span className="text-muted-foreground">Environment</span>
                        <span>{latestEvent.environment}</span>
                      </div>
                    )}
                    {latestEvent.release && (
                      <div className="flex items-start justify-between gap-2">
                        <span className="text-muted-foreground">Release</span>
                        <span className="text-right min-w-0 max-w-[65%] break-words [overflow-wrap:anywhere]">{latestEvent.release}</span>
                      </div>
                    )}
                    {latestEvent.user && (
                      <div className="flex items-start justify-between gap-2">
                        <span className="text-muted-foreground">User</span>
                        <span className="text-right min-w-0 max-w-[65%] break-all">{latestEvent.user.email || latestEvent.user.id}</span>
                      </div>
                    )}
                  </div>
                </CardContent>
              </Card>
            )}

            {/* Tags & Context */}
            {latestEvent && (Object.keys(latestEventTags).length > 0 || contextEntries.length > 0) && (
              <Card>
                <CardHeader className="pb-2 px-3 pt-3">
                  <CardTitle className="flex items-center gap-2 text-sm font-medium">
                    <Info className="h-4 w-4 text-blue-500 dark:text-blue-400" />
                    Tags & Context
                  </CardTitle>
                </CardHeader>
                <CardContent className="px-3 pb-3 space-y-3">
                  <div>
                    <div className="mb-2 flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
                      <Tag className="h-3 w-3" />
                      Tags
                      {Object.keys(latestEventTags).length > 0 && (
                        <Badge variant="secondary" className="ml-1 text-[10px] px-1 py-0">{Object.keys(latestEventTags).length}</Badge>
                      )}
                    </div>
                    {Object.keys(latestEventTags).length === 0 ? (
                      <p className="text-xs text-muted-foreground">No tags</p>
                    ) : (
                      <div className="flex flex-wrap gap-1.5">
                        {Object.entries(latestEventTags).map(([key, value]) => (
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

                  <div>
                    <div className="mb-2 flex items-center justify-between">
                      <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
                        <Layers className="h-3 w-3" />
                        Contexts
                        {contextEntries.length > 0 && (
                          <Badge variant="secondary" className="ml-1 text-[10px] px-1 py-0">{contextEntries.length}</Badge>
                        )}
                      </div>
                      {contextEntries.length > 0 && (
                        <label className="flex cursor-pointer items-center gap-1.5">
                          <Checkbox
                            checked={expandContextsByDefault}
                            onCheckedChange={(checked) => setExpandContextsByDefault(checked === true)}
                            className="h-3.5 w-3.5"
                          />
                          <Label className="cursor-pointer text-[11px] font-normal text-muted-foreground">
                            Expand
                          </Label>
                        </label>
                      )}
                    </div>

                    {contextEntries.length === 0 ? (
                      <p className="text-xs text-muted-foreground">No context entries</p>
                    ) : (
                      <div key={String(expandContextsByDefault)} className="space-y-2">
                        {contextEntries.map(([key, value]) => (
                          <ContextSection key={key} name={key} data={value} defaultOpen={expandContextsByDefault} />
                        ))}
                      </div>
                    )}
                  </div>
                </CardContent>
              </Card>
            )}

            {/* Related Transactions */}
            {relatedTransactions.length > 0 && (
              <Card>
                <CardHeader className="pb-2 px-3 pt-3">
                  <CardTitle className="flex items-center gap-2 text-sm font-medium">
                    <Activity className="h-4 w-4 text-indigo-500 dark:text-indigo-400" />
                    Transactions ({relatedTransactions.length})
                  </CardTitle>
                </CardHeader>
                <CardContent className="px-3 pb-3">
                  <div className="space-y-1.5 max-h-[300px] overflow-auto">
                    {relatedTransactions.map((tx) => (
                      <Link
                        key={tx.eventId}
                        to="/performance/$transactionId"
                        params={{ transactionId: tx.eventId }}
                        className="flex items-center justify-between rounded border p-2.5 transition-colors hover:bg-accent"
                      >
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-1.5 flex-wrap">
                            <span className="truncate font-medium text-sm">{tx.name || '(unnamed)'}</span>
                            {tx.op && <Badge variant="secondary" className="text-[10px] px-1.5 py-0">{tx.op}</Badge>}
                          </div>
                          <div className="mt-0.5 flex items-center gap-2 text-xs text-muted-foreground">
                            <span className="inline-flex items-center gap-0.5">
                              <Clock3 className="h-3 w-3" />
                              {formatDuration(tx.duration)}
                            </span>
                            <span>{formatRelativeTime(tx.timestamp)}</span>
                          </div>
                        </div>
                        <ArrowUpRight className="h-3.5 w-3.5 text-muted-foreground flex-shrink-0" />
                      </Link>
                    ))}
                  </div>
                </CardContent>
              </Card>
            )}

            {/* Session Replays */}
            {linkedReplays.length > 0 && (
              <Card>
                <CardHeader className="pb-2 px-3 pt-3">
                  <CardTitle className="flex items-center gap-2 text-sm font-medium">
                    <Play className="h-4 w-4 text-emerald-500 dark:text-emerald-400" />
                    Replays ({linkedReplays.length})
                  </CardTitle>
                </CardHeader>
                <CardContent className="px-3 pb-3">
                  <div className="space-y-1.5 max-h-[300px] overflow-auto">
                    {linkedReplays.map((replay) => (
                      <Link
                        key={replay.replayId}
                        to="/replays/$replayId"
                        params={{ replayId: replay.replayId }}
                        className="flex items-center justify-between rounded border p-2.5 transition-colors hover:bg-accent"
                      >
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-1.5">
                            <span className="truncate font-medium text-sm">
                              {replay.user?.email || replay.user?.username || replay.user?.id || 'Anonymous'}
                            </span>
                            {replay.errorCount > 0 && (
                              <Badge variant="destructive" className="text-[10px] px-1.5 py-0 flex items-center gap-0.5">
                                <AlertCircle className="h-2.5 w-2.5" />
                                {replay.errorCount}
                              </Badge>
                            )}
                          </div>
                          <div className="mt-0.5 flex items-center gap-2 text-xs text-muted-foreground">
                            <span className="inline-flex items-center gap-0.5">
                              <Clock3 className="h-3 w-3" />
                              {formatDuration(replay.durationMs)}
                            </span>
                            <span>{formatRelativeTime(replay.startedAt)}</span>
                          </div>
                        </div>
                        <ArrowUpRight className="h-3.5 w-3.5 text-muted-foreground flex-shrink-0" />
                      </Link>
                    ))}
                  </div>
                </CardContent>
              </Card>
            )}

            {/* Recent Events */}
            {events.length > 1 && (
              <Card>
                <CardHeader className="pb-2 px-3 pt-3">
                  <CardTitle className="flex items-center gap-2 text-sm font-medium">
                    <Activity className="h-4 w-4 text-violet-500 dark:text-violet-400" />
                    Recent Events ({events.length})
                  </CardTitle>
                </CardHeader>
                <CardContent className="px-3 pb-3">
                  <div className="space-y-1.5 max-h-[300px] overflow-auto">
                    {events.map((event) => (
                      <div
                        key={event.eventId}
                        className="flex items-center justify-between p-2.5 rounded border hover:bg-accent"
                      >
                        <div className="flex-1 min-w-0">
                          <div className="font-mono text-[10px] text-muted-foreground truncate">{event.eventId}</div>
                          <div className="text-sm truncate">{event.message}</div>
                        </div>
                        <div className="text-xs text-muted-foreground flex-shrink-0 ml-2">
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
          <div className="text-red-600 dark:text-red-400 font-semibold mb-1 break-words [overflow-wrap:anywhere]">
            {value.type}: {value.value}
          </div>
          {frames.map((frame: any, idx: number) => {
            const method = frame.function || '<unknown>'
            const file = frame.filename || frame.module || '<unknown>'
            const line = frame.lineno ? `:${frame.lineno}` : ''
            const module = frame.module ? `${frame.module}.` : ''
            
            return (
              <div key={idx} className="pl-4 break-words [overflow-wrap:anywhere]">
                <span className="text-muted-foreground">at </span>
                <span className="text-blue-600 dark:text-blue-400 [overflow-wrap:anywhere]">{module}{method}</span>
                <span className="text-muted-foreground">(</span>
                <span className="text-amber-600 dark:text-amber-400 break-all">{file}</span>
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
  const STACK_TRACE_VIEW_KEY = 'issue-stack-trace-view-preference'
  const savedView = localStorage.getItem(STACK_TRACE_VIEW_KEY) || 'formatted'
  
  const rawContent = typeof exception === 'string' ? exception : JSON.stringify(exception, null, 2)
  
  const handleValueChange = (value: string) => {
    localStorage.setItem(STACK_TRACE_VIEW_KEY, value)
  }
  
  try {
    const parsed = typeof exception === 'string' ? JSON.parse(exception) : exception
    const exceptions = parsed.values || [parsed]
    const stackTraceText = formatAsStackTrace(exception)
    
    return (
      <Tabs defaultValue={savedView} onValueChange={handleValueChange} className="w-full">
        <TabsList>
          <TabsTrigger value="formatted">Formatted</TabsTrigger>
          <TabsTrigger value="raw">Raw</TabsTrigger>
        </TabsList>
        
        <TabsContent value="formatted" className="mt-4">
          <div className="space-y-6">
            {exceptions.map((value: any, idx: number) => (
              <div key={idx}>
                <div className="font-semibold text-red-600 dark:text-red-400 mb-4 text-lg break-words [overflow-wrap:anywhere]">
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
          <div className="text-xs bg-muted p-4 rounded overflow-auto max-h-[600px] font-mono leading-relaxed break-words [overflow-wrap:anywhere]">
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
      <div className="flex flex-wrap items-start justify-between gap-1 p-2 bg-muted/50">
        <div className="flex items-center gap-2 flex-1 min-w-0">
          <span className="font-semibold text-foreground break-words [overflow-wrap:anywhere]">
            {frame.function || '<anonymous>'}
          </span>
        </div>
        <div className="text-muted-foreground text-right ml-2 min-w-0 max-w-full break-all">
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
                <div key={`pre-${i}`} className="text-muted-foreground/60 leading-relaxed break-words [overflow-wrap:anywhere]">
                  {line}
                </div>
              ))}
              <div className="bg-red-500/10 text-red-600 dark:text-red-400 px-2 py-0.5 -ml-3 pl-3 font-semibold leading-relaxed border-l-2 border-red-500 break-words [overflow-wrap:anywhere]">
                → {frame.context_line}
              </div>
              {frame.post_context?.map((line: string, i: number) => (
                <div key={`post-${i}`} className="text-muted-foreground/60 leading-relaxed break-words [overflow-wrap:anywhere]">
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
                  <div key={key} className="flex gap-2 min-w-0">
                    <span className="text-primary flex-shrink-0">{key}:</span>
                    <span className="text-muted-foreground min-w-0 break-all">{String(val)}</span>
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
    <div className={`rounded-lg border border-border bg-card shadow-sm ${isNested ? 'ml-4 mt-2 mb-3 border-l-2 border-l-primary/20 first:mt-0' : ''}`}>
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="flex w-full items-center justify-between gap-2 rounded-t-lg px-3 py-2 text-left text-sm font-medium transition-colors hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
      >
        <span className="capitalize">{name.replace(/_/g, ' ')}</span>
        <div className="flex items-center gap-1.5">
          <span className="text-xs text-muted-foreground">
            {entries.length} {entries.length === 1 ? 'field' : 'fields'}
          </span>
          <ChevronRight className={`h-4 w-4 text-muted-foreground transition-transform duration-200 ${open ? 'rotate-90' : ''}`} />
        </div>
      </button>
      {open && (
        <div className="overflow-hidden rounded-b-lg border-y border-border bg-muted/10">
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
                  <span className="break-all text-right font-mono text-xs">
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
                  <p className="text-sm text-foreground mt-0.5 ml-6 break-words [overflow-wrap:anywhere]">
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
