import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {LandingPage} from '@/components/landing/landing-page'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, formatErrorForLogging} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {formatRelativeTime} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from '@/components/ui/select'
import {Card} from '@/components/ui/card'
import {Checkbox} from '@/components/ui/checkbox'
import {
  Activity,
  AlertCircle,
  CheckCircle2,
  Clock,
  FolderKanban,
  Plus,
  Search,
  Settings,
  TrendingUp,
  Users
} from 'lucide-react'
import {useState} from 'react'
import {StatsCard} from '@/components/charts/stats-card'
import {EventsChart} from '@/components/charts/events-chart'
import {useToast} from '@/hooks/use-toast'

// Helper function to get level color for badges
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

// Get color for level left-border indicator
function getLevelBorderColor(level: string): string {
  switch (level.toLowerCase()) {
    case 'fatal': return 'border-l-red-800'
    case 'error': return 'border-l-red-500'
    case 'warning': return 'border-l-amber-500'
    case 'info': return 'border-l-blue-500'
    case 'debug': return 'border-l-gray-400'
    default: return 'border-l-border'
  }
}

// Get freshness color based on how recently the last event occurred
function getLastSeenColor(lastSeen: string): string {
  const diff = Date.now() - new Date(lastSeen).getTime()
  const hours = diff / (1000 * 60 * 60)
  if (hours < 1) return 'text-red-500 dark:text-red-400'
  if (hours < 24) return 'text-amber-600 dark:text-amber-400'
  return 'text-muted-foreground'
}

// Check if an issue is new (first seen within last 24 hours)
function isNewIssue(firstSeen: string): boolean {
  const diff = Date.now() - new Date(firstSeen).getTime()
  return diff < 24 * 60 * 60 * 1000
}

// Format large numbers compactly
function formatCount(n: number): string {
  if (n >= 100000) return `${(n / 1000).toFixed(0)}k`
  if (n >= 10000) return `${(n / 1000).toFixed(1)}k`
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`
  return n.toLocaleString()
}

function getIssueDisplayTitle(issue: { title: string; culprit: string }): string {
  const title = issue.title?.trim() ?? ''
  const culprit = issue.culprit?.trim() ?? ''

  if (!title) return culprit || 'Unknown error'
  if (!culprit) return title

  const normalizedTitle = title.toLowerCase()
  const normalizedCulprit = culprit.toLowerCase()
  if (
    normalizedTitle.startsWith(`${normalizedCulprit}:`) ||
    normalizedTitle.startsWith(`${normalizedCulprit} `)
  ) {
    return title
  }

  return `${culprit}: ${title}`
}

// Simple sparkline component
function EventSparkline({ eventCount }: { eventCount: number }) {
  // Generate a simple frequency visualization
  const bars = Math.min(Math.ceil(eventCount / 10), 10)
  const heights = Array.from({ length: 10 }, (_, i) => {
    if (i < bars) {
      return 40 + Math.random() * 60 // 40-100% height for active bars
    }
    return 0
  })

  return (
    <div className="flex items-end gap-0.5 h-8 w-20">
      {heights.map((height, i) => (
        <div
          key={i}
          className="flex-1 bg-primary/60 rounded-sm transition-all"
          style={{ height: `${height}%` }}
        />
      ))}
    </div>
  )
}

export const Route = createFileRoute('/')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      return
    }
    try {
      const user = await api.getCurrentUser()
      if (!user.onboardingCompleted) {
        throw redirect({ to: '/onboarding' })
      }
    } catch (error) {
      if (error instanceof Error && 'redirect' in error) throw error
      console.error('Failed to fetch user:', formatErrorForLogging(error))
    }
  },
  component: IndexPage,
})

function IndexPage() {
  if (!api.isAuthenticated()) {
    return <LandingPage />
  }
  return <DashboardPage />
}

function DashboardPage() {
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('unresolved')
  const [selectedIssues, setSelectedIssues] = useState<Set<string>>(new Set())
  const { selectedProjectId, setSelectedProjectId } = useProject()
  const { toast } = useToast()
  const queryClient = useQueryClient()

  const { data: projects, isLoading } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })
  const hasProjects = (projects?.length ?? 0) > 0

  const projectId = selectedProjectId || projects?.[0]?.id
  const currentProject = projects?.find(p => p.id === projectId)

  // Auto-set the first project if none is selected
  if (!selectedProjectId && projects && projects.length > 0 && projects[0]?.id) {
    setSelectedProjectId(projects[0].id)
  }

  const { data: issues = [] } = useQuery({
    queryKey: ['issues', projectId, statusFilter],
    queryFn: () => (projectId ? api.getIssues(projectId) : []),
    enabled: !!projectId,
  })

  const { data: stats } = useQuery({
    queryKey: ['stats', projectId],
    queryFn: () => (projectId ? api.getProjectStats(projectId, '24h') : null),
    enabled: !!projectId,
  })

  const resolveMutation = useMutation({
    mutationFn: async (issueIds: string[]) => {
      await Promise.all(issueIds.map(id => api.updateIssue(id, { status: 'resolved' })))
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['issues', projectId] })
      queryClient.invalidateQueries({ queryKey: ['stats', projectId] })
      toast({
        title: 'Success',
        description: `${selectedIssues.size} issue${selectedIssues.size === 1 ? '' : 's'} resolved`,
      })
      setSelectedIssues(new Set())
    },
    onError: () => {
      toast({
        title: 'Error',
        description: 'Failed to resolve issues',
        variant: 'destructive',
      })
    },
  })

  const handleToggleIssue = (issueId: string) => {
    const newSelected = new Set(selectedIssues)
    if (newSelected.has(issueId)) {
      newSelected.delete(issueId)
    } else {
      newSelected.add(issueId)
    }
    setSelectedIssues(newSelected)
  }

  const handleToggleAll = () => {
    if (selectedIssues.size === filteredIssues.length) {
      setSelectedIssues(new Set())
    } else {
      setSelectedIssues(new Set(filteredIssues.map(issue => issue.id)))
    }
  }

  const handleResolveSelected = () => {
    resolveMutation.mutate(Array.from(selectedIssues))
  }

  if (isLoading) return <div className="p-8">Loading...</div>

  const filteredIssues = issues.filter((issue) => {
    const matchesSearch =
      searchQuery === '' ||
      issue.title?.toLowerCase().includes(searchQuery.toLowerCase()) ||
      issue.culprit?.toLowerCase().includes(searchQuery.toLowerCase())
    const matchesStatus = statusFilter === 'all' || issue.status === statusFilter
    return matchesSearch && matchesStatus
  })

  return (
    <div className="min-h-screen">
      <div className="px-6 py-4">
        {/* Header */}
        <div className="mb-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div>
              <h2 className="text-2xl font-bold tracking-tight">Dashboard</h2>
              {currentProject && (
                <p className="text-sm text-muted-foreground">{currentProject.name}</p>
              )}
            </div>
            <span className="hidden sm:inline-flex h-2 w-2 rounded-full bg-emerald-500 animate-pulse" aria-hidden />
          </div>
          {hasProjects && (
            <div className="flex items-center gap-2">
              {projectId && (
                <Link to="/projects/$projectId/settings" params={{ projectId: String(projectId) }}>
                  <Button
                    size="icon"
                    variant="outline"
                    className="border-primary/30 hover:bg-primary/10 hover:border-primary/50"
                    aria-label="Project settings"
                  >
                    <Settings className="h-4 w-4" />
                  </Button>
                </Link>
              )}
              <Link to="/projects">
                <Button size="sm" variant="outline" className="border-primary/30 hover:bg-primary/10 hover:border-primary/50">
                  <Plus className="h-4 w-4" />
                  New Project
                </Button>
              </Link>
            </div>
          )}
        </div>

        {!hasProjects ? (
          <Card className="p-12 text-center border-primary/20 bg-gradient-to-b from-card to-primary/5">
            <div className="max-w-md mx-auto space-y-4">
              <div className="flex justify-center">
                <div className="rounded-full bg-violet-500/15 p-4 ring-2 ring-violet-500/20">
                  <FolderKanban className="h-10 w-10 text-violet-600 dark:text-violet-400" />
                </div>
              </div>
              <div>
                <h3 className="text-lg font-semibold mb-2">No projects yet</h3>
                <p className="text-muted-foreground mb-4">
                  Create your first project to start tracking errors and monitoring your applications.
                </p>
              </div>
              <Link to="/projects" className="flex justify-center">
                <Button size="lg" className="w-full max-w-sm sm:w-auto sm:mx-auto">
                  <Plus className="h-4 w-4" />
                  Create Your First Project
                </Button>
              </Link>
            </div>
          </Card>
        ) : (
          <>
            {/* Overview Stats & Chart — always visible, compact side-by-side layout */}
            {stats && (
              <div className="mb-4 grid grid-cols-1 xl:grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-3">
                <div className="grid grid-cols-2 gap-3">
                  <StatsCard
                    title="Events (24h)"
                    value={formatCount(stats.totalEvents)}
                    icon={Activity}
                    accent="blue"
                  />
                  <StatsCard
                    title="Unresolved"
                    value={formatCount(stats.unresolvedIssues)}
                    icon={AlertCircle}
                    accent="amber"
                    valueColor={stats.unresolvedIssues > 0 ? 'text-amber-600 dark:text-amber-400' : undefined}
                  />
                  <StatsCard
                    title="Users (24h)"
                    value={formatCount(stats.affectedUsers)}
                    icon={Users}
                    accent="emerald"
                  />
                  <StatsCard
                    title="Total Issues"
                    value={formatCount(stats.totalIssues)}
                    icon={TrendingUp}
                    accent="violet"
                  />
                </div>
                {stats.eventsTimeline.length > 0 && (
                  <div className="min-h-[160px]">
                    <EventsChart
                      data={stats.eventsTimeline}
                      title="Events in Last 24 Hours"
                      fillHeight
                    />
                  </div>
                )}
              </div>
            )}

            {/* Toolbar */}
            <div className="mb-3 flex gap-3 items-center">
              {filteredIssues.length > 0 && (
                <div className="flex items-center gap-2">
                  <Checkbox
                    checked={selectedIssues.size === filteredIssues.length && filteredIssues.length > 0}
                    onCheckedChange={handleToggleAll}
                    aria-label="Select all issues"
                  />
                  <span className="text-sm text-muted-foreground whitespace-nowrap hidden sm:inline">Select all</span>
                </div>
              )}
              
              {selectedIssues.size > 0 && (
                <div className="flex items-center gap-2 bg-primary/10 border border-primary/20 rounded-lg px-3 py-1.5">
                  <CheckCircle2 className="h-4 w-4 text-primary" />
                  <span className="text-sm font-medium whitespace-nowrap">
                    {selectedIssues.size} selected
                  </span>
                  <Button
                    onClick={handleResolveSelected}
                    disabled={resolveMutation.isPending}
                    size="sm"
                    className="bg-green-600 hover:bg-green-700 h-7 ml-2"
                  >
                    {resolveMutation.isPending ? 'Resolving...' : 'Resolve'}
                  </Button>
                </div>
              )}

              <div className="relative flex-1">
                <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <Input
                  placeholder="Search issues..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="pl-10"
                />
              </div>
              <Select value={statusFilter} onValueChange={setStatusFilter}>
                <SelectTrigger className="w-[180px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">All Issues</SelectItem>
                  <SelectItem value="unresolved">Unresolved</SelectItem>
                  <SelectItem value="resolved">Resolved</SelectItem>
                </SelectContent>
              </Select>
              <span className="text-sm text-muted-foreground whitespace-nowrap hidden lg:inline">
                {filteredIssues.length} result{filteredIssues.length !== 1 ? 's' : ''}
              </span>
            </div>

            {filteredIssues.length === 0 ? (
              <Card className="p-12 text-center border-blue-500/20 bg-gradient-to-b from-card to-blue-500/5">
                <div className="max-w-md mx-auto space-y-4">
                  <div className="flex justify-center">
                    <div className="rounded-full bg-blue-500/10 p-4">
                      {searchQuery || statusFilter !== 'unresolved' ? (
                        <Search className="h-10 w-10 text-blue-600 dark:text-blue-400" />
                      ) : (
                        <AlertCircle className="h-10 w-10 text-blue-600 dark:text-blue-400" />
                      )}
                    </div>
                  </div>
                  <div>
                    <h3 className="text-lg font-semibold mb-2">
                      {searchQuery || statusFilter !== 'unresolved'
                        ? 'No issues match your filters'
                        : 'No issues yet'}
                    </h3>
                    <p className="text-muted-foreground">
                      {searchQuery || statusFilter !== 'unresolved'
                        ? 'Try adjusting your search or filters.'
                        : 'Start sending errors to this project to see them tracked here. Visit the setup guide to integrate your application.'}
                    </p>
                  </div>
                </div>
              </Card>
            ) : (
              <div className="rounded-lg border border-border/60 bg-card overflow-hidden">
                {/* Table header */}
                <div className="hidden md:flex items-center gap-3 py-2 px-4 bg-muted/40 border-b border-border/40 text-[11px] font-medium text-muted-foreground uppercase tracking-wider select-none">
                  <div className="w-5 shrink-0" />
                  <div className="w-14 shrink-0">Level</div>
                  <div className="flex-1 min-w-0">Issue</div>
                  <div className="hidden lg:block w-20 shrink-0">Platform</div>
                  <div className="hidden lg:block w-20 shrink-0 text-center">Trend</div>
                  <div className="w-[55px] shrink-0 text-right">Events</div>
                  <div className="hidden sm:block w-[45px] shrink-0 text-right">Users</div>
                  <div className="w-20 shrink-0 text-right">Last Seen</div>
                </div>
                {/* Issue rows */}
                <div className="divide-y divide-border/40">
                  {filteredIssues.map((issue) => (
                    <div
                      key={issue.id}
                      className={`hover:bg-accent/40 transition border-l-[3px] ${getLevelBorderColor(issue.level)}`}
                    >
                      <div className="flex items-center gap-3 py-2.5 px-4">
                        <Checkbox
                          checked={selectedIssues.has(issue.id)}
                          onCheckedChange={() => handleToggleIssue(issue.id)}
                          onClick={(e) => e.stopPropagation()}
                          aria-label={`Select ${issue.title}`}
                          className="shrink-0"
                        />
                        <Link
                          to="/issues/$issueId"
                          params={{ issueId: issue.id }}
                          className="flex-1 flex items-center gap-3 min-w-0"
                        >
                          <Badge className={`${getLevelColor(issue.level)} shrink-0 text-[11px] px-1.5 py-0 w-14 justify-center`}>
                            {issue.level.toUpperCase()}
                          </Badge>
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2 min-w-0">
                              <span className="font-semibold truncate flex-1 min-w-0" title={getIssueDisplayTitle(issue)}>
                                {getIssueDisplayTitle(issue)}
                              </span>
                              <div className="flex items-center gap-2 shrink-0">
                                {isNewIssue(issue.firstSeen) && (
                                  <span className="text-[10px] font-bold text-emerald-500 bg-emerald-500/10 px-1.5 py-0.5 rounded uppercase">
                                    New
                                  </span>
                                )}
                                {issue.status === 'resolved' && (
                                  <Badge variant="default" className="bg-green-600 text-[11px] px-1.5 py-0">
                                    Resolved
                                  </Badge>
                                )}
                              </div>
                            </div>
                            <div className="text-xs text-muted-foreground mt-0.5">
                              <Clock className="inline h-3 w-3 mr-1 -mt-0.5" />
                              First seen {formatRelativeTime(issue.firstSeen)}
                            </div>
                          </div>
                          <div className="hidden lg:block w-20 shrink-0">
                            <Badge variant="outline" className="text-[11px] px-1.5 py-0">{issue.platform}</Badge>
                          </div>
                          <div className="hidden lg:flex w-20 shrink-0 justify-center">
                            <EventSparkline eventCount={issue.eventCount} />
                          </div>
                          <div className="w-[55px] shrink-0 text-right">
                            <div className="font-semibold text-foreground">{formatCount(issue.eventCount)}</div>
                            <div className="text-xs text-muted-foreground">events</div>
                          </div>
                          <div className="hidden sm:block w-[45px] shrink-0 text-right">
                            <div className="font-semibold text-foreground">{issue.userCount ?? 0}</div>
                            <div className="text-xs text-muted-foreground">users</div>
                          </div>
                          <div className="hidden md:block w-20 shrink-0 text-right">
                            <span className={`text-xs font-medium ${getLastSeenColor(issue.lastSeen)}`}>
                              {formatRelativeTime(issue.lastSeen)}
                            </span>
                          </div>
                        </Link>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
