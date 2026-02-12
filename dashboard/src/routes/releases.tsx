import {createFileRoute, Link, Outlet, redirect, useMatches} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api, formatErrorForLogging} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {useMemo, useState} from 'react'
import {Card, CardContent} from '@/components/ui/card'
import {Input} from '@/components/ui/input'
import {Badge} from '@/components/ui/badge'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from '@/components/ui/select'
import {StatsCard} from '@/components/charts/stats-card'
import {Activity, AlertCircle, Flame, Package, Search, ShieldCheck, Users} from 'lucide-react'

export const Route = createFileRoute('/releases')({
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
      console.error('Failed to fetch user:', formatErrorForLogging(error))
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
  const { selectedProjectId } = useProject()

  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectId = selectedProjectId || projects?.[0]?.id

  const { data: releases, isLoading, error } = useQuery({
    queryKey: ['releases', projectId],
    queryFn: async () => {
      if (!projectId) return []
      return api.getReleases(projectId)
    },
    enabled: !!projectId,
  })
  
  if (error) {
    console.error('Error loading releases:', formatErrorForLogging(error))
  }

  const releaseList = releases ?? []

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
    <div>
      <div className="container mx-auto px-4 py-6">
        <div className="mb-6">
          <h2 className="text-2xl font-bold">Releases</h2>
          <p className="text-muted-foreground mt-1">
            Track release health, new issues, and crash-free rates
          </p>
        </div>

        {!projects || projects.length === 0 ? (
          <Card className="p-12 text-center">
            <div className="max-w-md mx-auto space-y-4">
              <p className="text-muted-foreground">
                No projects yet. Create a project to view releases.
              </p>
            </div>
          </Card>
        ) : isLoading ? (
          <div className="p-8 text-center">Loading releases...</div>
        ) : !releases || releases.length === 0 ? (
          <Card className="p-12 text-center">
            <div className="max-w-md mx-auto space-y-4">
              <Package className="h-12 w-12 mx-auto text-muted-foreground" />
              <h3 className="text-lg font-semibold">No releases detected</h3>
              <p className="text-muted-foreground">
                Releases are auto-detected when events include a release version.
                Configure your SDK with a release version to start tracking.
              </p>
            </div>
          </Card>
        ) : (
          <div className="space-y-4">
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
              <StatsCard
                title="Tracked Releases"
                value={releaseList.length.toLocaleString()}
                icon={Package}
                accent="blue"
              />
              <StatsCard
                title="Healthy Releases"
                value={summary.healthyCount.toLocaleString()}
                icon={ShieldCheck}
                accent="emerald"
              />
              <StatsCard
                title="Regressing Releases"
                value={summary.regressingCount.toLocaleString()}
                icon={Flame}
                accent="amber"
              />
              <StatsCard
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
              <CardContent className="p-4">
                <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-3">
                  <div className="relative w-full lg:max-w-sm">
                    <Search className="h-4 w-4 text-muted-foreground absolute left-3 top-1/2 -translate-y-1/2" />
                    <Input
                      value={searchQuery}
                      onChange={(e) => setSearchQuery(e.target.value)}
                      placeholder="Search by release version"
                      className="pl-9"
                    />
                  </div>

                  <div className="flex flex-wrap items-center gap-2">
                    <Select value={sortBy} onValueChange={(value) => setSortBy(value as SortBy)}>
                      <SelectTrigger className="w-[200px]">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="latest">Sort: Latest seen</SelectItem>
                        <SelectItem value="events">Sort: Most events</SelectItem>
                        <SelectItem value="issues">Sort: Most new issues</SelectItem>
                        <SelectItem value="stability">Sort: Most stable</SelectItem>
                      </SelectContent>
                    </Select>
                    <Badge variant="outline">
                      {filteredAndSortedReleases.length} of {releaseList.length} releases
                    </Badge>
                    <Badge variant="outline">Most active: {summary.mostActiveVersion}</Badge>
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
                  <CardContent className="p-4">
                    <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
                      <div className="flex min-w-0 items-start gap-3 sm:gap-4">
                        <div className="flex items-center justify-center w-10 h-10 rounded-lg bg-primary/10 shrink-0">
                          <Package className="h-5 w-5 text-primary" />
                        </div>
                        <div className="min-w-0">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="font-semibold break-all">{release.version}</span>
                            {release.version === summary.latestVersion && (
                              <span className="text-xs px-2 py-0.5 rounded-full bg-primary/20 text-primary font-medium">
                                Latest
                              </span>
                            )}
                            {release.newIssueCount > 0 && (
                              <Badge
                                variant="outline"
                                className="max-w-full whitespace-normal break-words text-amber-700 border-amber-400/50"
                              >
                                Regression risk
                              </Badge>
                            )}
                          </div>
                          <div className="text-sm text-muted-foreground break-words">
                            {formatDate(release.firstSeen)}
                            {release.firstSeen !== release.lastSeen && (
                              <> – {formatDate(release.lastSeen)}</>
                            )}
                          </div>
                        </div>
                      </div>
                      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 md:justify-end md:gap-6">
                        <div className="flex items-center gap-2 text-sm">
                          <Activity className="h-4 w-4 text-muted-foreground" />
                          <span>{release.eventCount.toLocaleString()} events</span>
                        </div>
                        <div className="flex items-center gap-2 text-sm">
                          <AlertCircle className="h-4 w-4 text-muted-foreground" />
                          <span>{release.newIssueCount} new issues</span>
                        </div>
                        {release.crashFreeRate != null && (
                          <div className="flex items-center gap-2 text-sm">
                            <span
                              className={
                                release.crashFreeRate >= 99
                                  ? 'text-emerald-600'
                                  : release.crashFreeRate >= 95
                                    ? 'text-amber-600'
                                    : 'text-red-600'
                              }
                            >
                              {release.crashFreeRate.toFixed(1)}% crash-free
                            </span>
                          </div>
                        )}
                        <div className="flex items-center gap-2 text-sm">
                          <Users className="h-4 w-4 text-muted-foreground" />
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
  )
}
