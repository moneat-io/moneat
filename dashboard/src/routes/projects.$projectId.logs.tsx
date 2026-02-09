import {createFileRoute, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {useCallback, useEffect, useMemo, useRef, useState} from 'react'
import {api, formatErrorForLogging, type LogEntry} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {type FacetFilter, LogSearchBar, TIME_PRESETS} from '@/components/logs/LogSearchBar'
import {TagFacets} from '@/components/logs/TagFacets'
import {LogTable} from '@/components/logs/LogTable'
import {LogDetail} from '@/components/logs/LogDetail'
import {LiveTailToggle} from '@/components/logs/LiveTailToggle'
import {LogSetupGuide} from '@/components/logs/LogSetupGuide'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {cn} from '@/lib/utils'
import {ChevronLeft, ChevronRight, Loader2, PanelLeftClose, PanelLeftOpen, TerminalSquare,} from 'lucide-react'

export const Route = createFileRoute('/projects/$projectId/logs')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  loader: async ({params}) => {
    const project = await api.getProject(Number(params.projectId))
    return {project}
  },
  component: ProjectLogsPage,
})

function toIsoOrUndefined(value: string): string | undefined {
  if (!value) return undefined
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return undefined
  return date.toISOString()
}

function computeTimeRange(
  preset: string,
  customFrom: string,
  customTo: string
): {from?: string; to?: string} {
  if (preset === 'custom') {
    return {
      from: toIsoOrUndefined(customFrom),
      to: toIsoOrUndefined(customTo),
    }
  }

  const match = TIME_PRESETS.find((p) => p.value === preset)
  if (match) {
    const now = new Date()
    const from = new Date(now.getTime() - match.minutes * 60_000)
    return {from: from.toISOString(), to: undefined}
  }

  return {}
}

function formatLiveTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const base = date.toLocaleTimeString(undefined, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
  const ms = String(date.getMilliseconds()).padStart(3, '0')
  return `${base}.${ms}`
}

function ProjectLogsPage() {
  const {project} = Route.useLoaderData()
  const {projectId} = Route.useParams()
  const numericProjectId = Number(projectId)
  const {setSelectedProjectId} = useProject()
  const {data: sdkVersionsResponse} = useQuery({
    queryKey: ['sdk-versions'],
    queryFn: () => api.getSdkVersions(),
    staleTime: 30 * 60 * 1000,
  })

  // Search / filter state
  const [query, setQuery] = useState('')
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>([])
  const [levels, setLevels] = useState<string[]>([])
  const [timePreset, setTimePreset] = useState('15m')
  const [customFrom, setCustomFrom] = useState('')
  const [customTo, setCustomTo] = useState('')

  // Pagination
  const [cursor, setCursor] = useState<string | null>(null)
  const [cursorHistory, setCursorHistory] = useState<Array<string | null>>([])

  // Detail panel
  const [selectedLog, setSelectedLog] = useState<LogEntry | null>(null)
  const [detailOpen, setDetailOpen] = useState(false)

  // Facets sidebar
  const [showFacets, setShowFacets] = useState(true)

  // Live tail state
  const [liveTailEnabled, setLiveTailEnabled] = useState(false)
  const [tailPaused, setTailPaused] = useState(false)
  const [tailBufferedCount, setTailBufferedCount] = useState(0)
  const [tailStatus, setTailStatus] = useState<'connecting' | 'open' | 'closed'>('closed')
  const [tailLogs, setTailLogs] = useState<LogEntry[]>([])

  const bufferedTailLogsRef = useRef<LogEntry[]>([])
  const pausedRef = useRef(false)
  const tailScrollRef = useRef<HTMLDivElement>(null)

  // Derive API params from state
  const timeRange = useMemo(
    () => computeTimeRange(timePreset, customFrom, customTo),
    [timePreset, customFrom, customTo]
  )

  // Derive service/environment/tags from facetFilters
  const derivedFilters = useMemo(() => {
    let service: string | undefined
    let environment: string | undefined
    const tags: Record<string, string> = {}

    for (const filter of facetFilters) {
      if (filter.exclude) continue // Exclude is handled differently if needed
      if (filter.key === 'service') {
        service = filter.value
      } else if (filter.key === 'environment') {
        environment = filter.value
      } else {
        tags[filter.key] = filter.value
      }
    }

    return {service, environment, tags}
  }, [facetFilters])

  const levelsKey = levels.join(',')

  useEffect(() => {
    pausedRef.current = tailPaused
  }, [tailPaused])

  useEffect(() => {
    if (Number.isFinite(numericProjectId)) {
      setSelectedProjectId(numericProjectId)
    }
  }, [numericProjectId, setSelectedProjectId])

  // Reset pagination when filters change
  useEffect(() => {
    setCursor(null)
    setCursorHistory([])
  }, [numericProjectId, query, levelsKey, timeRange.from, timeRange.to, facetFilters])

  // Fetch filter options
  const {data: filterOptions} = useQuery({
    queryKey: ['project-log-filters', numericProjectId, timeRange.from, timeRange.to],
    queryFn: () =>
      api.getProjectLogFilters(numericProjectId, {from: timeRange.from, to: timeRange.to}),
    enabled: Number.isFinite(numericProjectId),
  })

  // Fetch logs
  const {
    data: logPage,
    isLoading,
    isFetching,
  } = useQuery({
    queryKey: [
      'project-logs',
      numericProjectId,
      cursor,
      query,
      levelsKey,
      timeRange.from,
      timeRange.to,
      derivedFilters.service,
      derivedFilters.environment,
      JSON.stringify(derivedFilters.tags),
    ],
    queryFn: () =>
      api.getProjectLogs(numericProjectId, {
        cursor: cursor || undefined,
        limit: 150,
        query: query || undefined,
        levels: levels.length > 0 ? levels : undefined,
        service: derivedFilters.service,
        environment: derivedFilters.environment,
        from: timeRange.from,
        to: timeRange.to,
        tags: Object.keys(derivedFilters.tags).length > 0 ? derivedFilters.tags : undefined,
      }),
    enabled: Number.isFinite(numericProjectId),
  })

  const logs = logPage?.logs ?? []

  // Open detail when selecting a log
  const handleSelectLog = useCallback((log: LogEntry) => {
    setSelectedLog(log)
    setDetailOpen(true)
  }, [])

  // Live tail
  useEffect(() => {
    if (!liveTailEnabled || !Number.isFinite(numericProjectId)) return

    setTailStatus('connecting')
    const source = api.createProjectLogTailStream(numericProjectId, {
      query: query || undefined,
      levels: levels.length > 0 ? levels : undefined,
      service: derivedFilters.service,
      environment: derivedFilters.environment,
    })

    source.onopen = () => {
      setTailStatus('open')
    }

    source.onerror = () => {
      setTailStatus('closed')
    }

    source.onmessage = (event) => {
      try {
        const next = JSON.parse(event.data) as LogEntry

        if (pausedRef.current) {
          bufferedTailLogsRef.current = [...bufferedTailLogsRef.current, next].slice(-400)
          setTailBufferedCount(bufferedTailLogsRef.current.length)
          return
        }

        setTailLogs((current) => [...current, next].slice(-400))
        requestAnimationFrame(() => {
          const node = tailScrollRef.current
          if (node) {
            node.scrollTop = node.scrollHeight
          }
        })
      } catch (error) {
        console.error('Failed to parse live log payload:', formatErrorForLogging(error))
      }
    }

    return () => {
      source.close()
      setTailStatus('closed')
    }
  }, [liveTailEnabled, numericProjectId, query, derivedFilters.service, derivedFilters.environment, levelsKey, levels])

  const toggleLevel = (level: string) => {
    setLevels((current) =>
      current.includes(level) ? current.filter((item) => item !== level) : [...current, level]
    )
  }

  const handleNextPage = () => {
    if (!logPage?.nextCursor) return
    setCursorHistory((current) => [...current, cursor])
    setCursor(logPage.nextCursor)
  }

  const handlePreviousPage = () => {
    if (cursorHistory.length === 0) return
    const previous = cursorHistory[cursorHistory.length - 1] ?? null
    setCursorHistory((current) => current.slice(0, -1))
    setCursor(previous)
  }

  const handleToggleLiveTail = () => {
    setLiveTailEnabled((current) => {
      const next = !current
      if (next) {
        setTailLogs([])
        bufferedTailLogsRef.current = []
        setTailBufferedCount(0)
        setTailPaused(false)
      }
      return next
    })
  }

  const handleTogglePause = () => {
    if (!tailPaused) {
      setTailPaused(true)
      return
    }

    setTailPaused(false)
    if (bufferedTailLogsRef.current.length > 0) {
      setTailLogs((current) =>
        [...current, ...bufferedTailLogsRef.current].slice(-400)
      )
      bufferedTailLogsRef.current = []
      setTailBufferedCount(0)
      requestAnimationFrame(() => {
        const node = tailScrollRef.current
        if (node) {
          node.scrollTop = node.scrollHeight
        }
      })
    }
  }

  const handleTailScroll = () => {
    const node = tailScrollRef.current
    if (!node || !liveTailEnabled) return

    const distanceFromBottom = node.scrollHeight - node.scrollTop - node.clientHeight
    if (distanceFromBottom > 48 && !tailPaused) {
      setTailPaused(true)
    }
  }

  const showEmptyState = !isLoading && logs.length === 0 && !query && facetFilters.length === 0 && levels.length === 0

  return (
    <div className="flex h-screen flex-col overflow-hidden bg-gradient-to-br from-background via-background to-blue-500/[0.03]">
      {/* Header bar */}
      <div className="shrink-0 border-b bg-background/95 backdrop-blur-sm z-20">
          <div className="flex items-center justify-between gap-4 px-4 py-3 lg:px-6">
            <div className="flex items-center gap-3">
              <div className="rounded-lg bg-gradient-to-br from-blue-500/15 to-violet-500/15 p-2 ring-1 ring-blue-500/20">
                <TerminalSquare className="h-5 w-5 text-blue-500" />
              </div>
              <div>
                <h2 className="text-lg font-semibold leading-tight">Log Explorer</h2>
                <p className="text-[11px] text-muted-foreground">
                  Search, filter, and stream logs in real time
                </p>
              </div>
            </div>

            <LiveTailToggle
              enabled={liveTailEnabled}
              paused={tailPaused}
              bufferedCount={tailBufferedCount}
              status={tailStatus}
              onToggleEnabled={handleToggleLiveTail}
              onTogglePaused={handleTogglePause}
            />
          </div>

          {/* Search bar */}
          <div className="border-t bg-card/40 px-4 py-3 lg:px-6">
            <LogSearchBar
              query={query}
              onQueryChange={setQuery}
              facetFilters={facetFilters}
              onFacetFiltersChange={setFacetFilters}
              levels={levels}
              onToggleLevel={toggleLevel}
              availableTagKeys={filterOptions?.tagKeys ?? []}
              availableServices={filterOptions?.services ?? []}
              availableEnvironments={filterOptions?.environments ?? []}
              timePreset={timePreset}
              onTimePresetChange={setTimePreset}
              customFrom={customFrom}
              customTo={customTo}
              onCustomFromChange={setCustomFrom}
              onCustomToChange={setCustomTo}
            />
          </div>
      </div>

      {/* Main content */}
      <div className="flex flex-1 overflow-hidden">
          {/* Facets sidebar */}
          <div
            className={cn(
              'shrink-0 border-r transition-all duration-200',
              showFacets ? 'w-[240px]' : 'w-0 overflow-hidden border-r-0'
            )}
          >
            {showFacets && (
              <TagFacets
                projectId={numericProjectId}
                availableTagKeys={filterOptions?.tagKeys ?? []}
                availableServices={filterOptions?.services ?? []}
                availableEnvironments={filterOptions?.environments ?? []}
                facetFilters={facetFilters}
                onFacetFiltersChange={setFacetFilters}
                from={timeRange.from}
                to={timeRange.to}
              />
            )}
          </div>

          {/* Log content area */}
          <div className="flex flex-1 min-h-0 flex-col overflow-hidden">
            {/* Facets toggle + status bar */}
            <div className="flex h-11 items-center gap-2 border-b bg-card/30 px-3">
              <button
                type="button"
                onClick={() => setShowFacets(!showFacets)}
                className="rounded-md p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
                title={showFacets ? 'Hide facets' : 'Show facets'}
              >
                {showFacets ? (
                  <PanelLeftClose className="h-4 w-4" />
                ) : (
                  <PanelLeftOpen className="h-4 w-4" />
                )}
              </button>

              <div className="flex-1 text-xs text-muted-foreground">
                {isLoading ? (
                  <span className="flex items-center gap-1.5">
                    <Loader2 className="h-3 w-3 animate-spin" />
                    Loading logs...
                  </span>
                ) : (
                  <span>
                    {logs.length} log{logs.length !== 1 ? 's' : ''} shown
                    {isFetching && (
                      <span className="ml-2 inline-flex items-center gap-1">
                        <Loader2 className="h-3 w-3 animate-spin" />
                        refreshing
                      </span>
                    )}
                  </span>
                )}
              </div>

              {/* Pagination */}
              <div className="flex items-center gap-1">
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={handlePreviousPage}
                  disabled={cursorHistory.length === 0}
                  className="h-7 w-7 p-0"
                >
                  <ChevronLeft className="h-4 w-4" />
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={handleNextPage}
                  disabled={!logPage?.hasMore}
                  className="h-7 w-7 p-0"
                >
                  <ChevronRight className="h-4 w-4" />
                </Button>
              </div>
            </div>

            {/* Log table or empty state */}
            <div className="flex-1 overflow-y-auto">
              {showEmptyState ? (
                <LogSetupGuide dsn={project.dsn} sdkVersions={sdkVersionsResponse?.versions} />
              ) : isLoading ? (
                <div className="flex items-center justify-center py-24">
                  <div className="text-center">
                    <Loader2 className="mx-auto h-8 w-8 animate-spin text-muted-foreground" />
                    <p className="mt-3 text-sm text-muted-foreground">Loading logs...</p>
                  </div>
                </div>
              ) : logs.length === 0 ? (
                <div className="flex items-center justify-center py-24">
                  <div className="text-center">
                    <TerminalSquare className="mx-auto h-10 w-10 text-muted-foreground/30" />
                    <p className="mt-3 text-sm font-medium text-muted-foreground">No logs match your filters</p>
                    <p className="mt-1 text-xs text-muted-foreground/70">
                      Try adjusting your search query, time range, or filters
                    </p>
                  </div>
                </div>
              ) : (
                <LogTable
                  logs={logs}
                  selectedLogId={selectedLog?.logId}
                  onSelectLog={handleSelectLog}
                />
              )}

              {/* Live tail stream */}
              {liveTailEnabled && (
                <div className="border-t">
                  <div className="flex items-center gap-2 border-b bg-card/50 px-4 py-2">
                    <span className={cn('h-2 w-2 rounded-full', tailStatus === 'open' ? 'bg-emerald-500 animate-pulse' : 'bg-zinc-400')} />
                    <span className="text-xs font-medium">Live Tail Stream</span>
                    {tailLogs.length > 0 && (
                      <Badge variant="secondary" className="text-[10px] font-mono">{tailLogs.length} events</Badge>
                    )}
                  </div>
                  <div
                    ref={tailScrollRef}
                    onScroll={handleTailScroll}
                    className="max-h-[320px] overflow-auto"
                  >
                    {tailLogs.length === 0 ? (
                      <div className="flex items-center gap-2 px-4 py-8 text-sm text-muted-foreground">
                        <Loader2 className="h-4 w-4 animate-spin" />
                        Waiting for live log events...
                      </div>
                    ) : (
                      <div className="divide-y divide-border/30">
                        {tailLogs.map((log) => {
                          const normalizedLevel = (log.level || 'info').toLowerCase()
                          return (
                            <button
                              key={`${log.logId}-${log.timestamp}`}
                              className="w-full px-4 py-1.5 text-left transition-colors hover:bg-accent/40"
                              onClick={() => handleSelectLog(log)}
                            >
                              <div className="flex items-start gap-3">
                                <span className="min-w-[90px] font-mono text-[11px] text-muted-foreground">
                                  {formatLiveTime(log.timestamp)}
                                </span>
                                <Badge
                                  variant="outline"
                                  className={cn(
                                    'min-w-[44px] justify-center font-mono text-[9px] uppercase py-0 px-1',
                                    normalizedLevel === 'error' && 'bg-red-500/10 text-red-600 dark:text-red-400 border-red-500/20',
                                    normalizedLevel === 'warn' && 'bg-amber-500/10 text-amber-600 dark:text-amber-400 border-amber-500/20',
                                    normalizedLevel === 'info' && 'bg-blue-500/10 text-blue-600 dark:text-blue-400 border-blue-500/20',
                                    normalizedLevel === 'debug' && 'bg-cyan-500/10 text-cyan-600 dark:text-cyan-400 border-cyan-500/20'
                                  )}
                                >
                                  {normalizedLevel}
                                </Badge>
                                <span className="font-mono text-xs break-all text-foreground/80">
                                  {log.message}
                                </span>
                              </div>
                            </button>
                          )
                        })}
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>

      {/* Log detail sheet */}
      <LogDetail log={selectedLog} open={detailOpen} onClose={() => setDetailOpen(false)} />
    </div>
  )
}
