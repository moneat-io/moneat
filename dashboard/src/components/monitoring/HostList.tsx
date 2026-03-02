// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api, type DdHostResponse} from '@/lib/api'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {Card, CardContent} from '@/components/ui/card'
import {Input} from '@/components/ui/input'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {
  Cpu,
  HardDrive,
  Loader2,
  MemoryStick,
  Search,
  Server,
  ServerOff,
  CircleCheck,
  CircleX,
  Microchip,
} from 'lucide-react'
import {useState, useMemo} from 'react'
import {cn, formatRelativeTime} from '@/lib/utils'

function formatBytes(kb: number): string {
  if (kb < 1024) return `${kb} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  const gb = mb / 1024
  return `${gb.toFixed(1)} GB`
}

type StatusFilter = 'all' | 'online' | 'offline'
type SortField = 'hostname' | 'cores' | 'memory' | 'lastSeen'
type SortDir = 'asc' | 'desc'

export function HostList() {
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all')
  const [sortField, setSortField] = useState<SortField>('hostname')
  const [sortDir, setSortDir] = useState<SortDir>('asc')

  const {data, isLoading} = useQuery({
    queryKey: ['hosts'],
    queryFn: () => api.getHosts(),
    enabled: api.isAuthenticated(),
    refetchInterval: 30000,
  })

  const hosts = data?.hosts ?? []

  const maxMemory = useMemo(
    () => Math.max(...hosts.map((h) => h.memoryTotalKb), 1),
    [hosts]
  )

  const filtered = useMemo(() => {
    let result = hosts
    if (searchQuery) {
      const q = searchQuery.toLowerCase()
      result = result.filter(
        (h) =>
          h.hostname?.toLowerCase().includes(q) ||
          h.os?.toLowerCase().includes(q) ||
          h.platform?.toLowerCase().includes(q) ||
          h.processor?.toLowerCase().includes(q) ||
          h.agentVersion?.toLowerCase().includes(q)
      )
    }
    if (statusFilter === 'online') result = result.filter((h) => h.isOnline)
    else if (statusFilter === 'offline') result = result.filter((h) => !h.isOnline)

    result = [...result].sort((a, b) => {
      const dir = sortDir === 'asc' ? 1 : -1
      switch (sortField) {
        case 'hostname':
          return a.hostname.localeCompare(b.hostname) * dir
        case 'cores':
          return ((a.cpuCores || 0) - (b.cpuCores || 0)) * dir
        case 'memory':
          return ((a.memoryTotalKb || 0) - (b.memoryTotalKb || 0)) * dir
        case 'lastSeen':
          return (new Date(a.lastSeenAt).getTime() - new Date(b.lastSeenAt).getTime()) * dir
        default:
          return 0
      }
    })

    return result
  }, [hosts, searchQuery, statusFilter, sortField, sortDir])

  const onlineCount = hosts.filter((h) => h.isOnline).length
  const offlineCount = hosts.length - onlineCount
  const totalCores = hosts.reduce((sum, h) => sum + (h.cpuCores || 0), 0)
  const totalMemoryKb = hosts.reduce((sum, h) => sum + (h.memoryTotalKb || 0), 0)

  function toggleSort(field: SortField) {
    if (sortField === field) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortField(field)
      setSortDir(field === 'hostname' ? 'asc' : 'desc')
    }
  }

  function renderSortIndicator(field: SortField) {
    if (sortField !== field) return null
    return <span className="ml-1 text-[10px]">{sortDir === 'asc' ? '▲' : '▼'}</span>
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
        {!isLoading && hosts.length > 0 && (
          <div className="flex items-center gap-3 text-sm flex-wrap">
            <div className="flex items-center gap-1.5">
              <HardDrive className="h-3.5 w-3.5 text-blue-500" />
              <span className="font-semibold tabular-nums">{hosts.length}</span>
              <span className="text-muted-foreground text-xs">hosts</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <CircleCheck className="h-3.5 w-3.5 text-emerald-500" />
              <span className="font-semibold tabular-nums">{onlineCount}</span>
              <span className="text-muted-foreground text-xs">online</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <CircleX className="h-3.5 w-3.5 text-red-500" />
              <span className="font-semibold tabular-nums">{offlineCount}</span>
              <span className="text-muted-foreground text-xs">offline</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <Cpu className="h-3.5 w-3.5 text-violet-500" />
              <span className="font-semibold tabular-nums">{totalCores}</span>
              <span className="text-muted-foreground text-xs">cores</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <MemoryStick className="h-3.5 w-3.5 text-sky-500" />
              <span className="font-semibold tabular-nums">{formatBytes(totalMemoryKb)}</span>
              <span className="text-muted-foreground text-xs">memory</span>
            </div>
          </div>
        )}
      </div>

      <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
        <div className="relative flex-1 max-w-md">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Search by hostname, OS, or processor..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9"
          />
        </div>
        <div className="flex items-center gap-1 rounded-lg border bg-background p-1">
          {([
            {key: 'all' as const, color: 'bg-blue-500', count: hosts.length},
            {key: 'online' as const, color: 'bg-emerald-500', count: onlineCount},
            {key: 'offline' as const, color: 'bg-red-500', count: offlineCount},
          ]).map((f) => (
            <button
              key={f.key}
              onClick={() => setStatusFilter(f.key)}
              className={cn(
                'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors',
                statusFilter === f.key
                  ? 'bg-secondary text-secondary-foreground shadow-sm'
                  : 'text-muted-foreground hover:text-foreground hover:bg-muted/50'
              )}
            >
              <div className={cn('h-1.5 w-1.5 rounded-full', f.color)} />
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
            <p className="text-muted-foreground text-sm">Loading hosts...</p>
          </div>
        </div>
      ) : hosts.length === 0 ? (
        <Card className="border-dashed">
          <CardContent className="py-16 text-center">
            <div className="mx-auto mb-6 flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500/10 to-violet-500/10">
              <HardDrive className="h-10 w-10 text-blue-500" />
            </div>
            <h3 className="text-xl font-semibold mb-2">No hosts reporting</h3>
            <p className="text-muted-foreground mb-2 max-w-sm mx-auto">
              Hosts will appear when an agent sends metadata.
            </p>
          </CardContent>
        </Card>
      ) : filtered.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No hosts match your filters</p>
          <p className="text-sm mt-1">Try adjusting your search or status filter.</p>
        </div>
      ) : (
        <Card className="overflow-hidden border-border/60 shadow-sm">
          <CardContent className="p-0">
            <Table className="min-w-[900px]">
              <TableHeader>
                <TableRow className="hover:bg-transparent bg-muted/30">
                  <TableHead className="pl-4">Status</TableHead>
                  <TableHead
                    className="cursor-pointer select-none hover:text-foreground transition-colors"
                    onClick={() => toggleSort('hostname')}
                  >
                    Host
                    {renderSortIndicator('hostname')}
                  </TableHead>
                  <TableHead>OS / Platform</TableHead>
                  <TableHead>Processor</TableHead>
                  <TableHead
                    className="cursor-pointer select-none hover:text-foreground transition-colors"
                    onClick={() => toggleSort('cores')}
                  >
                    CPU Cores
                    {renderSortIndicator('cores')}
                  </TableHead>
                  <TableHead
                    className="min-w-[160px] cursor-pointer select-none hover:text-foreground transition-colors"
                    onClick={() => toggleSort('memory')}
                  >
                    Memory
                    {renderSortIndicator('memory')}
                  </TableHead>
                  <TableHead>Agent</TableHead>
                  <TableHead
                    className="text-right pr-4 cursor-pointer select-none hover:text-foreground transition-colors"
                    onClick={() => toggleSort('lastSeen')}
                  >
                    Last Seen
                    {renderSortIndicator('lastSeen')}
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((host: DdHostResponse) => {
                  const online = host.isOnline
                  const memPct = maxMemory > 0 ? (host.memoryTotalKb / maxMemory) * 100 : 0

                  return (
                    <TableRow key={host.id} className="group hover:bg-muted/50 transition-colors">
                      <TableCell className="pl-4">
                        <Badge
                          variant="secondary"
                          className={cn(
                            'text-xs gap-1.5',
                            online
                              ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20 hover:bg-emerald-500/20'
                              : 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/20 hover:bg-red-500/20'
                          )}
                        >
                          <div
                            className={cn(
                              'h-1.5 w-1.5 rounded-full',
                              online ? 'bg-emerald-500 animate-pulse' : 'bg-red-500'
                            )}
                          />
                          {online ? 'Online' : 'Offline'}
                        </Badge>
                      </TableCell>

                      <TableCell>
                        <div className="flex items-center gap-3 min-w-0">
                          <div
                            className={cn(
                              'flex h-8 w-8 shrink-0 items-center justify-center rounded-md',
                              online
                                ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                                : 'bg-red-500/10 text-red-600 dark:text-red-400'
                            )}
                          >
                            {online ? <Server className="h-4 w-4" /> : <ServerOff className="h-4 w-4" />}
                          </div>
                          <div className="min-w-0">
                            <TooltipProvider delayDuration={300}>
                              <Tooltip>
                                <TooltipTrigger asChild>
                                  <p className="font-medium text-sm truncate max-w-[230px]">
                                    {host.hostname}
                                  </p>
                                </TooltipTrigger>
                                <TooltipContent side="top">
                                  <p className="font-mono text-xs">{host.hostname}</p>
                                </TooltipContent>
                              </Tooltip>
                            </TooltipProvider>
                            {host.firstSeenAt && (
                              <p className="text-[11px] text-muted-foreground truncate max-w-[230px]">
                                First seen {formatRelativeTime(host.firstSeenAt)}
                              </p>
                            )}
                          </div>
                        </div>
                      </TableCell>

                      <TableCell>
                        <div className="min-w-0">
                          <p className="text-sm truncate max-w-[180px]">{host.os || '—'}</p>
                          {host.platform && host.platform !== host.os && (
                            <p className="text-[11px] text-muted-foreground truncate max-w-[180px]">
                              {host.platform}
                            </p>
                          )}
                        </div>
                      </TableCell>

                      <TableCell>
                        <TooltipProvider delayDuration={300}>
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <div className="flex items-center gap-1.5 min-w-0">
                                <Microchip className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                                <span className="text-xs font-mono truncate max-w-[160px]">
                                  {host.processor || '—'}
                                </span>
                              </div>
                            </TooltipTrigger>
                            {host.processor && (
                              <TooltipContent side="top" className="max-w-xs">
                                <p className="font-mono text-xs">{host.processor}</p>
                              </TooltipContent>
                            )}
                          </Tooltip>
                        </TooltipProvider>
                      </TableCell>

                      <TableCell>
                        <div className="flex items-center gap-1.5">
                          <Cpu className="h-3.5 w-3.5 text-violet-500" />
                          <span className="text-sm font-medium tabular-nums">
                            {host.cpuCores || '—'}
                          </span>
                        </div>
                      </TableCell>

                      <TableCell>
                        <div className="space-y-1">
                          <p className="text-xs font-medium tabular-nums">
                            {host.memoryTotalKb ? formatBytes(host.memoryTotalKb) : '—'}
                          </p>
                          {host.memoryTotalKb > 0 && (
                            <div className="h-1.5 w-full rounded-full bg-muted/80 overflow-hidden">
                              <div
                                className="h-full rounded-full transition-all duration-500 bg-sky-500"
                                style={{width: `${Math.min(100, memPct)}%`}}
                              />
                            </div>
                          )}
                        </div>
                      </TableCell>

                      <TableCell>
                        {host.agentVersion ? (
                          <Badge
                            variant="outline"
                            className="text-[10px] font-medium font-mono border-violet-500/30 text-violet-600 dark:text-violet-400 bg-violet-500/5"
                          >
                            v{host.agentVersion}
                          </Badge>
                        ) : (
                          <span className="text-xs text-muted-foreground">—</span>
                        )}
                      </TableCell>

                      <TableCell className="text-right pr-4 text-xs text-muted-foreground">
                        {host.lastSeenAt ? formatRelativeTime(host.lastSeenAt) : 'Never seen'}
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
