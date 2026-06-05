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

import {createFileRoute, Link, Navigate} from '@tanstack/react-router'
import {LandingPage} from '@/components/landing/LandingPage'
import {useQuery} from '@tanstack/react-query'
import {type ComponentType, type ReactNode, useState, useEffect} from 'react'
import {
  api,
  type Feedback,
  type Incident,
  type Issue,
  type MonitorHostResponse,
  type OnCallSchedule,
  type PerformanceStats,
  type ProjectStats,
  type Release,
  type Replay,
  type StatusPage,
  type StatusPageDetail,
  type UptimeHeartbeat,
  type UptimeMonitor,
} from '@/lib/api'
import {hasEnterpriseModule, useEnterpriseFeatures} from '@/hooks/useEnterpriseFeatures'
import {formatRelativeTime} from '@/lib/utils'
import {APP_OVERVIEW_SEARCH, APP_OVERVIEW_VIEW, normalizeAppOverviewSearch} from '@/lib/overview-route'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {levelBadgeVariant, levelBorderClass} from '@/lib/severity'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {PageHeader} from '@/components/ui/page-header'
import HeartbeatBar from '@/components/uptime/HeartbeatBar'
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
import {StatsCard, StatsCardSkeleton} from '@/components/charts/StatsCard'
import {EventsChart, EventsChartSkeleton} from '@/components/charts/EventsChart'
import {getNow} from '@/lib/demo'
import {
  hasAccessibleServices,
  HOME_OVERVIEW_FEEDBACK_OPTIONS,
  HOME_OVERVIEW_ISSUES_QUERY,
  HOME_OVERVIEW_REPLAYS_OPTIONS,
  issueDetailSearch,
  primaryServiceResourceId,
} from '@/lib/service-facet-scope'

// ─── Incident status → badge variant ─────────────────────────────────
function incidentStatusVariant(status: string): BadgeProps['variant'] {
  switch (status) {
    case 'TRIGGERED':
      return 'danger'
    case 'ACKNOWLEDGED':
      return 'warning'
    case 'RESOLVED':
      return 'success'
    default:
      return 'neutral'
  }
}

function formatCount(n: number): string {
  if (n >= 100000) return `${(n / 1000).toFixed(0)}k`
  if (n >= 10000) return `${(n / 1000).toFixed(1)}k`
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`
  return n.toLocaleString()
}

function getPriorityColor(priority: string) {
  switch (priority) {
    case 'P0':
      return 'text-danger-fg'
    case 'P1':
      return 'text-[hsl(var(--chart-6))]'
    case 'P2':
      return 'text-warning-fg'
    case 'P3':
      return 'text-info-fg'
    default:
      return 'text-muted-foreground'
  }
}

function getStatusDot(status: string) {
  switch (status) {
    case 'up':
      return 'bg-success-solid'
    case 'down':
      return 'bg-danger-solid'
    case 'degraded':
      return 'bg-warning-solid'
    default:
      return 'bg-muted-foreground/40'
  }
}

function normalizeHostStatus(status?: string): 'up' | 'down' | 'degraded' | 'unknown' {
  const normalized = (status ?? '').toLowerCase()
  if (normalized === 'online') return 'up'
  if (normalized === 'offline') return 'down'
  if (normalized === 'up' || normalized === 'down' || normalized === 'degraded') return normalized
  return 'unknown'
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
  validateSearch: normalizeAppOverviewSearch,
  component: IndexPage,
})

function IndexPage() {
  const {view} = Route.useSearch()
  const [isAuthenticated, setIsAuthenticated] = useState(api.isAuthenticated())
  const [isChecking, setIsChecking] = useState(true)
  const { data: features, isLoading: featuresLoading } = useEnterpriseFeatures()

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
  if (isChecking || (!isAuthenticated && featuresLoading)) {
    return null
  }

  const isOverviewView = view === APP_OVERVIEW_VIEW
  const showPublicLandingPage = !isAuthenticated || !isOverviewView
  if (showPublicLandingPage) {
    if (features?.selfHost) {
      if (isAuthenticated) {
        return <Navigate to="/" search={APP_OVERVIEW_SEARCH} />
      }
      return <Navigate to="/login" />
    }
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
}: Readonly<{
  title: string
  icon: ComponentType<{ className?: string }>
  to: string
  children: ReactNode
  headerRight?: ReactNode
  iconClassName?: string
  iconBgClassName?: string
}>) {
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
          <div className="hidden sm:block">{headerRight}</div>
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

function EmptySection({message}: Readonly<{ message: string }>) {
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

type DashboardReleaseSummary = {
  version: string
  eventCount: number
  userCount: number
  crashFreeRate?: number | null
}

function renderDashboardReleasesContent(
  isLoadingReleases: boolean,
  recentReleases: DashboardReleaseSummary[]
): ReactNode {
  if (isLoadingReleases) return <SkeletonSection />
  if (recentReleases.length === 0) return <EmptySection message="No releases" />

  return (
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
            <span>{formatCount(release.eventCount)} signals</span>
            <span>{release.userCount} users</span>
            {release.crashFreeRate != null && (
              <span>{release.crashFreeRate.toFixed(1)}% crash-free</span>
            )}
          </div>
        </Link>
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
  if (percent >= 85) return 'bg-danger-solid/80'
  if (percent >= 70) return 'bg-warning-solid/80'
  return 'bg-success-solid/75'
}

function UtilizationBar({
  label,
  value,
}: Readonly<{
  label: string
  value?: number | null
}>) {
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

type DashboardStatsOverviewProps = Readonly<{
  isLoading: boolean
  stats?: ProjectStats | null
  unresolvedIssueCount: number
  activeIncidentCount: number
  uptimeUp: number
  uptimeDown: number
  uptimeCount: number
  hostsUp: number
  hostsDown: number
  hostCount: number
}>

function DashboardStatsOverview({
  isLoading,
  stats,
  unresolvedIssueCount,
  activeIncidentCount,
  uptimeUp,
  uptimeDown,
  uptimeCount,
  hostsUp,
  hostsDown,
  hostCount,
}: DashboardStatsOverviewProps) {
  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2 mb-4">
      {isLoading ? (
        <>
          <StatsCardSkeleton accent="amber" compact />
          <StatsCardSkeleton accent="blue" compact />
          <StatsCardSkeleton accent="rose" compact />
          <StatsCardSkeleton accent="emerald" compact />
          <StatsCardSkeleton accent="cyan" compact />
          <StatsCardSkeleton accent="violet" compact />
        </>
      ) : (
        <>
          <StatsCard
            title="Unresolved Issues"
            value={formatCount(stats?.unresolvedIssues ?? unresolvedIssueCount)}
            icon={AlertCircle}
            accent="amber"
            compact
          />
          <StatsCard
            title="Errors (24h)"
            value={formatCount(stats?.totalEvents ?? 0)}
            icon={Activity}
            accent="blue"
            compact
          />
          <StatsCard title="Active Incidents" value={activeIncidentCount} icon={Bell} accent="rose" compact />
          <StatsCard
            title="Uptime Monitors"
            value={`${uptimeUp}/${uptimeCount}`}
            icon={HeartPulse}
            accent="emerald"
            subtitle={uptimeDown > 0 ? `${uptimeDown} down` : 'All healthy'}
            compact
          />
          <StatsCard
            title="Infrastructure"
            value={`${hostsUp}/${hostCount}`}
            icon={Server}
            accent="cyan"
            subtitle={hostsDown > 0 ? `${hostsDown} down` : 'All online'}
            compact
          />
          <StatsCard
            title="Users (24h)"
            value={formatCount(stats?.affectedUsers ?? 0)}
            icon={Users}
            accent="violet"
            compact
          />
        </>
      )}
    </div>
  )
}

function DashboardEventsOverview({
  isLoading,
  stats,
}: Readonly<{
  isLoading: boolean
  stats?: ProjectStats | null
}>) {
  if (isLoading) {
    return (
      <div className="mb-5 h-[100px]">
        <EventsChartSkeleton fillHeight compact />
      </div>
    )
  }

  if (!stats || stats.eventsTimeline.length === 0) return null

  return (
    <div className="mb-5 h-[100px]">
      <EventsChart data={stats.eventsTimeline} title="Errors — Last 24 Hours" fillHeight compact />
    </div>
  )
}

function renderOnCallHeader(triggeredAlerts: Incident[], activeAlerts: Incident[]): ReactNode {
  if (triggeredAlerts.length === 0 && activeAlerts.length === 0) return null

  return (
    <div className="flex items-center gap-3 text-xs font-medium">
      {triggeredAlerts.length > 0 && (
        <div className="flex items-center gap-1.5 text-danger-fg">
          <Bell className="h-3.5 w-3.5" />
          <span>{triggeredAlerts.length} Triggered alerts</span>
        </div>
      )}
      {activeAlerts.length > 0 && (
        <div className="flex items-center gap-1.5 text-muted-foreground">
          <Activity className="h-3.5 w-3.5" />
          <span>{activeAlerts.length} Active</span>
        </div>
      )}
    </div>
  )
}

function OnCallCurrentAssignments({schedules}: Readonly<{ schedules: OnCallSchedule[] }>) {
  const currentSchedules = schedules.filter(schedule => schedule.currentOnCall)
  if (currentSchedules.length === 0) return null

  return (
    <div className="mb-3 flex flex-wrap gap-1.5">
      {currentSchedules.map(schedule => (
        <div
          key={schedule.id}
          className="flex items-center gap-1.5 text-xs bg-muted/50 border border-border/40 rounded-md px-2.5 py-1"
        >
          <Shield className="h-3 w-3 text-muted-foreground" />
          <span className="font-medium">{schedule.currentOnCall?.userName}</span>
          <span className="text-muted-foreground">on {schedule.name}</span>
        </div>
      ))}
    </div>
  )
}

function OnCallRecentAlertsContent({
  isLoading,
  alerts,
}: Readonly<{
  isLoading: boolean
  alerts: Incident[]
}>) {
  if (isLoading) return <SkeletonSection />
  if (alerts.length === 0) return <EmptySection message="No alerts" />

  return (
    <div className="space-y-0.5">
      {alerts.slice(0, 5).map(alert => (
        <a
          key={alert.id}
          href={`/on-call/alerts/${alert.id}`}
          className="grid grid-cols-[7.75rem_auto_minmax(0,1fr)_auto] items-center gap-2 py-2 px-2.5 rounded-md hover:bg-muted/50 transition"
        >
          <Badge variant={incidentStatusVariant(alert.status)} className="w-full justify-center text-[10px] px-1.5 py-0 shrink-0">
            {alert.status}
          </Badge>
          <span className={`text-[11px] font-semibold ${getPriorityColor(alert.priority)} shrink-0 tabular-nums`}>
            {alert.priority}
          </span>
          <span className="text-sm truncate flex-1">{alert.title}</span>
          <span className="text-[11px] text-muted-foreground shrink-0">
            {formatRelativeTime(alert.triggeredAt)}
          </span>
        </a>
      ))}
    </div>
  )
}

function OnCallDashboardSection({
  hasOnCall,
  triggeredAlerts,
  activeAlerts,
  recentAlerts,
  isLoading,
  schedules,
}: Readonly<{
  hasOnCall: boolean
  triggeredAlerts: Incident[]
  activeAlerts: Incident[]
  recentAlerts: Incident[]
  isLoading: boolean
  schedules: OnCallSchedule[]
}>) {
  if (!hasOnCall) return null

  return (
    <DashboardSection
      title="On-Call"
      icon={Bell}
      to="/on-call"
      iconClassName="text-warning-fg"
      iconBgClassName="bg-warning-bg"
      headerRight={renderOnCallHeader(triggeredAlerts, activeAlerts)}
    >
      <OnCallCurrentAssignments schedules={schedules} />
      <OnCallRecentAlertsContent isLoading={isLoading} alerts={recentAlerts} />
    </DashboardSection>
  )
}

type IssuePlatformInfo = {
  Icon: ComponentType<{ className?: string }>
  label: string
}

function getIssuePlatformInfo(platform?: string | null): IssuePlatformInfo | null {
  const normalized = platform?.toLowerCase() ?? ''
  if (normalized.includes('cocoa') || normalized.includes('ios')) {
    return {Icon: Smartphone, label: 'iOS'}
  }
  if (normalized.includes('android')) return {Icon: Smartphone, label: 'Android'}
  if (normalized.includes('javascript') || normalized.includes('node')) return {Icon: Globe, label: 'Web'}
  if (normalized.includes('python') || normalized.includes('java') || normalized.includes('go')) {
    return {Icon: Server, label: 'Backend'}
  }
  return null
}

function renderIssuesHeader(unresolvedCount: number, issueLevelCounts: Record<string, number>): ReactNode {
  if (unresolvedCount === 0) return null

  return (
    <div className="flex items-center gap-3 text-xs font-medium">
      <div className="flex items-center gap-1.5 text-muted-foreground">
        <AlertCircle className="h-3.5 w-3.5" />
        <span>{unresolvedCount} Total</span>
      </div>
      {issueLevelCounts.fatal > 0 && (
        <div className="flex items-center gap-1.5 text-danger-fg">
          <XCircle className="h-3.5 w-3.5" />
          <span>{issueLevelCounts.fatal} Fatal</span>
        </div>
      )}
      {issueLevelCounts.error > 0 && (
        <div className="flex items-center gap-1.5 text-danger-fg">
          <AlertTriangle className="h-3.5 w-3.5" />
          <span>{issueLevelCounts.error} Error</span>
        </div>
      )}
    </div>
  )
}

function IssueOverviewRow({issue}: Readonly<{ issue: Issue }>) {
  const levelAccent = levelBorderClass(issue.level)
  const platformInfo = getIssuePlatformInfo(issue.platform)

  return (
    <Link
      key={issue.id}
      to="/issues/$issueId"
      params={{issueId: issue.id}}
      search={issueDetailSearch(issue)}
      className={`flex items-center gap-2 py-2 px-2.5 rounded-md border-l-2 ${levelAccent} hover:bg-muted/50 transition`}
    >
      <Badge variant={levelBadgeVariant(issue.level)} className="text-[10px] px-1.5 py-0 w-14 justify-center shrink-0">
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
          {issue.lastSeen && <span>{formatRelativeTime(issue.lastSeen)}</span>}
        </div>
      </div>
      <div className="flex items-center gap-3 shrink-0">
        <span className="text-[11px] text-muted-foreground tabular-nums">
          {formatCount(issue.eventCount)} events
        </span>
      </div>
    </Link>
  )
}

function IssuesDashboardSection({
  isLoading,
  unresolvedIssues,
  issueLevelCounts,
}: Readonly<{
  isLoading: boolean
  unresolvedIssues: Issue[]
  issueLevelCounts: Record<string, number>
}>) {
  let content: ReactNode

  if (isLoading) {
    content = <SkeletonSection />
  } else if (unresolvedIssues.length === 0) {
    content = <EmptySection message="No unresolved issues" />
  } else {
    content = (
      <div className="space-y-0.5">
        {unresolvedIssues.slice(0, 6).map(issue => <IssueOverviewRow key={issue.id} issue={issue} />)}
      </div>
    )
  }

  return (
    <DashboardSection
      title="Issues"
      icon={AlertCircle}
      to="/issues"
      iconClassName="text-danger-fg"
      iconBgClassName="bg-danger-bg"
      headerRight={renderIssuesHeader(unresolvedIssues.length, issueLevelCounts)}
    >
      {content}
    </DashboardSection>
  )
}

function renderUptimeHeader(uptimeMonitors: UptimeMonitor[], uptimeUp: number, uptimeDown: number): ReactNode {
  if (uptimeMonitors.length === 0) return null

  return (
    <div className="flex items-center gap-3 text-xs font-medium">
      {uptimeDown > 0 && (
        <div className="flex items-center gap-1.5 text-danger-fg">
          <ArrowDown className="h-3.5 w-3.5" />
          <span>{uptimeDown} Down</span>
        </div>
      )}
      {uptimeUp > 0 && (
        <div className="flex items-center gap-1.5 text-success-fg">
          <ArrowUp className="h-3.5 w-3.5" />
          <span>{uptimeUp} Up</span>
        </div>
      )}
      <div className="flex items-center gap-1.5 text-muted-foreground">
        <Activity className="h-3.5 w-3.5" />
        <span>{uptimeMonitors.length} Total</span>
      </div>
    </div>
  )
}

function sampledSuccessPercent(heartbeats: UptimeHeartbeat[]): number | null {
  if (heartbeats.length === 0) return null
  return (heartbeats.filter((heartbeat) => heartbeat.status === 1).length / heartbeats.length) * 100
}

function UptimeMonitorOverviewRow({
  monitor,
  heartbeats,
  nowMs,
}: Readonly<{
  monitor: UptimeMonitor
  heartbeats: UptimeHeartbeat[]
  nowMs: number
}>) {
  const latestHeartbeat = getLatestHeartbeat(heartbeats)
  const isHeartbeatFresh = isRecentHeartbeat(monitor.lastCheckAt, monitor.intervalSeconds, nowMs)
  const sampledSuccess = sampledSuccessPercent(heartbeats)

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
          <Badge variant="warning" className="text-[10px] px-1.5 py-0">
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
          {sampledSuccess != null && <span>{sampledSuccess.toFixed(0)}% success</span>}
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
}

function UptimeDashboardSection({
  isLoading,
  uptimeMonitors,
  dashboardUptimeMonitors,
  uptimeHeartbeatsByMonitor,
  nowMs,
  uptimeUp,
  uptimeDown,
}: Readonly<{
  isLoading: boolean
  uptimeMonitors: UptimeMonitor[]
  dashboardUptimeMonitors: UptimeMonitor[]
  uptimeHeartbeatsByMonitor: Record<string, UptimeHeartbeat[]>
  nowMs: number
  uptimeUp: number
  uptimeDown: number
}>) {
  let content: ReactNode

  if (isLoading) {
    content = <SkeletonSection />
  } else if (uptimeMonitors.length === 0) {
    content = <EmptySection message="No uptime monitors configured" />
  } else {
    content = (
      <div className="space-y-0.5">
        {dashboardUptimeMonitors.map(monitor => (
          <UptimeMonitorOverviewRow
            key={monitor.id}
            monitor={monitor}
            heartbeats={uptimeHeartbeatsByMonitor[monitor.id] ?? []}
            nowMs={nowMs}
          />
        ))}
      </div>
    )
  }

  return (
    <DashboardSection
      title="Uptime"
      icon={HeartPulse}
      to="/uptime"
      iconClassName="text-success-fg"
      iconBgClassName="bg-success-bg"
      headerRight={renderUptimeHeader(uptimeMonitors, uptimeUp, uptimeDown)}
    >
      {content}
    </DashboardSection>
  )
}

function renderInfrastructureHeader(
  monitorHosts: MonitorHostResponse[],
  hostsUp: number,
  hostsDown: number,
): ReactNode {
  if (monitorHosts.length === 0) return null

  return (
    <div className="flex items-center gap-3 text-xs font-medium">
      {hostsDown > 0 && (
        <div className="flex items-center gap-1.5 text-danger-fg">
          <ArrowDown className="h-3.5 w-3.5" />
          <span>{hostsDown} Offline</span>
        </div>
      )}
      {hostsUp > 0 && (
        <div className="flex items-center gap-1.5 text-chart-2">
          <ArrowUp className="h-3.5 w-3.5" />
          <span>{hostsUp} Online</span>
        </div>
      )}
      <div className="flex items-center gap-1.5 text-muted-foreground">
        <Server className="h-3.5 w-3.5" />
        <span>{monitorHosts.length} Total</span>
      </div>
    </div>
  )
}

function InfrastructureHostOverviewRow({host}: Readonly<{ host: MonitorHostResponse }>) {
  return (
    <Link
      key={host.id}
      to="/monitoring/hosts/$hostId"
      params={{hostId: String(host.id)}}
      className="grid gap-3 py-2 px-2.5 rounded-md hover:bg-muted/50 transition md:[grid-template-columns:minmax(12rem,15rem)_minmax(0,1fr)_minmax(0,1fr)_minmax(0,1fr)_minmax(0,11rem)] md:items-center"
    >
      <div className="flex min-w-0 items-center gap-2.5">
        <span className={`h-2 w-2 rounded-full shrink-0 ${getStatusDot(normalizeHostStatus(host.status))}`} />
        <span className="text-sm flex-1 truncate min-w-0">{host.name ?? host.hostname}</span>
      </div>

      <UtilizationBar label="CPU" value={host.latest_metrics?.cpu_percent} />
      <UtilizationBar label="RAM" value={host.latest_metrics?.mem_percent} />
      <UtilizationBar label="Disk" value={host.latest_metrics?.disk_percent} />
      {host.os ? (
        <Badge
          variant="outline"
          className="w-fit max-w-[11rem] justify-self-start truncate text-[10px] px-2 py-0"
        >
          {host.os}
        </Badge>
      ) : (
        <span className="text-[10px] text-muted-foreground">—</span>
      )}
    </Link>
  )
}

function InfrastructureDashboardSection({
  isLoading,
  monitorHosts,
  hostsUp,
  hostsDown,
}: Readonly<{
  isLoading: boolean
  monitorHosts: MonitorHostResponse[]
  hostsUp: number
  hostsDown: number
}>) {
  let content: ReactNode

  if (isLoading) {
    content = <SkeletonSection />
  } else if (monitorHosts.length === 0) {
    content = <EmptySection message="No hosts being monitored" />
  } else {
    content = (
      <div className="space-y-0.5">
        {monitorHosts.slice(0, 6).map(host => <InfrastructureHostOverviewRow key={host.id} host={host} />)}
      </div>
    )
  }

  return (
    <DashboardSection
      title="Infrastructure"
      icon={Server}
      to="/monitoring"
      iconClassName="text-chart-2"
      iconBgClassName="bg-chart-2/15"
      headerRight={renderInfrastructureHeader(monitorHosts, hostsUp, hostsDown)}
    >
      {content}
    </DashboardSection>
  )
}

function renderTracesHeader(perfStats?: PerformanceStats | null): ReactNode {
  if (!perfStats) return null

  return (
    <div className="flex items-center gap-3 text-xs font-medium">
      <div className="flex items-center gap-1.5 text-primary">
        <Activity className="h-3.5 w-3.5" />
        <span>{perfStats.apdex.toFixed(2)} Apdex</span>
      </div>
      <div className="flex items-center gap-1.5 text-muted-foreground">
        <Clock className="h-3.5 w-3.5" />
        <span>{formatMs(perfStats.avgDuration)}</span>
      </div>
    </div>
  )
}

function TracesDashboardSection({
  isLoading,
  perfStats,
}: Readonly<{
  isLoading: boolean
  perfStats?: PerformanceStats | null
}>) {
  let content: ReactNode

  if (isLoading) {
    content = <SkeletonSection />
  } else if (perfStats) {
    content = (
      <div>
        {perfStats.slowestTransactions.length > 0 && (
          <div>
            <p className="text-[11px] font-medium text-muted-foreground mb-1.5 px-1">Slowest Transactions</p>
            <div className="space-y-0.5">
              {perfStats.slowestTransactions.slice(0, 4).map(tx => (
                <div key={`${tx.name}-${tx.duration}`} className="flex items-center gap-2 py-1.5 px-2.5 rounded-md bg-muted/20">
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
    )
  } else {
    content = <EmptySection message="No performance data" />
  }

  return (
    <DashboardSection
      title="Traces"
      icon={Zap}
      to="/performance/traces"
      iconClassName="text-primary"
      iconBgClassName="bg-[hsl(var(--primary)/0.12)]"
      headerRight={renderTracesHeader(perfStats)}
    >
      {content}
    </DashboardSection>
  )
}

function renderStatusPagesHeader(statusPages: StatusPage[]): ReactNode {
  if (statusPages.length === 0) return null

  return (
    <div className="flex items-center gap-3 text-xs font-medium">
      <div className="flex items-center gap-1.5 text-chart-7">
        <Globe className="h-3.5 w-3.5" />
        <span>{statusPages.filter(page => page.isPublic).length} Public</span>
      </div>
      <div className="flex items-center gap-1.5 text-muted-foreground">
        <Shield className="h-3.5 w-3.5" />
        <span>{statusPages.length} Total</span>
      </div>
    </div>
  )
}

function getMonitorStatusBadgeClass(monitorSummary: StatusPageMonitorSummary | null): string {
  if (!monitorSummary || monitorSummary.total === 0) return 'text-muted-foreground'
  if (monitorSummary.down > 0) return 'border-danger-border bg-danger-bg text-danger-fg'
  if (monitorSummary.pending > 0) return 'border-warning-border bg-warning-bg text-warning-fg'
  return 'border-success-border bg-success-bg text-success-fg'
}

function getMonitorStatusLabel(monitorSummary: StatusPageMonitorSummary | null): string {
  if (!monitorSummary || monitorSummary.total === 0) return 'No monitors'
  if (monitorSummary.down > 0) return `${monitorSummary.down} down`
  if (monitorSummary.pending > 0) return `${monitorSummary.pending} pending`
  return 'Operational'
}

function StatusPageOverviewRow({
  page,
  detail,
  uptimeMonitorStatusById,
}: Readonly<{
  page: StatusPage
  detail?: StatusPageDetail
  uptimeMonitorStatusById: Map<string, string>
}>) {
  const monitorSummary = detail
    ? summarizeStatusPageMonitors(
      detail.monitors.map((monitor) => monitor.monitorId),
      uptimeMonitorStatusById,
    )
    : null
  const monitorStatusBadgeClass = getMonitorStatusBadgeClass(monitorSummary)

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
          {getMonitorStatusLabel(monitorSummary)}
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
}

function StatusPagesDashboardSection({
  isLoading,
  statusPages,
  statusPageDetailsById,
  uptimeMonitorStatusById,
}: Readonly<{
  isLoading: boolean
  statusPages: StatusPage[]
  statusPageDetailsById: Record<string, StatusPageDetail>
  uptimeMonitorStatusById: Map<string, string>
}>) {
  let content: ReactNode

  if (isLoading) {
    content = <SkeletonSection />
  } else if (statusPages.length === 0) {
    content = <EmptySection message="No status pages configured" />
  } else {
    content = (
      <div className="space-y-0.5">
        {statusPages.map(page => (
          <StatusPageOverviewRow
            key={page.id}
            page={page}
            detail={statusPageDetailsById[page.id]}
            uptimeMonitorStatusById={uptimeMonitorStatusById}
          />
        ))}
      </div>
    )
  }

  return (
    <DashboardSection
      title="Status Pages"
      icon={Globe}
      to="/status-pages"
      iconClassName="text-chart-7"
      iconBgClassName="bg-chart-7/15"
      headerRight={renderStatusPagesHeader(statusPages)}
    >
      {content}
    </DashboardSection>
  )
}

function renderReleasesHeader(releases: Release[], recentReleases: Release[]): ReactNode {
  if (releases.length === 0) return null

  return (
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
  )
}

function ReleasesDashboardSection({
  releases,
  recentReleases,
  content,
}: Readonly<{
  releases: Release[]
  recentReleases: Release[]
  content: ReactNode
}>) {
  return (
    <DashboardSection
      title="Releases"
      icon={Package}
      to="/releases"
      iconClassName="text-info-fg"
      iconBgClassName="bg-info-bg"
      headerRight={renderReleasesHeader(releases, recentReleases)}
    >
      {content}
    </DashboardSection>
  )
}

function renderReplaysHeader(replays: Replay[], replaysWithErrors: number): ReactNode {
  if (replays.length === 0) return null

  return (
    <div className="flex items-center gap-3 text-xs font-medium">
      {replaysWithErrors > 0 && (
        <div className="flex items-center gap-1.5 text-danger-fg">
          <AlertCircle className="h-3.5 w-3.5" />
          <span>{replaysWithErrors} Errors</span>
        </div>
      )}
      <div className="flex items-center gap-1.5 text-muted-foreground">
        <Play className="h-3.5 w-3.5" />
        <span>{replays.length} Sessions</span>
      </div>
    </div>
  )
}

function replayTitle(replay: Replay): string {
  return replay.user?.email || replay.user?.username || replay.urls[0] || 'Session'
}

function ReplaysDashboardSection({
  isLoading,
  replays,
  replaysWithErrors,
}: Readonly<{
  isLoading: boolean
  replays: Replay[]
  replaysWithErrors: number
}>) {
  let content: ReactNode

  if (isLoading) {
    content = <SkeletonSection />
  } else if (replays.length === 0) {
    content = <EmptySection message="No recent replays" />
  } else {
    content = (
      <div className="space-y-0.5">
        {replays.slice(0, 5).map(replay => (
          <Link
            key={replay.replayId}
            to="/replays/$replayId"
            params={{replayId: replay.replayId}}
            className="flex items-center gap-2 py-2 px-2.5 rounded-md hover:bg-muted/50 transition"
          >
            <Play className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
            <span className="text-sm truncate flex-1">{replayTitle(replay)}</span>
            <div className="flex items-center gap-2 shrink-0 text-[11px] text-muted-foreground tabular-nums">
              {replay.errorCount > 0 && <span className="text-danger-fg/80">{replay.errorCount} errors</span>}
              <span>{Math.round(replay.durationMs / 1000)}s</span>
              {replay.browserName && <span className="truncate max-w-[60px]">{replay.browserName}</span>}
            </div>
          </Link>
        ))}
      </div>
    )
  }

  return (
    <DashboardSection
      title="Replays"
      icon={Play}
      to="/replays"
      iconClassName="text-chart-3"
      iconBgClassName="bg-chart-3/15"
      headerRight={renderReplaysHeader(replays, replaysWithErrors)}
    >
      {content}
    </DashboardSection>
  )
}

function renderFeedbackHeader(feedback: Feedback[], newFeedback: number): ReactNode {
  if (feedback.length === 0) return null

  return (
    <div className="flex items-center gap-3 text-xs font-medium">
      {newFeedback > 0 && (
        <div className="flex items-center gap-1.5 text-info-fg">
          <MessageSquare className="h-3.5 w-3.5" />
          <span>{newFeedback} New</span>
        </div>
      )}
      <div className="flex items-center gap-1.5 text-muted-foreground">
        <MessageSquare className="h-3.5 w-3.5" />
        <span>{feedback.length} Total</span>
      </div>
    </div>
  )
}

function FeedbackDashboardSection({
  isLoading,
  feedback,
  recentFeedback,
  newFeedback,
}: Readonly<{
  isLoading: boolean
  feedback: Feedback[]
  recentFeedback: Feedback[]
  newFeedback: number
}>) {
  let content: ReactNode

  if (isLoading) {
    content = <SkeletonSection />
  } else if (recentFeedback.length === 0) {
    content = <EmptySection message="No feedback received" />
  } else {
    content = (
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
              {fb.contactEmail && <span className="truncate max-w-[100px]">{fb.contactEmail}</span>}
              <span>{formatRelativeTime(fb.timestamp)}</span>
            </div>
          </Link>
        ))}
      </div>
    )
  }

  return (
    <DashboardSection
      title="Feedback"
      icon={MessageSquare}
      to="/feedback"
      iconClassName="text-info-fg"
      iconBgClassName="bg-info-bg"
      headerRight={renderFeedbackHeader(feedback, newFeedback)}
    >
      {content}
    </DashboardSection>
  )
}

// ─── Main Dashboard ───────────────────────────────────────────────────
function DashboardPage() {
  const {data: projects, isLoading: isLoadingServices} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const hasServices = hasAccessibleServices(projects)
  const primaryServiceId = primaryServiceResourceId(projects)

  const {data: stats, isLoading: isLoadingStats} = useQuery({
    queryKey: ['stats', 'primary-service', primaryServiceId],
    queryFn: () => (primaryServiceId ? api.getProjectStats(primaryServiceId, '24h') : null),
    enabled: !!primaryServiceId,
  })

  const {data: issues = [], isLoading: isLoadingIssues} = useQuery({
    queryKey: ['issues', 'organization', 'unresolved', 'overview'],
    queryFn: () => api.getOrganizationIssues(HOME_OVERVIEW_ISSUES_QUERY),
    enabled: hasServices,
  })

  const {data: perfStats, isLoading: isLoadingPerf} = useQuery({
    queryKey: ['perf-stats', 'primary-service', primaryServiceId],
    queryFn: () => (primaryServiceId ? api.getPerformanceStats(primaryServiceId, {period: '24h'}) : null),
    enabled: !!primaryServiceId,
  })

  const {data: releases = [], isLoading: isLoadingReleases} = useQuery({
    queryKey: ['releases-overview', 'organization'],
    queryFn: () => api.getOrganizationReleases(),
    enabled: hasServices,
  })

  const {data: replays = [], isLoading: isLoadingReplays} = useQuery({
    queryKey: ['replays-overview', 'organization'],
    queryFn: () => api.getOrganizationReplays(HOME_OVERVIEW_REPLAYS_OPTIONS),
    enabled: hasServices,
  })

  const {data: feedback = [], isLoading: isLoadingFeedback} = useQuery({
    queryKey: ['feedback-overview', 'organization'],
    queryFn: () => api.getOrganizationFeedback(HOME_OVERVIEW_FEEDBACK_OPTIONS),
    enabled: hasServices,
  })

  const {data: uptimeMonitors = [], isLoading: isLoadingUptime} = useQuery({
    queryKey: ['uptime-monitors'],
    queryFn: () => api.getUptimeMonitors(),
  })

  const {data: monitorHosts = [], isLoading: isLoadingMonitors} = useQuery({
    queryKey: ['monitor-hosts'],
    queryFn: () => api.getMonitorHosts(),
  })

  const {data: enterpriseFeatures} = useEnterpriseFeatures()
  const hasOnCall = hasEnterpriseModule(enterpriseFeatures, 'oncall')

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
  const recentAlerts = activeIncidents.length > 0 ? activeIncidents : incidents
  const isOnCallLoading = isLoadingIncidents || isLoadingSchedules
  const uptimeUp = uptimeMonitors.filter(m => m.status === 'up').length
  const uptimeDown = uptimeMonitors.filter(m => m.status === 'down').length
  const hostsUp = monitorHosts.filter(h => normalizeHostStatus(h.status) === 'up').length
  const hostsDown = monitorHosts.filter(h => normalizeHostStatus(h.status) === 'down').length
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

  const releasesSectionContent = renderDashboardReleasesContent(isLoadingReleases, recentReleases)

  // Feedback status counts
  const newFeedback = feedback.filter(f => f.status === 'new' || f.status === 'unresolved').length
  const replaysWithErrors = replays.filter(r => r.errorCount > 0).length

  return (
    <div className="min-h-screen">
      <div className="px-6 py-3">
        <PageHeader
          className="mb-3"
          title="Overview"
          description="Overview of your systems, applications, and incidents"
        />

        <DashboardStatsOverview
          isLoading={isLoadingServices || isLoadingStats || isLoadingIssues}
          stats={stats}
          unresolvedIssueCount={unresolvedIssues.length}
          activeIncidentCount={activeIncidents.length}
          uptimeUp={uptimeUp}
          uptimeDown={uptimeDown}
          uptimeCount={uptimeMonitors.length}
          hostsUp={hostsUp}
          hostsDown={hostsDown}
          hostCount={monitorHosts.length}
        />

        <DashboardEventsOverview isLoading={isLoadingStats} stats={stats} />

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mb-4">
          <OnCallDashboardSection
            hasOnCall={hasOnCall}
            triggeredAlerts={triggeredIncidents}
            activeAlerts={activeIncidents}
            recentAlerts={recentAlerts}
            isLoading={isOnCallLoading}
            schedules={onCallSchedules}
          />
          <IssuesDashboardSection
            isLoading={isLoadingIssues}
            unresolvedIssues={unresolvedIssues}
            issueLevelCounts={issueLevelCounts}
          />
          <UptimeDashboardSection
            isLoading={isLoadingUptime}
            uptimeMonitors={uptimeMonitors}
            dashboardUptimeMonitors={dashboardUptimeMonitors}
            uptimeHeartbeatsByMonitor={uptimeHeartbeatsByMonitor}
            nowMs={nowMs}
            uptimeUp={uptimeUp}
            uptimeDown={uptimeDown}
          />
          <InfrastructureDashboardSection
            isLoading={isLoadingMonitors}
            monitorHosts={monitorHosts}
            hostsUp={hostsUp}
            hostsDown={hostsDown}
          />
          <TracesDashboardSection isLoading={isLoadingPerf} perfStats={perfStats} />
          <StatusPagesDashboardSection
            isLoading={isLoadingStatusPages}
            statusPages={statusPages}
            statusPageDetailsById={statusPageDetailsById}
            uptimeMonitorStatusById={uptimeMonitorStatusById}
          />
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <ReleasesDashboardSection
            releases={releases}
            recentReleases={recentReleases}
            content={releasesSectionContent}
          />
          <ReplaysDashboardSection
            isLoading={isLoadingReplays}
            replays={replays}
            replaysWithErrors={replaysWithErrors}
          />
          <FeedbackDashboardSection
            isLoading={isLoadingFeedback}
            feedback={feedback}
            recentFeedback={recentFeedback}
            newFeedback={newFeedback}
          />
        </div>
      </div>
    </div>
  )
}
