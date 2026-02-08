import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {useState} from 'react'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from '@/components/ui/select'
import {EventsChart} from '@/components/charts/events-chart'
import {DonutChart} from '@/components/charts/donut-chart'
import {BarChart} from '@/components/charts/bar-chart'
import {StatsCard} from '@/components/charts/stats-card'
import {Activity, AlertCircle, TrendingUp, Users} from 'lucide-react'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'

export const Route = createFileRoute('/analytics')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
    
    try {
      const user = await api.getCurrentUser()
      if (!user.onboardingCompleted) {
        throw redirect({ to: '/onboarding' })
      }
    } catch (error) {
      console.error('Failed to fetch user:', error)
    }
  },
  component: AnalyticsPage,
})

function AnalyticsPage() {
  const [period, setPeriod] = useState<'24h' | '7d' | '30d'>('7d')
  const { selectedProjectId } = useProject()

  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectId = selectedProjectId || projects?.[0]?.id

  const { data: stats, isLoading } = useQuery({
    queryKey: ['stats', projectId, period],
    queryFn: () => (projectId ? api.getProjectStats(projectId, period) : null),
    enabled: !!projectId,
  })

  return (
    <div className="min-h-screen bg-background">
      <div className="p-6 max-w-7xl mx-auto">
        {/* Header */}
        <div className="mb-6 flex items-center justify-between">
          <h2 className="text-2xl font-bold">Analytics</h2>
          <Select value={period} onValueChange={(val) => setPeriod(val as '24h' | '7d' | '30d')}>
            <SelectTrigger className="w-[180px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="24h">Last 24 Hours</SelectItem>
              <SelectItem value="7d">Last 7 Days</SelectItem>
              <SelectItem value="30d">Last 30 Days</SelectItem>
            </SelectContent>
          </Select>
        </div>

        {!projects || projects.length === 0 ? (
          <Card className="p-12 text-center">
            <div className="max-w-md mx-auto space-y-4">
              <p className="text-muted-foreground">No projects yet. Create a project to view analytics.</p>
            </div>
          </Card>
        ) : isLoading ? (
          <div className="p-8 text-center">Loading analytics...</div>
        ) : stats ? (
          <div className="space-y-6">
            {/* Summary Stats */}
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
              <StatsCard
                title="Total Events"
                value={stats.totalEvents.toLocaleString()}
                icon={Activity}
              />
              <StatsCard
                title="Total Issues"
                value={stats.totalIssues.toLocaleString()}
                icon={TrendingUp}
              />
              <StatsCard
                title="Unresolved Issues"
                value={stats.unresolvedIssues.toLocaleString()}
                icon={AlertCircle}
              />
              <StatsCard
                title="Affected Users"
                value={stats.affectedUsers.toLocaleString()}
                icon={Users}
              />
            </div>

            {/* Primary Charts */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              <EventsChart
                data={stats.eventsTimeline}
                title="Events Over Time"
                height={300}
                releaseMarkers={stats.releaseMarkers ?? []}
              />
              <EventsChart
                data={stats.usersTimeline}
                title="Affected Users Over Time"
                height={300}
                releaseMarkers={stats.releaseMarkers ?? []}
              />
            </div>

            {/* Status and Level Charts */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              {Object.keys(stats.issuesByStatus).length > 0 && (
                <DonutChart
                  data={stats.issuesByStatus}
                  title="Issues by Status"
                  colors={[
                    'hsl(0, 84%, 60%)',     // red for unresolved
                    'hsl(142, 76%, 36%)',   // green for resolved
                    'hsl(47, 96%, 53%)',    // yellow for ignored
                  ]}
                />
              )}
              {Object.keys(stats.eventsByLevel).length > 0 && (
                <BarChart
                  data={stats.eventsByLevel}
                  title="Events by Level"
                  color="hsl(0, 84%, 60%)"
                />
              )}
            </div>

            {/* Platform and Browser Charts */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              {Object.keys(stats.eventsByPlatform).length > 0 && (
                <BarChart
                  data={stats.eventsByPlatform}
                  title="Events by Platform"
                  color="hsl(221, 83%, 53%)"
                />
              )}
              {Object.keys(stats.eventsByBrowser).length > 0 && (
                <BarChart
                  data={stats.eventsByBrowser}
                  title="Events by Browser"
                  color="hsl(142, 76%, 36%)"
                />
              )}
            </div>

            {/* Environment Chart */}
            {Object.keys(stats.eventsByEnvironment).length > 0 && (
              <BarChart
                data={stats.eventsByEnvironment}
                title="Events by Environment"
                color="hsl(280, 65%, 60%)"
              />
            )}

            {/* Top Issues */}
            {stats.topIssues.length > 0 && (
              <Card>
                <CardHeader>
                  <CardTitle>Top Issues</CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="mb-4 text-sm text-muted-foreground">
                    Most frequent issues in the selected period.
                  </p>
                  <div className="space-y-3">
                    {stats.topIssues.map((issue, index) => (
                      <Link
                        key={issue.issueId}
                        to="/issues/$issueId"
                        params={{ issueId: issue.issueId }}
                        className="group block rounded-xl border border-border/70 bg-card/60 p-4 transition-colors hover:bg-accent/50"
                      >
                        <div className="flex items-start gap-3 sm:gap-4">
                          <div className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-sm font-semibold text-primary">
                            {index + 1}
                          </div>
                          <div className="min-w-0 flex-1">
                            <div className="font-medium leading-snug break-words [overflow-wrap:anywhere] line-clamp-4 sm:line-clamp-2">
                              {issue.title}
                            </div>
                            <div className="mt-2 text-xs font-mono text-muted-foreground break-all">
                              {issue.issueId}
                            </div>
                          </div>
                          <div className="hidden shrink-0 text-right sm:block">
                            <div className="text-lg font-semibold">{issue.count.toLocaleString()}</div>
                            <div className="text-xs text-muted-foreground">events</div>
                          </div>
                        </div>
                        <div className="mt-3 flex items-center justify-between rounded-lg bg-muted/50 px-3 py-2 text-sm sm:hidden">
                          <div className="text-muted-foreground">Events</div>
                          <div className="font-semibold tabular-nums">{issue.count.toLocaleString()}</div>
                        </div>
                      </Link>
                    ))}
                  </div>
                </CardContent>
              </Card>
            )}
          </div>
        ) : null}
      </div>
    </div>
  )
}
