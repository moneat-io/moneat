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

import {createFileRoute, redirect, useNavigate} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {formatRelativeTime} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Separator} from '@/components/ui/separator'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Activity, ArrowLeft, CheckCircle2, Clock, Pause, Pencil, Play, Trash2} from 'lucide-react'
import {useToast} from '@/hooks/useToast'
import {Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis} from 'recharts'
import HeartbeatBar from '@/components/uptime/HeartbeatBar'
import EditMonitorDialog from '@/components/uptime/EditMonitorDialog'
import {getStatusBadge} from '@/components/uptime/MonitorStatusBadge'
import {useState} from 'react'
import {getNow} from '@/lib/demo'
import {useTimezone} from '@/hooks/useTimezone'
import {formatTimeHM} from '@/lib/date-format'

export const Route = createFileRoute('/uptime/$monitorId')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: UptimeDetailPage,
})

function UptimeDetailPage() {
  const {monitorId} = Route.useParams()
  const navigate = useNavigate()
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [editDialogOpen, setEditDialogOpen] = useState(false)
  const {timezone} = useTimezone()

  const {data: monitor, isLoading} = useQuery({
    queryKey: ['uptime-monitor', monitorId],
    queryFn: () => api.getUptimeMonitor(monitorId),
  })

  const {data: heartbeats = []} = useQuery({
    queryKey: ['uptime-heartbeats', monitorId],
    queryFn: () => {
      const now = getNow()
      const dayAgo = now - 24 * 60 * 60 * 1000
      return api.getUptimeHeartbeats(monitorId, dayAgo, now)
    },
    refetchInterval: 30000,
  })

  const deleteMutation = useMutation({
    mutationFn: () => api.deleteUptimeMonitor(monitorId),
    onSuccess: () => {
      toast({title: 'Monitor deleted'})
      navigate({to: '/uptime'})
    },
  })

  const pauseMutation = useMutation({
    mutationFn: () => api.pauseUptimeMonitor(monitorId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['uptime-monitor', monitorId]})
      toast({title: 'Monitor paused'})
    },
  })

  const resumeMutation = useMutation({
    mutationFn: () => api.resumeUptimeMonitor(monitorId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['uptime-monitor', monitorId]})
      toast({title: 'Monitor resumed'})
    },
  })

  if (isLoading) {
    return (
      <div className="container mx-auto px-4 py-8">
        <div className="flex items-center justify-center h-64">
          <Activity className="h-8 w-8 animate-spin text-muted-foreground" />
        </div>
      </div>
    )
  }

  if (!monitor) {
    return (
      <div className="container mx-auto px-4 py-8">
        <p>Monitor not found</p>
      </div>
    )
  }

  const chartData = heartbeats
    .filter((h) => h.responseTimeMs >= 0)
    .map((h) => ({
      timestamp: formatTimeHM(new Date(h.timestamp), timezone),
      responseTime: h.responseTimeMs,
    }))
    .reverse()

  const recentHeartbeats = heartbeats.slice(0, 20)

  return (
    <div>
      {/* Header */}
      <div className="border-b bg-card/50">
        <div className="container mx-auto px-4 py-4">
          <Button variant="ghost" size="sm" onClick={() => navigate({to: '/uptime'})} className="mb-2 -ml-2 text-muted-foreground hover:text-foreground">
            <ArrowLeft className="mr-2 h-4 w-4" />
            Back to monitors
          </Button>
          
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <h1 className="text-2xl font-bold tracking-tight">{monitor.name}</h1>
              {getStatusBadge(monitor.status)}
              <Badge variant="outline" className="font-mono text-xs">{monitor.type.toUpperCase()}</Badge>
            </div>
            
            <div className="flex items-center gap-2">
              <a 
                href={monitor.url} 
                target="_blank" 
                rel="noopener noreferrer"
                className="text-sm text-muted-foreground hover:text-primary hover:underline mr-2 truncate max-w-[200px]"
              >
                {monitor.url || monitor.hostname}
              </a>
              <Separator orientation="vertical" className="h-6 mx-2 hidden sm:block" />
              <Button variant="ghost" size="sm" onClick={() => setEditDialogOpen(true)}>
                <Pencil className="mr-2 h-4 w-4" />
                Edit
              </Button>
              {monitor.status !== 'paused' ? (
                <Button variant="ghost" size="sm" onClick={() => pauseMutation.mutate()}>
                  <Pause className="mr-2 h-4 w-4" />
                  Pause
                </Button>
              ) : (
                <Button variant="ghost" size="sm" onClick={() => resumeMutation.mutate()}>
                  <Play className="mr-2 h-4 w-4" />
                  Resume
                </Button>
              )}
              <Button
                variant="ghost"
                size="sm"
                className="text-red-500 hover:text-red-600 hover:bg-red-50"
                onClick={() => {
                  if (confirm(`Delete monitor "${monitor.name}"?`)) {
                    deleteMutation.mutate()
                  }
                }}
              >
                <Trash2 className="mr-2 h-4 w-4" />
                Delete
              </Button>
            </div>
          </div>
        </div>
      </div>

      <div className="container mx-auto px-4 py-6 space-y-6">
        {/* Stats Grid */}
        <div className="grid gap-4 grid-cols-1 md:grid-cols-3">
          <Card className="bg-gradient-to-br from-emerald-500/5 to-emerald-600/10 border-emerald-500/10">
            <CardContent className="p-4 flex items-center justify-between">
              <div>
                <p className="text-sm font-medium text-muted-foreground mb-1">24h Uptime</p>
                <div className="text-2xl font-bold text-emerald-600 dark:text-emerald-400">
                  {monitor.uptime24h !== undefined ? `${monitor.uptime24h.toFixed(2)}%` : 'N/A'}
                </div>
              </div>
              <CheckCircle2 className="h-8 w-8 text-emerald-500/20" />
            </CardContent>
          </Card>
          
          <Card className="bg-gradient-to-br from-blue-500/5 to-blue-600/10 border-blue-500/10">
            <CardContent className="p-4 flex items-center justify-between">
              <div>
                <p className="text-sm font-medium text-muted-foreground mb-1">7d Uptime</p>
                <div className="text-2xl font-bold text-blue-600 dark:text-blue-400">
                  {monitor.uptime7d !== undefined ? `${monitor.uptime7d.toFixed(2)}%` : 'N/A'}
                </div>
              </div>
              <Activity className="h-8 w-8 text-blue-500/20" />
            </CardContent>
          </Card>
          
          <Card className="bg-gradient-to-br from-violet-500/5 to-violet-600/10 border-violet-500/10">
            <CardContent className="p-4 flex items-center justify-between">
              <div>
                <p className="text-sm font-medium text-muted-foreground mb-1">Avg Response</p>
                <div className="text-2xl font-bold text-violet-600 dark:text-violet-400">
                  {monitor.avgResponseTime !== undefined ? `${monitor.avgResponseTime}ms` : 'N/A'}
                </div>
              </div>
              <Clock className="h-8 w-8 text-violet-500/20" />
            </CardContent>
          </Card>
        </div>

        {/* Heartbeat Bar */}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base font-medium">Heartbeat History (24h)</CardTitle>
          </CardHeader>
          <CardContent>
            <HeartbeatBar heartbeats={heartbeats} />
          </CardContent>
        </Card>

        <div className="grid gap-6 md:grid-cols-2">
          {/* Response Time Chart */}
          <Card className="md:col-span-1">
            <CardHeader className="pb-2">
              <CardTitle className="text-base font-medium">Response Time</CardTitle>
            </CardHeader>
            <CardContent>
              {chartData.length > 0 ? (
                <div className="h-[300px] w-full">
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={chartData}>
                      <defs>
                        <linearGradient id="colorResponse" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="hsl(var(--primary))" stopOpacity={0.3}/>
                          <stop offset="95%" stopColor="hsl(var(--primary))" stopOpacity={0}/>
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" className="stroke-muted/30" vertical={false} />
                      <XAxis 
                        dataKey="timestamp" 
                        className="text-[10px]" 
                        tickLine={false}
                        axisLine={false}
                        minTickGap={30}
                      />
                      <YAxis 
                        className="text-[10px]" 
                        tickLine={false}
                        axisLine={false}
                        width={30}
                      />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'hsl(var(--popover))',
                          border: '1px solid hsl(var(--border))',
                          borderRadius: 'var(--radius)',
                          fontSize: '12px',
                        }}
                        itemStyle={{ color: 'hsl(var(--foreground))' }}
                        labelStyle={{ color: 'hsl(var(--muted-foreground))', marginBottom: '4px' }}
                      />
                      <Area
                        type="monotone"
                        dataKey="responseTime"
                        stroke="hsl(var(--primary))"
                        fillOpacity={1}
                        fill="url(#colorResponse)"
                        name="Response Time (ms)"
                        strokeWidth={2}
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <div className="flex items-center justify-center h-[300px] text-muted-foreground">
                  No data available
                </div>
              )}
            </CardContent>
          </Card>

          {/* Recent Checks Table */}
          <Card className="md:col-span-1 flex flex-col">
            <CardHeader className="pb-2">
              <CardTitle className="text-base font-medium">Recent Checks</CardTitle>
            </CardHeader>
            <CardContent className="flex-1 overflow-auto p-0">
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead className="w-[100px]">Time</TableHead>
                    <TableHead className="w-[80px]">Status</TableHead>
                    <TableHead className="text-right">Response</TableHead>
                    <TableHead className="hidden sm:table-cell">Message</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {recentHeartbeats.map((heartbeat, i) => (
                    <TableRow key={`${heartbeat.timestamp}-${i}`}>
                      <TableCell className="font-mono text-xs text-muted-foreground">
                        {formatRelativeTime(heartbeat.timestamp)}
                      </TableCell>
                      <TableCell>
                        {heartbeat.status === 1 ? (
                          <Badge variant="outline" className="bg-emerald-500/10 text-emerald-600 border-emerald-500/20 hover:bg-emerald-500/20 text-[10px] h-5 px-1.5">Up</Badge>
                        ) : (
                          <Badge variant="outline" className="bg-red-500/10 text-red-600 border-red-500/20 hover:bg-red-500/20 text-[10px] h-5 px-1.5">Down</Badge>
                        )}
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs">
                        {heartbeat.responseTimeMs >= 0 ? `${heartbeat.responseTimeMs}ms` : '-'}
                      </TableCell>
                      <TableCell className="hidden sm:table-cell text-xs text-muted-foreground truncate max-w-[150px]" title={heartbeat.message}>
                        {heartbeat.message}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </div>
      </div>

      {/* Edit Dialog */}
      <EditMonitorDialog 
        open={editDialogOpen} 
        onOpenChange={setEditDialogOpen}
        monitor={monitor}
      />
    </div>
  )
}
