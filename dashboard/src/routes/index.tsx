import { createFileRoute, redirect, Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useProject } from '@/contexts/project-context'
import { formatRelativeTime } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Card } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Plus, Search, Activity, AlertCircle, Users, TrendingUp, FolderKanban, CheckCircle2 } from 'lucide-react'
import { useState } from 'react'
import { StatsCard } from '@/components/charts/stats-card'
import { EventsChart } from '@/components/charts/events-chart'
import { useToast } from '@/hooks/use-toast'

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
      throw redirect({ to: '/login' })
    }
    
    // Check if user needs to complete onboarding
    try {
      const user = await api.getCurrentUser()
      if (!user.onboardingCompleted) {
        throw redirect({ to: '/onboarding' })
      }
    } catch (error) {
      // If we can't fetch user, let them continue (auth will handle it)
      console.error('Failed to fetch user:', error)
    }
  },
  component: DashboardPage,
})

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

  const projectId = selectedProjectId || projects?.[0]?.id

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
    <div className="min-h-screen bg-gradient-to-br from-background via-background to-primary/5">
      <div className="p-6 max-w-7xl mx-auto">
        <div className="mb-6 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <h2 className="text-2xl font-bold">Dashboard</h2>
            <span className="hidden sm:inline-flex h-2 w-2 rounded-full bg-emerald-500 animate-pulse" aria-hidden />
          </div>
          <Link to="/projects">
            <Button size="sm" variant="outline" className="border-primary/30 hover:bg-primary/10 hover:border-primary/50">
              <Plus className="h-4 w-4 mr-2" />
              New Project
            </Button>
          </Link>
        </div>

        {!projects || projects.length === 0 ? (
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
              <Link to="/projects">
                <Button size="lg">
                  <Plus className="h-4 w-4 mr-2" />
                  Create Your First Project
                </Button>
              </Link>
            </div>
          </Card>
        ) : (
          <>
            {/* Stats Tiles */}
            {stats && (
              <div className="mb-6 grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
                <StatsCard
                  title="Events (24h)"
                  value={stats.totalEvents.toLocaleString()}
                  icon={Activity}
                  accent="blue"
                />
                <StatsCard
                  title="Unresolved Issues"
                  value={stats.unresolvedIssues.toLocaleString()}
                  icon={AlertCircle}
                  accent="amber"
                />
                <StatsCard
                  title="Affected Users (24h)"
                  value={stats.affectedUsers.toLocaleString()}
                  icon={Users}
                  accent="emerald"
                />
                <StatsCard
                  title="Total Issues"
                  value={stats.totalIssues.toLocaleString()}
                  icon={TrendingUp}
                  accent="violet"
                />
              </div>
            )}

            {/* Mini Events Chart */}
            {stats && stats.eventsTimeline.length > 0 && (
              <div className="mb-6">
                <EventsChart
                  data={stats.eventsTimeline}
                  title="Events in Last 24 Hours"
                  height={200}
                />
              </div>
            )}

            <div className="mb-4 flex gap-4 items-center">
              {filteredIssues.length > 0 && (
                <div className="flex items-center gap-2">
                  <Checkbox
                    checked={selectedIssues.size === filteredIssues.length}
                    onCheckedChange={handleToggleAll}
                    aria-label="Select all issues"
                  />
                  <span className="text-sm text-muted-foreground whitespace-nowrap">Select all</span>
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
              <div className="space-y-2">
                {filteredIssues.map((issue) => (
                  <div
                    key={issue.id}
                    className="rounded-lg border border-border/80 bg-card hover:bg-accent hover:border-primary/20 transition"
                  >
                    <div className="flex items-center gap-4 p-4">
                      <div className="flex items-center">
                        <Checkbox
                          checked={selectedIssues.has(issue.id)}
                          onCheckedChange={() => handleToggleIssue(issue.id)}
                          onClick={(e) => e.stopPropagation()}
                          aria-label={`Select ${issue.title}`}
                        />
                      </div>
                      <Link
                        to="/issues/$issueId"
                        params={{ issueId: issue.id }}
                        className="flex-1 flex items-start gap-4"
                      >
                          <div className="flex-1">
                            <div className="font-semibold">{issue.title}</div>
                            <div className="text-sm text-muted-foreground">{issue.culprit}</div>
                            <div className="mt-2 flex gap-2">
                              <Badge className={getLevelColor(issue.level)}>
                                {issue.level.toUpperCase()}
                              </Badge>
                              <Badge variant="outline">{issue.platform}</Badge>
                              {issue.status === 'resolved' && (
                                <Badge variant="default" className="bg-green-500">
                                  Resolved
                                </Badge>
                              )}
                            </div>
                          </div>
                          <div className="flex items-center gap-6">
                            <div className="text-center">
                              <div className="text-xs text-muted-foreground mb-1">Frequency</div>
                              <EventSparkline eventCount={issue.eventCount} />
                            </div>
                            <div className="text-right text-sm text-muted-foreground min-w-[100px]">
                              <div className="font-semibold text-foreground">{issue.eventCount} events</div>
                              <div>{formatRelativeTime(issue.lastSeen)}</div>
                            </div>
                          </div>
                      </Link>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
