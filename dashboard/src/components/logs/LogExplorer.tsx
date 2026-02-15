import {useCallback, useEffect, useMemo, useRef, useState} from 'react'
import {useNavigate} from '@tanstack/react-router'
import {api, formatErrorForLogging, type LogEntry, type LogFilterOptionsWithCounts} from '@/lib/api'
import {type FacetFilter, LEVEL_OPTIONS, LogSearchBar, TIME_PRESETS} from '@/components/logs/LogSearchBar'
import {TagFacets} from '@/components/logs/TagFacets'
import {LogTable} from '@/components/logs/LogTable'
import {LogDetail} from '@/components/logs/LogDetail'
import {LiveTailToggle} from '@/components/logs/LiveTailToggle'
import {LogSetupGuide} from '@/components/logs/LogSetupGuide'
import {LogVizTabs, type LogVizMode} from '@/components/logs/LogVizTabs'
import {LogHistogram} from '@/components/logs/LogHistogram'
import {LogTopList} from '@/components/logs/LogTopList'
import {LogPieChart} from '@/components/logs/LogPieChart'
import {LogAggregateTable} from '@/components/logs/LogAggregateTable'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {cn} from '@/lib/utils'
import {stripAnsi} from '@/lib/ansi'
import {ChevronDown, ChevronLeft, Download, Loader2, PanelLeftClose, PanelLeftOpen, TerminalSquare} from 'lucide-react'
import {useQuery} from '@tanstack/react-query'
import {
  serializeLogViewState,
  parseFacetFiltersFromUrl,
  parseLevelsFromUrl,
  type LogViewSearch,
} from '@/components/logs/logViewUrlState'

interface LogExplorerProps {
  projectId?: number
  systemId?: string
  initialQuery?: string
  initialContainerName?: string
  dsn?: string
  sdkVersions?: Record<string, string>
  className?: string
  enableLiveTail?: boolean
  enableFacets?: boolean
  defaultTimeRange?: string
  initialScrollToBottom?: boolean
  enableUrlSync?: boolean
  urlSearch?: LogViewSearch
}

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

function formatLogCount(n: number): string {
  if (n >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(2)}B`
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  return String(n)
}

function toDateTimeLocalValue(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function intervalToMs(interval: string | undefined): number {
  switch (interval) {
    case '1m':
      return 60_000
    case '5m':
      return 5 * 60_000
    case '15m':
      return 15 * 60_000
    case '1h':
      return 60 * 60_000
    case '1d':
      return 24 * 60 * 60_000
    default:
      return 60 * 60_000
  }
}

export function LogExplorer({
  projectId,
  systemId,
  initialQuery = '',
  initialContainerName,
  dsn,
  sdkVersions,
  className,
  enableLiveTail = true,
  enableFacets = true,
  defaultTimeRange = '7d',
  // @ts-ignore - unused param kept for interface compatibility
  initialScrollToBottom = false,
  enableUrlSync = false,
  urlSearch,
}: LogExplorerProps) {
  const navigate = useNavigate()
  
  // Hydrate from URL if enabled
  const getInitialState = useCallback(() => {
    if (enableUrlSync && urlSearch) {
      return {
        query: urlSearch.q || initialQuery,
        facetFilters: parseFacetFiltersFromUrl(urlSearch.facets),
        levels: urlSearch.levels ? parseLevelsFromUrl(urlSearch.levels) : [...LEVEL_OPTIONS],
        timePreset: urlSearch.timePreset || defaultTimeRange,
        customFrom: urlSearch.from || '',
        customTo: urlSearch.to || '',
        vizMode: urlSearch.viz || 'list' as LogVizMode,
        groupBy: urlSearch.groupBy || '',
        topField: urlSearch.topField || 'service',
        cursor: urlSearch.cursor || null,
        selectedLogId: urlSearch.logId || null,
      }
    }
    return {
      query: initialQuery,
      facetFilters: [] as FacetFilter[],
      levels: [...LEVEL_OPTIONS],
      timePreset: defaultTimeRange,
      customFrom: '',
      customTo: '',
      vizMode: 'list' as LogVizMode,
      groupBy: '',
      topField: 'service',
      cursor: null,
      selectedLogId: null,
    }
  }, [enableUrlSync, urlSearch, initialQuery, defaultTimeRange])
  
  const initialState = getInitialState()
  
  // Search / filter state
  const [query, setQuery] = useState(initialState.query)
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>(initialState.facetFilters)
  const [levels, setLevels] = useState<string[]>(initialState.levels)
  const [timePreset, setTimePreset] = useState(initialState.timePreset)
  const [customFrom, setCustomFrom] = useState(initialState.customFrom)
  const [customTo, setCustomTo] = useState(initialState.customTo)

  // Pagination
  const [cursor, setCursor] = useState<string | null>(initialState.cursor)
  const [cursorHistory, setCursorHistory] = useState<Array<string | null>>([])
  
  // Infinite scroll state
  const [accumulatedLogs, setAccumulatedLogs] = useState<LogEntry[]>([])
  const [isLoadingMore, setIsLoadingMore] = useState(false)
  const scrollSentinelRef = useRef<HTMLDivElement>(null)

  // Detail panel
  const [selectedLog, setSelectedLog] = useState<LogEntry | null>(null)
  const [detailOpen, setDetailOpen] = useState(false)

  // Facets sidebar
  const [showFacets, setShowFacets] = useState(enableFacets)

  // Visualization mode
  const [vizMode, setVizMode] = useState<LogVizMode>(initialState.vizMode)
  const [groupBy, setGroupBy] = useState<string>(initialState.groupBy)
  const [topField, setTopField] = useState<string>(initialState.topField)

  // Live tail state
  const [liveTailEnabled, setLiveTailEnabled] = useState(false)
  const [tailPaused, setTailPaused] = useState(false)
  const [tailBufferedCount, setTailBufferedCount] = useState(0)
  const [tailStatus, setTailStatus] = useState<'connecting' | 'open' | 'closed'>('closed')
  const [tailLogs, setTailLogs] = useState<LogEntry[]>([])

  const bufferedTailLogsRef = useRef<LogEntry[]>([])
  const pausedRef = useRef(false)
  const tailScrollRef = useRef<HTMLDivElement>(null)
  const syncTimeoutRef = useRef<NodeJS.Timeout | null>(null)
  const isHydratingRef = useRef(false)
  
  // Hydrate from URL on back/forward navigation
  useEffect(() => {
    if (!enableUrlSync || !urlSearch || isHydratingRef.current) return
    
    isHydratingRef.current = true
    
    setQuery(urlSearch.q || '')
    setFacetFilters(parseFacetFiltersFromUrl(urlSearch.facets))
    setLevels(urlSearch.levels ? parseLevelsFromUrl(urlSearch.levels) : [...LEVEL_OPTIONS])
    setTimePreset(urlSearch.timePreset || defaultTimeRange)
    setCustomFrom(urlSearch.from || '')
    setCustomTo(urlSearch.to || '')
    setVizMode(urlSearch.viz || 'list')
    setGroupBy(urlSearch.groupBy || '')
    setTopField(urlSearch.topField || 'service')
    setCursor(urlSearch.cursor || null)
    
    // Defer clearing hydration flag to avoid race conditions
    setTimeout(() => {
      isHydratingRef.current = false
    }, 100)
  }, [enableUrlSync, urlSearch, defaultTimeRange])
  
  // Sync state to URL with debounce
  useEffect(() => {
    if (!enableUrlSync || isHydratingRef.current) return
    
    if (syncTimeoutRef.current) {
      clearTimeout(syncTimeoutRef.current)
    }
    
    syncTimeoutRef.current = setTimeout(() => {
      const newSearch = serializeLogViewState({
        query,
        levels,
        facetFilters,
        timePreset,
        customFrom,
        customTo,
        vizMode,
        groupBy,
        topField,
        cursor,
        selectedLogId: selectedLog?.logId || null,
      })
      
      navigate({
        search: newSearch as any,
        replace: true,
      })
    }, 300)
    
    return () => {
      if (syncTimeoutRef.current) {
        clearTimeout(syncTimeoutRef.current)
      }
    }
  }, [enableUrlSync, navigate, query, levels, facetFilters, timePreset, customFrom, customTo, vizMode, groupBy, topField, cursor, selectedLog])

  useEffect(() => {
    if (initialContainerName) {
      setFacetFilters(prev => {
        if (prev.some(f => f.key === 'container_name' && f.value === initialContainerName)) return prev
        return [...prev, {key: 'container_name', value: initialContainerName}]
      })
    }
  }, [initialContainerName])

  // Derive API params from state
  const timeRange = useMemo(
    () => computeTimeRange(timePreset, customFrom, customTo),
    [timePreset, customFrom, customTo]
  )

  // Derive service/environment/tags/containerName from facetFilters
  const derivedFilters = useMemo(() => {
    let service: string | undefined
    let environment: string | undefined
    let containerName: string | undefined
    const tags: Record<string, string> = {}
    const excludeTags: Record<string, string> = {}
    let excludeService: string | undefined
    let excludeEnvironment: string | undefined
    let excludeContainerName: string | undefined

    for (const filter of facetFilters) {
      if (filter.exclude) {
        // Handle excludes
        if (filter.key === 'service') {
          excludeService = filter.value
        } else if (filter.key === 'environment') {
          excludeEnvironment = filter.value
        } else if (filter.key === 'container_name') {
          excludeContainerName = filter.value
        } else {
          excludeTags[filter.key] = filter.value
        }
      } else {
        // Handle includes
        if (filter.key === 'service') {
          service = filter.value
        } else if (filter.key === 'environment') {
          environment = filter.value
        } else if (filter.key === 'container_name') {
          containerName = filter.value
        } else {
          tags[filter.key] = filter.value
        }
      }
    }

    return {
      service, 
      environment, 
      containerName, 
      tags,
      excludeService,
      excludeEnvironment,
      excludeContainerName,
      excludeTags
    }
  }, [facetFilters])

  const hasCustomLevelFilter = levels.length > 0 && levels.length < LEVEL_OPTIONS.length
  const levelsKey = hasCustomLevelFilter ? levels.join(',') : '__all__'

  useEffect(() => {
    pausedRef.current = tailPaused
  }, [tailPaused])

  // Reset pagination when filters change
  useEffect(() => {
    setCursor(null)
    setCursorHistory([])
    setAccumulatedLogs([])
  }, [projectId, systemId, query, levelsKey, timeRange.from, timeRange.to, facetFilters])

  // Fetch filter options (with counts)
  const {data: filterOptions} = useQuery({
    queryKey: ['log-filters', projectId, systemId, timeRange.from, timeRange.to],
    queryFn: async () => {
      if (projectId) {
        return api.getProjectLogFilters(projectId, {from: timeRange.from, to: timeRange.to})
      }
      return {services: [], environments: [], levels: [], tagKeys: []} as LogFilterOptionsWithCounts
    },
    enabled: Boolean(projectId || systemId) && enableFacets,
  })

  // Fetch logs
  const {
    data: logPage,
    isLoading: isInitialLoading,
    isFetching,
  } = useQuery({
    queryKey: [
      'logs',
      projectId,
      systemId,
      cursor,
      query,
      levelsKey,
      timeRange.from,
      timeRange.to,
      derivedFilters.service,
      derivedFilters.environment,
      derivedFilters.containerName,
      JSON.stringify(derivedFilters.tags),
    ],
    queryFn: () => {
      const commonOptions = {
        cursor: cursor || undefined,
        limit: 150,
        query: query || undefined,
        levels: hasCustomLevelFilter ? levels : undefined,
        service: derivedFilters.service,
        environment: derivedFilters.environment,
        containerName: derivedFilters.containerName,
        from: timeRange.from,
        to: timeRange.to,
        tags: Object.keys(derivedFilters.tags).length > 0 ? derivedFilters.tags : undefined,
        excludeService: derivedFilters.excludeService,
        excludeEnvironment: derivedFilters.excludeEnvironment,
        excludeContainerName: derivedFilters.excludeContainerName,
        excludeTags: Object.keys(derivedFilters.excludeTags).length > 0 ? derivedFilters.excludeTags : undefined,
      }

      if (systemId) {
        return api.getSystemLogs(systemId, commonOptions)
      } else if (projectId) {
        return api.getProjectLogs(projectId, commonOptions)
      }
      throw new Error('Either projectId or systemId must be provided')
    },
    enabled: Boolean(projectId || systemId),
  })

  // Accumulate logs when new page loads (only in list mode and not live tail)
  useEffect(() => {
    if (!logPage?.logs || liveTailEnabled) return
    
    // Only accumulate in list mode
    if (vizMode === 'list') {
      // If cursor is null, this is the first page (or reset), so replace
      if (cursor === null) {
        setAccumulatedLogs(logPage.logs)
      } else {
        // Otherwise, append to accumulated logs
        setAccumulatedLogs(prev => [...prev, ...logPage.logs])
      }
      setIsLoadingMore(false)
    } else {
      // When switching away from list mode, populate accumulated logs
      // so when switching back, we don't start empty
      if (accumulatedLogs.length === 0 && cursor === null) {
        setAccumulatedLogs(logPage.logs)
      }
    }
  }, [logPage, cursor, liveTailEnabled, vizMode, accumulatedLogs.length])
  
  const logs = vizMode === 'list' && !liveTailEnabled ? accumulatedLogs : (logPage?.logs ?? [])
  const totalCount = logPage?.totalCount ?? null

  // Aggregate query for histogram - always enabled to show above all modes
  const {data: aggregateData} = useQuery({
    queryKey: [
      'log-aggregate', projectId, timeRange.from, timeRange.to,
      query, levelsKey, derivedFilters.service, derivedFilters.environment,
      JSON.stringify(derivedFilters.tags), groupBy,
    ],
    queryFn: () => {
      if (!projectId) throw new Error('Missing projectId')
      return api.getProjectLogAggregate(projectId, {
        from: timeRange.from,
        to: timeRange.to,
        query: query || undefined,
        levels: hasCustomLevelFilter ? levels : undefined,
        service: derivedFilters.service,
        environment: derivedFilters.environment,
        tags: Object.keys(derivedFilters.tags).length > 0 ? derivedFilters.tags : undefined,
        groupBy,
      })
    },
    enabled: Boolean(projectId),
  })

  // Top values query for toplist/pie/table views
  const {data: topData} = useQuery({
    queryKey: [
      'log-top', projectId, topField, timeRange.from, timeRange.to,
      query, levelsKey, derivedFilters.service, derivedFilters.environment,
      JSON.stringify(derivedFilters.tags),
    ],
    queryFn: () => {
      if (!projectId) throw new Error('Missing projectId')
      return api.getProjectLogTop(projectId, {
        field: topField,
        limit: 20,
        from: timeRange.from,
        to: timeRange.to,
        query: query || undefined,
        levels: hasCustomLevelFilter ? levels : undefined,
        service: derivedFilters.service,
        environment: derivedFilters.environment,
        tags: Object.keys(derivedFilters.tags).length > 0 ? derivedFilters.tags : undefined,
      })
    },
    enabled: Boolean(projectId) && (vizMode === 'toplist' || vizMode === 'pie' || vizMode === 'table'),
  })

  const handleExportCsv = useCallback(async () => {
    if (!projectId) return
    try {
      await api.downloadProjectLogExport(projectId, {
        from: timeRange.from,
        to: timeRange.to,
        query: query || undefined,
        levels: hasCustomLevelFilter ? levels : undefined,
        service: derivedFilters.service,
        environment: derivedFilters.environment,
        tags: Object.keys(derivedFilters.tags).length > 0 ? derivedFilters.tags : undefined,
      })
    } catch (error) {
      console.error('CSV export failed:', formatErrorForLogging(error))
    }
  }, [projectId, timeRange, query, hasCustomLevelFilter, levels, derivedFilters])

  const handleHistogramBucketClick = useCallback((bucketStartIso: string) => {
    const bucketStart = new Date(bucketStartIso)
    if (Number.isNaN(bucketStart.getTime())) return
    const bucketMs = intervalToMs(aggregateData?.interval)
    const bucketEnd = new Date(bucketStart.getTime() + bucketMs)
    setTimePreset('custom')
    setCustomFrom(toDateTimeLocalValue(bucketStart))
    setCustomTo(toDateTimeLocalValue(bucketEnd))
  }, [aggregateData?.interval])

  // Open detail when selecting a log
  const handleSelectLog = useCallback((log: LogEntry) => {
    setSelectedLog(log)
    setDetailOpen(true)
  }, [])
  
  // View in context: clear filters and show ±5 minute window around selected log
  const handleViewInContext = useCallback((log: LogEntry) => {
    const logTime = new Date(log.timestamp)
    if (Number.isNaN(logTime.getTime())) return
    
    // Calculate ±5 minutes
    const fiveMinutesMs = 5 * 60 * 1000
    const contextFrom = new Date(logTime.getTime() - fiveMinutesMs)
    const contextTo = new Date(logTime.getTime() + fiveMinutesMs)
    
    // Clear filters and query
    setQuery('')
    setFacetFilters([])
    setLevels([...LEVEL_OPTIONS])
    
    // Set custom time range
    setTimePreset('custom')
    setCustomFrom(toDateTimeLocalValue(contextFrom))
    setCustomTo(toDateTimeLocalValue(contextTo))
    
    // Reset pagination
    setCursor(null)
    setCursorHistory([])
    
    // Keep the log selected for highlight
    setSelectedLog(log)
    setDetailOpen(false)
  }, [])

  // Live tail
  useEffect(() => {
    if (!liveTailEnabled || !enableLiveTail || !projectId) return

    setTailStatus('connecting')
    const source = api.createProjectLogTailStream(projectId, {
      query: query || undefined,
      levels: hasCustomLevelFilter ? levels : undefined,
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
  }, [liveTailEnabled, enableLiveTail, projectId, query, derivedFilters.service, derivedFilters.environment, levelsKey, levels, hasCustomLevelFilter])

  const toggleLevel = (level: string) => {
    setLevels((current) =>
      current.includes(level) ? current.filter((item) => item !== level) : [...current, level]
    )
  }

  const handleNextPage = useCallback(() => {
    if (!logPage?.nextCursor || isLoadingMore) return
    setIsLoadingMore(true)
    setCursorHistory((current) => [...current, cursor])
    setCursor(logPage.nextCursor)
  }, [logPage?.nextCursor, isLoadingMore, cursor])

  const handlePreviousPage = () => {
    if (cursorHistory.length === 0) return
    const previous = cursorHistory[cursorHistory.length - 1] ?? null
    setCursorHistory((current) => current.slice(0, -1))
    setCursor(previous)
    setAccumulatedLogs([])
  }
  
  // Infinite scroll with Intersection Observer
  useEffect(() => {
    if (!scrollSentinelRef.current || liveTailEnabled || vizMode !== 'list') return
    
    const observer = new IntersectionObserver(
      (entries) => {
        const [entry] = entries
        if (entry.isIntersecting && logPage?.hasMore && !isLoadingMore && !isFetching) {
          handleNextPage()
        }
      },
      {
        root: logContainerRef.current,
        rootMargin: '200px', // Load 200px before reaching the end
        threshold: 0,
      }
    )
    
    observer.observe(scrollSentinelRef.current)
    
    return () => {
      observer.disconnect()
    }
  }, [logPage?.hasMore, isLoadingMore, isFetching, liveTailEnabled, vizMode, handleNextPage])

  const handleToggleLiveTail = () => {
    setLiveTailEnabled((current) => {
      const next = !current
      if (next) {
        setTailLogs([])
        bufferedTailLogsRef.current = []
        setTailBufferedCount(0)
        setTailPaused(false)
        setAccumulatedLogs([]) // Reset accumulated logs when starting live tail
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

  // Show loading state only on initial load with no accumulated logs
  const isInitialLoadingState = isInitialLoading && accumulatedLogs.length === 0
  const showEmptyState = !isInitialLoadingState && logs.length === 0 && !query && facetFilters.length === 0 && !hasCustomLevelFilter && totalCount === 0
  const logContainerRef = useRef<HTMLDivElement>(null)

  return (
    <div className={cn("flex flex-col overflow-hidden bg-gradient-to-br from-background via-background to-blue-500/[0.03]", className)}>
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

            {enableLiveTail && (
              <LiveTailToggle
                enabled={liveTailEnabled}
                paused={tailPaused}
                bufferedCount={tailBufferedCount}
                status={tailStatus}
                onToggleEnabled={handleToggleLiveTail}
                onTogglePaused={handleTogglePause}
              />
            )}
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
              availableServices={(filterOptions?.services ?? []).map(s => s.value)}
              availableEnvironments={(filterOptions?.environments ?? []).map(e => e.value)}
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
              showFacets && enableFacets ? 'w-[240px]' : 'w-0 overflow-hidden border-r-0'
            )}
          >
            {showFacets && enableFacets && (
              <TagFacets
                projectId={projectId ?? 0} 
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
            {/* Toolbar: facets toggle, viz tabs, group by, export, pagination */}
            <div className="flex items-center gap-2 border-b bg-card/30 px-3 py-1.5">
              {enableFacets && (
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
              )}

              <LogVizTabs mode={vizMode} onModeChange={setVizMode} />

              {/* Group By / Field selector for non-list views */}
              {(vizMode === 'toplist' || vizMode === 'pie' || vizMode === 'table') && (
                <div className="relative">
                  <select
                    value={topField}
                    onChange={(e) => setTopField(e.target.value)}
                    className="h-7 appearance-none rounded-md border bg-background px-2 pr-6 text-xs"
                  >
                    <option value="service">service</option>
                    <option value="level">level</option>
                    <option value="environment">environment</option>
                    <option value="host">host</option>
                    <option value="container_name">container</option>
                  </select>
                  <ChevronDown className="pointer-events-none absolute right-1.5 top-1.5 h-3.5 w-3.5 text-muted-foreground" />
                </div>
              )}

              {aggregateData && aggregateData.buckets.length > 0 && (
                <div className="relative">
                  <select
                    value={groupBy}
                    onChange={(e) => setGroupBy(e.target.value)}
                    className="h-7 appearance-none rounded-md border bg-background px-2 pr-6 text-xs"
                  >
                    <option value="">No grouping</option>
                    <option value="level">Group by level</option>
                    <option value="service">Group by service</option>
                    <option value="environment">Group by environment</option>
                  </select>
                  <ChevronDown className="pointer-events-none absolute right-1.5 top-1.5 h-3.5 w-3.5 text-muted-foreground" />
                </div>
              )}

              <div className="flex-1 text-xs text-muted-foreground">
                {isInitialLoadingState ? (
                  <span className="flex items-center gap-1.5">
                    <Loader2 className="h-3 w-3 animate-spin" />
                    Loading...
                  </span>
                ) : (
                  <span>
                    {aggregateData?.totalCount != null
                      ? `${formatLogCount(aggregateData.totalCount)} results found`
                      : `${logs.length} result${logs.length !== 1 ? 's' : ''} shown`}
                  </span>
                )}
              </div>

              {/* CSV Export */}
              {projectId && (
                <Button variant="ghost" size="sm" onClick={handleExportCsv} className="h-7 gap-1.5 text-xs">
                  <Download className="h-3.5 w-3.5" />
                  CSV
                </Button>
              )}

              {/* Pagination (only in list mode, shown only if we have history) */}
              {vizMode === 'list' && !liveTailEnabled && cursorHistory.length > 0 && (
                <div className="flex items-center gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={handlePreviousPage}
                    disabled={cursorHistory.length === 0}
                    className="h-7 w-7 p-0"
                    title="Reset to first page"
                  >
                    <ChevronLeft className="h-4 w-4" />
                  </Button>
                </div>
              )}
            </div>

            {/* Histogram - always visible above all modes (like Datadog) */}
            {vizMode === 'timeseries' && aggregateData && aggregateData.buckets.length > 0 && (
              <div className="shrink-0 border-b bg-card/50">
                <div className="px-3 pt-2 pb-1.5">
                  <div className="mb-1 flex items-center justify-between">
                    <span className="text-[10px] font-medium text-muted-foreground">Log volume ({aggregateData.interval} buckets)</span>
                    <span className="text-[10px] text-muted-foreground">Click a bar to zoom</span>
                  </div>
                  <LogHistogram
                    buckets={aggregateData.buckets}
                    grouped={true}
                    height={108}
                    onBucketClick={handleHistogramBucketClick}
                  />
                </div>
              </div>
            )}

            {/* Additional visualizations - shown compactly above list */}
            {(vizMode === 'toplist' || vizMode === 'pie' || vizMode === 'table') && topData && (
              <div className="shrink-0 border-b bg-card/50">
                <div className="px-3 py-2">
                  {vizMode === 'toplist' && (
                    <div className="max-h-64 overflow-y-auto">
                      <LogTopList
                        values={topData.values.slice(0, 10)}
                        totalCount={topData.totalCount}
                        field={topField}
                        onValueClick={(value) => {
                          setFacetFilters(prev => [...prev.filter(f => f.key !== topField), {key: topField, value, exclude: false}])
                        }}
                      />
                    </div>
                  )}
                  {vizMode === 'pie' && (
                    <div className="max-h-72">
                      <LogPieChart values={topData.values} field={topField} />
                    </div>
                  )}
                  {vizMode === 'table' && (
                    <div className="max-h-64 overflow-y-auto">
                      <LogAggregateTable
                        values={topData.values}
                        totalCount={topData.totalCount}
                        field={topField}
                        onValueClick={(value) => {
                          setFacetFilters(prev => [...prev.filter(f => f.key !== topField), {key: topField, value, exclude: false}])
                        }}
                      />
                    </div>
                  )}
                </div>
              </div>
            )}

            {/* Content area - logs list */}
            <div className="flex-1 overflow-y-auto" ref={logContainerRef}>
              {showEmptyState ? (
                <div className="px-3 pb-6 sm:px-4 sm:pb-8">
                  {dsn ? (
                    <LogSetupGuide dsn={dsn} sdkVersions={sdkVersions} />
                  ) : (
                    <div className="flex items-center justify-center py-24">
                      <div className="text-center">
                        <TerminalSquare className="mx-auto h-10 w-10 text-muted-foreground/30" />
                        <p className="mt-3 text-sm font-medium text-muted-foreground">No logs found</p>
                      </div>
                    </div>
                  )}
                </div>
              ) : isInitialLoadingState ? (
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
                <>
                  <LogTable
                    logs={logs}
                    selectedLogId={selectedLog?.logId}
                    onSelectLog={handleSelectLog}
                    compact={true}
                  />
                  
                  {/* Infinite scroll sentinel and loading indicator */}
                  {vizMode === 'list' && !liveTailEnabled && (
                    <div ref={scrollSentinelRef} className="px-3 py-4">
                      {(isLoadingMore || isFetching) && logPage?.hasMore ? (
                        <div className="flex items-center justify-center gap-2 py-2">
                          <div className="h-1 w-full max-w-xs overflow-hidden rounded-full bg-muted">
                            <div className="h-full w-1/2 animate-pulse bg-blue-500" style={{animation: 'pulse 1.5s cubic-bezier(0.4, 0, 0.6, 1) infinite'}} />
                          </div>
                        </div>
                      ) : logPage?.hasMore ? (
                        <div className="text-center text-xs text-muted-foreground/50">
                          Scroll for more
                        </div>
                      ) : logs.length > 150 ? (
                        <div className="border-t pt-3 text-center text-xs text-muted-foreground/70">
                          End of results • {logs.length} logs loaded
                        </div>
                      ) : null}
                    </div>
                  )}
                </>
              )}

              {/* Live tail stream */}
              {enableLiveTail && liveTailEnabled && (
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
                                    normalizedLevel === 'info' && 'bg-indigo-500/10 text-indigo-600 dark:text-indigo-400 border-indigo-500/20',
                                    normalizedLevel === 'debug' && 'bg-teal-500/10 text-teal-600 dark:text-teal-400 border-teal-500/20'
                                  )}
                                >
                                  {normalizedLevel}
                                </Badge>
                                <span className="font-mono text-xs break-all text-foreground/80">
                                  {stripAnsi(log.message)}
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
      <LogDetail 
        log={selectedLog} 
        open={detailOpen} 
        onClose={() => setDetailOpen(false)} 
        onViewInContext={enableUrlSync ? handleViewInContext : undefined}
      />
    </div>
  )
}
