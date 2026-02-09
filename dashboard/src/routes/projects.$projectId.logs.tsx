import {createFileRoute, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {useEffect, useMemo, useRef, useState} from 'react'
import {api, formatErrorForLogging, type LogEntry} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {LogFilters} from '@/components/logs/LogFilters'
import {LogTable} from '@/components/logs/LogTable'
import {LogDetail} from '@/components/logs/LogDetail'
import {LiveTailToggle} from '@/components/logs/LiveTailToggle'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {TerminalSquare} from 'lucide-react'

export const Route = createFileRoute('/projects/$projectId/logs')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: ProjectLogsPage,
})

function parseTagFilter(raw: string): Record<string, string> {
  if (!raw.trim()) return {}
  return raw
    .split(',')
    .map((pair) => pair.trim())
    .filter(Boolean)
    .map((pair) => {
      const idx = pair.indexOf(':')
      if (idx <= 0) return null
      const key = pair.slice(0, idx).trim()
      const value = pair.slice(idx + 1).trim()
      if (!key) return null
      return [key, value] as const
    })
    .filter((entry): entry is readonly [string, string] => entry !== null)
    .reduce<Record<string, string>>((acc, [key, value]) => {
      acc[key] = value
      return acc
    }, {})
}

function toIsoOrUndefined(value: string): string | undefined {
  if (!value) return undefined
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return undefined
  return date.toISOString()
}

function formatLiveTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleTimeString()
}

function ProjectLogsPage() {
  const {projectId} = Route.useParams()
  const numericProjectId = Number(projectId)
  const {setSelectedProjectId} = useProject()

  const [query, setQuery] = useState('')
  const [service, setService] = useState('all')
  const [environment, setEnvironment] = useState('all')
  const [levels, setLevels] = useState<string[]>([])
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [tagFilter, setTagFilter] = useState('')

  const [cursor, setCursor] = useState<string | null>(null)
  const [cursorHistory, setCursorHistory] = useState<Array<string | null>>([])

  const [selectedLog, setSelectedLog] = useState<LogEntry | null>(null)

  const [liveTailEnabled, setLiveTailEnabled] = useState(false)
  const [tailPaused, setTailPaused] = useState(false)
  const [tailBufferedCount, setTailBufferedCount] = useState(0)
  const [tailStatus, setTailStatus] = useState<'connecting' | 'open' | 'closed'>('closed')
  const [tailLogs, setTailLogs] = useState<LogEntry[]>([])

  const bufferedTailLogsRef = useRef<LogEntry[]>([])
  const pausedRef = useRef(false)
  const tailScrollRef = useRef<HTMLDivElement>(null)

  const levelsKey = levels.join(',')
  const tagMap = useMemo(() => parseTagFilter(tagFilter), [tagFilter])
  const fromIso = useMemo(() => toIsoOrUndefined(from), [from])
  const toIso = useMemo(() => toIsoOrUndefined(to), [to])

  useEffect(() => {
    pausedRef.current = tailPaused
  }, [tailPaused])

  useEffect(() => {
    if (Number.isFinite(numericProjectId)) {
      setSelectedProjectId(numericProjectId)
    }
  }, [numericProjectId, setSelectedProjectId])

  useEffect(() => {
    setCursor(null)
    setCursorHistory([])
  }, [numericProjectId, query, service, environment, levelsKey, fromIso, toIso, tagFilter])

  const {data: filterOptions} = useQuery({
    queryKey: ['project-log-filters', numericProjectId, fromIso, toIso],
    queryFn: () => api.getProjectLogFilters(numericProjectId, {from: fromIso, to: toIso}),
    enabled: Number.isFinite(numericProjectId),
  })

  const {
    data: logPage,
    isLoading,
    isFetching,
  } = useQuery({
    queryKey: ['project-logs', numericProjectId, cursor, query, service, environment, levelsKey, fromIso, toIso, tagFilter],
    queryFn: () =>
      api.getProjectLogs(numericProjectId, {
        cursor: cursor || undefined,
        limit: 150,
        query: query || undefined,
        levels: levels.length > 0 ? levels : undefined,
        service: service === 'all' ? undefined : service,
        environment: environment === 'all' ? undefined : environment,
        from: fromIso,
        to: toIso,
        tags: Object.keys(tagMap).length > 0 ? tagMap : undefined,
      }),
    enabled: Number.isFinite(numericProjectId),
  })

  const logs = logPage?.logs ?? []

  useEffect(() => {
    if (logs.length === 0) {
      setSelectedLog(null)
      return
    }
    if (!selectedLog || !logs.some((log) => log.logId === selectedLog.logId)) {
      setSelectedLog(logs[0] ?? null)
    }
  }, [logs, selectedLog])

  useEffect(() => {
    if (!liveTailEnabled || !Number.isFinite(numericProjectId)) return

    setTailStatus('connecting')
    const source = api.createProjectLogTailStream(numericProjectId, {
      query: query || undefined,
      levels: levels.length > 0 ? levels : undefined,
      service: service === 'all' ? undefined : service,
      environment: environment === 'all' ? undefined : environment,
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
  }, [liveTailEnabled, numericProjectId, query, service, environment, levelsKey])

  const toggleLevel = (level: string) => {
    setLevels((current) =>
      current.includes(level)
        ? current.filter((item) => item !== level)
        : [...current, level]
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
      setTailLogs((current) => [...current, ...bufferedTailLogsRef.current].slice(-400))
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

  return (
    <div className="min-h-screen bg-gradient-to-br from-background via-background to-blue-500/5">
      <div className="mx-auto max-w-[1500px] space-y-4 p-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="rounded-xl bg-blue-500/15 p-2.5 ring-1 ring-blue-500/30">
              <TerminalSquare className="h-6 w-6 text-blue-600 dark:text-blue-300" />
            </div>
            <div>
              <h2 className="text-2xl font-bold">Log Explorer</h2>
              <p className="text-sm text-muted-foreground">Search, filter, and tail project logs in real time.</p>
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

        <LogFilters
          query={query}
          onQueryChange={setQuery}
          service={service}
          onServiceChange={setService}
          environment={environment}
          onEnvironmentChange={setEnvironment}
          levels={levels}
          onToggleLevel={toggleLevel}
          availableServices={filterOptions?.services ?? []}
          availableEnvironments={filterOptions?.environments ?? []}
          from={from}
          onFromChange={setFrom}
          to={to}
          onToChange={setTo}
          tagFilter={tagFilter}
          onTagFilterChange={setTagFilter}
        />

        <div className="grid gap-4 xl:grid-cols-[2fr,1fr]">
          <div className="space-y-3">
            {isLoading ? (
              <div className="rounded-xl border bg-card p-12 text-center text-sm text-muted-foreground">
                Loading logs...
              </div>
            ) : (
              <LogTable
                logs={logs}
                selectedLogId={selectedLog?.logId}
                onSelectLog={setSelectedLog}
              />
            )}

            <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border bg-card p-3 text-sm">
              <div className="text-muted-foreground">
                {logs.length} logs shown{isFetching ? ' • refreshing...' : ''}
              </div>
              <div className="flex items-center gap-2">
                <Button variant="outline" size="sm" onClick={handlePreviousPage} disabled={cursorHistory.length === 0}>
                  Previous
                </Button>
                <Button variant="outline" size="sm" onClick={handleNextPage} disabled={!logPage?.hasMore}>
                  Next
                </Button>
              </div>
            </div>

            {liveTailEnabled && (
              <Card>
                <CardHeader className="pb-3">
                  <CardTitle className="text-base">Live Tail Stream</CardTitle>
                </CardHeader>
                <CardContent>
                  <div
                    ref={tailScrollRef}
                    onScroll={handleTailScroll}
                    className="max-h-[320px] overflow-auto rounded-lg border bg-muted/20"
                  >
                    {tailLogs.length === 0 ? (
                      <div className="p-6 text-sm text-muted-foreground">Waiting for live log events...</div>
                    ) : (
                      <div className="divide-y">
                        {tailLogs.map((log) => (
                          <button
                            key={`${log.logId}-${log.timestamp}`}
                            className="w-full px-3 py-2 text-left hover:bg-accent/60"
                            onClick={() => setSelectedLog(log)}
                          >
                            <div className="flex items-start gap-3">
                              <span className="min-w-[72px] font-mono text-xs text-muted-foreground">{formatLiveTime(log.timestamp)}</span>
                              <span className="min-w-[50px] font-mono text-xs uppercase text-muted-foreground">{log.level}</span>
                              <span className="font-mono text-xs break-all">{log.message}</span>
                            </div>
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                </CardContent>
              </Card>
            )}
          </div>

          <LogDetail log={selectedLog} />
        </div>
      </div>
    </div>
  )
}
