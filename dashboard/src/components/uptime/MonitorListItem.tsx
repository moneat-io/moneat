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

import {Link} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, type UptimeMonitor} from '@/lib/api'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDateTime, formatTime} from '@/lib/date-format'
import {cn, formatRelativeTime} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent} from '@/components/ui/card'
import {CheckCircle2, Clock, Pause, Play, Trash2, XCircle} from 'lucide-react'
import {useToast} from '@/hooks/useToast'
import HeartbeatBar from './HeartbeatBar'

interface MonitorListItemProps {
  monitor: UptimeMonitor
}

function getStatusBadge(status: string) {
  switch (status) {
    case 'up':
      return <Badge className="bg-emerald-500 hover:bg-emerald-600"><CheckCircle2 className="mr-1 h-3 w-3" />Up</Badge>
    case 'down':
      return <Badge className="bg-red-500 hover:bg-red-600"><XCircle className="mr-1 h-3 w-3" />Down</Badge>
    case 'paused':
      return <Badge variant="outline"><Pause className="mr-1 h-3 w-3" />Paused</Badge>
    case 'pending':
    default:
      return <Badge variant="secondary"><Clock className="mr-1 h-3 w-3" />Pending</Badge>
  }
}

function getMonitorTypeLabel(type: string): string {
  const labels: Record<string, string> = {
    http: 'HTTP(S)',
    keyword: 'Keyword',
    json_query: 'JSON Query',
    tcp: 'TCP Port',
    ping: 'Ping',
    dns: 'DNS',
    websocket: 'WebSocket',
    push: 'Push',
    docker: 'Docker',
    database: 'Database',
    ssl: 'SSL Certificate',
  }
  return labels[type] || type.toUpperCase()
}

export default function MonitorListItem({monitor}: MonitorListItemProps) {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const { timezone } = useTimezone()

  const {data: heartbeats = []} = useQuery({
    queryKey: ['uptime-heartbeats', monitor.id],
    queryFn: () => api.getUptimeHeartbeats(monitor.id),
    // Refresh every minute
    refetchInterval: 60000,
    // Only fetch if monitor is active/up/down (not paused ideally, but user might want to see history)
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

  const statusColor = {
    up: 'border-l-emerald-500',
    down: 'border-l-red-500',
    paused: 'border-l-yellow-500',
    pending: 'border-l-gray-300',
  }[monitor.status] || 'border-l-gray-300'

  return (
    <Card className={cn("hover:shadow-md transition-shadow overflow-hidden border-l-4", statusColor)}>
      <CardContent className="p-4">
        <div className="flex flex-col gap-4">
          {/* Header Row */}
          <div className="flex items-start justify-between">
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-1">
                <Link 
                  to="/uptime/$monitorId" 
                  params={{monitorId: monitor.id}}
                  className="text-lg font-bold hover:underline truncate"
                >
                  {monitor.name}
                </Link>
                {getStatusBadge(monitor.status)}
              </div>
              <div className="flex items-center gap-3 text-sm text-muted-foreground">
                <Badge variant="outline" className="text-xs font-normal h-5 px-1.5">{getMonitorTypeLabel(monitor.type)}</Badge>
                <a 
                  href={monitor.url} 
                  target="_blank" 
                  rel="noopener noreferrer"
                  className="hover:text-primary truncate max-w-[300px]"
                  onClick={(e) => e.stopPropagation()}
                >
                  {monitor.url || monitor.hostname}
                </a>
                <span className="text-xs">•</span>
                <span className="text-xs" title={formatDateTime(new Date(monitor.lastCheckAt || 0), timezone)}>
                  Checked {monitor.lastCheckAt ? formatRelativeTime(monitor.lastCheckAt) : 'never'}
                </span>
              </div>
            </div>
            
            <div className="flex items-center gap-2">
              <div className="text-right mr-4 hidden sm:block">
                <div className="text-2xl font-bold leading-none">
                  {monitor.uptime24h !== undefined && monitor.uptime24h !== null ? `${monitor.uptime24h.toFixed(0)}%` : '--'}
                </div>
                <div className="text-xs text-muted-foreground uppercase tracking-wider">24h Uptime</div>
              </div>
              
              <div className="text-right mr-4 hidden sm:block">
                <div className="text-xl font-semibold leading-none flex items-center justify-end gap-1">
                  {monitor.avgResponseTime !== undefined && monitor.avgResponseTime !== null ? (
                    <>
                      {monitor.avgResponseTime}
                      <span className="text-xs font-normal text-muted-foreground">ms</span>
                    </>
                  ) : '--'}
                </div>
                <div className="text-xs text-muted-foreground uppercase tracking-wider">Avg Response</div>
              </div>
            </div>
          </div>

          {/* Heartbeat Bar */}
          <div className="w-full">
            <HeartbeatBar heartbeats={heartbeats} maxBars={60} />
          </div>

          {/* Actions Row */}
          <div className="flex items-center justify-between pt-2 border-t mt-2">
            <div className="text-xs text-muted-foreground">
              {heartbeats.length > 0 ? (
                <span>Last heartbeat: {formatTime(new Date(heartbeats[heartbeats.length - 1].timestamp), timezone)}</span>
              ) : (
                <span>No heartbeat data</span>
              )}
            </div>
            <div className="flex gap-2">
              {monitor.status !== 'paused' ? (
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-8 px-2 text-muted-foreground hover:text-foreground"
                  onClick={() => pauseMutation.mutate(monitor.id)}
                  title="Pause Monitor"
                >
                  <Pause className="h-4 w-4 mr-1" />
                  Pause
                </Button>
              ) : (
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-8 px-2 text-muted-foreground hover:text-foreground"
                  onClick={() => resumeMutation.mutate(monitor.id)}
                  title="Resume Monitor"
                >
                  <Play className="h-4 w-4 mr-1" />
                  Resume
                </Button>
              )}
              <Button
                size="sm"
                variant="ghost"
                className="h-8 px-2 text-red-500 hover:text-red-600 hover:bg-red-50"
                onClick={() => {
                  if (confirm(`Delete monitor "${monitor.name}"?`)) {
                    deleteMutation.mutate(monitor.id)
                  }
                }}
                title="Delete Monitor"
              >
                <Trash2 className="h-4 w-4 mr-1" />
                Delete
              </Button>
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
