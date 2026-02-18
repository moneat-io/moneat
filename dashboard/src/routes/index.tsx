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

import {createFileRoute, Link} from '@tanstack/react-router'
import {LandingPage} from '@/components/landing/landing-page'
import {useQuery} from '@tanstack/react-query'
import {useState, useEffect} from 'react'
import {api, type StatusPageDetail, type UptimeHeartbeat} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {useEnterpriseFeatures} from '@/hooks/useEnterpriseFeatures'
import {formatRelativeTime} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import HeartbeatBar from '@/components/uptime/heartbeat-bar'
import {
  Activity,
  AlertCircle,
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  ArrowUpRight,
  Bell,
  Clock,
  Globe,
  HeartPulse,
  MessageSquare,
  Package,
  Play,
  Server,
  Shield,
  Terminal,
  Timer,
  Users,
  Smartphone,
  XCircle,
  Zap,
} from 'lucide-react'
import {StatsCard, StatsCardSkeleton} from '@/components/charts/stats-card'
import {EventsChart, EventsChartSkeleton} from '@/components/charts/events-chart'
import {getNow} from '@/lib/demo'

// ─── Subtle badge colors ─────────────────────────────────────────────
function getLevelBadge(level: string): string {
  switch (level.toLowerCase()) {
    case 'fatal':
      return 'border-red-300 bg-red-50 text-red-800 dark:border-red-800 dark:bg-red-950/50 dark:text-red-300'
    case 'error':
      return 'border-red-200 bg-red-50/80 text-red-700 dark:border-red-800/60 dark:bg-red-950/30 dark:text-red-400'
    case 'warning':
      return 'border-amber-200 bg-amber-50/80 text-amber-700 dark:border-amber-800/60 dark:bg-amber-950/30 dark:text-amber-400'
    case 'info':
      return 'border-blue-200 bg-blue-50/80 text-blue-700 dark:border-blue-800/60 dark:bg-blue-950/30 dark:text-blue-400'
    case 'debug':
      return 'border-zinc-200 bg-zinc-50/80 text-zinc-600 dark:border-zinc-700 dark:bg-zinc-800/30 dark:text-zinc-400'
    default:
      return 'border-border bg-muted text-muted-foreground'
  }
}

function formatCount(n: number): string {
  if (n >= 100000) return `${(n / 1000).toFixed(0)}k`
  if (n >= 10000) return `${(n / 1000).toFixed(1)}k`
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`
  return n.toLocaleString()
}

function getIncidentStatusBadge(status: string) {
  switch (status) {
    case 'TRIGGERED':
      return 'border-red-200 bg-red-50/80 text-red-700 dark:border-red-800/60 dark:bg-red-950/30 dark:text-red-400'
    case 'ACKNOWLEDGED':
      return 'border-amber-200 bg-amber-50/80 text-amber-700 dark:border-amber-800/60 dark:bg-amber-950/30 dark:text-amber-400'
    case 'RESOLVED':
      return 'border-emerald-200 bg-emerald-50/80 text-emerald-700 dark:border-emerald-800/60 dark:bg-emerald-950/30 dark:text-emerald-400'
    default:
      return 'border-border bg-muted text-muted-foreground'
  }
}

function getPriorityColor(priority: string) {
  switch (priority) {
    case 'P0':
      return 'text-red-600 dark:text-red-400'
    case 'P1':
      return 'text-orange-600 dark:text-orange-400'
    case 'P2':
      return 'text-amber-600 dark:text-amber-400'
    case 'P3':
      return 'text-blue-600 dark:text-blue-400'
    default:
      return 'text-muted-foreground'
  }
}

function getStatusDot(status: string) {
  switch (status) {
    case 'up':
      return 'bg-emerald-400 dark:bg-emerald-500'
    case 'down':
      return 'bg-red-400 dark:bg-red-500'
    case 'degraded':
      return 'bg-amber-400 dark:bg-amber-500'
    default:
      return 'bg-zinc-300 dark:bg-zinc-600'
  }
}

type StatusPageMonitorSummary = {
  total: number
  up: number
  down: number
  pending: number
}

function summarizeStatusPageMonitors(
  monitorIds: string[],
  monitorStatusById: Map<string, string>,
): StatusPageMonitorSummary {
  const summary: StatusPageMonitorSummary = {
    total: monitorIds.length,
    up: 0,
    down: 0,
    pending: 0,
  }

  for (const monitorId of monitorIds) {
    const status = (monitorStatusById.get(monitorId) || '').toLowerCase()
    if (status === 'up') {
      summary.up += 1
    } else if (status === 'down') {
      summary.down += 1
    } else {
      summary.pending += 1
    }
  }

  return summary
}

function formatMs(ms: number): string {
  if (ms < 1) return '<1ms'
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.round(ms)}ms`
}

function getMonitorTypeLabel(type: string): string {
  const labels: Record<string, string> = {
    http: 'HTTP(S)',
    keyword: 'Keyword',
    json_query: 'JSON Query',
    tcp: 'TCP',
    ping: 'Ping',
    dns: 'DNS',
    websocket: 'WebSocket',
    push: 'Push',
    docker: 'Docker',
    database: 'Database',
    ssl: 'SSL',
  }
  return labels[type] || type.toUpperCase()
}

function getLatestHeartbeat(heartbeats: UptimeHeartbeat[]): UptimeHeartbeat | null {
  if (heartbeats.length === 0) return null

  return heartbeats.reduce<UptimeHeartbeat | null>((latest, heartbeat) => {
    if (!latest) return heartbeat
    return heartbeat.timestamp > latest.timestamp ? heartbeat : latest
  }, null)
}

function isRecentHeartbeat(
  lastCheckAt: number | undefined,
  intervalSeconds: number | undefined,
  nowMs: number,
): boolean {
  if (!lastCheckAt) return false
  const expectedIntervalMs = (intervalSeconds ?? 60) * 1000
  const graceWindowMs = Math.max(expectedIntervalMs * 2, 5 * 60 * 1000)
  return nowMs - lastCheckAt <= graceWindowMs
}

export const Route = createFileRoute('/')({
  component: IndexPage,
})

function IndexPage() {
  const [isAuthenticated, setIsAuthenticated] = useState(api.isAuthenticated())
  const [isChecking, setIsChecking] = useState(true)

  useEffect(() => {
    async function checkAuth() {
      // For cold loads, verify auth state via API
      if (!api.isAuthenticated()) {
        await api.checkAuth()
      }
      setIsAuthenticated(api.isAuthenticated())
      setIsChecking(false)
    }
    checkAuth()
  }, []) // Re-run when component mounts

  // Show nothing while checking auth to avoid flash
  if (isChecking) {
    return null
  }

  if (!isAuthenticated) {
    return <LandingPage />
  }
  return <DashboardPage />
}

// ─── Section wrapper ──────────────────────────────────────────────────
function DashboardSection({
  title,
  icon: Icon,
  to,
  children,
  headerRight,
  iconClassName,
  iconBgClassName,
}: {
  title: string
  icon: React.ComponentType<{ className?: string }>
  to: string
  children: React.ReactNode
  headerRight?: React.ReactNode
  iconClassName?: string
  iconBgClassName?: string
}) {
  return (
    <Card className="overflow-hidden border-border/60">
      <CardHeader className="px-5 py-3.5 flex flex-row items-center justify-between space-y-0 border-b border-border/40">
        <div className="flex items-center gap-2.5">
          <div className={`h-7 w-7 rounded-md flex items-center justify-center ${iconBgClassName || 'bg-muted/60'}`}>
            <Icon className={`h-3.5 w-3.5 ${iconClassName || 'text-muted-foreground'}`} />
          </div>
          <CardTitle className="text-sm font-semibold">{title}</CardTitle>
        </div>
        <div className="flex items-center gap-2">
          {headerRight}
          <Link to={to as "/"}>
            <Button variant="ghost" size="sm" className="h-7 text-xs text-muted-foreground hover:text-foreground gap-1 px-2">
              View <ArrowUpRight className="h-3 w-3" />
            </Button>
          </Link>
        </div>
      </CardHeader>
      <CardContent className="px-5 pb-4 pt-3">
        {children}
      </CardContent>
    </Card>
  )
}

function EmptySection({message}: { message: string }) {
  return (
    <div className="flex items-center justify-center py-8 text-sm text-muted-foreground">
      {message}
    </div>
  )
}

function SkeletonSection() {
  return (
    <div className="space-y-0.5">
      {[...Array(5)].map((_, i) => (
        <div key={i} className="grid grid-cols-[7.75rem_auto_minmax(0,1fr)_auto] items-center gap-2 py-2 px-2.5 rounded-md">
          <div className="h-5 bg-muted rounded animate-pulse" />
          <div className="h-4 w-8 bg-muted rounded animate-pulse" />
          <div className="h-4 bg-muted rounded animate-pulse" />
          <div className="h-3 w-16 bg-muted rounded animate-pulse" />
        </div>
      ))}
    </div>
  )
}

function normalizePercent(value?: number | null): number | null {
  if (value == null || Number.isNaN(value)) return null
  return Math.max(0, Math.min(100, value))
}

function getUtilizationFillColor(percent: number | null): string {
  if (percent == null) return 'bg-muted-foreground/30'
  if (percent >= 85) return 'bg-red-500/80'
  if (percent >= 70) return 'bg-amber-500/80'
  return 'bg-emerald-500/75'
}

function UtilizationBar({
  label,
  value,
}: {
  label: string
  value?: number | null
}) {
  const percent = normalizePercent(value)

  return (
    <div className="min-w-0">
      <div className="flex items-center justify-between gap-2 text-[11px] tabular-nums">
        <span className="text-muted-foreground">{label}</span>
        <span className="font-medium text-foreground/90">
          {percent != null ? `${Math.round(percent)}%` : '—'}
        </span>
      </div>
      <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-muted/60">
        <div
          className={`h-full rounded-full transition-[width] duration-300 ${getUtilizationFillColor(percent)}`}
          style={{width: `${percent ?? 0}%`}}
        />
      </div>
    </div>
  )
}

// ─── Main Dashboard ───────────────────────────────────────────────────
function DashboardPage() {
  const {selectedProjectId, setSelectedProjectId} = useProject()

  const {data: projects} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectId = selectedProjectId || projects?.[0]?.id

  if (!selectedProjectId && projects && projects.length > 0 && projects[0]?.id) {
    setSelectedProjectId(projects[0].id)
  }

  const {data: stats, isLoading: isLoadingStats} = useQuery({
    queryKey: ['stats', projectId],
    queryFn: () => (projectId ? api.getProjectStats(projectId, '24h') : null),
    enabled: !!projectId,
  })

  const {data: issues = [], isLoading: isLoadingIssues} = useQuery({
    queryKey: ['issues', projectId, 'unresolved'],
    queryFn: () => (projectId ? api.getIssues(projectId) : []),
    enabled: !!projectId,
  })

  const {data: perfStats, isLoading: isLoadingPerf} = useQuery({
    queryKey: ['perf-stats', projectId],
    queryFn: () => (projectId ? api.getPerformanceStats(projectId, {period: '24h'}) : null),
    enabled: !!projectId,
  })

  const {data: releases = [], isLoading: isLoadingReleases} = useQuery({
    queryKey: ['releases-overview', projectId],
    queryFn: () => (projectId ? api.getReleases(projectId) : []),
    enabled: !!projectId,
  })

  const {data: replays = [], isLoading: isLoadingReplays} = useQuery({
    queryKey: ['replays-overview', projectId],
    queryFn: () => (projectId ? api.getReplays(projectId, {limit: 5, period: '24h'}) : []),
    enabled: !!projectId,
  })

  const {data: feedback = [], isLoading: isLoadingFeedback} = useQuery({
    queryKey: ['feedback-overview', projectId],
    queryFn: () => (projectId ? api.getFeedback(projectId, {limit: 5}) : []),
    enabled: !!projectId,
  })

  const {data: uptimeMonitors = [], isLoading: isLoadingUptime} = useQuery({
    queryKey: ['uptime-monitors'],
    queryFn: () => api.getUptimeMonitors(),
  })

  const {data: monitorSystems = [], isLoading: isLoadingMonitors} = useQuery({
    queryKey: ['monitor-systems'],
    queryFn: () => api.getMonitorSystems(),
  })

  const {data: enterpriseFeatures} = useEnterpriseFeatures()
  const hasOnCall = enterpriseFeatures?.modules?.includes('oncall') ?? false

  const {data: incidents = [], isLoading: isLoadingIncidents} = useQuery({
    queryKey: ['incidents-overview'],
    queryFn: () => api.getIncidents(),
    refetchInterval: 30_000,
    enabled: hasOnCall,
  })

  const {data: onCallSchedules = [], isLoading: isLoadingSchedules} = useQuery({
    queryKey: ['oncall-schedules'],
    queryFn: () => api.getOnCallSchedules(),
    enabled: hasOnCall,
  })

  const {data: statusPages = [], isLoading: isLoadingStatusPages} = useQuery({
    queryKey: ['status-pages'],
    queryFn: () => api.getStatusPages(),
  })
  const {data: statusPageDetailsById = {}} = useQuery({
    queryKey: ['dashboard-status-page-details', statusPages.map((page) => page.id)],
    queryFn: async () => {
      const detailEntries = await Promise.all(
        statusPages.map(async (page) => {
          const detail = await api.getStatusPage(page.id)
          return [page.id, detail] as const
        }),
      )
      return Object.fromEntries(detailEntries) as Record<string, StatusPageDetail>
    },
    enabled: statusPages.length > 0,
    staleTime: 60_000,
  })

  // ── Derived stats ──────────────────────────────────────────────────
  const unresolvedIssues = issues.filter(i => i.status === 'unresolved')
  const activeIncidents = incidents.filter(i => i.status !== 'RESOLVED')
  const triggeredIncidents = incidents.filter(i => i.status === 'TRIGGERED')
  const uptimeUp = uptimeMonitors.filter(m => m.status === 'up').length
  const uptimeDown = uptimeMonitors.filter(m => m.status === 'down').length
  const systemsUp = monitorSystems.filter(s => s.status === 'up').length
  const systemsDown = monitorSystems.filter(s => s.status === 'down').length
  const recentReleases = releases.slice(0, 5)
  const recentFeedback = feedback.slice(0, 5)
  const dashboardUptimeMonitors = uptimeMonitors.slice(0, 6)
  const nowMs = getNow()
  const uptimeMonitorStatusById = new Map(uptimeMonitors.map((monitor) => [monitor.id, monitor.status]))

  const {data: uptimeHeartbeatsByMonitor = {}} = useQuery({
    queryKey: ['dashboard-uptime-heartbeats', dashboardUptimeMonitors.map((monitor) => monitor.id)],
    queryFn: async () => {
      const heartbeatEntries = await Promise.all(
        dashboardUptimeMonitors.map(async (monitor) => {
          const heartbeats = await api.getUptimeHeartbeats(monitor.id)
          return [monitor.id, heartbeats] as const
        }),
      )
      return Object.fromEntries(heartbeatEntries) as Record<string, UptimeHeartbeat[]>
    },
    enabled: dashboardUptimeMonitors.length > 0,
    staleTime: 30_000,
    refetchInterval: 60_000,
  })

  // Issue breakdown by level
  const issueLevelCounts = unresolvedIssues.reduce((acc, i) => {
    acc[i.level] = (acc[i.level] || 0) + 1
    return acc
  }, {} as Record<string, number>)

  // Feedback status counts
  const newFeedback = feedback.filter(f => f.status === 'new' || f.status === 'unresolved').length
  const replaysWithErrors = replays.filter(r => r.errorCount > 0).length

  return (
    <div className="min-h-screen">
      <div className="px-6 py-4">
        {/* Header */}
        <div className="mb-5">
          <h2 className="text-2xl font-bold tracking-tight">Dashboard</h2>
          <p className="text-sm text-muted-foreground mt-0.5">
            Overview of your systems, applications, and incidents
          </p>
        </div>

        {/* ── Top-level stats ──────────────────────────────────────── */}
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3 mb-5">
          {isLoadingStats || isLoadingIssues ? (
            <>
              <StatsCardSkeleton accent="amber" />
              <StatsCardSkeleton accent="blue" />
              <StatsCardSkeleton accent="rose" />
              <StatsCardSkeleton accent="emerald" />
              <StatsCardSkeleton accent="cyan" />
              <StatsCardSkeleton accent="violet" />
            </>
          ) : (
            <>
              <StatsCard
                title="Unresolved Issues"
                value={formatCount(stats?.unresolvedIssues ?? unresolvedIssues.length)}
                icon={AlertCircle}
                accent="amber"
              />
              <StatsCard
                title="Events (24h)"
                value={formatCount(stats?.totalEvents ?? 0)}
                icon={Activity}
                accent="blue"
              />
              <StatsCard
                title="Active Incidents"
                value={activeIncidents.length}
                icon={Bell}
                accent="rose"
              />
              <StatsCard
                title="Uptime Monitors"
                value={`${uptimeUp}/${uptimeMonitors.length}`}
                icon={HeartPulse}
                accent="emerald"
                subtitle={uptimeDown > 0 ? `${uptimeDown} down` : 'All healthy'}
              />
              <StatsCard
                title="Infrastructure"
                value={`${systemsUp}/${monitorSystems.length}`}
                icon={Server}
                accent="cyan"
                subtitle={systemsDown > 0 ? `${systemsDown} down` : 'All online'}
              />
              <StatsCard
                title="Users (24h)"
                value={formatCount(stats?.affectedUsers ?? 0)}
                icon={Users}
                accent="violet"
              />
            </>
          )}
        </div>

        {/* ── Events Chart ─────────────────────────────────────────── */}
        {isLoadingStats ? (
          <div className="mb-5 h-[100px]">
            <EventsChartSkeleton fillHeight compact />
          </div>
        ) : stats && stats.eventsTimeline.length > 0 ? (
          <div className="mb-5 h-[100px]">
            <EventsChart
              data={stats.eventsTimeline}
              title="Events — Last 24 Hours"
              fillHeight
              compact
            />
          </div>
        ) : null}

        {/* ── Two-column grid for feature sections ─────────────────── */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mb-4">

          {/* ── On-Call / Incidents (Enterprise) ───────────────────── */}
          {hasOnCall && (
          <DashboardSection
            title="On-Call"
            icon={Bell}
            to={"/on-call" as string}
            iconClassName="text-amber-600 dark:text-amber-400"
            iconBgClassName="bg-amber-100 dark:bg-amber-900/20"
            headerRight={
              (triggeredIncidents.length > 0 || activeIncidents.length > 0) ? (
                <div className="flex items-center gap-3 text-xs font-medium">
                  {triggeredIncidents.length > 0 && (
                    <div className="flex items-center gap-1.5 text-red-600 dark:text-red-400">
                      <Bell className="h-3.5 w-3.5" />
                      <span>{triggeredIncidents.length} Triggered</span>
                    </div>
                  )}
                  {activeIncidents.length > 0 && (
                    <div className="flex items-center gap-1.5 text-muted-foreground">
                      <Activity className="h-3.5 w-3.5" />
                      <span>{activeIncidents.length} Active</span>
                    </div>
                  )}
                </div>
              ) : null
            }
          >
            {/* Who's on call */}
            {onCallSchedules.length > 0 && onCallSchedules.some(s => s.currentOnCall) && (
              <div className="mb-3 flex flex-wrap gap-1.5">
                {onCallSchedules.map(schedule => (
                  schedule.currentOnCall && (
                    <div
                      key={schedule.id}
                      className="flex items-center gap-1.5 text-xs bg-muted/50 border border-border/40 rounded-md px-2.5 py-1"
                    >
                      <Shield className="h-3 w-3 text-muted-foreground" />
                      <span className="font-medium">{schedule.currentOnCall.userName}</span>
                      <span className="text-muted-foreground">on {schedule.name}</span>
                    </div>
                  )
                ))}
              </div>
            )}

            {/* Recent incidents */}
            {isLoadingIncidents || isLoadingSchedules ? (
              <SkeletonSection />
            ) : activeIncidents.length === 0 && incidents.length === 0 ? (
              <EmptySection message="No incidents" />
            ) : (
              <div className="space-y-0.5">
                {(activeIncidents.length > 0 ? activeIncidents : incidents)
                  .slice(0, 5)
                  .map(incident => (
                    <a
                      key={incident.id}
                      href="/on-call"
                      className="grid grid-cols-[7.75rem_auto_minmax(0,1fr)_auto] items-center gap-2 py-2 px-2.5 rounded-md hover:bg-muted/50 transition"
                    >
                      <Badge variant="outline" className={`${getIncidentStatusBadge(incident.status)} w-full justify-center text-[10px] px-1.5 py-0 shrink-0`}>
                        {incident.status}
                      </Badge>
                      <span className={`text-[11px] font-semibold ${getPriorityColor(incident.priorityLevel)} shrink-0 tabular-nums`}>
                        {incident.priorityLevel}
                      </span>
                      <span className="text-sm truncate flex-1">{incident.title}</span>
                      <span className="text-[11px] text-muted-foreground shrink-0">
                        {formatRelativeTime(incident.triggeredAt)}
                      </span>
                    </a>
                  ))}
              </div>
            )}
          </DashboardSection>
          )}

          {/* ── Issues ─────────────────────────────────────────────── */}
          <DashboardSection
            title="Issues"
            icon={AlertCircle}
            to="/issues"
            iconClassName="text-red-600 dark:text-red-400"
            iconBgClassName="bg-red-100 dark:bg-red-900/20"
            headerRight={
              unresolvedIssues.length > 0 ? (
                <div className="flex items-center gap-3 text-xs font-medium">
                  <div className="flex items-center gap-1.5 text-muted-foreground">
                    <AlertCircle className="h-3.5 w-3.5" />
                    <span>{unresolvedIssues.length} Total</span>
                  </div>
                  {issueLevelCounts['fatal'] > 0 && (
                    <div className="flex items-center gap-1.5 text-red-600 dark:text-red-400">
                      <XCircle className="h-3.5 w-3.5" />
                      <span>{issueLevelCounts['fatal']} Fatal</span>
                    </div>
                  )}
                  {issueLevelCounts['error'] > 0 && (
                    <div className="flex items-center gap-1.5 text-red-500 dark:text-red-500">
                      <AlertTriangle className="h-3.5 w-3.5" />
                      <span>{issueLevelCounts['error']} Error</span>
                    </div>
                  )}
                </div>
              ) : null
            }
          >
            {isLoadingIssues ? (
              <SkeletonSection />
            ) : unresolvedIssues.length === 0 ? (
              <EmptySection message="No unresolved issues" />
            ) : (
              <div className="space-y-0.5">
                {unresolvedIssues.slice(0, 6).map(issue => {
                  const levelAccent = {
                    fatal: 'border-l-red-500',
                    error: 'border-l-orange-400',
                    warning: 'border-l-amber-400',
                    info: 'border-l-blue-400',
                    debug: 'border-l-gray-400',
                  }[issue.level.toLowerCase()] ?? 'border-l-gray-300'

                  const platformInfo = issue.platform?.toLowerCase().includes('cocoa') || issue.platform?.toLowerCase().includes('ios')
                    ? { Icon: Smartphone, label: 'iOS' }
                    : issue.platform?.toLowerCase().includes('android')
                    ? { Icon: Smartphone, label: 'Android' }
                    : issue.platform?.toLowerCase().includes('javascript') || issue.platform?.toLowerCase().includes('node')
                    ? { Icon: Globe, label: 'Web' }
                    : issue.platform?.toLowerCase().includes('python') || issue.platform?.toLowerCase().includes('java') || issue.platform?.toLowerCase().includes('go')
                    ? { Icon: Server, label: 'Backend' }
                    : null

                  return (
                    <Link
                      key={issue.id}
                      to="/issues/$issueId"
                      params={{issueId: issue.id}}
                      className={`flex items-center gap-2 py-2 px-2.5 rounded-md border-l-2 ${levelAccent} hover:bg-muted/50 transition`}
                    >
                      <Badge variant="outline" className={`${getLevelBadge(issue.level)} text-[10px] px-1.5 py-0 w-14 justify-center shrink-0`}>
                        {issue.level.toUpperCase()}
                      </Badge>
                      <div className="flex flex-col gap-0.5 min-w-0 flex-1">
                        <span className="text-sm truncate">{issue.title || issue.culprit}</span>
                        <div className="flex items-center gap-2 text-[10px] text-muted-foreground/70">
                          {platformInfo && (
                            <span className="flex items-center gap-0.5" title={platformInfo.label}>
                              <platformInfo.Icon className="h-3 w-3 text-muted-foreground/60" />
                              <span>{platformInfo.label}</span>
                            </span>
                          )}
                          {issue.lastSeen && (
                            <span>{formatRelativeTime(issue.lastSeen)}</span>
                          )}
                        </div>
                      </div>
                      <div className="flex items-center gap-3 shrink-0">
                        <span className="text-[11px] text-muted-foreground tabular-nums">
                          {formatCount(issue.eventCount)} events
                        </span>
                      </div>
                    </Link>
                  )
                })}
              </div>
            )}
          </DashboardSection>

          {/* ── Uptime ─────────────────────────────────────────────── */}
          <DashboardSection
            title="Uptime"
            icon={HeartPulse}
            to="/uptime"
            iconClassName="text-emerald-600 dark:text-emerald-400"
            iconBgClassName="bg-emerald-100 dark:bg-emerald-900/20"
            headerRight={
              uptimeMonitors.length > 0 ? (
                <div className="flex items-center gap-3 text-xs font-medium">
                  {uptimeDown > 0 && (
                    <div className="flex items-center gap-1.5 text-red-600 dark:text-red-400">
                      <ArrowDown className="h-3.5 w-3.5" />
                      <span>{uptimeDown} Down</span>
                    </div>
                  )}
                  {uptimeUp > 0 && (
                    <div className="flex items-center gap-1.5 text-emerald-600 dark:text-emerald-500">
                      <ArrowUp className="h-3.5 w-3.5" />
                      <span>{uptimeUp} Up</span>
                    </div>
                  )}
                  <div className="flex items-center gap-1.5 text-muted-foreground">
                    <Activity className="h-3.5 w-3.5" />
                    <span>{uptimeMonitors.length} Total</span>
                  </div>
                </div>
              ) : null
            }
          >
            {isLoadingUptime ? (
              <SkeletonSection />
            ) : uptimeMonitors.length === 0 ? (
              <EmptySection message="No uptime monitors configured" />
            ) : (
              <div className="space-y-0.5">
                {dashboardUptimeMonitors.map(monitor => {
                  const heartbeats = uptimeHeartbeatsByMonitor[monitor.id] ?? []
                  const latestHeartbeat = getLatestHeartbeat(heartbeats)
                  const isHeartbeatFresh = isRecentHeartbeat(monitor.lastCheckAt, monitor.intervalSeconds, nowMs)
                  const sampledSuccess = heartbeats.length > 0
                    ? (heartbeats.filter((heartbeat) => heartbeat.status === 1).length / heartbeats.length) * 100
                    : null

                  return (
                    <Link
                      key={monitor.id}
                      to="/uptime/$monitorId"
                      params={{monitorId: monitor.id}}
                      className="grid gap-2 py-2 px-2.5 rounded-md hover:bg-muted/50 transition md:grid-cols-[minmax(12rem,26%)_minmax(0,1fr)_auto] md:items-center"
                    >
                      <div className="flex min-w-0 items-center gap-2.5">
                        <span className={`h-2 w-2 rounded-full shrink-0 ${getStatusDot(monitor.status)}`} />
                        <span className="text-sm truncate">{monitor.name}</span>
                        {!isHeartbeatFresh && monitor.active && monitor.status !== 'paused' && (
                          <Badge variant="outline" className="text-[10px] px-1.5 py-0 border-amber-300/60 text-amber-700 dark:text-amber-400">
                            stale
                          </Badge>
                        )}
                      </div>

                      <div className="min-w-0">
                        <HeartbeatBar heartbeats={heartbeats} maxBars={24} className="h-3.5 w-full" />
                        <div className="mt-1 flex items-center gap-3 text-[11px] text-muted-foreground tabular-nums">
                          <span>
                            {latestHeartbeat ? `Last beat ${formatRelativeTime(latestHeartbeat.timestamp)}` : 'No heartbeat data'}
                          </span>
                          {sampledSuccess != null && (
                            <span>{sampledSuccess.toFixed(0)}% success</span>
                          )}
                        </div>
                      </div>

                      <div className="flex items-center gap-3 shrink-0">
                        {monitor.uptime24h != null && (
                          <span className="text-[11px] text-muted-foreground tabular-nums">
                            {monitor.uptime24h.toFixed(2)}%
                          </span>
                        )}
                        {monitor.avgResponseTime != null && (
                          <span className="text-[11px] text-muted-foreground tabular-nums">
                            {formatMs(monitor.avgResponseTime)}
                          </span>
                        )}
                        <Badge variant="outline" className="text-[10px] px-1.5 py-0">
                          {getMonitorTypeLabel(monitor.type)}
                        </Badge>
                      </div>
                    </Link>
                  )
                })}
              </div>
            )}
          </DashboardSection>

          {/* ── Infrastructure Monitoring ───────────────────────────── */}
          <DashboardSection
            title="Infrastructure"
            icon={Server}
            to="/monitoring"
            iconClassName="text-blue-600 dark:text-blue-400"
            iconBgClassName="bg-blue-100 dark:bg-blue-900/20"
            headerRight={
              monitorSystems.length > 0 ? (
                <div className="flex items-center gap-3 text-xs font-medium">
                  {systemsDown > 0 && (
                    <div className="flex items-center gap-1.5 text-red-600 dark:text-red-400">
                      <ArrowDown className="h-3.5 w-3.5" />
                      <span>{systemsDown} Offline</span>
                    </div>
                  )}
                  {systemsUp > 0 && (
                    <div className="flex items-center gap-1.5 text-blue-600 dark:text-blue-500">
                      <ArrowUp className="h-3.5 w-3.5" />
                      <span>{systemsUp} Online</span>
                    </div>
                  )}
                  <div className="flex items-center gap-1.5 text-muted-foreground">
                    <Server className="h-3.5 w-3.5" />
                    <span>{monitorSystems.length} Total</span>
                  </div>
                </div>
              ) : null
            }
          >
            {isLoadingMonitors ? (
              <SkeletonSection />
            ) : monitorSystems.length === 0 ? (
              <EmptySection message="No systems being monitored" />
            ) : (
              <div className="space-y-0.5">
                {monitorSystems.slice(0, 6).map(sys => (
                  <Link
                    key={sys.id}
                    to="/monitoring/$systemId"
                    params={{systemId: sys.id}}
                    className="grid gap-3 py-2 px-2.5 rounded-md hover:bg-muted/50 transition md:[grid-template-columns:minmax(12rem,15rem)_minmax(0,1fr)_minmax(0,1fr)_minmax(0,1fr)_minmax(0,11rem)] md:items-center"
                  >
                    <div className="flex min-w-0 items-center gap-2.5">
                      <span className={`h-2 w-2 rounded-full shrink-0 ${getStatusDot(sys.status)}`} />
                      <span className="text-sm flex-1 truncate min-w-0">{sys.name}</span>
                    </div>

                    <UtilizationBar label="CPU" value={sys.cpuPercent} />
                    <UtilizationBar label="RAM" value={sys.latest_metrics?.mem_percent} />
                    <UtilizationBar label="Disk" value={sys.latest_metrics?.disk_percent} />
                    {sys.os ? (
                      <Badge
                        variant="outline"
                        className="w-fit max-w-[11rem] justify-self-start truncate text-[10px] px-2 py-0"
                      >
                        {sys.os}
                      </Badge>
                    ) : (
                      <span className="text-[10px] text-muted-foreground">—</span>
                    )}
                  </Link>
                ))}
              </div>
            )}
          </DashboardSection>

          {/* ── Performance ─────────────────────────────────────────── */}
          <DashboardSection
            title="Performance"
            icon={Zap}
            to="/performance"
            iconClassName="text-violet-600 dark:text-violet-400"
            iconBgClassName="bg-violet-100 dark:bg-violet-900/20"
            headerRight={
              perfStats ? (
                <div className="flex items-center gap-3 text-xs font-medium">
                  <div className="flex items-center gap-1.5 text-violet-600 dark:text-violet-400">
                    <Activity className="h-3.5 w-3.5" />
                    <span>{perfStats.apdex.toFixed(2)} Apdex</span>
                  </div>
                  <div className="flex items-center gap-1.5 text-muted-foreground">
                    <Clock className="h-3.5 w-3.5" />
                    <span>{formatMs(perfStats.avgDuration)}</span>
                  </div>
                </div>
              ) : null
            }
          >
            {isLoadingPerf ? (
              <SkeletonSection />
            ) : !perfStats ? (
              <EmptySection message="No performance data" />
            ) : (
              <div>
                {perfStats.slowestTransactions.length > 0 && (
                  <div>
                    <p className="text-[11px] font-medium text-muted-foreground mb-1.5 px-1">Slowest Transactions</p>
                    <div className="space-y-0.5">
                      {perfStats.slowestTransactions.slice(0, 4).map((tx, i) => (
                        <div key={i} className="flex items-center gap-2 py-1.5 px-2.5 rounded-md bg-muted/20">
                          <Timer className="h-3 w-3 text-muted-foreground shrink-0" />
                          <span className="text-sm truncate flex-1">{tx.name}</span>
                          <span className="text-sm font-medium tabular-nums shrink-0">
                            {formatMs(tx.duration)}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            )}
          </DashboardSection>

          {/* ── Status Pages ────────────────────────────────────────── */}
          <DashboardSection
            title="Status Pages"
            icon={Globe}
            to="/status-pages"
            iconClassName="text-indigo-600 dark:text-indigo-400"
            iconBgClassName="bg-indigo-100 dark:bg-indigo-900/20"
            headerRight={
              statusPages.length > 0 ? (
                <div className="flex items-center gap-3 text-xs font-medium">
                  <div className="flex items-center gap-1.5 text-indigo-600 dark:text-indigo-400">
                    <Globe className="h-3.5 w-3.5" />
                    <span>{statusPages.filter(p => p.isPublic).length} Public</span>
                  </div>
                  <div className="flex items-center gap-1.5 text-muted-foreground">
                    <Shield className="h-3.5 w-3.5" />
                    <span>{statusPages.length} Total</span>
                  </div>
                </div>
              ) : null
            }
          >
            {isLoadingStatusPages ? (
              <SkeletonSection />
            ) : statusPages.length === 0 ? (
              <EmptySection message="No status pages configured" />
            ) : (
              <div className="space-y-0.5">
                {statusPages.map(page => {
                  const detail = statusPageDetailsById[page.id]
                  const monitorSummary = detail
                    ? summarizeStatusPageMonitors(
                      detail.monitors.map((monitor) => monitor.monitorId),
                      uptimeMonitorStatusById,
                    )
                    : null
                  const monitorStatusBadgeClass = !monitorSummary || monitorSummary.total === 0
                    ? 'text-muted-foreground'
                    : monitorSummary.down > 0
                      ? 'border-red-500/40 bg-red-500/10 text-red-600 dark:text-red-400'
                      : monitorSummary.pending > 0
                        ? 'border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400'
                        : 'border-emerald-500/40 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'

                  return (
                    <Link
                      key={page.id}
                      to="/status-pages/$pageId"
                      params={{pageId: page.id}}
                      className="grid gap-2.5 py-2.5 px-2.5 rounded-md hover:bg-muted/50 transition md:[grid-template-columns:minmax(0,15rem)_minmax(0,1fr)_auto] md:items-center"
                    >
                      <div className="flex min-w-0 items-center gap-2.5">
                        <Globe className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                        <div className="min-w-0">
                          <p className="text-sm truncate">{page.name}</p>
                          <p className="text-[11px] text-muted-foreground truncate">/s/{page.slug}</p>
                        </div>
                      </div>

                      <p className="text-[11px] text-muted-foreground leading-relaxed md:truncate">
                        {page.description || 'No description provided'}
                      </p>

                      <div className="flex items-center gap-1.5 flex-wrap md:justify-end">
                        <Badge variant="outline" className={`text-[10px] px-1.5 py-0 ${monitorStatusBadgeClass}`}>
                          {!monitorSummary || monitorSummary.total === 0
                            ? 'No monitors'
                            : monitorSummary.down > 0
                              ? `${monitorSummary.down} down`
                              : monitorSummary.pending > 0
                                ? `${monitorSummary.pending} pending`
                                : 'Operational'}
                        </Badge>
                        <Badge variant="outline" className="text-[10px] px-1.5 py-0 text-muted-foreground">
                          {monitorSummary ? `${monitorSummary.total} monitors` : 'Loading...'}
                        </Badge>
                        <Badge variant="outline" className="text-[10px] px-1.5 py-0">
                          {page.isPublic ? 'Public' : 'Private'}
                        </Badge>
                      </div>
                    </Link>
                  )
                })}
              </div>
            )}
          </DashboardSection>
        </div>

        {/* ── Third row: Releases, Replays, Feedback ───────────────── */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">

          {/* ── Releases ────────────────────────────────────────────── */}
          <DashboardSection
            title="Releases"
            icon={Package}
            to="/releases"
            iconClassName="text-blue-600 dark:text-blue-400"
            iconBgClassName="bg-blue-100 dark:bg-blue-900/20"
            headerRight={
              releases.length > 0 ? (
                <div className="flex items-center gap-3 text-xs font-medium">
                  <div className="flex items-center gap-1.5 text-muted-foreground">
                    <Terminal className="h-3.5 w-3.5" />
                    <span className="font-mono">{recentReleases[0]?.version ?? '—'}</span>
                  </div>
                  <div className="flex items-center gap-1.5 text-muted-foreground">
                    <Package className="h-3.5 w-3.5" />
                    <span>{releases.length} Total</span>
                  </div>
                </div>
              ) : null
            }
          >
            {isLoadingReleases ? (
              <SkeletonSection />
            ) : recentReleases.length === 0 ? (
              <EmptySection message="No releases" />
            ) : (
              <div className="space-y-0.5">
                {recentReleases.map(release => (
                  <Link
                    key={release.version}
                    to="/releases/$version"
                    params={{version: release.version}}
                    className="flex items-center gap-2 py-2 px-2.5 rounded-md hover:bg-muted/50 transition"
                  >
                    <Package className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                    <span className="text-sm font-mono truncate flex-1">{release.version}</span>
                    <div className="flex items-center gap-3 shrink-0 text-[11px] text-muted-foreground tabular-nums">
                      <span>{formatCount(release.eventCount)} events</span>
                      <span>{release.userCount} users</span>
                      {release.crashFreeRate != null && (
                        <span>{release.crashFreeRate.toFixed(1)}% crash-free</span>
                      )}
                    </div>
                  </Link>
                ))}
              </div>
            )}
          </DashboardSection>

          {/* ── Replays ─────────────────────────────────────────────── */}
          <DashboardSection
            title="Replays"
            icon={Play}
            to="/replays"
            iconClassName="text-cyan-600 dark:text-cyan-400"
            iconBgClassName="bg-cyan-100 dark:bg-cyan-900/20"
            headerRight={
              replays.length > 0 ? (
                <div className="flex items-center gap-3 text-xs font-medium">
                  {replaysWithErrors > 0 && (
                    <div className="flex items-center gap-1.5 text-red-600 dark:text-red-400">
                      <AlertCircle className="h-3.5 w-3.5" />
                      <span>{replaysWithErrors} Errors</span>
                    </div>
                  )}
                  <div className="flex items-center gap-1.5 text-muted-foreground">
                    <Play className="h-3.5 w-3.5" />
                    <span>{replays.length} Sessions</span>
                  </div>
                </div>
              ) : null
            }
          >
            {isLoadingReplays ? (
              <SkeletonSection />
            ) : replays.length === 0 ? (
              <EmptySection message="No recent replays" />
            ) : (
              <div className="space-y-0.5">
                {replays.slice(0, 5).map(replay => (
                  <Link
                    key={replay.replayId}
                    to="/replays/$replayId"
                    params={{replayId: replay.replayId}}
                    className="flex items-center gap-2 py-2 px-2.5 rounded-md hover:bg-muted/50 transition"
                  >
                    <Play className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                    <span className="text-sm truncate flex-1">
                      {replay.user?.email || replay.user?.username || replay.urls[0] || 'Session'}
                    </span>
                    <div className="flex items-center gap-2 shrink-0 text-[11px] text-muted-foreground tabular-nums">
                      {replay.errorCount > 0 && (
                        <span className="text-red-600/70 dark:text-red-400/70">{replay.errorCount} errors</span>
                      )}
                      <span>{Math.round(replay.durationMs / 1000)}s</span>
                      {replay.browserName && (
                        <span className="truncate max-w-[60px]">{replay.browserName}</span>
                      )}
                    </div>
                  </Link>
                ))}
              </div>
            )}
          </DashboardSection>

          {/* ── Feedback ────────────────────────────────────────────── */}
          <DashboardSection
            title="Feedback"
            icon={MessageSquare}
            to="/feedback"
            iconClassName="text-teal-600 dark:text-teal-400"
            iconBgClassName="bg-teal-100 dark:bg-teal-900/20"
            headerRight={
              feedback.length > 0 ? (
                <div className="flex items-center gap-3 text-xs font-medium">
                  {newFeedback > 0 && (
                    <div className="flex items-center gap-1.5 text-teal-600 dark:text-teal-400">
                      <MessageSquare className="h-3.5 w-3.5" />
                      <span>{newFeedback} New</span>
                    </div>
                  )}
                  <div className="flex items-center gap-1.5 text-muted-foreground">
                    <MessageSquare className="h-3.5 w-3.5" />
                    <span>{feedback.length} Total</span>
                  </div>
                </div>
              ) : null
            }
          >
            {isLoadingFeedback ? (
              <SkeletonSection />
            ) : recentFeedback.length === 0 ? (
              <EmptySection message="No feedback received" />
            ) : (
              <div className="space-y-0.5">
                {recentFeedback.map(fb => (
                  <Link
                    key={fb.feedbackId}
                    to="/feedback/$feedbackId"
                    params={{feedbackId: fb.feedbackId}}
                    className="flex items-center gap-2 py-2 px-2.5 rounded-md hover:bg-muted/50 transition"
                  >
                    <MessageSquare className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                    <span className="text-sm truncate flex-1">{fb.message}</span>
                    <div className="flex items-center gap-2 shrink-0 text-[11px] text-muted-foreground">
                      {fb.contactEmail && (
                        <span className="truncate max-w-[100px]">{fb.contactEmail}</span>
                      )}
                      <span>{formatRelativeTime(fb.timestamp)}</span>
                    </div>
                  </Link>
                ))}
              </div>
            )}
          </DashboardSection>
        </div>
      </div>
    </div>
  )
}
