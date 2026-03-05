// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'

export interface MergedContainer {
  id: string
  host: string
  containerId: string
  name: string
  image: string
  state: string
  cpuPercent: number
  memUsage: number
  memLimit: number
  netRxBytes: number
  netTxBytes: number
  source: 'datadog-agent'
  timestamp?: string
}
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {Card, CardContent} from '@/components/ui/card'
import {Input} from '@/components/ui/input'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {
  Box,
  Cpu,
  ArrowDownToLine,
  ArrowUpFromLine,
  Loader2,
  MemoryStick,
  Search,
  CircleCheck,
  CircleX,
  CirclePause,
  Boxes,
} from 'lucide-react'
import {useState, useMemo} from 'react'
import {cn} from '@/lib/utils'
import {formatRelativeTime} from '@/lib/utils'

function parseTimestamp(ts: string | undefined): number {
  if (!ts) return 0
  const parsed = Date.parse(ts)
  return isNaN(parsed) ? 0 : parsed
}

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
  if (value >= 80) return 'text-red-500'
  if (value >= 50) return 'text-orange-500'
  if (value >= 25) return 'text-yellow-500'
  return 'text-emerald-500'
}

function getCpuBarColor(value: number): string {
  if (value >= 80) return 'bg-red-500'
  if (value >= 50) return 'bg-orange-500'
  if (value >= 25) return 'bg-yellow-500'
  return 'bg-emerald-500'
}

function getMemColor(percent: number): string {
  if (percent >= 90) return 'text-red-500'
  if (percent >= 75) return 'text-orange-500'
  if (percent >= 50) return 'text-yellow-500'
  return 'text-emerald-500'
}

function getMemBarColor(percent: number): string {
  if (percent >= 90) return 'bg-red-500'
  if (percent >= 75) return 'bg-orange-500'
  if (percent >= 50) return 'bg-yellow-500'
  return 'bg-emerald-500'
}

type StateFilter = 'all' | 'running' | 'stopped'

function StateIcon({state}: {state: string}) {
  switch (state) {
    case 'running':
      return <CircleCheck className="h-3.5 w-3.5 text-emerald-500" />
    case 'exited':
    case 'dead':
      return <CircleX className="h-3.5 w-3.5 text-red-500" />
    default:
      return <CirclePause className="h-3.5 w-3.5 text-yellow-500" />
  }
}

function stateBadgeClasses(state: string): string {
  switch (state) {
    case 'running':
      return 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20 hover:bg-emerald-500/20'
    case 'exited':
    case 'dead':
      return 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/20 hover:bg-red-500/20'
    default:
      return 'bg-yellow-500/15 text-yellow-700 dark:text-yellow-300 border-yellow-500/20 hover:bg-yellow-500/20'
  }
}

export function ContainerList() {
  const [searchQuery, setSearchQuery] = useState('')
  const [stateFilter, setStateFilter] = useState<StateFilter>('all')

  const {data: containerData, isLoading} = useQuery({
    queryKey: ['containers'],
    queryFn: async () => {
      const res = await api.getContainers({limit: 200})
      const mapped = res.containers.map((c) => ({
        id: `dd-${c.host}-${c.containerId}`,
        host: c.host,
        containerId: c.containerId,
        name: c.name,
        image: c.image,
        state: c.state,
        cpuPercent: c.cpuPercent,
        memUsage: c.memUsage,
        memLimit: c.memLimit,
        netRxBytes: c.netRxBytes,
        netTxBytes: c.netTxBytes,
        source: 'datadog-agent' as const,
        timestamp: c.timestamp,
      } satisfies MergedContainer))
      // Deduplicate by (host, containerId), keeping the most recent entry
      const seen = new Map<string, MergedContainer>()
      for (const c of mapped) {
        const key = `${c.host}::${c.containerId}`
        const existing = seen.get(key)
        if (!existing || parseTimestamp(c.timestamp) > parseTimestamp(existing.timestamp)) {
          seen.set(key, c)
        }
      }
      return Array.from(seen.values())
    },
    enabled: api.isAuthenticated(),
    refetchInterval: 10000,
  })

  const containers: MergedContainer[] = containerData ?? []

  const filtered = useMemo(() => {
    let result = containers
    if (searchQuery) {
      const q = searchQuery.toLowerCase()
      result = result.filter(
        (c) =>
          c.name?.toLowerCase().includes(q) ||
          c.image?.toLowerCase().includes(q) ||
          c.host?.toLowerCase().includes(q) ||
          c.containerId?.toLowerCase().includes(q)
      )
    }
    if (stateFilter === 'running') {
      result = result.filter((c) => c.state === 'running')
    } else if (stateFilter === 'stopped') {
      result = result.filter((c) => c.state !== 'running')
    }
    return result
  }, [containers, searchQuery, stateFilter])

  const runningCount = containers.filter((c: MergedContainer) => c.state === 'running').length
  const stoppedCount = containers.length - runningCount
  const totalCpu = containers.reduce((sum: number, c: MergedContainer) => sum + c.cpuPercent, 0)
  const totalMem = containers.reduce((sum: number, c: MergedContainer) => sum + c.memUsage, 0)
  const uniqueHosts = new Set(containers.map((c) => c.host)).size

  return (
    <div className="space-y-3">
      <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
        {!isLoading && containers.length > 0 && (
          <div className="flex items-center gap-3 text-sm flex-wrap">
            <div className="flex items-center gap-1.5">
              <Boxes className="h-3.5 w-3.5 text-blue-500" />
              <span className="font-semibold tabular-nums">{containers.length}</span>
              <span className="text-muted-foreground text-xs">total</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <CircleCheck className="h-3.5 w-3.5 text-emerald-500" />
              <span className="font-semibold tabular-nums">{runningCount}</span>
              <span className="text-muted-foreground text-xs">running</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <CircleX className="h-3.5 w-3.5 text-red-500" />
              <span className="font-semibold tabular-nums">{stoppedCount}</span>
              <span className="text-muted-foreground text-xs">stopped</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <Cpu className="h-3.5 w-3.5 text-violet-500" />
              <span className="font-semibold tabular-nums">{totalCpu.toFixed(1)}%</span>
              <span className="text-muted-foreground text-xs">CPU</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <MemoryStick className="h-3.5 w-3.5 text-sky-500" />
              <span className="font-semibold tabular-nums">{formatBytes(totalMem)}</span>
              <span className="text-muted-foreground text-xs">memory</span>
            </div>
          </div>
        )}
      </div>

      <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
        <div className="relative flex-1 max-w-md">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Search by name, image, or host..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9"
          />
        </div>
        <div className="flex items-center gap-1 rounded-lg border bg-background p-1">
          {(['all', 'running', 'stopped'] as const).map((f) => (
            <button
              key={f}
              onClick={() => setStateFilter(f)}
              className={cn(
                'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors',
                stateFilter === f
                  ? 'bg-secondary text-secondary-foreground shadow-sm'
                  : 'text-muted-foreground hover:text-foreground hover:bg-muted/50'
              )}
            >
              {f === 'running' && <div className="h-1.5 w-1.5 rounded-full bg-emerald-500" />}
              {f === 'stopped' && <div className="h-1.5 w-1.5 rounded-full bg-red-500" />}
              {f === 'all' && <div className="h-1.5 w-1.5 rounded-full bg-blue-500" />}
              <span className="capitalize">{f}</span>
              <span className="ml-0.5 text-[10px] text-muted-foreground">
                {f === 'all'
                  ? containers.length
                  : f === 'running'
                    ? runningCount
                    : stoppedCount}
              </span>
            </button>
          ))}
        </div>
        {uniqueHosts > 1 && (
          <span className="text-xs text-muted-foreground">
            across {uniqueHosts} hosts
          </span>
        )}
      </div>

      {/* Table */}
      {isLoading ? (
        <div className="flex items-center justify-center py-16">
          <div className="flex flex-col items-center gap-3">
            <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
            <p className="text-muted-foreground text-sm">Loading containers...</p>
          </div>
        </div>
      ) : containers.length === 0 ? (
        <Card className="border-dashed">
          <CardContent className="py-16 text-center">
            <div className="mx-auto mb-6 flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500/10 to-violet-500/10">
              <Box className="h-10 w-10 text-blue-500" />
            </div>
            <h3 className="text-xl font-semibold mb-2">No containers found</h3>
            <p className="text-muted-foreground mb-2 max-w-sm mx-auto">
              Containers will appear when a monitoring agent with Docker socket access sends container data.
            </p>
          </CardContent>
        </Card>
      ) : filtered.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No containers match your filters</p>
          <p className="text-sm mt-1">Try adjusting your search or state filter.</p>
        </div>
      ) : (
        <Card className="overflow-hidden border-border/60 shadow-sm">
          <CardContent className="p-0">
            <Table className="min-w-[980px]">
              <TableHeader>
                <TableRow className="hover:bg-transparent bg-muted/30">
                  <TableHead className="pl-4">Container</TableHead>
                  <TableHead>State</TableHead>
                  <TableHead>Image</TableHead>
                  <TableHead>Host</TableHead>
                  <TableHead className="min-w-[80px]">Source</TableHead>
                  <TableHead className="min-w-[130px]">CPU</TableHead>
                  <TableHead className="min-w-[160px]">Memory</TableHead>
                  <TableHead>Network</TableHead>
                  <TableHead className="text-right pr-4">Last Seen</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((c: MergedContainer) => {
                  const memPercent = c.memLimit > 0 ? (c.memUsage / c.memLimit) * 100 : 0
                  const displayName = c.name || c.containerId.slice(0, 12)

                  return (
                    <TableRow key={c.id} className="group hover:bg-muted/50 transition-colors">
                      <TableCell className="pl-4">
                        <div className="flex items-center gap-3 min-w-0">
                          <div
                            className={cn(
                              'flex h-8 w-8 shrink-0 items-center justify-center rounded-md',
                              c.state === 'running'
                                ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                                : c.state === 'exited' || c.state === 'dead'
                                  ? 'bg-red-500/10 text-red-600 dark:text-red-400'
                                  : 'bg-yellow-500/10 text-yellow-600 dark:text-yellow-400'
                            )}
                          >
                            <Box className="h-4 w-4" />
                          </div>
                          <div className="min-w-0">
                            <TooltipProvider delayDuration={300}>
                              <Tooltip>
                                <TooltipTrigger asChild>
                                  <p className="font-medium text-sm truncate max-w-[200px]">
                                    {displayName}
                                  </p>
                                </TooltipTrigger>
                                <TooltipContent side="top">
                                  <p className="font-mono text-xs">{c.name || c.containerId}</p>
                                </TooltipContent>
                              </Tooltip>
                            </TooltipProvider>
                            <p className="text-[11px] text-muted-foreground font-mono truncate max-w-[200px]">
                              {c.containerId.slice(0, 12)}
                            </p>
                          </div>
                        </div>
                      </TableCell>

                      <TableCell>
                        <Badge
                          variant="secondary"
                          className={cn('text-xs gap-1.5', stateBadgeClasses(c.state))}
                        >
                          <StateIcon state={c.state} />
                          {c.state}
                        </Badge>
                      </TableCell>

                      <TableCell>
                        <TooltipProvider delayDuration={300}>
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <span className="text-xs font-mono max-w-[180px] truncate block">
                                {c.image || '—'}
                              </span>
                            </TooltipTrigger>
                            {c.image && (
                              <TooltipContent side="top">
                                <p className="font-mono text-xs">{c.image}</p>
                              </TooltipContent>
                            )}
                          </Tooltip>
                        </TooltipProvider>
                      </TableCell>

                      <TableCell>
                        <span className="font-mono text-xs">{c.host}</span>
                      </TableCell>

                      <TableCell>
                        <Badge
                          variant="outline"
                          className={cn(
                            'text-[10px] font-medium',
                            'border-violet-500/30 text-violet-600 dark:text-violet-400 bg-violet-500/5'
                          )}
                        >
                          Datadog
                        </Badge>
                      </TableCell>

                      <TableCell>
                        <div className="space-y-1">
                          <p className={cn('text-xs font-medium tabular-nums', getCpuColor(c.cpuPercent))}>
                            {c.cpuPercent.toFixed(1)}%
                          </p>
                          <div className="h-1.5 w-full rounded-full bg-muted/80 overflow-hidden">
                            <div
                              className={cn('h-full rounded-full transition-all duration-500', getCpuBarColor(c.cpuPercent))}
                              style={{width: `${Math.min(100, c.cpuPercent)}%`}}
                            />
                          </div>
                        </div>
                      </TableCell>

                      <TableCell>
                        <div className="space-y-1">
                          <p className={cn('text-xs font-medium tabular-nums', c.memLimit > 0 ? getMemColor(memPercent) : 'text-foreground')}>
                            {formatBytes(c.memUsage)}
                            {c.memLimit > 0 && (
                              <span className="text-muted-foreground font-normal"> / {formatBytes(c.memLimit)}</span>
                            )}
                          </p>
                          {c.memLimit > 0 && (
                            <div className="h-1.5 w-full rounded-full bg-muted/80 overflow-hidden">
                              <div
                                className={cn('h-full rounded-full transition-all duration-500', getMemBarColor(memPercent))}
                                style={{width: `${Math.min(100, memPercent)}%`}}
                              />
                            </div>
                          )}
                        </div>
                      </TableCell>

                      <TableCell>
                        <div className="text-xs font-medium tabular-nums leading-5">
                          <div className="flex items-center gap-1 text-sky-600 dark:text-sky-400">
                            <ArrowDownToLine className="h-3 w-3" />
                            {formatBytes(c.netRxBytes)}
                          </div>
                          <div className="flex items-center gap-1 text-indigo-600 dark:text-indigo-400">
                            <ArrowUpFromLine className="h-3 w-3" />
                            {formatBytes(c.netTxBytes)}
                          </div>
                        </div>
                      </TableCell>

                      <TableCell className="text-right pr-4 text-xs text-muted-foreground">
                        {c.timestamp ? formatRelativeTime(c.timestamp) : '—'}
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
