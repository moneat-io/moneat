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

import {createFileRoute, Link} from '@tanstack/react-router'
import {type ReactElement, useState} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {trackEvent} from '@/lib/analytics'
import {APP_OVERVIEW_SEARCH} from '@/lib/overview-route'
import {formatRelativeTime} from '@/lib/utils'
import {useToast} from '@/hooks/useToast'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {SectionCard} from '@/components/ui/section-card'
import {StatCard} from '@/components/ui/stat-card'
import {StatusDot} from '@/components/ui/status-dot'
import {EmptyState} from '@/components/ui/empty-state'
import {Checkbox} from '@/components/ui/checkbox'
import {Label} from '@/components/ui/label'
import {DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger} from '@/components/ui/dropdown-menu'
import {SpanWaterfall} from '@/components/SpanWaterfall'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {EmbeddedLogs} from '@/components/logs/EmbeddedLogs'
import {levelBadgeVariant, levelTone} from '@/lib/severity'
import {
    Activity,
    AlertCircle,
    ArrowUpRight,
    Battery,
    CheckCircle2,
    ChevronDown,
    ChevronLeft,
    ChevronRight,
    Circle,
    Clock3,
    Copy,
    DatabaseZap,
    EyeOff,
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
    Timer,
    Users,
    Zap,
} from 'lucide-react'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDateTime, formatTime} from '@/lib/date-format'

interface StackFrameData {
  function?: string
  filename?: string
  module?: string
  lineno?: number
  colno?: number
  context_line?: string
  pre_context?: string[]
  post_context?: string[]
  vars?: Record<string, unknown>
  in_app?: boolean
}

interface ExceptionValue {
  type?: string
  value?: string
  stacktrace?: { frames?: StackFrameData[] }
}

interface Breadcrumb {
  timestamp?: number
  category?: string
  type?: string
  message?: string
  data?: Record<string, unknown>
  level?: string
}

function formatDuration(ms: number) {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${ms.toFixed(1)}ms`
}

function parseContextEntries(rawContexts: unknown): [string, unknown][] {
  if (!rawContexts) return []

  try {
    const parsed = typeof rawContexts === 'string' ? JSON.parse(rawContexts) : rawContexts
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return []
    return Object.entries(parsed as Record<string, unknown>)
  } catch {
    return []
  }
}

function normalizeApmTraceId(value: unknown): string | null {
  if (typeof value === 'number') {
    if (!Number.isInteger(value) || value < 0) return null
    return String(value)
  }
  if (typeof value !== 'string') return null
  const normalized = value.trim()
  return /^\d+$/.test(normalized) ? normalized : null
}

export const Route = createFileRoute('/issues/$issueId')({
  validateSearch: (search: Record<string, unknown>) => ({
    projectId: typeof search.projectId === 'string' ? search.projectId : undefined,
  }),
  component: IssueDetailPage,
})

function IssueDetailPage() {
  const { issueId } = Route.useParams()
  const { projectId } = Route.useSearch()
  const queryClient = useQueryClient()
  const { timezone } = useTimezone()
  const { toast } = useToast()
  const scopedProjectId = projectId
  const [expandContextsByDefault, setExpandContextsByDefault] = useState(true)

  const { data: issue, isLoading } = useQuery({
    queryKey: ['issue', issueId, scopedProjectId],
    queryFn: () => api.getIssue(issueId, scopedProjectId),
  })

  const { data: events = [] } = useQuery({
    queryKey: ['issue-events', issueId, scopedProjectId],
    queryFn: () => api.getIssueEvents(issueId, 50, scopedProjectId),
  })

  const { data: relatedTransactions = [] } = useQuery({
    queryKey: ['issue-transactions', issueId, scopedProjectId],
    queryFn: () => api.getIssueTransactions(issueId, 20, scopedProjectId),
  })

  const { data: linkedReplays = [] } = useQuery({
    queryKey: ['issue-replays', issueId, scopedProjectId],
    queryFn: () => api.getReplaysForIssue(issueId, 10, scopedProjectId),
  })

  const primaryTransactionId = relatedTransactions[0]?.eventId
  const relatedTraceTransactions = relatedTransactions.filter((tx) => !!tx.traceId)

  const { data: primaryTransactionSpans } = useQuery({
    queryKey: ['issue-transaction-spans', primaryTransactionId],
    queryFn: () => api.getTransactionSpans(primaryTransactionId!),
    enabled: !!primaryTransactionId,
  })

  const statusMutation = useMutation({
    mutationFn: (status: string) => api.updateIssue(issueId, { status }, scopedProjectId),
    onSuccess: (_, status) => {
      trackEvent('Issue StatusChange', { source: 'detail', status })
      queryClient.invalidateQueries({ queryKey: ['issue', issueId, scopedProjectId] })
      const messages: Record<string, { title: string; description: string }> = {
        resolved: { title: 'Issue resolved', description: 'The issue has been marked as resolved.' },
        unresolved: { title: 'Issue unresolved', description: 'The issue has been marked as unresolved.' },
        ignored: { title: 'Issue ignored', description: 'The issue has been ignored.' },
        resolvedInNextRelease: { title: 'Resolve in next release', description: 'The issue will auto-resolve when a new release is deployed.' },
      }
      const msg = messages[status] ?? { title: 'Status updated', description: `Status set to ${status}.` }
      toast({ variant: 'success', ...msg })
    },
  })

  if (isLoading) {
    return (
      <div className="px-3 py-3 lg:px-5 lg:py-4">
        <div className="h-4 w-48 animate-pulse rounded bg-muted" />
        <div className="mt-3 h-28 animate-pulse rounded-lg border bg-card" />
        <div className="mt-3 grid grid-cols-2 gap-3 lg:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="h-[88px] animate-pulse rounded-lg border bg-card" />
          ))}
        </div>
        <div className="mt-3 grid grid-cols-1 gap-3 lg:grid-cols-5">
          <div className="h-72 animate-pulse rounded-lg border bg-card lg:col-span-3" />
          <div className="h-72 animate-pulse rounded-lg border bg-card lg:col-span-2" />
        </div>
      </div>
    )
  }
  if (!issue) {
    return (
      <div className="px-3 py-10 lg:px-5">
        <EmptyState
          icon={AlertCircle}
          title="Issue not found"
          description="This issue may have been deleted or merged, or you may not have access to it."
          action={
            <Button asChild variant="secondary" size="sm">
              <Link to="/issues">Back to issues</Link>
            </Button>
          }
        />
      </div>
    )
  }

  const latestEvent = events[0] || issue.latestEvent
  const latestEventTags = latestEvent?.tags ?? {}
  const contextEntries = parseContextEntries(latestEvent?.contexts)

  return (
    <div>
      <div className="px-3 py-3 lg:px-5 lg:py-4">
        {/* Breadcrumb nav */}
        <nav className="mb-3 flex items-center gap-2 text-sm">
          <Link
            to="/"
            search={APP_OVERVIEW_SEARCH}
            className="inline-flex items-center gap-1 text-muted-foreground hover:text-foreground transition-colors"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
            Dashboard
          </Link>
          <span className="text-muted-foreground/50">/</span>
          <Link
            to="/issues"
            className="text-muted-foreground hover:text-foreground transition-colors truncate max-w-[120px] sm:max-w-none"
          >
            {issue.projectName || 'Issues'}
          </Link>
          <span className="text-muted-foreground/50">/</span>
          <span className="text-foreground font-medium truncate max-w-[200px] sm:max-w-none" title={issue.title}>
            Issue
          </span>
        </nav>

        {/* Issue header */}
        <div className="mb-3 overflow-hidden rounded-lg border bg-card">
          <div
            className={`h-1 w-full ${
              levelTone(issue.level) === 'danger'
                ? 'bg-danger-solid'
                : levelTone(issue.level) === 'warning'
                  ? 'bg-warning-solid'
                  : levelTone(issue.level) === 'info'
                    ? 'bg-info-solid'
                    : 'bg-muted-foreground/40'
            }`}
            aria-hidden
          />
          <div className="flex flex-col gap-3 px-4 py-3.5 sm:flex-row sm:items-start sm:justify-between sm:px-5">
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-1.5 flex-wrap">
                <span className="inline-flex items-center gap-1.5">
                  <StatusDot tone={levelTone(issue.level)} pulse={levelTone(issue.level) === 'danger'} />
                  <Badge variant={levelBadgeVariant(issue.level)}>
                    {issue.level.toUpperCase()}
                  </Badge>
                </span>
                <Badge variant="neutral">{issue.platform}</Badge>
                {issue.status === 'resolved' && (
                  <Badge variant="success">
                    <CheckCircle2 className="h-3 w-3" />
                    Resolved
                  </Badge>
                )}
                {issue.status === 'ignored' && (
                  <Badge variant="neutral">
                    <EyeOff className="h-3 w-3" />
                    Ignored
                  </Badge>
                )}
                {issue.status === 'resolvedInNextRelease' && (
                  <Badge variant="info">
                    <Timer className="h-3 w-3" />
                    Resolves in next release
                  </Badge>
                )}
              </div>
              <h1 className="text-lg sm:text-xl font-semibold leading-tight mb-1 break-words [overflow-wrap:anywhere]">
                {issue.title}
              </h1>
              <p className="font-mono text-xs text-muted-foreground break-words [overflow-wrap:anywhere]">{issue.culprit}</p>
            </div>
            <div className="flex gap-2 flex-shrink-0">
              {issue.status === 'resolved' && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => statusMutation.mutate('unresolved')}
                  disabled={statusMutation.isPending}
                >
                  <AlertCircle className="h-4 w-4 mr-2" />
                  Unresolve
                </Button>
              )}
              {issue.status === 'ignored' && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => statusMutation.mutate('unresolved')}
                  disabled={statusMutation.isPending}
                >
                  <AlertCircle className="h-4 w-4 mr-2" />
                  Unignore
                </Button>
              )}
              {issue.status === 'resolvedInNextRelease' && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => statusMutation.mutate('unresolved')}
                  disabled={statusMutation.isPending}
                >
                  <AlertCircle className="h-4 w-4 mr-2" />
                  Unresolve
                </Button>
              )}
              {issue.status === 'unresolved' && (
                <div className="flex items-center">
                  <Button
                    size="sm"
                    onClick={() => statusMutation.mutate('resolved')}
                    disabled={statusMutation.isPending}
                    className="rounded-r-none"
                  >
                    <CheckCircle2 className="h-4 w-4 mr-2" />
                    Resolve
                  </Button>
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button size="sm" disabled={statusMutation.isPending} className="rounded-l-none border-l px-1.5">
                        <ChevronDown className="h-4 w-4" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                      <DropdownMenuItem onClick={() => statusMutation.mutate('resolvedInNextRelease')}>
                        <Timer className="h-4 w-4 mr-2" />
                        Resolve in Next Release
                      </DropdownMenuItem>
                      <DropdownMenuItem onClick={() => statusMutation.mutate('ignored')}>
                        <EyeOff className="h-4 w-4 mr-2" />
                        Ignore
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>
              )}
            </div>
          </div>

        </div>

        {/* Headline metrics */}
        <div className="mb-3 grid grid-cols-2 gap-3 lg:grid-cols-4">
          <StatCard label="Events" value={(issue.eventCount ?? 0).toLocaleString()} icon={Activity} tone="info" />
          <StatCard label="Users affected" value={(issue.userCount ?? 0).toLocaleString()} icon={Users} tone="accent" />
          <StatCard
            label="First seen"
            value={formatRelativeTime(issue.firstSeen)}
            icon={Clock3}
            tone="neutral"
            subtitle={formatDateTime(issue.firstSeen, timezone)}
          />
          <StatCard
            label="Last seen"
            value={formatRelativeTime(issue.lastSeen)}
            icon={Clock3}
            tone="neutral"
            subtitle={formatDateTime(issue.lastSeen, timezone)}
          />
        </div>

        {/* Two-column layout: main content + sidebar */}
        <div className="grid grid-cols-1 lg:grid-cols-5 gap-3">
          {/* Main column */}
          <div className="lg:col-span-3 space-y-3">
            {/* Exception */}
            {latestEvent && (
              <>
                <SectionCard title="Exception" icon={AlertCircle} iconTone="danger">
                  {latestEvent.exception ? (
                    <StackTraceViewer exception={latestEvent.exception} />
                  ) : (
                    <p className="text-muted-foreground text-sm">No stack trace available</p>
                  )}
                </SectionCard>

                {latestEvent.breadcrumbs && (
                  <SectionCard title="Breadcrumbs" icon={Navigation} iconTone="warning">
                    <BreadcrumbsViewer breadcrumbs={latestEvent.breadcrumbs} />
                  </SectionCard>
                )}

                <SectionCard title="Logs context" icon={TerminalSquare} iconTone="info" flushBody>
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
                </SectionCard>
              </>
            )}

            {/* Spans Preview - wide content, keep in main column */}
            {primaryTransactionSpans && (
              <SectionCard
                title="Spans preview"
                icon={DatabaseZap}
                iconTone="accent"
                count={primaryTransactionSpans.spans.length}
                bodyClassName="space-y-3"
              >
                <div className="max-h-[350px] overflow-auto">
                  <SpanWaterfall
                    transaction={primaryTransactionSpans.transaction}
                    spans={primaryTransactionSpans.spans}
                  />
                </div>
                {primaryTransactionSpans.transaction.traceId && (
                  <Link
                    to="/performance/traces/$traceId"
                    params={{ traceId: primaryTransactionSpans.transaction.traceId }}
                    className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
                  >
                    Open full trace
                    <ArrowUpRight className="h-3.5 w-3.5" />
                  </Link>
                )}
              </SectionCard>
            )}
          </div>

          {/* Sidebar */}
          <div className="lg:col-span-2 space-y-3">
            {/* Event Details */}
            {latestEvent && (
              <SectionCard title="Event details" icon={Activity} iconTone="neutral">
                  <div className="space-y-1.5 text-sm">
                    <div className="flex items-start justify-between gap-2">
                      <span className="text-muted-foreground flex-shrink-0">Event ID</span>
                      <span className="font-mono text-xs text-right min-w-0 max-w-[65%] break-all">{latestEvent.eventId}</span>
                    </div>
                    <div className="flex justify-between gap-2">
                      <span className="text-muted-foreground flex-shrink-0">Timestamp</span>
                      <span className="text-xs">{formatDateTime(latestEvent.timestamp, timezone)}</span>
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
              </SectionCard>
            )}

            {/* Tags & Context */}
            {latestEvent && (Object.keys(latestEventTags).length > 0 || contextEntries.length > 0) && (
              <SectionCard title="Tags & context" icon={Info} iconTone="info" bodyClassName="space-y-3">
                  <div>
                    <div className="mb-2 flex items-center justify-between">
                      <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
                        <Tag className="h-3 w-3" />
                        Tags
                        {Object.keys(latestEventTags).length > 0 && (
                          <Badge variant="secondary" className="ml-1 text-[10px] px-1 py-0">{Object.keys(latestEventTags).length}</Badge>
                        )}
                      </div>
                      {Object.keys(latestEventTags).length > 0 && (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => {
                            navigator.clipboard.writeText(JSON.stringify(latestEventTags, null, 2))
                            toast({ title: 'Copied', description: 'Tags copied to clipboard.' })
                          }}
                          className="h-6 gap-1 px-2"
                        >
                          <Copy className="h-3 w-3" />
                          <span className="text-[11px]">Copy</span>
                        </Button>
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

                  {/* Cross-link to APM trace if trace_id exists in contexts */}
                  {(() => {
                    const traceCtx = contextEntries.find(([key]) => key === 'trace')
                    const traceId = traceCtx
                      ? normalizeApmTraceId(
                          (traceCtx[1] as Record<string, unknown>)?.trace_id
                        )
                      : null
                    if (!traceId) return null
                    return (
                      <div className="rounded-lg border border-info-border bg-info-bg px-4 py-3 flex items-center justify-between gap-3">
                        <div className="flex items-center gap-2 text-sm">
                          <Activity className="h-4 w-4 text-info-fg" />
                          <span className="text-muted-foreground">
                            This error has an associated APM trace
                          </span>
                        </div>
                        <Link
                          to="/performance/traces/$traceId"
                          params={{ traceId }}
                          className="text-sm text-primary hover:underline font-medium flex items-center gap-1"
                        >
                          View trace
                          <ArrowUpRight className="h-3.5 w-3.5" />
                        </Link>
                      </div>
                    )
                  })()}

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
                          <>
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => {
                                const contextsObj = Object.fromEntries(contextEntries)
                                navigator.clipboard.writeText(JSON.stringify(contextsObj, null, 2))
                                toast({ title: 'Copied', description: 'Contexts copied to clipboard.' })
                              }}
                              className="h-6 gap-1 px-2"
                            >
                              <Copy className="h-3 w-3" />
                              <span className="text-[11px]">Copy</span>
                            </Button>
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
                          </>
                        )}
                      </div>
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
              </SectionCard>
            )}

            {/* Related Traces */}
            {relatedTraceTransactions.length > 0 && (
              <SectionCard title="Traces" icon={Activity} iconTone="accent" count={relatedTraceTransactions.length}>
                  <div className="space-y-1.5 max-h-[300px] overflow-auto">
                    {relatedTraceTransactions.map((tx) => (
                      <Link
                        key={tx.eventId}
                        to="/performance/traces/$traceId"
                        params={{ traceId: tx.traceId ?? '' }}
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
              </SectionCard>
            )}

            {/* Session Replays */}
            {linkedReplays.length > 0 && (
              <SectionCard title="Replays" icon={Play} iconTone="success" count={linkedReplays.length}>
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
                              <Badge variant="danger" className="text-[10px] px-1.5 py-0 flex items-center gap-0.5">
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
              </SectionCard>
            )}

            {/* Recent Events */}
            {events.length > 1 && (
              <SectionCard title="Recent events" icon={Activity} iconTone="accent" count={events.length}>
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
              </SectionCard>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

function formatAsStackTrace(exception: unknown): ReactElement[] {
  try {
    const parsed = typeof exception === 'string' ? JSON.parse(exception) : exception
    const exceptions = parsed.values || [parsed]
    
    return exceptions.map((value: ExceptionValue, exIdx: number) => {
      const frames = value.stacktrace?.frames ? [...value.stacktrace.frames].reverse() : []
      
      return (
        <div key={exIdx}>
          <div className="text-danger-fg font-semibold mb-1 break-words [overflow-wrap:anywhere]">
            {value.type}: {value.value}
          </div>
          {frames.map((frame: StackFrameData, idx: number) => {
            const method = frame.function || '<unknown>'
            const file = frame.filename || frame.module || '<unknown>'
            const line = frame.lineno ? `:${frame.lineno}` : ''
            const module = frame.module ? `${frame.module}.` : ''
            
            return (
              <div key={idx} className="pl-4 break-words [overflow-wrap:anywhere]">
                <span className="text-muted-foreground">at </span>
                <span className="text-info-fg [overflow-wrap:anywhere]">{module}{method}</span>
                <span className="text-muted-foreground">(</span>
                <span className="text-warning-fg break-all">{file}</span>
                {line && <span className="text-success-fg">{line}</span>}
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
  } catch {
    const text = typeof exception === 'string' ? exception : JSON.stringify(exception, null, 2)
    return [<div key="error">{text}</div>]
  }
}

function formatAsStackTraceText(exception: unknown): string {
  try {
    const parsed = typeof exception === 'string' ? JSON.parse(exception) : exception
    const exceptions = parsed.values || [parsed]
    
    return exceptions.map((value: ExceptionValue, exIdx: number) => {
      const frames = value.stacktrace?.frames ? [...value.stacktrace.frames].reverse() : []
      let text = `${value.type}: ${value.value}\n`
      
      frames.forEach((frame: StackFrameData) => {
        const method = frame.function || '<unknown>'
        const file = frame.filename || frame.module || '<unknown>'
        const line = frame.lineno ? `:${frame.lineno}` : ''
        const module = frame.module ? `${frame.module}.` : ''
        text += `  at ${module}${method}(${file}${line})\n`
      })
      
      if (exIdx < exceptions.length - 1) {
        text += '\nCaused by:\n'
      }
      
      return text
    }).join('\n')
  } catch {
    return typeof exception === 'string' ? exception : JSON.stringify(exception, null, 2)
  }
}

function StackTraceViewer({ exception }: { exception: string }) {
  const STACK_TRACE_VIEW_KEY = 'issue-stack-trace-view-preference'
  const savedView = localStorage.getItem(STACK_TRACE_VIEW_KEY) || 'formatted'
  const { toast } = useToast()
  
  const rawContent = typeof exception === 'string' ? exception : JSON.stringify(exception, null, 2)
  
  const handleValueChange = (value: string) => {
    localStorage.setItem(STACK_TRACE_VIEW_KEY, value)
  }
  
  const copyRawToClipboard = () => {
    const stackTraceText = formatAsStackTraceText(exception)
    navigator.clipboard.writeText(stackTraceText)
    toast({ title: 'Copied', description: 'Stack trace copied to clipboard.' })
  }

  let parsed: { values?: ExceptionValue[] } | null = null
  let parseError = false
  try {
    parsed = typeof exception === 'string' ? JSON.parse(exception) : exception
  } catch (e) {
    console.error('Failed to parse stack trace:', e)
    parseError = true
  }

  if (parseError || !parsed) {
    return (
      <div className="space-y-2">
        <p className="text-sm text-muted-foreground mb-2">Raw stack trace:</p>
        <pre className="text-xs bg-muted p-4 rounded overflow-auto max-h-96 whitespace-pre-wrap break-words font-mono">
          {rawContent}
        </pre>
      </div>
    )
  }

  const exceptions = parsed.values || [parsed]
  const stackTraceText = formatAsStackTrace(exception)

  return (
    <Tabs defaultValue={savedView} onValueChange={handleValueChange} className="w-full">
      <div className="flex items-center justify-between">
        <TabsList>
          <TabsTrigger value="formatted">Formatted</TabsTrigger>
          <TabsTrigger value="raw">Raw</TabsTrigger>
        </TabsList>
        <Button 
          variant="outline" 
          size="sm" 
          onClick={copyRawToClipboard}
          className="gap-1.5"
        >
          <Copy className="h-3.5 w-3.5" />
          Copy Raw
        </Button>
      </div>
      
      <TabsContent value="formatted" className="mt-4">
        <div className="space-y-6">
          {(exceptions as ExceptionValue[]).map((value: ExceptionValue, idx: number) => (
            <div key={idx}>
              <div className="font-semibold text-danger-fg mb-4 text-lg break-words [overflow-wrap:anywhere]">
                {value.type}: {value.value}
              </div>
              {value.stacktrace?.frames && value.stacktrace.frames.length > 0 ? (
                <div className="space-y-1">
                  {value.stacktrace.frames.map((frame: StackFrameData, frameIdx: number) => (
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
}

function StackFrame({ frame }: { frame: StackFrameData }) {
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
              <div className="bg-danger-bg text-danger-fg px-2 py-0.5 -ml-3 pl-3 font-semibold leading-relaxed border-l-2 border-danger-solid break-words [overflow-wrap:anywhere]">
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
                {Object.entries(frame.vars!).map(([key, val]) => (
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
    <div className={`rounded-lg border border-border bg-card ${isNested ? 'ml-4 mt-2 mb-3 border-l-2 border-l-primary/20 first:mt-0' : ''}`}>
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

// Breadcrumb categories are differentiated with the categorical chart palette
// (style guide); badges stay neutral so the timeline dot/icon carries the color.
type BreadcrumbColors = { border: string; icon: string; badge: string; dot: string; line: string }
function getBreadcrumbCategoryColor(category: string): BreadcrumbColors {
  const make = (icon: string, dot: string): BreadcrumbColors => ({
    border: 'border-border',
    icon,
    badge: 'bg-muted text-muted-foreground',
    dot,
    line: 'bg-border',
  })
  const cat = (category || '').toLowerCase()
  if (cat.includes('lifecycle')) return make('text-chart-4', 'bg-chart-4 ring-[hsl(var(--chart-4)/0.2)]')
  if (cat.includes('click') || cat.includes('touch')) return make('text-chart-1', 'bg-chart-1 ring-[hsl(var(--chart-1)/0.2)]')
  if (cat.includes('navigation')) return make('text-chart-2', 'bg-chart-2 ring-[hsl(var(--chart-2)/0.2)]')
  if (cat.includes('action')) return make('text-chart-5', 'bg-chart-5 ring-[hsl(var(--chart-5)/0.2)]')
  if (cat.includes('http') || cat.includes('network')) return make('text-chart-3', 'bg-chart-3 ring-[hsl(var(--chart-3)/0.2)]')
  if (cat.includes('device')) return make('text-chart-6', 'bg-chart-6 ring-[hsl(var(--chart-6)/0.2)]')
  if (cat.includes('message') || cat.includes('log')) return make('text-chart-7', 'bg-chart-7 ring-[hsl(var(--chart-7)/0.2)]')
  return make('text-muted-foreground', 'bg-muted-foreground ring-[hsl(var(--muted-foreground)/0.2)]')
}

// Helper to get icon for breadcrumb category
function getBreadcrumbIcon(category: string, data?: Record<string, unknown>) {
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
    if (data && ((data.action as string)?.includes('BATTERY') || data.level !== undefined)) {
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
function formatBreadcrumbData(crumb: Breadcrumb): string {
  if (crumb.message) {
    return crumb.message
  }
  
  if (!crumb.data) {
    return ''
  }
  
  const data = crumb.data as Record<string, string | number | boolean | undefined>
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
    const viewClass = (data['view.class'] as string)?.split('.').pop() || ''
    const viewId = data['view.id'] || ''
    return `Clicked ${viewClass}${viewId ? ` (${viewId})` : ''}`
  }
  
  // Device events
  if (category.includes('device.event')) {
    if ((data.action as string)?.includes('BATTERY')) {
      const charging = data.charging ? '⚡' : ''
      return `Battery ${data.level}%${charging ? ' (charging)' : ''}`
    }
    return (data.action as string) || 'Device event'
  }
  
  // Navigation
  if (category.includes('navigation')) {
    return `${data.from || ''} → ${data.to || ''}`
  }
  
  // Default: show key data points
  const parts: string[] = []
  if (data.url) parts.push(String(data.url))
  if (data.status_code) parts.push(`${data.status_code}`)
  if (data.method) parts.push(String(data.method))
  
  return parts.length > 0 ? parts.join(' ') : JSON.stringify(data)
}

function BreadcrumbsViewer({ breadcrumbs }: { breadcrumbs: string }) {
  const { timezone } = useTimezone()
  let crumbs: Breadcrumb[] = []
  let parseError = false
  try {
    const parsed = JSON.parse(breadcrumbs)
    crumbs = Array.isArray(parsed) ? parsed : parsed.values || []
  } catch {
    parseError = true
  }

  if (parseError) {
    return <pre className="text-xs bg-muted p-4 rounded overflow-auto">{breadcrumbs}</pre>
  }

  return (
    <div className="relative">
      {crumbs.map((crumb: Breadcrumb, idx: number) => {
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
                  {timestamp ? formatTime(timestamp, timezone) : '--:--:--'}
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
}
