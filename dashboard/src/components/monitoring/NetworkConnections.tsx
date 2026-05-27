// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api, type DdConnectionResponse} from '@/lib/api'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {Card, CardContent} from '@/components/ui/card'
import {Input} from '@/components/ui/input'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {
  ArrowDownToLine,
  ArrowUpFromLine,
  ArrowLeftRight,
  Globe,
  Loader2,
  Network,
  Search,
  Server,
  Wifi,
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

type ProtocolFilter = 'all' | 'tcp' | 'udp'
type DirectionFilter = 'all' | 'incoming' | 'outgoing'

function directionBadgeClasses(direction: string): string {
  switch (direction?.toLowerCase()) {
    case 'incoming':
      return 'bg-sky-500/15 text-sky-700 dark:text-sky-300 border-sky-500/20'
    case 'outgoing':
      return 'bg-indigo-500/15 text-indigo-700 dark:text-indigo-300 border-indigo-500/20'
    default:
      return 'bg-muted text-muted-foreground border-border'
  }
}

function protocolBadgeClasses(protocol: string): string {
  switch (protocol?.toLowerCase()) {
    case 'tcp':
      return 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20'
    case 'udp':
      return 'bg-orange-500/15 text-orange-700 dark:text-orange-300 border-orange-500/20'
    default:
      return 'bg-muted text-muted-foreground border-border'
  }
}

function DirectionIcon({direction}: {direction: string}) {
  switch (direction?.toLowerCase()) {
    case 'incoming':
      return <ArrowDownToLine className="h-3 w-3" />
    case 'outgoing':
      return <ArrowUpFromLine className="h-3 w-3" />
    default:
      return <ArrowLeftRight className="h-3 w-3" />
  }
}

export function NetworkConnections() {
  const [searchQuery, setSearchQuery] = useState('')
  const [protocolFilter, setProtocolFilter] = useState<ProtocolFilter>('all')
  const [directionFilter, setDirectionFilter] = useState<DirectionFilter>('all')

  const {data, isLoading} = useQuery({
    queryKey: ['connections'],
    queryFn: () =>
      api.getConnections({
        limit: 200,
      }),
    enabled: api.isAuthenticated(),
    refetchInterval: 10000,
  })

  const connections = data?.connections ?? []

  const filtered = useMemo(() => {
    let result = connections
    if (searchQuery) {
      const q = searchQuery.toLowerCase()
      result = result.filter(
        (c) =>
          c.localAddr?.toLowerCase().includes(q) ||
          c.remoteAddr?.toLowerCase().includes(q) ||
          c.host?.toLowerCase().includes(q) ||
          String(c.pid).includes(q) ||
          String(c.localPort).includes(q) ||
          String(c.remotePort).includes(q)
      )
    }
    if (protocolFilter !== 'all') {
      result = result.filter((c) => c.protocol?.toLowerCase() === protocolFilter)
    }
    if (directionFilter !== 'all') {
      result = result.filter((c) => c.direction?.toLowerCase() === directionFilter)
    }
    return result
  }, [connections, searchQuery, protocolFilter, directionFilter])

  const totalSent = connections.reduce((sum, c) => sum + c.bytesSent, 0)
  const totalRecv = connections.reduce((sum, c) => sum + c.bytesRecv, 0)
  const uniqueHosts = new Set(connections.map((c) => c.host)).size
  const tcpCount = connections.filter((c) => c.protocol?.toLowerCase() === 'tcp').length
  const udpCount = connections.filter((c) => c.protocol?.toLowerCase() === 'udp').length
  const incomingCount = connections.filter((c) => c.direction?.toLowerCase() === 'incoming').length
  const outgoingCount = connections.filter((c) => c.direction?.toLowerCase() === 'outgoing').length

  return (
    <div className="space-y-3">
      <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
        {!isLoading && connections.length > 0 && (
          <div className="flex items-center gap-3 text-sm flex-wrap">
            <div className="flex items-center gap-1.5">
              <Network className="h-3.5 w-3.5 text-blue-500" />
              <span className="font-semibold tabular-nums">{connections.length}</span>
              <span className="text-muted-foreground text-xs">connections</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <Server className="h-3.5 w-3.5 text-violet-500" />
              <span className="font-semibold tabular-nums">{uniqueHosts}</span>
              <span className="text-muted-foreground text-xs">{uniqueHosts === 1 ? 'host' : 'hosts'}</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <Wifi className="h-3.5 w-3.5 text-emerald-500" />
              <span className="font-semibold tabular-nums">{tcpCount}</span>
              <span className="text-muted-foreground text-xs">TCP</span>
              <span className="text-muted-foreground text-xs">/</span>
              <span className="font-semibold tabular-nums">{udpCount}</span>
              <span className="text-muted-foreground text-xs">UDP</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <ArrowUpFromLine className="h-3.5 w-3.5 text-sky-500" />
              <span className="font-semibold tabular-nums">{formatBytes(totalSent)}</span>
              <span className="text-muted-foreground text-xs">sent</span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-1.5">
              <ArrowDownToLine className="h-3.5 w-3.5 text-indigo-500" />
              <span className="font-semibold tabular-nums">{formatBytes(totalRecv)}</span>
              <span className="text-muted-foreground text-xs">received</span>
            </div>
          </div>
        )}
      </div>

      <div className="flex flex-col sm:flex-row items-start sm:items-center gap-3">
        <div className="relative flex-1 max-w-md">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Search by address, host, port, or PID..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="pl-9"
          />
        </div>

        <div className="flex items-center gap-1 rounded-lg border bg-background p-1">
          {(['all', 'tcp', 'udp'] as const).map((f) => (
            <button
              key={f}
              onClick={() => setProtocolFilter(f)}
              className={cn(
                'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors',
                protocolFilter === f
                  ? 'bg-secondary text-secondary-foreground'
                  : 'text-muted-foreground hover:text-foreground hover:bg-muted/50'
              )}
            >
              {f === 'tcp' && <div className="h-1.5 w-1.5 rounded-full bg-emerald-500" />}
              {f === 'udp' && <div className="h-1.5 w-1.5 rounded-full bg-orange-500" />}
              {f === 'all' && <div className="h-1.5 w-1.5 rounded-full bg-blue-500" />}
              <span className="uppercase">{f}</span>
              <span className="ml-0.5 text-[10px] text-muted-foreground">
                {f === 'all' ? connections.length : f === 'tcp' ? tcpCount : udpCount}
              </span>
            </button>
          ))}
        </div>

        <div className="flex items-center gap-1 rounded-lg border bg-background p-1">
          {(['all', 'incoming', 'outgoing'] as const).map((f) => (
            <button
              key={f}
              onClick={() => setDirectionFilter(f)}
              className={cn(
                'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors',
                directionFilter === f
                  ? 'bg-secondary text-secondary-foreground'
                  : 'text-muted-foreground hover:text-foreground hover:bg-muted/50'
              )}
            >
              {f === 'incoming' && <div className="h-1.5 w-1.5 rounded-full bg-sky-500" />}
              {f === 'outgoing' && <div className="h-1.5 w-1.5 rounded-full bg-indigo-500" />}
              {f === 'all' && <div className="h-1.5 w-1.5 rounded-full bg-blue-500" />}
              <span className="capitalize">{f}</span>
              <span className="ml-0.5 text-[10px] text-muted-foreground">
                {f === 'all' ? connections.length : f === 'incoming' ? incomingCount : outgoingCount}
              </span>
            </button>
          ))}
        </div>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16">
          <div className="flex flex-col items-center gap-3">
            <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
            <p className="text-muted-foreground text-sm">Loading connections...</p>
          </div>
        </div>
      ) : connections.length === 0 ? (
        <Card className="border-dashed">
          <CardContent className="py-16 text-center">
            <div className="mx-auto mb-6 flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500/10 to-violet-500/10">
              <Globe className="h-10 w-10 text-blue-500" />
            </div>
            <h3 className="text-xl font-semibold mb-2">No connections found</h3>
            <p className="text-muted-foreground mb-2 max-w-sm mx-auto">
              Network connections will appear when an agent sends connection data.
            </p>
          </CardContent>
        </Card>
      ) : filtered.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No connections match your filters</p>
          <p className="text-sm mt-1">Try adjusting your search, protocol, or direction filter.</p>
        </div>
      ) : (
        <Card className="overflow-hidden border-border/60">
          <CardContent className="p-0">
            <Table className="min-w-[950px]">
              <TableHeader>
                <TableRow className="hover:bg-transparent bg-muted/30">
                  <TableHead className="pl-4 w-[70px]">PID</TableHead>
                  <TableHead>Local Address</TableHead>
                  <TableHead>Remote Address</TableHead>
                  <TableHead>Protocol</TableHead>
                  <TableHead>Direction</TableHead>
                  <TableHead>Host</TableHead>
                  <TableHead>Throughput</TableHead>
                  <TableHead className="text-right pr-4">Last Seen</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((conn: DdConnectionResponse) => (
                  <TableRow key={conn.connectionId} className="group hover:bg-muted/50 transition-colors">
                    <TableCell className="pl-4 font-mono text-xs tabular-nums">
                      {conn.pid || '—'}
                    </TableCell>

                    <TableCell>
                      <TooltipProvider delayDuration={300}>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <span className="font-mono text-xs truncate block max-w-[180px]">
                              {conn.localAddr}:{conn.localPort}
                            </span>
                          </TooltipTrigger>
                          <TooltipContent side="top">
                            <p className="font-mono text-xs">{conn.localAddr}:{conn.localPort}</p>
                          </TooltipContent>
                        </Tooltip>
                      </TooltipProvider>
                    </TableCell>

                    <TableCell>
                      <TooltipProvider delayDuration={300}>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <span className="font-mono text-xs truncate block max-w-[180px]">
                              {conn.remoteAddr}:{conn.remotePort}
                            </span>
                          </TooltipTrigger>
                          <TooltipContent side="top">
                            <p className="font-mono text-xs">{conn.remoteAddr}:{conn.remotePort}</p>
                          </TooltipContent>
                        </Tooltip>
                      </TooltipProvider>
                    </TableCell>

                    <TableCell>
                      <Badge
                        variant="secondary"
                        className={cn('text-xs', protocolBadgeClasses(conn.protocol))}
                      >
                        {conn.protocol?.toUpperCase() || '—'}
                      </Badge>
                    </TableCell>

                    <TableCell>
                      {conn.direction ? (
                        <Badge
                          variant="secondary"
                          className={cn('text-xs gap-1', directionBadgeClasses(conn.direction))}
                        >
                          <DirectionIcon direction={conn.direction} />
                          {conn.direction}
                        </Badge>
                      ) : (
                        <span className="text-xs text-muted-foreground">—</span>
                      )}
                    </TableCell>

                    <TableCell>
                      <span className="font-mono text-xs">{conn.host}</span>
                    </TableCell>

                    <TableCell>
                      <div className="text-xs font-medium tabular-nums leading-5">
                        <div className="flex items-center gap-1 text-sky-600 dark:text-sky-400">
                          <ArrowUpFromLine className="h-3 w-3" />
                          {formatBytes(conn.bytesSent)}
                        </div>
                        <div className="flex items-center gap-1 text-indigo-600 dark:text-indigo-400">
                          <ArrowDownToLine className="h-3 w-3" />
                          {formatBytes(conn.bytesRecv)}
                        </div>
                      </div>
                    </TableCell>

                    <TableCell className="text-right pr-4 text-xs text-muted-foreground">
                      {formatRelativeTime(conn.timestamp)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
