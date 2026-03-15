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

import {startTransition, useCallback, useEffect, useMemo, useRef, useState} from 'react'
import {useNavigate} from '@tanstack/react-router'
import {api, formatErrorForLogging, type LogEntry, type LogFilterOptionsWithCounts} from '@/lib/api'
import {type FacetFilter, LEVEL_OPTIONS, LogSearchBar, TIME_PRESETS} from '@/components/logs/LogSearchBar'
import {TagFacets} from '@/components/logs/TagFacets'
import {LogTable} from '@/components/logs/LogTable'
import {LogDetail} from '@/components/logs/LogDetail'
import {AutoRefreshToggle, type RefreshInterval} from '@/components/logs/AutoRefreshToggle'
import {LogSetupGuide} from '@/components/logs/LogSetupGuide'
import {type LogVizMode, LogVizTabs} from '@/components/logs/LogVizTabs'
import {LogHistogram} from '@/components/logs/LogHistogram'
import {LogTopList} from '@/components/logs/LogTopList'
import {LogPieChart} from '@/components/logs/LogPieChart'
import {LogAggregateTable} from '@/components/logs/LogAggregateTable'
import {Button} from '@/components/ui/button'
import {cn} from '@/lib/utils'
import {ChevronDown, ChevronLeft, Download, Loader2, PanelLeftClose, PanelLeftOpen, TerminalSquare} from 'lucide-react'
import {useQuery} from '@tanstack/react-query'
import {
  type LogViewSearch,
  parseFacetFiltersFromUrl,
  parseLevelsFromUrl,
  serializeLogViewState,
} from '@/components/logs/logViewUrlState'
import {getNowDate} from '@/lib/demo'

interface LogExplorerProps {
  systemId?: string
  initialQuery?: string
  initialContainerName?: string
  sdkVersions?: Record<string, string>
  className?: string
  enableAutoRefresh?: boolean
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
    const now = getNowDate()
    const from = new Date(now.getTime() - match.minutes * 60_000)
    return {from: from.toISOString(), to: undefined}
  }

  return {}
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
  systemId,
  initialQuery = '',
  initialContainerName,
  sdkVersions,
  className,
  enableAutoRefresh = true,
  enableFacets = true,
  defaultTimeRange = '7d',
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  initialScrollToBottom: _initialScrollToBottom = false,
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
        vizMode: urlSearch.viz || 'timeseries' as LogVizMode,
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
      vizMode: 'timeseries' as LogVizMode,
      groupBy: '',
      topField: 'service',
      cursor: null,
      selectedLogId: null,
    }
  }, [enableUrlSync, urlSearch, initialQuery, defaultTimeRange])
  
  const initialState = getInitialState()
  
  // Search / filter state
  const [query, setQuery] = useState(initialState.query)
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>(() => {
    const base = initialState.facetFilters
    if (initialContainerName && !base.some(f => f.key === 'container_name' && f.value === initialContainerName)) {
      return [...base, {key: 'container_name', value: initialContainerName}]
    }
    return base
  })
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
  const logContainerRef = useRef<HTMLDivElement>(null)
  // Tracks whether the next logPage load should replace (reset) or append (infinite scroll)
  const isReplacingRef = useRef(true)
  // Ref holding latest scroll-callback state so the IntersectionObserver closure is never stale
  const scrollStateRef = useRef({
    hasMore: false,
    nextCursor: null as string | null,
    isLoadingMore: false,
    isFetching: false,
    cursor: null as string | null,
  })

  // Detail panel
  const [selectedLog, setSelectedLog] = useState<LogEntry | null>(null)
  const [detailOpen, setDetailOpen] = useState(false)

  // Facets sidebar
  const [showFacets, setShowFacets] = useState(() => enableFacets && window.innerWidth >= 1024)

  // Visualization mode
  const [vizMode, setVizMode] = useState<LogVizMode>(initialState.vizMode)
  const [groupBy, setGroupBy] = useState<string>(initialState.groupBy)
  const [topField, setTopField] = useState<string>(initialState.topField)

  // Auto-refresh state
  const [autoRefreshInterval, setAutoRefreshInterval] = useState<RefreshInterval>(null)
  const syncTimeoutRef = useRef<NodeJS.Timeout | null>(null)
  const isHydratingRef = useRef(false)
  const didMountRef = useRef(false)
  
  // Hydrate from URL on back/forward navigation (skip initial mount — state is
  // already initialised from urlSearch via getInitialState)
  useEffect(() => {
    if (!enableUrlSync || !urlSearch) return
    
    if (!didMountRef.current) {
      didMountRef.current = true
      return
    }
    
    if (isHydratingRef.current) return
    
    isHydratingRef.current = true
    
    startTransition(() => {
      setQuery(urlSearch.q || '')
      setFacetFilters(parseFacetFiltersFromUrl(urlSearch.facets))
      setLevels(urlSearch.levels ? parseLevelsFromUrl(urlSearch.levels) : [...LEVEL_OPTIONS])
      setTimePreset(urlSearch.timePreset || defaultTimeRange)
      setCustomFrom(urlSearch.from || '')
      setCustomTo(urlSearch.to || '')
      setVizMode(urlSearch.viz || 'timeseries')
      setGroupBy(urlSearch.groupBy || '')
      setTopField(urlSearch.topField || 'service')
      setCursor(urlSearch.cursor || null)
    })
    
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
      
      // Prevent our own navigate() from triggering the hydration effect and resetting state
      isHydratingRef.current = true
      navigate({
        search: newSearch as never,
        replace: true,
      })
      setTimeout(() => {
        isHydratingRef.current = false
      }, 100)
    }, 300)
    
    return () => {
      if (syncTimeoutRef.current) {
        clearTimeout(syncTimeoutRef.current)
      }
    }
  }, [enableUrlSync, navigate, query, levels, facetFilters, timePreset, customFrom, customTo, vizMode, groupBy, topField, cursor, selectedLog])

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
  const facetFiltersKey = JSON.stringify(facetFilters)


  // Reset pagination when filters change
  useEffect(() => {
    isReplacingRef.current = true
    startTransition(() => {
      setCursor(null)
      setCursorHistory([])
      setAccumulatedLogs([])
    })
  }, [systemId, query, levelsKey, timeRange.from, timeRange.to, facetFiltersKey])

  // Fetch filter options (with counts) - org-scoped when no systemId; monitor systems don't have filters
  const {data: filterOptions} = useQuery({
    queryKey: ['log-filters', systemId, timeRange.from, timeRange.to],
    queryFn: async () => {
      if (systemId) {
        return {services: [], environments: [], levels: [], tagKeys: []} as LogFilterOptionsWithCounts
      }
      return api.getLogFilters({from: timeRange.from, to: timeRange.to})
    },
    enabled: !systemId && enableFacets,
  })

  // Fetch logs - org-scoped (getLogs) when no systemId; getSystemLogs when systemId (monitor)
  const {
    data: logPage,
    isLoading: isInitialLoading,
    isFetching,
  } = useQuery({
    queryKey: [
      'logs',
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
      derivedFilters.excludeService,
      derivedFilters.excludeEnvironment,
      derivedFilters.excludeContainerName,
      JSON.stringify(derivedFilters.excludeTags),
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
      }
      return api.getLogs(commonOptions)
    },
    enabled: true,
    refetchInterval: autoRefreshInterval || false,
  })

  // Accumulate logs when new page loads
  useEffect(() => {
    if (!logPage?.logs) return
    
    startTransition(() => {
      if (isReplacingRef.current) {
        // First page (or reset): replace accumulated logs
        setAccumulatedLogs(logPage.logs)
        isReplacingRef.current = false
      } else {
        // Infinite scroll: append only unseen entries (prevents duplicates on refetch)
        setAccumulatedLogs((prev) => {
          const seen = new Set(prev.map((log) => `${log.logId}:${log.timestamp}`))
          const next = logPage.logs.filter((log) => !seen.has(`${log.logId}:${log.timestamp}`))
          return next.length > 0 ? [...prev, ...next] : prev
        })
      }
      setIsLoadingMore(false)
    })
  }, [logPage])
  
  const logs = accumulatedLogs
  const totalCount = logPage?.totalCount ?? null

  // Aggregate query for histogram - org-scoped only (monitor systems don't have aggregate)
  const {data: aggregateData} = useQuery({
    queryKey: [
      'log-aggregate', systemId, timeRange.from, timeRange.to,
      query, levelsKey, derivedFilters.service, derivedFilters.environment,
      JSON.stringify(derivedFilters.tags), groupBy,
      derivedFilters.excludeService, derivedFilters.excludeEnvironment,
      derivedFilters.excludeContainerName, JSON.stringify(derivedFilters.excludeTags),
    ],
    queryFn: () => {
      if (systemId) throw new Error('Aggregate not supported for monitor systems')
      return api.getLogAggregate({
        from: timeRange.from,
        to: timeRange.to,
        query: query || undefined,
        levels: hasCustomLevelFilter ? levels : undefined,
        service: derivedFilters.service,
        environment: derivedFilters.environment,
        tags: Object.keys(derivedFilters.tags).length > 0 ? derivedFilters.tags : undefined,
        excludeService: derivedFilters.excludeService,
        excludeEnvironment: derivedFilters.excludeEnvironment,
        excludeContainerName: derivedFilters.excludeContainerName,
        excludeTags: Object.keys(derivedFilters.excludeTags).length > 0 ? derivedFilters.excludeTags : undefined,
        groupBy,
      })
    },
    enabled: !systemId,
  })

  // Top values query for toplist/pie/table views - org-scoped only
  const {data: topData} = useQuery({
    queryKey: [
      'log-top', systemId, topField, timeRange.from, timeRange.to,
      query, levelsKey, derivedFilters.service, derivedFilters.environment,
      JSON.stringify(derivedFilters.tags),
      derivedFilters.excludeService, derivedFilters.excludeEnvironment,
      derivedFilters.excludeContainerName, JSON.stringify(derivedFilters.excludeTags),
    ],
    queryFn: () => {
      if (systemId) throw new Error('Top not supported for monitor systems')
      return api.getLogTop({
        field: topField,
        limit: 20,
        from: timeRange.from,
        to: timeRange.to,
        query: query || undefined,
        levels: hasCustomLevelFilter ? levels : undefined,
        service: derivedFilters.service,
        environment: derivedFilters.environment,
        tags: Object.keys(derivedFilters.tags).length > 0 ? derivedFilters.tags : undefined,
        excludeService: derivedFilters.excludeService,
        excludeEnvironment: derivedFilters.excludeEnvironment,
        excludeContainerName: derivedFilters.excludeContainerName,
        excludeTags: Object.keys(derivedFilters.excludeTags).length > 0 ? derivedFilters.excludeTags : undefined,
      })
    },
    enabled: !systemId && (vizMode === 'toplist' || vizMode === 'pie' || vizMode === 'table'),
  })

  const handleExportCsv = useCallback(async () => {
    if (systemId) return
    try {
      await api.downloadLogExport({
        from: timeRange.from,
        to: timeRange.to,
        query: query || undefined,
        levels: hasCustomLevelFilter ? levels : undefined,
        service: derivedFilters.service,
        environment: derivedFilters.environment,
        tags: Object.keys(derivedFilters.tags).length > 0 ? derivedFilters.tags : undefined,
        excludeService: derivedFilters.excludeService,
        excludeEnvironment: derivedFilters.excludeEnvironment,
        excludeContainerName: derivedFilters.excludeContainerName,
        excludeTags: Object.keys(derivedFilters.excludeTags).length > 0 ? derivedFilters.excludeTags : undefined,
      })
    } catch (error) {
      console.error('CSV export failed:', formatErrorForLogging(error))
    }
  }, [systemId, timeRange, query, hasCustomLevelFilter, levels, derivedFilters])

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

  const toggleLevel = (level: string) => {
    setLevels((current) =>
      current.includes(level) ? current.filter((item) => item !== level) : [...current, level]
    )
  }

  const handlePreviousPage = () => {
    if (cursorHistory.length === 0) return
    const previous = cursorHistory[cursorHistory.length - 1] ?? null
    setCursorHistory((current) => current.slice(0, -1))
    setCursor(previous)
    setAccumulatedLogs([])
  }
  
  // Keep scroll-state ref in sync so the observer callback reads fresh values
  useEffect(() => {
    scrollStateRef.current = {
      hasMore: logPage?.hasMore ?? false,
      nextCursor: logPage?.nextCursor ?? null,
      isLoadingMore,
      isFetching,
      cursor,
    }
  }, [logPage?.hasMore, logPage?.nextCursor, isLoadingMore, isFetching, cursor])

  // Infinite scroll with Intersection Observer
  // Only recreate the observer when the sentinel appears/disappears (logs.length changes)
  // or when hasMore/isFetching settle, so we avoid rapid disconnect/reconnect cycles
  // that can cause the browser to drop async intersection callbacks.
  useEffect(() => {
    if (!scrollSentinelRef.current || !logContainerRef.current) return

    const observer = new IntersectionObserver(
      (entries) => {
        const [entry] = entries
        const state = scrollStateRef.current
        if (entry.isIntersecting && state.hasMore && !state.isLoadingMore && !state.isFetching) {
          if (!state.nextCursor) return
          isReplacingRef.current = false
          setIsLoadingMore(true)
          setCursorHistory((current) => [...current, state.cursor])
          setCursor(state.nextCursor)
        }
      },
      {
        root: logContainerRef.current,
        rootMargin: '200px',
        threshold: 0,
      }
    )

    observer.observe(scrollSentinelRef.current)

    return () => {
      observer.disconnect()
    }
  }, [logs.length, logPage?.hasMore, isFetching])

  // Show loading when no accumulated logs yet — covers both the first fetch
  // (isInitialLoading) and revisiting the page with stale cache data that
  // hasn't been accumulated yet.
  const isInitialLoadingState =
    accumulatedLogs.length === 0 &&
    (isInitialLoading || (logPage?.logs?.length ?? 0) > 0)
  const showEmptyState = !isInitialLoadingState && logs.length === 0 && !query && facetFilters.length === 0 && !hasCustomLevelFilter && (totalCount === 0 || totalCount === null)

  return (
    <div className={cn("flex flex-col overflow-hidden bg-gradient-to-br from-background via-background to-blue-500/[0.03]", className)}>
      {/* Header bar */}
      <div className="shrink-0 border-b bg-background/95 backdrop-blur-sm z-20">
          <div className="flex items-center gap-3 px-3 py-2 sm:px-4 lg:px-6">
            <div className="flex items-center gap-2.5 shrink-0">
              <div className="rounded-lg bg-gradient-to-br from-blue-500/15 to-violet-500/15 p-1.5 ring-1 ring-blue-500/20">
                <TerminalSquare className="h-4 w-4 text-blue-500" />
              </div>
              <h2 className="text-sm font-semibold leading-tight hidden sm:block">Log Explorer</h2>
            </div>

            <div className="flex-1 min-w-0">
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

            {enableAutoRefresh && (
              <AutoRefreshToggle
                interval={autoRefreshInterval}
                onIntervalChange={setAutoRefreshInterval}
              />
            )}
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
                availableTagKeys={filterOptions?.tagKeys ?? []}
                availableServices={filterOptions?.services ?? []}
                availableEnvironments={filterOptions?.environments ?? []}
                facetFilters={facetFilters}
                onFacetFiltersChange={setFacetFilters}
                from={timeRange.from}
                to={timeRange.to}
                scopeId={systemId}
              />
            )}
          </div>

          {/* Log content area */}
          <div className="flex flex-1 min-h-0 flex-col overflow-hidden">
            {/* Toolbar: facets toggle, viz tabs, group by, export, pagination */}
            <div className="flex items-center gap-2 border-b bg-card/30 px-3 py-[5px]">
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

              {/* CSV Export - org-scoped only, not for monitor systems */}
              {!systemId && (
                <Button variant="ghost" size="sm" onClick={handleExportCsv} className="h-7 gap-1.5 text-xs">
                  <Download className="h-3.5 w-3.5" />
                  CSV
                </Button>
              )}

              {/* Pagination (shown only if we have history) */}
              {cursorHistory.length > 0 && (
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

            {/* Histogram - always visible above all modes */}
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
                  <LogSetupGuide sdkVersions={sdkVersions} />
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
                    ) : logPage && !logPage.hasMore ? (
                      <div className="border-t pt-3 text-center text-xs text-muted-foreground/70">
                        End of results • {logs.length} logs loaded
                      </div>
                    ) : null}
                  </div>
                </>
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
