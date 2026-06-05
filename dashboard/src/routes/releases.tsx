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

import {createFileRoute, Link, Outlet, redirect, useMatches} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api, formatErrorForLogging} from '@/lib/api'
import {useMemo, useState} from 'react'
import {FacetRail} from '@/components/filters/FacetRail'
import {Card, CardContent} from '@/components/ui/card'
import {Input} from '@/components/ui/input'
import {Badge} from '@/components/ui/badge'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from '@/components/ui/select'
import {StatsCard} from '@/components/charts/StatsCard'
import {PageHeader} from '@/components/ui/page-header'
import {EmptyState} from '@/components/ui/empty-state'
import {StatusDot} from '@/components/ui/status-dot'
import {Activity, AlertCircle, Flame, Package, Search, ShieldCheck, Users} from 'lucide-react'
import {
  facetValues,
  serviceNamesForQuery,
  serviceRailSections,
  serviceScopeKey,
} from '@/lib/service-facet-scope'
import type {FacetFilter} from '@/lib/filters/types'

export const Route = createFileRoute('/releases')({
  beforeLoad: async ({ location }) => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login', search: { redirect: location.href } })
    }
  },
  component: ReleasesLayout,
})

function ReleasesLayout() {
  const matches = useMatches()
  const showingChildRoute = matches.some((match) => match.id.includes('/releases/$version'))

  if (showingChildRoute) {
    return <Outlet />
  }

  return <ReleasesPage />
}

type SortBy = 'latest' | 'events' | 'issues' | 'stability'

function ReleasesPage() {
  const [searchQuery, setSearchQuery] = useState('')
  const [sortBy, setSortBy] = useState<SortBy>('latest')
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>([])

  const { data: projects, isLoading: projectsLoading, error: projectsError } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectList = projects ?? []
  const includedServices = useMemo(
    () => facetValues(facetFilters, 'service', false),
    [facetFilters]
  )
  const excludedServices = useMemo(
    () => facetValues(facetFilters, 'service', true),
    [facetFilters]
  )
  const hasServiceFilters = includedServices.length > 0 || excludedServices.length > 0
  const serviceNames = useMemo(
    () => serviceNamesForQuery(projectList, includedServices, excludedServices),
    [projectList, includedServices, excludedServices]
  )
  const scopeKey = serviceScopeKey(serviceNames, hasServiceFilters)
  const hasReleaseScope = projectList.length > 0 && (!hasServiceFilters || serviceNames.length > 0)
  const serviceScopeParams = useMemo(() => (
    serviceNames.length > 0 ? {services: [...serviceNames]} : {}
  ), [serviceNames])
  const railSections = useMemo(() => serviceRailSections(projectList), [projectList])

  const { data: releases, isLoading, error } = useQuery({
    queryKey: ['releases', 'organization', scopeKey, serviceScopeParams],
    queryFn: () => api.getOrganizationReleases(serviceScopeParams),
    enabled: hasReleaseScope,
  })
  
  if (error) {
    console.error('Error loading releases:', formatErrorForLogging(error))
  }

  const releaseList = useMemo(() => releases ?? [], [releases])

  const summary = useMemo(() => {
    if (releaseList.length === 0) {
      return {
        healthyCount: 0,
        regressingCount: 0,
        avgCrashFreeRate: null as number | null,
        latestVersion: 'N/A',
        mostActiveVersion: 'N/A',
      }
    }

    const withCrashRate = releaseList.filter((release) => release.crashFreeRate != null)
    const healthyCount = releaseList.filter(
      (release) => (release.crashFreeRate ?? 0) >= 99 && release.newIssueCount === 0
    ).length
    const regressingCount = releaseList.filter(
      (release) => release.newIssueCount > 0 && (release.crashFreeRate == null || release.crashFreeRate < 98)
    ).length
    const avgCrashFreeRate =
      withCrashRate.length > 0
        ? withCrashRate.reduce((sum, release) => sum + (release.crashFreeRate ?? 0), 0) /
          withCrashRate.length
        : null

    const mostActive = [...releaseList].sort((a, b) => b.eventCount - a.eventCount)[0]
    const latest = [...releaseList].sort(
      (a, b) => new Date(b.lastSeen).getTime() - new Date(a.lastSeen).getTime()
    )[0]

    return {
      healthyCount,
      regressingCount,
      avgCrashFreeRate,
      latestVersion: latest?.version ?? 'N/A',
      mostActiveVersion: mostActive?.version ?? 'N/A',
    }
  }, [releaseList])

  const filteredAndSortedReleases = useMemo(() => {
    const filtered = releaseList.filter((release) =>
      release.version.toLowerCase().includes(searchQuery.trim().toLowerCase())
    )

    return filtered.sort((a, b) => {
      if (sortBy === 'events') return b.eventCount - a.eventCount
      if (sortBy === 'issues') return b.newIssueCount - a.newIssueCount
      if (sortBy === 'stability') {
        const aRate = a.crashFreeRate ?? -1
        const bRate = b.crashFreeRate ?? -1
        return bRate - aRate
      }

      return new Date(b.lastSeen).getTime() - new Date(a.lastSeen).getTime()
    })
  }, [releaseList, searchQuery, sortBy])

  const formatDate = (isoString: string) => {
    if (!isoString) return 'N/A'
    const date = new Date(isoString)
    if (isNaN(date.getTime())) return 'Invalid Date'
    return date.toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
  }

  return (
    <div className="grid gap-2 p-3 lg:grid-cols-[220px_minmax(0,1fr)]">
      <FacetRail
        sections={railSections}
        facetFilters={facetFilters}
        onFacetFiltersChange={setFacetFilters}
        title="Releases"
        className="h-auto max-h-[340px] rounded-md border lg:sticky lg:top-2 lg:h-[calc(100vh-8rem)] lg:max-h-none"
      />

      <div className="min-w-0">
        <div className="container mx-auto px-4 py-4 space-y-3">
          <PageHeader
            icon={Package}
            title="Releases"
            description="Track release health, new issues, and telemetry by version"
          />

          {projectsLoading ? (
          <div className="p-6 text-center text-sm">Loading services...</div>
        ) : projectsError ? (
          <div className="p-8 text-destructive">
            Failed to load services: {projectsError instanceof Error ? projectsError.message : 'Unknown error'}
          </div>
        ) : projectList.length === 0 ? (
          <EmptyState
            icon={Package}
            title="No services yet"
            description="Create a service to view releases."
          />
        ) : !hasReleaseScope ? (
          <EmptyState
            icon={Package}
            title="No services match filters"
            description="Adjust the selected services to view releases."
          />
        ) : isLoading ? (
          <div className="p-6 text-center text-sm">Loading releases...</div>
        ) : !releases || releases.length === 0 ? (
          <EmptyState
            icon={Package}
            title="No releases detected"
            description="Releases are auto-detected when telemetry includes a release or service version."
          />
          ) : (
          <div className="space-y-3">
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-2">
              <StatsCard
                compact
                title="Tracked Releases"
                value={releaseList.length.toLocaleString()}
                icon={Package}
                accent="blue"
              />
              <StatsCard
                compact
                title="Healthy Releases"
                value={summary.healthyCount.toLocaleString()}
                icon={ShieldCheck}
                accent="emerald"
              />
              <StatsCard
                compact
                title="Regressing Releases"
                value={summary.regressingCount.toLocaleString()}
                icon={Flame}
                accent="amber"
              />
              <StatsCard
                compact
                title="Avg Crash-Free Rate"
                value={
                  summary.avgCrashFreeRate == null
                    ? 'N/A'
                    : `${summary.avgCrashFreeRate.toFixed(1)}%`
                }
                icon={Activity}
                accent="violet"
              />
            </div>

            <Card>
              <CardContent className="p-2">
                <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-2">
                  <div className="relative w-full lg:max-w-sm">
                    <Search className="h-3 w-3 text-muted-foreground absolute left-2.5 top-1/2 -translate-y-1/2" />
                    <Input
                      value={searchQuery}
                      onChange={(e) => setSearchQuery(e.target.value)}
                      placeholder="Search by release version"
                      className="pl-8 h-8 text-xs"
                    />
                  </div>

                  <div className="flex flex-wrap items-center gap-1.5">
                    <Select value={sortBy} onValueChange={(value) => setSortBy(value as SortBy)}>
                      <SelectTrigger className="w-[180px] h-8 text-xs">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="latest">Sort: Latest seen</SelectItem>
                        <SelectItem value="events">Sort: Most signals</SelectItem>
                        <SelectItem value="issues">Sort: Most new issues</SelectItem>
                        <SelectItem value="stability">Sort: Most stable</SelectItem>
                      </SelectContent>
                    </Select>
                    <Badge variant="outline" className="text-xs py-0">
                      {filteredAndSortedReleases.length} of {releaseList.length} releases
                    </Badge>
                    <Badge variant="outline" className="text-xs py-0">Most active: {summary.mostActiveVersion}</Badge>
                  </div>
                </div>
              </CardContent>
            </Card>

            {filteredAndSortedReleases.map((release) => (
              <Link
                key={release.version}
                to="/releases/$version"
                params={{ version: release.version }}
                className="block"
              >
                <Card className="hover:bg-accent/50 transition-colors cursor-pointer">
                  <CardContent className="p-2">
                    <div className="flex flex-col gap-1.5 md:flex-row md:items-center md:justify-between">
                      <div className="flex min-w-0 items-start gap-2 sm:gap-3">
                        <div className="flex items-center justify-center w-6 h-6 rounded-lg bg-muted shrink-0">
                          <Package className="h-3 w-3 text-muted-foreground" />
                        </div>
                        <div className="min-w-0">
                          <div className="flex flex-wrap items-center gap-1.5">
                            <span className="font-mono font-semibold text-sm break-all">{release.version}</span>
                            {release.version === summary.latestVersion && (
                              <Badge variant="accent" size="sm" shape="pill">
                                Latest
                              </Badge>
                            )}
                            {release.newIssueCount > 0 && (
                              <Badge
                                variant="warning"
                                size="sm"
                                className="max-w-full whitespace-normal break-words"
                              >
                                Regression risk
                              </Badge>
                            )}
                          </div>
                          <div className="text-xs text-muted-foreground break-words">
                            {formatDate(release.firstSeen)}
                            {release.firstSeen !== release.lastSeen && (
                              <> – {formatDate(release.lastSeen)}</>
                            )}
                          </div>
                        </div>
                      </div>
                      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 md:justify-end md:gap-4">
                        <div className="flex items-center gap-1.5 text-xs">
                          <Activity className="h-3 w-3 text-muted-foreground" />
                          <span>{release.eventCount.toLocaleString()} signals</span>
                        </div>
                        <div className="flex items-center gap-1.5 text-xs">
                          <AlertCircle className="h-3 w-3 text-muted-foreground" />
                          <span>{release.newIssueCount} new issues</span>
                        </div>
                        {release.crashFreeRate != null && (
                          <div className="flex items-center gap-1.5 text-xs">
                            <StatusDot
                              size="sm"
                              tone={
                                release.crashFreeRate >= 99
                                  ? 'success'
                                  : release.crashFreeRate >= 95
                                    ? 'warning'
                                    : 'danger'
                              }
                            />
                            <span
                              className={
                                release.crashFreeRate >= 99
                                  ? 'text-success-fg'
                                  : release.crashFreeRate >= 95
                                    ? 'text-warning-fg'
                                    : 'text-danger-fg'
                              }
                            >
                              {release.crashFreeRate.toFixed(1)}% crash-free
                            </span>
                          </div>
                        )}
                        <div className="flex items-center gap-1.5 text-xs">
                          <Users className="h-3 w-3 text-muted-foreground" />
                          <span>{release.userCount.toLocaleString()} users</span>
                        </div>
                      </div>
                    </div>
                  </CardContent>
                </Card>
              </Link>
            ))}
          </div>
          )}
        </div>
      </div>
    </div>
  )
}
