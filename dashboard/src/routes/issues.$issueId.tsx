import { createFileRoute, redirect, Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { formatRelativeTime } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { CheckCircle2, AlertCircle, ChevronLeft } from 'lucide-react'

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

  const { data: issue, isLoading } = useQuery({
    queryKey: ['issue', issueId],
    queryFn: () => api.getIssue(issueId),
  })

  const { data: events = [] } = useQuery({
    queryKey: ['issue-events', issueId],
    queryFn: () => api.getIssueEvents(issueId),
  })

  const resolveMutation = useMutation({
    mutationFn: (status: string) => api.updateIssue(issueId, { status }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['issue', issueId] })
    },
  })

  if (isLoading) return <div className="p-8">Loading...</div>
  if (!issue) return <div className="p-8">Issue not found</div>

  const latestEvent = events[0] || issue.latestEvent

  return (
    <div className="min-h-screen bg-background">
      <nav className="border-b bg-card px-6 py-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-4">
            <Button variant="ghost" size="sm" asChild>
              <Link to="/" className="flex items-center gap-1">
                <ChevronLeft className="h-4 w-4" />
                Back to Issues
              </Link>
            </Button>
            <h1 className="text-2xl font-bold">Moneat</h1>
          </div>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              api.logout()
              window.location.href = '/login'
            }}
          >
            Logout
          </Button>
        </div>
      </nav>

      <div className="p-6 max-w-7xl mx-auto">
        {/* Issue Header */}
        <div className="mb-6 bg-card rounded-lg border p-6">
          <div className="flex items-start justify-between mb-4">
            <div className="flex-1">
              <div className="flex items-center gap-2 mb-2">
                <Badge variant={issue.level === 'error' ? 'destructive' : 'secondary'}>
                  {issue.level}
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
            <div>
              <div className="text-xs text-muted-foreground uppercase mb-1">Events</div>
              <div className="text-2xl font-bold">{issue.eventCount}</div>
            </div>
            <div>
              <div className="text-xs text-muted-foreground uppercase mb-1">Users</div>
              <div className="text-2xl font-bold">{issue.userCount}</div>
            </div>
            <div>
              <div className="text-xs text-muted-foreground uppercase mb-1">First Seen</div>
              <div className="text-sm">{formatRelativeTime(issue.firstSeen)}</div>
            </div>
            <div>
              <div className="text-xs text-muted-foreground uppercase mb-1">Last Seen</div>
              <div className="text-sm">{formatRelativeTime(issue.lastSeen)}</div>
            </div>
          </div>
        </div>

        {/* Latest Event Details */}
        {latestEvent && (
          <>
            <Card className="mb-6">
              <CardHeader>
                <CardTitle>Exception</CardTitle>
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
              <Card className="mb-6">
                <CardHeader>
                  <CardTitle>Breadcrumbs</CardTitle>
                </CardHeader>
                <CardContent>
                  <BreadcrumbsViewer breadcrumbs={latestEvent.breadcrumbs} />
                </CardContent>
              </Card>
            )}

            <div className="grid grid-cols-2 gap-6">
              <Card>
                <CardHeader>
                  <CardTitle>Tags</CardTitle>
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

              <Card>
                <CardHeader>
                  <CardTitle>Event Details</CardTitle>
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

        {/* Event Timeline */}
        {events.length > 1 && (
          <Card className="mt-6">
            <CardHeader>
              <CardTitle>Recent Events ({events.length})</CardTitle>
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

function StackTraceViewer({ exception }: { exception: string }) {
  try {
    const parsed = JSON.parse(exception)
    
    return (
      <div className="space-y-2">
        {parsed.values?.map((value: any, idx: number) => (
          <div key={idx} className="mb-4">
            <div className="font-semibold text-destructive mb-2">
              {value.type}: {value.value}
            </div>
            {value.stacktrace?.frames?.map((frame: any, frameIdx: number) => (
              <div
                key={frameIdx}
                className="font-mono text-xs bg-muted p-3 rounded border mb-1"
              >
                <div className="flex items-start justify-between mb-1">
                  <div className="font-semibold">
                    {frame.function || '<anonymous>'}
                  </div>
                  <div className="text-muted-foreground">
                    {frame.filename}:{frame.lineno}
                  </div>
                </div>
                {frame.context_line && (
                  <div className="mt-2">
                    {frame.pre_context?.map((line: string, i: number) => (
                      <div key={i} className="text-muted-foreground/70">
                        {line}
                      </div>
                    ))}
                    <div className="bg-destructive/10 text-destructive px-1">
                      → {frame.context_line}
                    </div>
                    {frame.post_context?.map((line: string, i: number) => (
                      <div key={i} className="text-muted-foreground/70">
                        {line}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        ))}
      </div>
    )
  } catch (e) {
    return <pre className="text-xs bg-muted p-4 rounded overflow-auto">{exception}</pre>
  }
}

function BreadcrumbsViewer({ breadcrumbs }: { breadcrumbs: string }) {
  try {
    const parsed = JSON.parse(breadcrumbs)
    const crumbs = Array.isArray(parsed) ? parsed : parsed.values || []

    return (
      <div className="space-y-2">
        {crumbs.map((crumb: any, idx: number) => (
          <div key={idx} className="flex gap-4 text-sm border-l-2 border-muted-foreground/30 pl-4 py-2">
            <div className="text-muted-foreground font-mono text-xs w-32">
              {new Date(crumb.timestamp * 1000).toLocaleTimeString()}
            </div>
            <div className="flex-1">
              <Badge variant="outline" className="mr-2">
                {crumb.category || crumb.type}
              </Badge>
              <span>{crumb.message || JSON.stringify(crumb.data)}</span>
            </div>
          </div>
        ))}
      </div>
    )
  } catch (e) {
    return <pre className="text-xs bg-muted p-4 rounded overflow-auto">{breadcrumbs}</pre>
  }
}
