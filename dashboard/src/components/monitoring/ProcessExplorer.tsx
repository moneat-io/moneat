// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api, type DdProcessResponse} from '@/lib/api'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {StatusDot} from '@/components/ui/status-dot'
import {Card, CardContent} from '@/components/ui/card'
import {Input} from '@/components/ui/input'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {
  Cpu,
  Loader2,
  MemoryStick,
  Search,
  Activity,
  Server,
  Hash,
  Terminal,
  CircleCheck,
  CirclePause,
  CircleX,
  CircleDot,
} from 'lucide-react'
import {useState, useMemo} from 'react'
import {cn} from '@/lib/utils'
import {formatRelativeTime} from '@/lib/utils'

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb.toFixed(1)} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  const gb = mb / 1024
  return `${gb.toFixed(2)} GB`
}

function getCpuColor(value: number): string {
  if (value >= 80) return 'text-danger-fg'
  if (value >= 50) return 'text-chart-6'
  if (value >= 25) return 'text-warning-fg'
  return 'text-success-fg'
}

function getCpuBarColor(value: number): string {
  if (value >= 80) return 'bg-danger-solid'
  if (value >= 50) return 'bg-chart-6'
  if (value >= 25) return 'bg-warning-solid'
  return 'bg-success-solid'
}

function getMemColor(rss: number, maxRss: number): string {
  if (maxRss === 0) return 'text-foreground'
  const pct = (rss / maxRss) * 100
  if (pct >= 80) return 'text-danger-fg'
  if (pct >= 50) return 'text-chart-6'
  if (pct >= 25) return 'text-warning-fg'
  return 'text-success-fg'
}

function getMemBarColor(rss: number, maxRss: number): string {
  if (maxRss === 0) return 'bg-muted'
  const pct = (rss / maxRss) * 100
  if (pct >= 80) return 'bg-danger-solid'
  if (pct >= 50) return 'bg-chart-6'
  if (pct >= 25) return 'bg-warning-solid'
  return 'bg-success-solid'
}

type StateFilter = 'all' | 'running' | 'sleeping' | 'zombie'

function stateLabel(state: string): string {
  switch (state?.toLowerCase()) {
    case 'r':
    case 'running':
      return 'Running'
    case 's':
    case 'sleeping':
    case 'idle':
      return 'Sleeping'
    case 'd':
    case 'disk sleep':
      return 'Disk Sleep'
    case 'z':
    case 'zombie':
      return 'Zombie'
    case 't':
    case 'stopped':
    case 'traced':
      return 'Stopped'
    default:
      return state || 'Unknown'
  }
}

function StateIcon({state}: {state: string}) {
  const s = state?.toLowerCase()
  if (s === 'r' || s === 'running') return <CircleCheck className="h-3.5 w-3.5 text-success-fg" />
  if (s === 'z' || s === 'zombie') return <CircleX className="h-3.5 w-3.5 text-danger-fg" />
  if (s === 't' || s === 'stopped' || s === 'traced') return <CirclePause className="h-3.5 w-3.5 text-warning-fg" />
  if (s === 's' || s === 'sleeping' || s === 'idle') return <CircleDot className="h-3.5 w-3.5 text-info-fg" />
  return <CircleDot className="h-3.5 w-3.5 text-muted-foreground" />
}

function stateBadgeVariant(state: string): 'success' | 'danger' | 'warning' | 'info' | 'neutral' {
  const s = state?.toLowerCase()
  if (s === 'r' || s === 'running') return 'success'
  if (s === 'z' || s === 'zombie') return 'danger'
  if (s === 't' || s === 'stopped' || s === 'traced') return 'warning'
  if (s === 's' || s === 'sleeping' || s === 'idle') return 'info'
  return 'neutral'
}

function isRunning(state: string): boolean {
  const s = state?.toLowerCase()
  return s === 'r' || s === 'running'
}

function isSleeping(state: string): boolean {
  const s = state?.toLowerCase()
  return s === 's' || s === 'sleeping' || s === 'idle' || s === 'd' || s === 'disk sleep'
}

function isZombie(state: string): boolean {
  const s = state?.toLowerCase()
  return s === 'z' || s === 'zombie'
}

type SortField = 'cpu' | 'memory' | 'name' | 'pid' | 'threads'
type SortDir = 'asc' | 'desc'

export function ProcessExplorer() {
  const [searchQuery, setSearchQuery] = useState('')
  const [stateFilter, setStateFilter] = useState<StateFilter>('all')
  const [sortField, setSortField] = useState<SortField>('cpu')
  const [sortDir, setSortDir] = useState<SortDir>('desc')

  const {data, isLoading} = useQuery({
    queryKey: ['processes'],
    queryFn: () =>
      api.getProcesses({
        limit: 200,
      }),
    enabled: api.isAuthenticated(),
    refetchInterval: 10000,
  })

  const processes = useMemo(() => data?.processes ?? [], [data?.processes])

  const maxRss = useMemo(
    () => Math.max(...processes.map((p) => p.memRss), 1),
    [processes]
  )

  const filtered = useMemo(() => {
    let result = processes
    if (searchQuery) {
      const q = searchQuery.toLowerCase()
      result = result.filter(
        (p) =>
          p.name?.toLowerCase().includes(q) ||
          p.command?.toLowerCase().includes(q) ||
          p.user?.toLowerCase().includes(q) ||
          p.host?.toLowerCase().includes(q) ||
          String(p.pid).includes(q)
      )
    }
    if (stateFilter === 'running') result = result.filter((p) => isRunning(p.state))
    else if (stateFilter === 'sleeping') result = result.filter((p) => isSleeping(p.state))
    else if (stateFilter === 'zombie') result = result.filter((p) => isZombie(p.state))

    result = [...result].sort((a, b) => {
      const dir = sortDir === 'asc' ? 1 : -1
      switch (sortField) {
        case 'cpu':
          return (a.cpuPercent - b.cpuPercent) * dir
        case 'memory':
          return (a.memRss - b.memRss) * dir
        case 'name':
          return a.name.localeCompare(b.name) * dir
        case 'pid':
          return (a.pid - b.pid) * dir
        case 'threads':
          return (a.threadCount - b.threadCount) * dir
        default:
          return 0
      }
    })

    return result
  }, [processes, searchQuery, stateFilter, sortField, sortDir])

  const runningCount = processes.filter((p) => isRunning(p.state)).length
  const sleepingCount = processes.filter((p) => isSleeping(p.state)).length
  const zombieCount = processes.filter((p) => isZombie(p.state)).length
  const totalCpu = processes.reduce((sum, p) => sum + p.cpuPercent, 0)
  const totalRss = processes.reduce((sum, p) => sum + p.memRss, 0)
  const totalThreads = processes.reduce((sum, p) => sum + p.threadCount, 0)
  const uniqueHosts = new Set(processes.map((p) => p.host)).size

  function toggleSort(field: SortField) {
    if (sortField === field) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortField(field)
      setSortDir(field === 'name' ? 'asc' : 'desc')
    }
  }

  function renderSortIndicator(field: SortField) {
    if (sortField !== field) return null
    return <span className="ml-1 text-[10px]">{sortDir === 'asc' ? '▲' : '▼'}</span>
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
        {!isLoading && processes.length > 0 && (
          <div className="flex items-center gap-3 text-sm flex-wrap">
            <div className="flex items-center gap-1.5">
              <Activity className="h-3.5 w-3.5 text-muted-foreground" />
              <span className="font-semibold tabular-nums">{processes.length}</span>
              <span className="text-muted-foreground text-xs">processes</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <Server className="h-3.5 w-3.5 text-chart-2" />
              <span className="font-semibold tabular-nums">{uniqueHosts}</span>
              <span className="text-muted-foreground text-xs">{uniqueHosts === 1 ? 'host' : 'hosts'}</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <Cpu className="h-3.5 w-3.5 text-chart-1" />
              <span className="font-semibold tabular-nums">{totalCpu.toFixed(1)}%</span>
              <span className="text-muted-foreground text-xs">CPU</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <MemoryStick className="h-3.5 w-3.5 text-chart-3" />
              <span className="font-semibold tabular-nums">{formatBytes(totalRss)}</span>
              <span className="text-muted-foreground text-xs">RSS</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <Hash className="h-3.5 w-3.5 text-chart-6" />
              <span className="font-semibold tabular-nums">{totalThreads.toLocaleString()}</span>
              <span className="text-muted-foreground text-xs">threads</span>
            </div>
          </div>
        )}
      </div>

      <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
        <div className="relative flex-1 max-w-md">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Search by name, command, user, or host..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9"
          />
        </div>
        <div className="flex items-center gap-1 rounded-lg border bg-background p-1">
          {([
            {key: 'all' as const, tone: 'neutral' as const, count: processes.length},
            {key: 'running' as const, tone: 'success' as const, count: runningCount},
            {key: 'sleeping' as const, tone: 'info' as const, count: sleepingCount},
            {key: 'zombie' as const, tone: 'danger' as const, count: zombieCount},
          ]).map((f) => (
            <button
              key={f.key}
              onClick={() => setStateFilter(f.key)}
              className={cn(
                'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors',
                stateFilter === f.key
                  ? 'bg-secondary text-secondary-foreground'
                  : 'text-muted-foreground hover:text-foreground hover:bg-muted/50'
              )}
            >
              <StatusDot tone={f.tone} size="sm" />
              <span className="capitalize">{f.key}</span>
              <span className="ml-0.5 text-[10px] text-muted-foreground">{f.count}</span>
            </button>
          ))}
        </div>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16">
          <div className="flex flex-col items-center gap-3">
            <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
            <p className="text-muted-foreground text-sm">Loading processes...</p>
          </div>
        </div>
      ) : processes.length === 0 ? (
        <Card className="border-dashed">
          <CardContent className="py-16 text-center">
            <div className="mx-auto mb-6 flex h-20 w-20 items-center justify-center rounded-2xl bg-muted text-muted-foreground">
              <Terminal className="h-10 w-10" />
            </div>
            <h3 className="text-xl font-semibold mb-2">No processes found</h3>
            <p className="text-muted-foreground mb-2 max-w-sm mx-auto">
              Processes will appear when a monitoring agent sends process data.
            </p>
          </CardContent>
        </Card>
      ) : filtered.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No processes match your filters</p>
          <p className="text-sm mt-1">Try adjusting your search or state filter.</p>
        </div>
      ) : (
        <Card className="overflow-hidden border-border/60">
          <CardContent className="p-0">
            <Table className="min-w-[950px]">
              <TableHeader>
                <TableRow className="hover:bg-transparent bg-muted/30">
                  <TableHead
                    className="pl-4 cursor-pointer select-none hover:text-foreground transition-colors"
                    onClick={() => toggleSort('pid')}
                  >
                    PID
                    {renderSortIndicator('pid')}
                  </TableHead>
                  <TableHead
                    className="cursor-pointer select-none hover:text-foreground transition-colors"
                    onClick={() => toggleSort('name')}
                  >
                    Process
                    {renderSortIndicator('name')}
                  </TableHead>
                  <TableHead>User</TableHead>
                  <TableHead>Host</TableHead>
                  <TableHead>State</TableHead>
                  <TableHead
                    className="min-w-[130px] cursor-pointer select-none hover:text-foreground transition-colors"
                    onClick={() => toggleSort('cpu')}
                  >
                    CPU
                    {renderSortIndicator('cpu')}
                  </TableHead>
                  <TableHead
                    className="min-w-[140px] cursor-pointer select-none hover:text-foreground transition-colors"
                    onClick={() => toggleSort('memory')}
                  >
                    RSS Memory
                    {renderSortIndicator('memory')}
                  </TableHead>
                  <TableHead
                    className="text-right cursor-pointer select-none hover:text-foreground transition-colors"
                    onClick={() => toggleSort('threads')}
                  >
                    Threads
                    {renderSortIndicator('threads')}
                  </TableHead>
                  <TableHead className="text-right pr-4">Last Seen</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((proc: DdProcessResponse) => {
                  const memPct = maxRss > 0 ? (proc.memRss / maxRss) * 100 : 0

                  return (
                    <TableRow key={proc.processId} className="group hover:bg-muted/50 transition-colors">
                      <TableCell className="pl-4 font-mono text-xs tabular-nums text-muted-foreground">
                        {proc.pid}
                      </TableCell>

                      <TableCell>
                        <div className="min-w-0">
                          <TooltipProvider delayDuration={300}>
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <p className="font-medium text-sm truncate max-w-[220px]">
                                  {proc.name}
                                </p>
                              </TooltipTrigger>
                              <TooltipContent side="top" className="max-w-md">
                                <p className="font-mono text-xs break-all">{proc.command || proc.name}</p>
                              </TooltipContent>
                            </Tooltip>
                          </TooltipProvider>
                          {proc.command && proc.command !== proc.name && (
                            <p className="text-[11px] text-muted-foreground font-mono truncate max-w-[220px]">
                              {proc.command}
                            </p>
                          )}
                        </div>
                      </TableCell>

                      <TableCell className="text-xs">{proc.user || '—'}</TableCell>

                      <TableCell>
                        <span className="font-mono text-xs">{proc.host}</span>
                      </TableCell>

                      <TableCell>
                        <Badge
                          variant={stateBadgeVariant(proc.state)}
                          className="text-[11px] gap-1.5 font-medium"
                        >
                          <StateIcon state={proc.state} />
                          {stateLabel(proc.state)}
                        </Badge>
                      </TableCell>

                      <TableCell>
                        <div className="space-y-1">
                          <p className={cn('text-xs font-medium tabular-nums', getCpuColor(proc.cpuPercent))}>
                            {proc.cpuPercent.toFixed(1)}%
                          </p>
                          <div className="h-1.5 w-full rounded-full bg-muted/80 overflow-hidden">
                            <div
                              className={cn('h-full rounded-full transition-all duration-500', getCpuBarColor(proc.cpuPercent))}
                              style={{width: `${Math.min(100, proc.cpuPercent)}%`}}
                            />
                          </div>
                        </div>
                      </TableCell>

                      <TableCell>
                        <div className="space-y-1">
                          <p className={cn('text-xs font-medium tabular-nums', getMemColor(proc.memRss, maxRss))}>
                            {formatBytes(proc.memRss)}
                            {proc.memVms > 0 && (
                              <span className="text-muted-foreground font-normal"> / {formatBytes(proc.memVms)} virt</span>
                            )}
                          </p>
                          <div className="h-1.5 w-full rounded-full bg-muted/80 overflow-hidden">
                            <div
                              className={cn('h-full rounded-full transition-all duration-500', getMemBarColor(proc.memRss, maxRss))}
                              style={{width: `${Math.min(100, memPct)}%`}}
                            />
                          </div>
                        </div>
                      </TableCell>

                      <TableCell className="text-right text-sm tabular-nums">
                        {proc.threadCount}
                      </TableCell>

                      <TableCell className="text-right pr-4 text-xs text-muted-foreground">
                        {formatRelativeTime(proc.timestamp)}
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
