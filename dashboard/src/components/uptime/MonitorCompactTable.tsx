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

import React from 'react'
import {Link} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, type UptimeMonitor} from '@/lib/api'
import {cn, formatRelativeTime} from '@/lib/utils'
import {Button} from '@/components/ui/button'
import {Card, CardContent} from '@/components/ui/card'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {CheckCircle2, XCircle, Pause, Play, Trash2, ArrowUpRight, Activity} from 'lucide-react'
import {useToast} from '@/hooks/useToast'
import HeartbeatBar from './HeartbeatBar'
import {getStatusBadge} from './MonitorStatusBadge'

interface MonitorCompactTableProps {
  readonly monitors: UptimeMonitor[]
}

function getMonitorIconClass(status: string, isOnline: boolean): string {
  if (isOnline) return 'flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
  if (status === 'down') return 'flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-red-500/10 text-red-600 dark:text-red-400'
  return 'flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-yellow-500/10 text-yellow-600 dark:text-yellow-400'
}

function getUptimeClass(uptime: number): string {
  if (uptime >= 99) return 'text-emerald-600 dark:text-emerald-400'
  if (uptime >= 95) return 'text-yellow-600 dark:text-yellow-400'
  return 'text-red-600 dark:text-red-400'
}

function MonitorRow({monitor}: {readonly monitor: UptimeMonitor}) {
  const {toast} = useToast()
  const queryClient = useQueryClient()

  const {data: heartbeats = []} = useQuery({
    queryKey: ['uptime-heartbeats', monitor.id],
    queryFn: () => api.getUptimeHeartbeats(monitor.id),
    refetchInterval: 60000,
    enabled: true,
  })

  const deleteMutation = useMutation({
    mutationFn: (monitorId: string) => api.deleteUptimeMonitor(monitorId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['uptime-monitors']})
      toast({title: 'Monitor deleted'})
    },
    onError: () => {
      toast({title: 'Failed to delete monitor', variant: 'destructive'})
    },
  })

  const pauseMutation = useMutation({
    mutationFn: (monitorId: string) => api.pauseUptimeMonitor(monitorId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['uptime-monitors']})
      toast({title: 'Monitor paused'})
    },
  })

  const resumeMutation = useMutation({
    mutationFn: (monitorId: string) => api.resumeUptimeMonitor(monitorId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['uptime-monitors']})
      toast({title: 'Monitor resumed'})
    },
  })

  const isOnline = monitor.status === 'up'
  const iconClass = getMonitorIconClass(monitor.status, isOnline)
  let statusIcon: React.ReactNode
  if (isOnline) {
    statusIcon = <CheckCircle2 className="h-4 w-4" />
  } else if (monitor.status === 'down') {
    statusIcon = <XCircle className="h-4 w-4" />
  } else {
    statusIcon = <Pause className="h-4 w-4" />
  }

  return (
    <TableRow className="group">
      <TableCell className="pl-4">
        <div className="flex items-center gap-3 min-w-0">
          <div className={cn(iconClass)}>
            {statusIcon}
          </div>
          <div className="min-w-0">
            <Link
              to="/uptime/$monitorId"
              params={{monitorId: monitor.id}}
              className="inline-flex items-center gap-1 font-medium hover:underline underline-offset-4"
            >
              <span className="truncate max-w-[230px]">{monitor.name}</span>
              <ArrowUpRight className="h-3.5 w-3.5 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100" />
            </Link>
            <div className="flex items-center gap-2 text-xs text-muted-foreground mt-0.5">
              <span className="uppercase">{monitor.type}</span>
              <span>•</span>
              <a 
                href={monitor.url} 
                target="_blank" 
                rel="noopener noreferrer"
                className="hover:text-primary truncate max-w-[200px]"
                onClick={(e) => e.stopPropagation()}
              >
                {monitor.url || monitor.hostname}
              </a>
            </div>
          </div>
        </div>
      </TableCell>

      <TableCell>
        {getStatusBadge(monitor.status)}
      </TableCell>

      <TableCell className="hidden md:table-cell min-w-[150px]">
        <div className="h-8 w-full flex items-center">
             <HeartbeatBar heartbeats={heartbeats} maxBars={30} className="h-6 w-full" />
        </div>
      </TableCell>

      <TableCell className="hidden sm:table-cell text-right">
        <div className="font-mono text-sm font-medium">
          {monitor.uptime24h !== undefined && monitor.uptime24h !== null ? (
            <span className={cn(getUptimeClass(monitor.uptime24h))}>
              {monitor.uptime24h.toFixed(2)}%
            </span>
          ) : '--'}
        </div>
        <div className="text-xs text-muted-foreground">24h</div>
      </TableCell>

      <TableCell className="hidden lg:table-cell text-right">
        <div className="flex items-center justify-end gap-1 font-mono text-sm">
          {monitor.avgResponseTime !== undefined && monitor.avgResponseTime !== null ? (
            <>
              <Activity className="h-3 w-3 text-muted-foreground" />
              {monitor.avgResponseTime}ms
            </>
          ) : '--'}
        </div>
      </TableCell>

      <TableCell className="text-right text-xs text-muted-foreground hidden xl:table-cell">
        {monitor.lastCheckAt ? formatRelativeTime(monitor.lastCheckAt) : 'Never'}
      </TableCell>

      <TableCell className="pr-4 text-right">
        <div className="flex items-center justify-end gap-1">
          {monitor.status === 'paused' ? (
            <Button
              size="sm"
              variant="ghost"
              className="h-7 w-7 p-0 text-muted-foreground hover:text-foreground"
              onClick={() => resumeMutation.mutate(monitor.id)}
              title="Resume Monitor"
            >
              <Play className="h-3.5 w-3.5" />
            </Button>
          ) : (
            <Button
              size="sm"
              variant="ghost"
              className="h-7 w-7 p-0 text-muted-foreground hover:text-foreground"
              onClick={() => pauseMutation.mutate(monitor.id)}
              title="Pause Monitor"
            >
              <Pause className="h-3.5 w-3.5" />
            </Button>
          )}
          <Button
            size="sm"
            variant="ghost"
            className="h-7 w-7 p-0 text-muted-foreground hover:text-destructive"
            onClick={() => {
              if (confirm(`Delete monitor "${monitor.name}"?`)) {
                deleteMutation.mutate(monitor.id)
              }
            }}
            title="Delete Monitor"
          >
            <Trash2 className="h-3.5 w-3.5" />
          </Button>
        </div>
      </TableCell>
    </TableRow>
  )
}

export default function MonitorCompactTable({monitors}: MonitorCompactTableProps) {
  return (
    <Card className="overflow-hidden border-border/60 shadow-sm">
      <CardContent className="p-0">
        <Table className="min-w-[800px]">
          <TableHeader>
            <TableRow className="hover:bg-transparent bg-muted/30">
              <TableHead className="pl-4 w-[300px]">Monitor</TableHead>
              <TableHead className="w-[100px]">Status</TableHead>
              <TableHead className="hidden md:table-cell">History (24h)</TableHead>
              <TableHead className="hidden sm:table-cell text-right">Uptime</TableHead>
              <TableHead className="hidden lg:table-cell text-right">Response</TableHead>
              <TableHead className="hidden xl:table-cell text-right">Last Check</TableHead>
              <TableHead className="pr-4 text-right w-[100px]">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {monitors.map((monitor) => (
              <MonitorRow key={monitor.id} monitor={monitor} />
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}
