import { createFileRoute, redirect, Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { formatRelativeTime } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { 
  CheckCircle2, 
  AlertCircle, 
  ChevronLeft,
  Activity,
  MousePointer,
  Navigation,
  Zap,
  Info,
  MessageSquare,
  Globe,
  Smartphone,
  Battery,
  Circle,
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
      <div className="p-6 max-w-7xl mx-auto">
        {/* Breadcrumbs */}
        <div className="mb-4">
          <Link to="/" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors">
            <ChevronLeft className="h-4 w-4" />
            Back to Dashboard
          </Link>
        </div>

        {/* Issue Header */}
        <div className="mb-6 bg-card rounded-lg border p-6">
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
      <div className="space-y-1">
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
          
          return (
            <div 
              key={idx} 
              className="flex items-start gap-3 p-3 rounded-lg border bg-card hover:bg-accent/50 transition-colors"
            >
              {/* Icon */}
              <div className="text-muted-foreground mt-0.5">
                {getBreadcrumbIcon(category, crumb.data)}
              </div>
              
              {/* Time */}
              <div className="text-muted-foreground font-mono text-xs w-20 flex-shrink-0 mt-0.5">
                {timestamp ? new Date(timestamp).toLocaleTimeString() : '--:--:--'}
              </div>
              
              {/* Content */}
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <Badge variant="secondary" className="text-xs">
                    {category}
                  </Badge>
                </div>
                {formattedData && (
                  <div className="text-sm text-foreground">
                    {formattedData}
                  </div>
                )}
              </div>
              
              {/* Connector for timeline */}
              {idx < crumbs.length - 1 && (
                <div className="absolute left-[2.15rem] mt-12 h-3 w-px bg-border" />
              )}
            </div>
          )
        })}
      </div>
    )
  } catch (e) {
    return <pre className="text-xs bg-muted p-4 rounded overflow-auto">{breadcrumbs}</pre>
  }
}
