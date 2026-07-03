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

import {createFileRoute, Link, Outlet, useMatches} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api, formatErrorForLogging} from '@/lib/api'
import {type ReactNode, useMemo, useState} from 'react'
import {ExplorerShell} from '@/components/filters/ExplorerShell'
import {FacetRail} from '@/components/filters/FacetRail'
import {Card, CardContent} from '@/components/ui/card'
import {Input} from '@/components/ui/input'
import {Badge} from '@/components/ui/badge'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from '@/components/ui/select'
import {StatsCard} from '@/components/charts/StatsCard'
import {EmptyState} from '@/components/ui/empty-state'
import {StatusDot} from '@/components/ui/status-dot'
import {Activity, AlertCircle, Flame, Package, Search, ShieldCheck, Users} from 'lucide-react'
import {
  SERVICE_FACET_URL_KEYS,
  facetValues,
  serviceNamesForQuery,
  serviceRailSections,
  serviceScopeKey,
} from '@/lib/service-facet-scope'
import {useReadableFacetUrlState} from '@/lib/filters/useReadableFacetUrlState'
import {parseDate} from '@/lib/date-format'

export const Route = createFileRoute('/releases')({
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
type CrashFreeTone = 'success' | 'warning' | 'danger'

function crashFreeTone(rate: number): CrashFreeTone {
  if (rate >= 99) return 'success'
  if (rate >= 95) return 'warning'
  return 'danger'
}

function crashFreeTextClass(tone: CrashFreeTone): string {
  if (tone === 'success') return 'text-success-fg'
  if (tone === 'warning') return 'text-warning-fg'
  return 'text-danger-fg'
}

function ReleasesPage() {
  const [sortBy, setSortBy] = useState<SortBy>('latest')
  const {
    query: searchQuery,
    setQuery: setSearchQuery,
    facetFilters,
    setFacetFilters,
  } = useReadableFacetUrlState({facetKeys: SERVICE_FACET_URL_KEYS})

  const { data: projects, isLoading: projectsLoading, error: projectsError } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectList = useMemo(() => projects ?? [], [projects])
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
      (a, b) => parseDate(b.lastSeen).getTime() - parseDate(a.lastSeen).getTime()
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

      return parseDate(b.lastSeen).getTime() - parseDate(a.lastSeen).getTime()
    })
  }, [releaseList, searchQuery, sortBy])

  const formatDate = (isoString: string) => {
    if (!isoString) return 'N/A'
    const date = parseDate(isoString)
    if (isNaN(date.getTime())) return 'Invalid Date'
    return date.toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
  }

  const avgCrashFreeRateValue =
    summary.avgCrashFreeRate == null
      ? 'N/A'
      : `${summary.avgCrashFreeRate.toFixed(1)}%`

  let releasesContent: ReactNode
  if (projectsLoading) {
    releasesContent = <div className="p-6 text-center text-sm">Loading services...</div>
  } else if (projectsError) {
    releasesContent = (
      <div className="p-8 text-destructive">
        Failed to load services: {projectsError instanceof Error ? projectsError.message : 'Unknown error'}
      </div>
    )
  } else if (projectList.length === 0) {
    releasesContent = (
      <EmptyState
        icon={Package}
        title="No services yet"
        description="Create a service to view releases."
      />
    )
  } else if (!hasReleaseScope) {
    releasesContent = (
      <EmptyState
        icon={Package}
        title="No services match filters"
        description="Adjust the selected services to view releases."
      />
    )
  } else if (isLoading) {
    releasesContent = <div className="p-6 text-center text-sm">Loading releases...</div>
  } else if (!releases || releases.length === 0) {
    releasesContent = (
      <EmptyState
        icon={Package}
        title="No releases detected"
        description="Releases are auto-detected when telemetry includes a release or service version."
      />
    )
  } else {
    releasesContent = (
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
            value={avgCrashFreeRateValue}
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

        {filteredAndSortedReleases.map((release) => {
          let releaseTone: CrashFreeTone | null = null
          if (release.crashFreeRate != null) {
            releaseTone = crashFreeTone(release.crashFreeRate)
          }

          return (
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
                      {release.crashFreeRate != null && releaseTone != null && (
                        <div className="flex items-center gap-1.5 text-xs">
                          <StatusDot size="sm" tone={releaseTone} />
                          <span className={crashFreeTextClass(releaseTone)}>
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
          )
        })}
      </div>
    )
  }

  return (
    <ExplorerShell
      title="Releases"
      icon={<Package className="h-4 w-4 text-muted-foreground" />}
      searchBar={<div />}
      rail={
        <FacetRail
          sections={railSections}
          facetFilters={facetFilters}
          onFacetFiltersChange={setFacetFilters}
          title="Releases"
        />
      }
    >
      <div className="space-y-3 p-3">
        {releasesContent}
      </div>
    </ExplorerShell>
  )
}
