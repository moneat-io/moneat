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
import {EventsChart} from '@/components/charts/EventsChart'
import {BarChart} from '@/components/charts/BarChart'
import {StatsCard} from '@/components/charts/StatsCard'
import {PageHeader} from '@/components/ui/page-header'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {Button} from '@/components/ui/button'
import {Activity, AlertCircle, AlertTriangle, ArrowLeft, ListOrdered, Package, Users} from 'lucide-react'

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
      <div className="p-4 max-w-7xl mx-auto space-y-4">
        <PageHeader
          icon={Package}
          eyebrow="Release"
          title={<span className="font-mono">{releaseVersion}</span>}
          description="Release statistics"
          actions={
            <Button asChild variant="outline" size="sm">
              <Link to="/releases">
                <ArrowLeft className="h-3.5 w-3.5" />
                Back to releases
              </Link>
            </Button>
          }
        />

        {!projectId ? (
          <EmptyState
            icon={Package}
            title="No project selected"
            description="Select a project to view release details."
          />
        ) : isLoading ? (
          <div className="p-8 text-center">Loading release stats...</div>
        ) : !stats ? (
          <EmptyState
            icon={AlertTriangle}
            title="Release not found"
            description="This release was not found or has no events yet."
          />
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
                  color="hsl(var(--chart-1))"
                  height={240}
                />
              )}
            </div>

            {stats.topIssues.length > 0 && (
              <SectionCard
                title="Top Issues in this Release"
                icon={ListOrdered}
                count={stats.topIssues.length}
              >
                <div className="space-y-2">
                  {stats.topIssues.map((issue, index) => (
                    <Link
                      key={issue.issueId}
                      to="/issues/$issueId"
                      params={{ issueId: issue.issueId }}
                      className="flex items-center justify-between p-2 rounded-lg border hover:bg-accent/40 transition-colors"
                    >
                      <div className="flex items-center gap-2 min-w-0">
                        <div className="flex items-center justify-center w-5 h-5 shrink-0 rounded-full bg-muted text-xs font-semibold tabular-nums">
                          {index + 1}
                        </div>
                        <div className="min-w-0">
                          <div className="font-medium text-sm truncate">{issue.title}</div>
                          <div className="text-[11px] text-muted-foreground truncate font-mono">
                            {issue.issueId}
                          </div>
                        </div>
                      </div>
                      <div className="text-right shrink-0">
                        <div className="font-semibold text-sm tabular-nums">
                          {issue.count.toLocaleString()}
                        </div>
                        <div className="text-[11px] text-muted-foreground">
                          events
                        </div>
                      </div>
                    </Link>
                  ))}
                </div>
              </SectionCard>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
