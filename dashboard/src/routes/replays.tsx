import {createFileRoute, Outlet, redirect, useMatches, useNavigate} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api, formatErrorForLogging} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {formatRelativeTime} from '@/lib/utils'
import {useEffect, useMemo, useState} from 'react'
import {Card} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {Input} from '@/components/ui/input'
import {Avatar, AvatarFallback} from '@/components/ui/avatar'
import {Button} from '@/components/ui/button'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from '@/components/ui/select'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger,} from '@/components/ui/tooltip'
import {
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  Clock,
  Globe,
  Monitor,
  MousePointerClick,
  Play,
  Search,
  Timer,
  User,
  Video,
} from 'lucide-react'

export const Route = createFileRoute('/replays')({
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
  component: ReplaysLayout,
})

function ReplaysLayout() {
  const matches = useMatches()
  const showingChildRoute = matches.some((match) => match.id.includes('/replays/$replayId'))

  if (showingChildRoute) {
    return <Outlet />
  }

  return <ReplaysPage />
}

function formatDuration(ms: number) {
  if (ms < 1000) return `${ms.toFixed(0)}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  const minutes = Math.floor(ms / 60000)
  const seconds = Math.floor((ms % 60000) / 1000)
  if (minutes < 60) return `${minutes}m ${seconds}s`
  const hours = Math.floor(minutes / 60)
  return `${hours}h ${minutes % 60}m`
}

function formatDate(isoString: string) {
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

function getInitials(user?: { id?: string; email?: string; username?: string }): string {
  if (user?.username) {
    const parts = user.username.trim().split(/\s+/)
    if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
    return user.username.slice(0, 2).toUpperCase()
  }
  if (user?.email) return user.email.slice(0, 2).toUpperCase()
  if (user?.id) return user.id.slice(0, 2).toUpperCase()
  return '?'
}

function getAvatarColor(user?: { id?: string; email?: string; username?: string }): string {
  const str = user?.username || user?.email || user?.id || ''
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash)
  }
  const colors = [
    'bg-violet-500/20 text-violet-700 dark:text-violet-300',
    'bg-blue-500/20 text-blue-700 dark:text-blue-300',
    'bg-emerald-500/20 text-emerald-700 dark:text-emerald-300',
    'bg-amber-500/20 text-amber-700 dark:text-amber-300',
    'bg-rose-500/20 text-rose-700 dark:text-rose-300',
    'bg-cyan-500/20 text-cyan-700 dark:text-cyan-300',
    'bg-pink-500/20 text-pink-700 dark:text-pink-300',
    'bg-orange-500/20 text-orange-700 dark:text-orange-300',
  ]
  return colors[Math.abs(hash) % colors.length]
}

function getActivityLevel(activity: number): { label: string; color: string; bgColor: string; barColor: string } {
  if (activity >= 80) return { label: 'High', color: 'text-emerald-700 dark:text-emerald-400', bgColor: 'bg-emerald-500/15 border-emerald-500/30', barColor: 'bg-emerald-500' }
  if (activity >= 40) return { label: 'Medium', color: 'text-amber-700 dark:text-amber-400', bgColor: 'bg-amber-500/15 border-amber-500/30', barColor: 'bg-amber-500' }
  if (activity > 0) return { label: 'Low', color: 'text-orange-700 dark:text-orange-400', bgColor: 'bg-orange-500/15 border-orange-500/30', barColor: 'bg-orange-500' }
  return { label: 'Dead', color: 'text-slate-500 dark:text-slate-400', bgColor: 'bg-slate-500/15 border-slate-500/30', barColor: 'bg-slate-400' }
}

function getDurationColor(ms: number): string {
  if (ms >= 300000) return 'text-emerald-600 dark:text-emerald-400' // 5+ min - good long session
  if (ms >= 60000) return 'text-blue-600 dark:text-blue-400' // 1-5 min
  if (ms >= 10000) return 'text-amber-600 dark:text-amber-400' // 10s-1m
  return 'text-slate-500 dark:text-slate-400' // very short
}

function ReplaysPage() {
  const navigate = useNavigate()
  const { selectedProjectId } = useProject()
  const [period, setPeriod] = useState<'24h' | '7d' | '30d' | '90d'>('7d')
  const [environment, setEnvironment] = useState('all')
  const [page, setPage] = useState(1)
  const [searchQuery, setSearchQuery] = useState('')

  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectId = selectedProjectId || projects?.[0]?.id
  const { data: billingUsage } = useQuery({
    queryKey: ['billing-usage'],
    queryFn: () => api.getBillingUsage(),
  })
  const retentionDays = billingUsage?.retentionDays ?? 30
  const availablePeriods = useMemo(() => {
    const options = [
      { value: '24h', label: 'Last 24 hours', minDays: 1 },
      { value: '7d', label: 'Last 7 days', minDays: 7 },
      { value: '30d', label: 'Last 30 days', minDays: 30 },
      { value: '90d', label: 'Last 90 days', minDays: 90 },
    ] as const
    const filtered = options.filter((option) => retentionDays >= option.minDays)
    return filtered.length > 0 ? filtered : [options[0]]
  }, [retentionDays])

  useEffect(() => {
    if (!availablePeriods.some((option) => option.value === period)) {
      setPeriod(availablePeriods[availablePeriods.length - 1].value)
      setPage(1)
    }
  }, [availablePeriods, period, setPeriod, setPage])

  const { data: replays = [], isLoading } = useQuery({
    queryKey: ['replays', projectId, period, environment, page],
    queryFn: () =>
      projectId
        ? api.getReplays(projectId, {
            page,
            limit: 25,
            period,
            environment: environment === 'all' ? undefined : environment,
          })
        : [],
    enabled: !!projectId,
  })

  const stats = useMemo(() => {
    const totalErrors = replays.reduce((sum, r) => sum + r.errorCount, 0)
    const avgDuration = replays.length > 0
      ? replays.reduce((sum, r) => sum + r.durationMs, 0) / replays.length
      : 0
    const uniqueUsers = new Set(
      replays
        .map((r) => r.user?.email || r.user?.username || r.user?.id)
        .filter(Boolean)
    ).size
    return {
      total: replays.length,
      totalErrors,
      avgDuration,
      uniqueUsers,
    }
  }, [replays])

  const filteredReplays = useMemo(() => {
    if (!searchQuery) return replays
    const q = searchQuery.toLowerCase()
    return replays.filter((r) => {
      const userName = r.user?.email || r.user?.username || r.user?.id || ''
      const urls = r.urls?.join(' ') || ''
      const browser = [r.browserName, r.osName].filter(Boolean).join(' ')
      return (
        userName.toLowerCase().includes(q) ||
        urls.toLowerCase().includes(q) ||
        browser.toLowerCase().includes(q)
      )
    })
  }, [replays, searchQuery])

  if (!projects || projects.length === 0) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-background via-background to-primary/5 p-6">
        <Card className="p-12 text-center border-blue-500/20 bg-gradient-to-b from-card to-blue-500/5">
          <div className="max-w-md mx-auto space-y-4">
            <div className="flex justify-center">
              <div className="rounded-full bg-blue-500/10 p-4 ring-2 ring-blue-500/20">
                <Video className="h-10 w-10 text-blue-600 dark:text-blue-400" />
              </div>
            </div>
            <div>
              <h3 className="text-lg font-semibold mb-2">No projects yet</h3>
              <p className="text-muted-foreground">
                Create a project to start capturing session replays.
              </p>
            </div>
          </div>
        </Card>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-background via-background to-blue-500/5">
      <div className="mx-auto max-w-7xl p-6">
        {/* Header */}
        <div className="mb-6 flex items-center gap-3">
          <div className="rounded-xl bg-blue-500/15 p-2.5 ring-1 ring-blue-500/20">
            <Video className="h-6 w-6 text-blue-600 dark:text-blue-400" />
          </div>
          <div>
            <h2 className="text-2xl font-bold">Session Replays</h2>
            <p className="text-sm text-muted-foreground">Watch real user sessions to understand behavior and debug issues</p>
          </div>
        </div>

        {/* Stats Cards */}
        {replays.length > 0 && (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-6">
            <div className="rounded-xl border border-blue-500/20 bg-blue-500/5 p-4 transition-all hover:shadow-md hover:border-blue-500/30">
              <div className="flex items-center gap-2 mb-2">
                <Play className="h-4 w-4 text-blue-500" />
                <span className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Sessions</span>
              </div>
              <div className="text-2xl font-bold text-blue-600 dark:text-blue-400">{stats.total}</div>
            </div>
            <div className="rounded-xl border border-rose-500/20 bg-rose-500/5 p-4 transition-all hover:shadow-md hover:border-rose-500/30">
              <div className="flex items-center gap-2 mb-2">
                <AlertCircle className="h-4 w-4 text-rose-500" />
                <span className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Errors</span>
              </div>
              <div className="text-2xl font-bold text-rose-600 dark:text-rose-400">{stats.totalErrors}</div>
            </div>
            <div className="rounded-xl border border-emerald-500/20 bg-emerald-500/5 p-4 transition-all hover:shadow-md hover:border-emerald-500/30">
              <div className="flex items-center gap-2 mb-2">
                <Timer className="h-4 w-4 text-emerald-500" />
                <span className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Avg Duration</span>
              </div>
              <div className="text-2xl font-bold text-emerald-600 dark:text-emerald-400">
                {formatDuration(stats.avgDuration)}
              </div>
            </div>
            <div className="rounded-xl border border-violet-500/20 bg-violet-500/5 p-4 transition-all hover:shadow-md hover:border-violet-500/30">
              <div className="flex items-center gap-2 mb-2">
                <User className="h-4 w-4 text-violet-500" />
                <span className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Unique Users</span>
              </div>
              <div className="text-2xl font-bold text-violet-600 dark:text-violet-400">{stats.uniqueUsers}</div>
            </div>
          </div>
        )}

        {/* Toolbar */}
        <div className="mb-4 flex flex-wrap items-center gap-3">
          <div className="relative flex-1 min-w-[200px]">
            <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-muted-foreground" />
            <Input
              placeholder="Search by user, URL, or browser..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-10"
            />
          </div>
          <Select value={period} onValueChange={(value) => { setPeriod(value as '24h' | '7d' | '30d' | '90d'); setPage(1) }}>
            <SelectTrigger className="w-[140px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {availablePeriods.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Select value={environment} onValueChange={(v) => { setEnvironment(v); setPage(1) }}>
            <SelectTrigger className="w-[160px]">
              <SelectValue placeholder="Environment" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All Environments</SelectItem>
              <SelectItem value="production">Production</SelectItem>
              <SelectItem value="staging">Staging</SelectItem>
              <SelectItem value="development">Development</SelectItem>
            </SelectContent>
          </Select>
        </div>

        {/* Content */}
        {isLoading ? (
          <div className="space-y-2">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="rounded-xl border border-border/60 bg-card p-4 animate-pulse">
                <div className="flex items-center gap-4">
                  <div className="h-9 w-9 rounded-full bg-muted" />
                  <div className="flex-1 space-y-2">
                    <div className="h-4 w-32 rounded bg-muted" />
                    <div className="h-3 w-48 rounded bg-muted" />
                  </div>
                  <div className="h-6 w-16 rounded bg-muted" />
                  <div className="h-3 w-20 rounded bg-muted" />
                </div>
              </div>
            ))}
          </div>
        ) : !replays.length ? (
          <Card className="p-12 text-center border-blue-500/20 bg-gradient-to-b from-card to-blue-500/5">
            <div className="max-w-md mx-auto space-y-4">
              <div className="flex justify-center">
                <div className="rounded-full bg-blue-500/10 p-4 ring-2 ring-blue-500/20">
                  <Play className="h-10 w-10 text-blue-600 dark:text-blue-400" />
                </div>
              </div>
              <div>
                <h3 className="text-lg font-semibold mb-2">No replays yet</h3>
                <p className="text-muted-foreground">
                  Session replays are recorded when you enable the Sentry Replay integration in your SDK.
                  Configure replays in your project setup to start capturing user sessions.
                </p>
              </div>
            </div>
          </Card>
        ) : filteredReplays.length === 0 ? (
          <Card className="p-12 text-center border-blue-500/20 bg-gradient-to-b from-card to-blue-500/5">
            <div className="max-w-md mx-auto space-y-4">
              <div className="flex justify-center">
                <div className="rounded-full bg-blue-500/10 p-4">
                  <Search className="h-10 w-10 text-blue-600 dark:text-blue-400" />
                </div>
              </div>
              <div>
                <h3 className="text-lg font-semibold mb-2">No replays match your search</h3>
                <p className="text-muted-foreground">
                  Try adjusting your search query or changing the filters.
                </p>
              </div>
            </div>
          </Card>
        ) : (
          <TooltipProvider>
            <div className="space-y-2">
              {filteredReplays.map((replay) => {
                const activityLevel = getActivityLevel(replay.activity)
                const durationColor = getDurationColor(replay.durationMs)
                const displayName = replay.user?.email || replay.user?.username || replay.user?.id || 'Anonymous'
                const initials = getInitials(replay.user)
                const avatarColor = getAvatarColor(replay.user)
                const hasErrors = replay.errorCount > 0

                return (
                  <div
                    key={replay.replayId}
                    onClick={() => navigate({ to: '/replays/$replayId', params: { replayId: replay.replayId } })}
                    className={`cursor-pointer rounded-xl border bg-card hover:bg-accent/50 transition-all hover:shadow-md border-l-[3px] ${
                      hasErrors
                        ? 'border-l-rose-500 border-border/60 hover:border-rose-500/30'
                        : 'border-l-blue-500/40 border-border/60 hover:border-blue-500/30'
                    }`}
                  >
                    <div className="flex items-center gap-4 p-4">
                      {/* Avatar */}
                      <Avatar className="h-9 w-9 shrink-0">
                        <AvatarFallback className={`text-xs font-semibold ${avatarColor}`}>
                          {initials}
                        </AvatarFallback>
                      </Avatar>

                      {/* Main Content */}
                      <div className="flex-1 min-w-0">
                        {/* Top row: user + time */}
                        <div className="flex items-center gap-2 mb-1">
                          <span className="font-semibold text-sm truncate">{displayName}</span>
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <span className="text-xs text-muted-foreground ml-auto shrink-0 flex items-center gap-1">
                                <Clock className="h-3 w-3" />
                                {formatRelativeTime(replay.startedAt)}
                              </span>
                            </TooltipTrigger>
                            <TooltipContent>{formatDate(replay.startedAt)}</TooltipContent>
                          </Tooltip>
                        </div>

                        {/* Bottom row: badges */}
                        <div className="flex flex-wrap items-center gap-2">
                          {/* Duration */}
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Badge variant="outline" className={`text-xs font-medium gap-1 ${durationColor}`}>
                                <Timer className="h-3 w-3" />
                                {formatDuration(replay.durationMs)}
                              </Badge>
                            </TooltipTrigger>
                            <TooltipContent>Session duration</TooltipContent>
                          </Tooltip>

                          {/* Activity */}
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Badge variant="outline" className={`text-xs font-medium gap-1.5 ${activityLevel.bgColor} ${activityLevel.color}`}>
                                <MousePointerClick className="h-3 w-3" />
                                <span>{activityLevel.label}</span>
                                <span className="h-1.5 w-8 rounded-full bg-current/20 overflow-hidden inline-flex">
                                  <span
                                    className={`h-full rounded-full ${activityLevel.barColor}`}
                                    style={{ width: `${Math.min(100, replay.activity)}%` }}
                                  />
                                </span>
                              </Badge>
                            </TooltipTrigger>
                            <TooltipContent>Activity: {replay.activity}%</TooltipContent>
                          </Tooltip>

                          {/* Errors */}
                          {hasErrors && (
                            <Badge variant="outline" className="text-xs font-medium gap-1 bg-rose-500/15 border-rose-500/30 text-rose-700 dark:text-rose-400">
                              <AlertCircle className="h-3 w-3" />
                              {replay.errorCount} error{replay.errorCount === 1 ? '' : 's'}
                            </Badge>
                          )}

                          {/* URLs */}
                          {replay.urls?.length > 0 && (
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <Badge variant="outline" className="text-xs font-normal gap-1 max-w-[220px] truncate">
                                  <Globe className="h-3 w-3 shrink-0" />
                                  <span className="truncate">
                                    {replay.urls[0]?.replace(/^https?:\/\//, '') || '-'}
                                  </span>
                                  {replay.urls.length > 1 && (
                                    <span className="shrink-0 text-muted-foreground">+{replay.urls.length - 1}</span>
                                  )}
                                </Badge>
                              </TooltipTrigger>
                              <TooltipContent className="max-w-xs">
                                <div className="space-y-1">
                                  {replay.urls.map((url, i) => (
                                    <div key={i} className="text-xs truncate">{url}</div>
                                  ))}
                                </div>
                              </TooltipContent>
                            </Tooltip>
                          )}

                          {/* Browser / OS */}
                          {(replay.browserName || replay.osName) && (
                            <Badge variant="outline" className="text-xs font-normal gap-1 text-muted-foreground">
                              <Monitor className="h-3 w-3" />
                              {[replay.browserName, replay.osName].filter(Boolean).join(' / ')}
                            </Badge>
                          )}
                        </div>
                      </div>

                      {/* Play button hint */}
                      <div className="shrink-0 opacity-0 group-hover:opacity-100 transition-opacity">
                        <div className="rounded-full bg-blue-500/10 p-2 ring-1 ring-blue-500/20">
                          <Play className="h-4 w-4 text-blue-600 dark:text-blue-400" />
                        </div>
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          </TooltipProvider>
        )}

        {/* Pagination */}
        {filteredReplays.length > 0 && (
          <div className="mt-4 flex items-center justify-between">
            <p className="text-xs text-muted-foreground">
              Showing {filteredReplays.length} replay{filteredReplays.length === 1 ? '' : 's'}
              {searchQuery && ' matching your search'}
              {' '}&middot; Page {page}
            </p>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setPage((p) => Math.max(1, p - 1))}
                disabled={page <= 1}
                className="h-8 gap-1"
              >
                <ChevronLeft className="h-4 w-4" />
                Previous
              </Button>
              <div className="flex items-center gap-1 px-2">
                <span className="text-sm font-medium">{page}</span>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setPage((p) => p + 1)}
                disabled={replays.length < 25}
                className="h-8 gap-1"
              >
                Next
                <ChevronRight className="h-4 w-4" />
              </Button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
