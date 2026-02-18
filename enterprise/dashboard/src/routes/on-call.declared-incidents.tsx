// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import {createFileRoute, Link, Outlet, useRouterState} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Card, CardContent} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {Filter, Zap, Clock, CheckCircle2, ChevronRight, FileText} from 'lucide-react'
import {useState} from 'react'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/on-call/declared-incidents')({
  component: DeclaredIncidents,
})

const getPriorityConfig = (priority: string) => {
  if (priority.startsWith('P0')) return {color: 'bg-red-500/15 text-red-400 border-red-500/30', dot: 'bg-red-500', label: 'Critical'}
  if (priority.startsWith('P1')) return {color: 'bg-orange-500/15 text-orange-400 border-orange-500/30', dot: 'bg-orange-500', label: 'High'}
  if (priority.startsWith('P2')) return {color: 'bg-amber-500/15 text-amber-400 border-amber-500/30', dot: 'bg-amber-500', label: 'Medium'}
  if (priority.startsWith('P3')) return {color: 'bg-blue-500/15 text-blue-400 border-blue-500/30', dot: 'bg-blue-500', label: 'Low'}
  if (priority.startsWith('P4')) return {color: 'bg-slate-500/15 text-slate-400 border-slate-500/30', dot: 'bg-slate-500', label: 'Info'}
  return {color: 'bg-muted text-muted-foreground', dot: 'bg-muted-foreground', label: 'Unknown'}
}

const getStatusConfig = (status: string) => {
  if (status === 'OPEN') return {color: 'bg-red-500/15 text-red-400 border-red-500/30', icon: Zap, label: 'Open'}
  if (status === 'RESOLVED') return {color: 'bg-green-500/15 text-green-400 border-green-500/30', icon: CheckCircle2, label: 'Resolved'}
  return {color: 'bg-muted text-muted-foreground', icon: Clock, label: status}
}

function timeAgo(date: string) {
  const seconds = Math.floor((Date.now() - new Date(date).getTime()) / 1000)
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  return `${days}d ago`
}

function DeclaredIncidents() {
  const pathname = useRouterState({select: state => state.location.pathname})
  const [statusFilter, setStatusFilter] = useState<string>('all')
  const [priorityFilter, setPriorityFilter] = useState<string>('all')
  const isDetailRoute = pathname.startsWith('/on-call/declared-incidents/')

  const {data: incidents, isLoading} = useQuery({
    queryKey: ['declared-incidents', statusFilter, priorityFilter],
    queryFn: () => {
      const filters: { status?: string; priorityLevel?: string } = {}
      if (statusFilter !== 'all') filters.status = statusFilter
      if (priorityFilter !== 'all') filters.priorityLevel = priorityFilter
      return api.getOnCallIncidents(filters)
    },
    refetchInterval: 30000,
  })

  const openCount = incidents?.filter(i => i.status === 'OPEN').length || 0
  const resolvedCount = incidents?.filter(i => i.status === 'RESOLVED').length || 0

  if (isDetailRoute) {
    return <Outlet />
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">Incidents</h2>
          <p className="text-muted-foreground text-sm mt-0.5">Manage user-declared incidents</p>
        </div>
      </div>

      {/* Stats Row */}
      {!isLoading && incidents && incidents.length > 0 && (
        <div className="flex gap-2">
          <button
            onClick={() => setStatusFilter(statusFilter === 'OPEN' ? 'all' : 'OPEN')}
            className={cn(
              'flex items-center gap-2 px-3.5 py-2 rounded-lg border text-sm font-medium transition-all',
              statusFilter === 'OPEN'
                ? 'bg-red-500/15 border-red-500/40 text-red-400 shadow-sm shadow-red-500/10'
                : 'hover:bg-muted/60'
            )}
          >
            <Zap className="h-3.5 w-3.5" />
            <span>{openCount} Open</span>
          </button>
          <button
            onClick={() => setStatusFilter(statusFilter === 'RESOLVED' ? 'all' : 'RESOLVED')}
            className={cn(
              'flex items-center gap-2 px-3.5 py-2 rounded-lg border text-sm font-medium transition-all',
              statusFilter === 'RESOLVED'
                ? 'bg-green-500/15 border-green-500/40 text-green-400 shadow-sm shadow-green-500/10'
                : 'hover:bg-muted/60'
            )}
          >
            <CheckCircle2 className="h-3.5 w-3.5" />
            <span>{resolvedCount} Resolved</span>
          </button>
        </div>
      )}

      {/* Filters */}
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Filter className="h-4 w-4" />
          <span>Filter:</span>
        </div>
        <div className="w-44">
          <Select value={statusFilter} onValueChange={setStatusFilter}>
            <SelectTrigger className="h-9">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All Statuses</SelectItem>
              <SelectItem value="OPEN">Open</SelectItem>
              <SelectItem value="RESOLVED">Resolved</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="w-44">
          <Select value={priorityFilter} onValueChange={setPriorityFilter}>
            <SelectTrigger className="h-9">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All Priorities</SelectItem>
              <SelectItem value="P0">P0 - Critical</SelectItem>
              <SelectItem value="P1">P1 - High</SelectItem>
              <SelectItem value="P2">P2 - Medium</SelectItem>
              <SelectItem value="P3">P3 - Low</SelectItem>
              <SelectItem value="P4">P4 - Info</SelectItem>
              <SelectItem value="P5">P5 - None</SelectItem>
            </SelectContent>
          </Select>
        </div>
        {(statusFilter !== 'all' || priorityFilter !== 'all') && (
          <button
            onClick={() => {setStatusFilter('all'); setPriorityFilter('all')}}
            className="text-xs text-muted-foreground hover:text-foreground underline"
          >
            Clear filters
          </button>
        )}
      </div>

      {/* Incidents List */}
      {isLoading ? (
        <div className="flex items-center justify-center py-16">
          <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-red-500" />
        </div>
      ) : incidents && incidents.length > 0 ? (
        <div className="space-y-2">
          {incidents.map((incident) => {
            const priorityCfg = getPriorityConfig(incident.priorityLevel)
            const statusCfg = getStatusConfig(incident.status)
            const StatusIcon = statusCfg.icon
            return (
              <Link
                key={incident.id}
                to="/on-call/declared-incidents/$incidentId"
                params={{incidentId: String(incident.id)}}
                className="block group"
              >
                <Card className={cn(
                  'transition-all hover:shadow-md border-l-4',
                  incident.status === 'OPEN' && 'border-l-red-500 hover:border-red-500/40',
                  incident.status === 'RESOLVED' && 'border-l-transparent hover:border-muted-foreground/20',
                )}>
                  <CardContent className="p-4">
                    <div className="flex items-start justify-between gap-4">
                      <div className="flex items-start gap-3 min-w-0">
                        <div className={cn('flex-shrink-0 h-2.5 w-2.5 rounded-full mt-2', priorityCfg.dot)} />
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <h3 className="font-semibold text-sm group-hover:text-foreground truncate">
                              {incident.title}
                            </h3>
                          </div>
                          {incident.description && (
                            <p className="text-sm text-muted-foreground mt-0.5 line-clamp-1">{incident.description}</p>
                          )}
                          <div className="flex items-center gap-3 mt-2 text-xs text-muted-foreground">
                            <span>Declared {timeAgo(incident.declaredAt)}</span>
                            {incident.declaredByName && (
                              <>
                                <span className="text-muted-foreground/40">·</span>
                                <span>by {incident.declaredByName}</span>
                              </>
                            )}
                            {incident.resolvedByName && (
                              <>
                                <span className="text-muted-foreground/40">·</span>
                                <span className="text-green-400">Resolved by {incident.resolvedByName}</span>
                              </>
                            )}
                          </div>
                        </div>
                      </div>
                      <div className="flex items-center gap-2 flex-shrink-0">
                        <Badge variant="outline" className={cn('text-xs', priorityCfg.color)}>
                          {incident.priorityLevel}
                        </Badge>
                        <Badge variant="outline" className={cn('text-xs gap-1', statusCfg.color)}>
                          <StatusIcon className="h-3 w-3" />
                          {statusCfg.label}
                        </Badge>
                        <ChevronRight className="h-4 w-4 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity" />
                      </div>
                    </div>
                  </CardContent>
                </Card>
              </Link>
            )
          })}
        </div>
      ) : (
        <div className="flex flex-col items-center justify-center py-20 border border-dashed rounded-xl">
          <div className="inline-flex items-center justify-center h-14 w-14 rounded-full bg-muted/60 mb-4">
            <FileText className="h-7 w-7 text-muted-foreground" />
          </div>
          <h3 className="text-base font-semibold mb-1">No incidents found</h3>
          <p className="text-sm text-muted-foreground text-center max-w-sm">
            {statusFilter !== 'all' || priorityFilter !== 'all'
              ? 'No incidents match your current filters. Try adjusting or clearing filters.'
              : 'User-declared incidents will appear here.'}
          </p>
        </div>
      )}
    </div>
  )
}
