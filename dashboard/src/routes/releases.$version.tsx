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

import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {useProject} from '@/contexts/ProjectContext'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {EventsChart} from '@/components/charts/EventsChart'
import {BarChart} from '@/components/charts/BarChart'
import {StatsCard} from '@/components/charts/StatsCard'
import {Activity, AlertCircle, ArrowLeft, Users} from 'lucide-react'

export const Route = createFileRoute('/releases/$version')({
  beforeLoad: async ({ location }) => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login', search: { redirect: location.href } })
    }
  },
  component: ReleaseDetailPage,
})

function ReleaseDetailPage() {
  const { version } = Route.useParams()
  const { selectedProjectId } = useProject()
  const releaseVersion = version

  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectId = selectedProjectId || projects?.[0]?.resourceId

  const { data: stats, isLoading } = useQuery({
    queryKey: ['releaseStats', projectId, releaseVersion],
    queryFn: () =>
      projectId
        ? api.getReleaseStats(projectId, releaseVersion)
        : Promise.reject(new Error('No project')),
    enabled: !!projectId,
  })

  return (
    <div>
      <div className="p-4 max-w-7xl mx-auto">
        <div className="mb-4">
          <Link
            to="/releases"
            className="inline-flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground mb-3"
          >
            <ArrowLeft className="h-3 w-3" />
            Back to releases
          </Link>
          <h2 className="text-xl font-bold">{releaseVersion}</h2>
          <p className="text-muted-foreground text-xs mt-0.5">Release statistics</p>
        </div>

        {!projectId ? (
          <Card className="p-8 text-center">
            <p className="text-muted-foreground">
              Select a project to view release details.
            </p>
          </Card>
        ) : isLoading ? (
          <div className="p-8 text-center">Loading release stats...</div>
        ) : !stats ? (
          <Card className="p-12 text-center">
            <p className="text-muted-foreground">
              Release not found or has no events yet.
            </p>
          </Card>
        ) : (
          <div className="space-y-4">
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
              <StatsCard
                compact
                title="Total Events"
                value={stats.totalEvents.toLocaleString()}
                icon={Activity}
              />
              <StatsCard
                compact
                title="New Issues"
                value={stats.newIssues.toLocaleString()}
                icon={AlertCircle}
              />
              {stats.crashFreeSessionRate != null && (
                <StatsCard
                  compact
                  title="Crash-Free Rate"
                  value={`${stats.crashFreeSessionRate.toFixed(1)}%`}
                  icon={Activity}
                />
              )}
              <StatsCard
                compact
                title="Affected Users"
                value={stats.userCount.toLocaleString()}
                icon={Users}
              />
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
              <EventsChart
                data={stats.eventsTimeline}
                title="Events Over Time"
                height={240}
              />
              {Object.keys(stats.eventsByLevel).length > 0 && (
                <BarChart
                  data={stats.eventsByLevel}
                  title="Events by Level"
                  color="hsl(0, 84%, 60%)"
                  height={240}
                />
              )}
            </div>

            {stats.topIssues.length > 0 && (
              <Card>
                <CardHeader className="py-3">
                  <CardTitle className="text-base">Top Issues in this Release</CardTitle>
                </CardHeader>
                <CardContent className="pt-0">
                  <div className="space-y-2">
                    {stats.topIssues.map((issue, index) => (
                      <Link
                        key={issue.issueId}
                        to="/issues/$issueId"
                        params={{ issueId: issue.issueId }}
                        className="flex items-center justify-between p-2 rounded-lg border hover:bg-accent transition-colors"
                      >
                        <div className="flex items-center gap-2 min-w-0">
                          <div className="flex items-center justify-center w-5 h-5 shrink-0 rounded-full bg-primary/10 text-xs font-semibold">
                            {index + 1}
                          </div>
                          <div className="min-w-0">
                            <div className="font-medium text-sm truncate">{issue.title}</div>
                            <div className="text-[11px] text-muted-foreground truncate">
                              {issue.issueId}
                            </div>
                          </div>
                        </div>
                        <div className="text-right shrink-0">
                          <div className="font-semibold text-sm">
                            {issue.count.toLocaleString()}
                          </div>
                          <div className="text-[11px] text-muted-foreground">
                            events
                          </div>
                        </div>
                      </Link>
                    ))}
                  </div>
                </CardContent>
              </Card>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
