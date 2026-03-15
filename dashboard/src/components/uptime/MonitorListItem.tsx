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
import {Pause, Play, Trash2} from 'lucide-react'
import {useToast} from '@/hooks/useToast'
import HeartbeatBar from './HeartbeatBar'
import {getStatusBadge} from './MonitorStatusBadge'

interface MonitorListItemProps {
  readonly monitor: UptimeMonitor
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
      <CardContent className="p-3">
        <div className="flex flex-col gap-3">
          {/* Header Row */}
          <div className="flex items-start justify-between">
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-1.5 mb-0.5">
                <Link 
                  to="/uptime/$monitorId" 
                  params={{monitorId: monitor.id}}
                  className="text-base font-bold hover:underline truncate"
                >
                  {monitor.name}
                </Link>
                {getStatusBadge(monitor.status)}
              </div>
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <Badge variant="outline" className="text-[10px] font-normal h-4 px-1">{getMonitorTypeLabel(monitor.type)}</Badge>
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
            
            <div className="flex items-center gap-3">
              <div className="text-right mr-2 hidden sm:block">
                <div className="text-xl font-bold leading-none">
                  {monitor.uptime24h !== undefined && monitor.uptime24h !== null ? `${monitor.uptime24h.toFixed(0)}%` : '--'}
                </div>
                <div className="text-[10px] text-muted-foreground uppercase tracking-wider">24h Uptime</div>
              </div>
              
              <div className="text-right mr-2 hidden sm:block">
                <div className="text-base font-semibold leading-none flex items-center justify-end gap-0.5">
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
          <div className="flex items-center justify-between pt-1.5 border-t mt-1.5">
            <div className="text-xs text-muted-foreground">
              {heartbeats.length > 0 ? (
                <span>Last heartbeat: {formatTime(new Date(heartbeats[heartbeats.length - 1].timestamp), timezone)}</span>
              ) : (
                <span>No heartbeat data</span>
              )}
            </div>
            <div className="flex gap-2">
              {monitor.status === 'paused' ? (
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-7 px-1.5 text-xs text-muted-foreground hover:text-foreground"
                  onClick={() => resumeMutation.mutate(monitor.id)}
                  title="Resume Monitor"
                >
                  <Play className="h-3 w-3 mr-0.5" />
                  Resume
                </Button>
              ) : (
                <Button
                  size="sm"
                  variant="ghost"
                  className="h-7 px-1.5 text-xs text-muted-foreground hover:text-foreground"
                  onClick={() => pauseMutation.mutate(monitor.id)}
                  title="Pause Monitor"
                >
                  <Pause className="h-3 w-3 mr-0.5" />
                  Pause
                </Button>
              )}
              <Button
                size="sm"
                variant="ghost"
                className="h-7 px-1.5 text-xs text-red-500 hover:text-red-600 hover:bg-red-50"
                onClick={() => {
                  if (confirm(`Delete monitor "${monitor.name}"?`)) {
                    deleteMutation.mutate(monitor.id)
                  }
                }}
                title="Delete Monitor"
              >
                <Trash2 className="h-3 w-3 mr-0.5" />
                Delete
              </Button>
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
