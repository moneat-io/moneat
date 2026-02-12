import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Avatar, AvatarFallback} from '@/components/ui/avatar'
import {Calendar, Users, AlertTriangle, Clock, ChevronRight, Plus, Zap, CheckCircle2, ArrowUpRight} from 'lucide-react'
import {Link} from '@tanstack/react-router'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/on-call/')({
  component: OnCallOverview,
})

const getPriorityConfig = (priority: string) => {
  if (priority.startsWith('P0')) return {color: 'bg-red-500/15 text-red-500 border-red-500/30', dot: 'bg-red-500'}
  if (priority.startsWith('P1')) return {color: 'bg-orange-500/15 text-orange-500 border-orange-500/30', dot: 'bg-orange-500'}
  if (priority.startsWith('P2')) return {color: 'bg-amber-500/15 text-amber-500 border-amber-500/30', dot: 'bg-amber-500'}
  if (priority.startsWith('P3')) return {color: 'bg-blue-500/15 text-blue-500 border-blue-500/30', dot: 'bg-blue-500'}
  return {color: 'bg-muted text-muted-foreground', dot: 'bg-muted-foreground'}
}

const getStatusConfig = (status: string) => {
  if (status === 'TRIGGERED') return {color: 'bg-red-500/15 text-red-400 border-red-500/30', label: 'Triggered', icon: Zap}
  if (status === 'ACKNOWLEDGED') return {color: 'bg-amber-500/15 text-amber-400 border-amber-500/30', label: 'Acknowledged', icon: Clock}
  return {color: 'bg-green-500/15 text-green-400 border-green-500/30', label: 'Resolved', icon: CheckCircle2}
}

function getInitials(name: string) {
  return name.split(' ').map(n => n[0]).join('').toUpperCase().slice(0, 2)
}

const avatarColors = [
  'bg-blue-600', 'bg-violet-600', 'bg-emerald-600', 'bg-amber-600',
  'bg-rose-600', 'bg-cyan-600', 'bg-indigo-600', 'bg-pink-600',
]

function OnCallOverview() {
  const {data: schedules, isLoading: schedulesLoading} = useQuery({
    queryKey: ['on-call-schedules'],
    queryFn: () => api.getOnCallSchedules(),
  })

  const {data: incidents, isLoading: incidentsLoading} = useQuery({
    queryKey: ['incidents', {status: 'TRIGGERED'}],
    queryFn: () => api.getIncidents({status: 'TRIGGERED'}),
  })

  const {data: policies, isLoading: policiesLoading} = useQuery({
    queryKey: ['escalation-policies'],
    queryFn: () => api.getEscalationPolicies(),
  })

  const activeIncidents = incidents?.filter((i) => i.status === 'TRIGGERED' || i.status === 'ACKNOWLEDGED') || []
  const hasActiveIncidents = activeIncidents.length > 0

  return (
    <div className="space-y-6">
      {/* Stats */}
      <div className="grid gap-4 md:grid-cols-3">
        <Card className={cn(
          'relative overflow-hidden transition-all',
          hasActiveIncidents && 'border-red-500/40 shadow-lg shadow-red-500/5'
        )}>
          {hasActiveIncidents && (
            <div className="absolute inset-0 bg-gradient-to-br from-red-500/5 to-transparent pointer-events-none" />
          )}
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">Active Incidents</CardTitle>
            <div className={cn(
              'flex items-center justify-center h-8 w-8 rounded-lg',
              hasActiveIncidents ? 'bg-red-500/15' : 'bg-muted'
            )}>
              <AlertTriangle className={cn('h-4 w-4', hasActiveIncidents ? 'text-red-500' : 'text-muted-foreground')} />
            </div>
          </CardHeader>
          <CardContent>
            <div className={cn('text-3xl font-bold', hasActiveIncidents && 'text-red-500')}>
              {incidentsLoading ? '...' : activeIncidents.length}
            </div>
            <p className="text-xs text-muted-foreground mt-1">
              {hasActiveIncidents ? 'Requires immediate attention' : 'All clear — no active incidents'}
            </p>
          </CardContent>
        </Card>

        <Card className="relative overflow-hidden">
          <div className="absolute inset-0 bg-gradient-to-br from-violet-500/5 to-transparent pointer-events-none" />
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">On-Call Schedules</CardTitle>
            <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-violet-500/15">
              <Calendar className="h-4 w-4 text-violet-500" />
            </div>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold">{schedulesLoading ? '...' : schedules?.length || 0}</div>
            <p className="text-xs text-muted-foreground mt-1">Active rotation schedules</p>
          </CardContent>
        </Card>

        <Card className="relative overflow-hidden">
          <div className="absolute inset-0 bg-gradient-to-br from-amber-500/5 to-transparent pointer-events-none" />
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">Escalation Policies</CardTitle>
            <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-amber-500/15">
              <Clock className="h-4 w-4 text-amber-500" />
            </div>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold">{policiesLoading ? '...' : policies?.length || 0}</div>
            <p className="text-xs text-muted-foreground mt-1">Configured policies</p>
          </CardContent>
        </Card>
      </div>

      {/* Active Incidents - prominent when present */}
      {hasActiveIncidents && (
        <Card className="border-red-500/30">
          <CardHeader className="pb-3">
            <div className="flex items-center justify-between">
              <CardTitle className="flex items-center gap-2">
                <div className="relative flex h-3 w-3">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-red-400 opacity-75" />
                  <span className="relative inline-flex rounded-full h-3 w-3 bg-red-500" />
                </div>
                Active Incidents
              </CardTitle>
              <Button asChild variant="ghost" size="sm" className="text-muted-foreground">
                <Link to="/on-call/incidents">
                  View all <ArrowUpRight className="h-3 w-3 ml-1" />
                </Link>
              </Button>
            </div>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              {activeIncidents.slice(0, 5).map((incident) => {
                const priorityCfg = getPriorityConfig(incident.priorityLevel)
                const statusCfg = getStatusConfig(incident.status)
                const StatusIcon = statusCfg.icon
                return (
                  <Link
                    key={incident.id}
                    to="/on-call/incidents/$incidentId"
                    params={{incidentId: String(incident.id)}}
                    className="flex items-center justify-between p-3 rounded-lg border hover:bg-accent/50 transition-all group"
                  >
                    <div className="flex items-center gap-3 min-w-0">
                      <div className={cn('flex-shrink-0 h-2 w-2 rounded-full', priorityCfg.dot)} />
                      <div className="min-w-0">
                        <p className="font-medium text-sm truncate group-hover:text-foreground">{incident.title}</p>
                        <p className="text-xs text-muted-foreground">
                          {new Date(incident.triggeredAt).toLocaleString()}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-2 flex-shrink-0 ml-3">
                      <Badge variant="outline" className={cn('text-xs', priorityCfg.color)}>
                        {incident.priorityLevel}
                      </Badge>
                      <Badge variant="outline" className={cn('text-xs gap-1', statusCfg.color)}>
                        <StatusIcon className="h-3 w-3" />
                        {statusCfg.label}
                      </Badge>
                      <ChevronRight className="h-4 w-4 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity" />
                    </div>
                  </Link>
                )
              })}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Who's On Call Now */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div>
              <CardTitle className="flex items-center gap-2">
                <Users className="h-5 w-5 text-blue-500" />
                Who's On Call
              </CardTitle>
              <CardDescription className="mt-1">Current on-call engineers for each schedule</CardDescription>
            </div>
            <Button asChild variant="outline" size="sm">
              <Link to="/on-call/schedules">
                <Plus className="h-3 w-3 mr-1" />
                New Schedule
              </Link>
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {schedulesLoading ? (
            <div className="flex items-center justify-center py-8">
              <div className="animate-spin rounded-full h-6 w-6 border-2 border-muted border-t-primary" />
            </div>
          ) : schedules && schedules.length > 0 ? (
            <div className="space-y-3">
              {schedules.map((schedule, idx) => (
                <div
                  key={schedule.id}
                  className="flex items-center justify-between p-4 rounded-lg border bg-card hover:bg-accent/30 transition-colors"
                >
                  <div className="flex items-center gap-3">
                    <div className={cn(
                      'flex items-center justify-center h-10 w-10 rounded-lg text-white text-sm font-bold',
                      avatarColors[idx % avatarColors.length]
                    )}>
                      {schedule.name.slice(0, 2).toUpperCase()}
                    </div>
                    <div>
                      <p className="font-medium">{schedule.name}</p>
                      <div className="flex items-center gap-2 mt-0.5">
                        <Badge variant="outline" className="text-xs bg-violet-500/10 text-violet-400 border-violet-500/30">
                          {schedule.rotationType}
                        </Badge>
                        <span className="text-xs text-muted-foreground">{schedule.timezone}</span>
                      </div>
                    </div>
                  </div>
                  {schedule.currentOnCall ? (
                    <div className="flex items-center gap-2">
                      <Avatar className="h-8 w-8">
                        <AvatarFallback className={cn(
                          'text-xs text-white',
                          avatarColors[(schedule.currentOnCall.userId || 0) % avatarColors.length]
                        )}>
                          {getInitials(schedule.currentOnCall.userName)}
                        </AvatarFallback>
                      </Avatar>
                      <div className="text-right">
                        <p className="text-sm font-medium">{schedule.currentOnCall.userName}</p>
                        <p className="text-xs text-green-500 flex items-center gap-1">
                          <span className="h-1.5 w-1.5 rounded-full bg-green-500 inline-block" />
                          On call now
                        </p>
                      </div>
                    </div>
                  ) : (
                    <Badge variant="outline" className="text-xs text-muted-foreground">
                      No one assigned
                    </Badge>
                  )}
                </div>
              ))}
            </div>
          ) : (
            <div className="text-center py-10 border-2 border-dashed rounded-lg">
              <div className="inline-flex items-center justify-center h-12 w-12 rounded-full bg-violet-500/10 mb-3">
                <Calendar className="h-6 w-6 text-violet-500" />
              </div>
              <h3 className="text-sm font-medium mb-1">No schedules configured</h3>
              <p className="text-sm text-muted-foreground mb-4 max-w-sm mx-auto">
                Create your first on-call schedule to start managing rotations
              </p>
              <Button asChild>
                <Link to="/on-call/schedules">
                  <Plus className="h-4 w-4 mr-2" />
                  Create Schedule
                </Link>
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Quick Actions */}
      <div className="grid gap-4 md:grid-cols-3">
        <Link to="/on-call/schedules" className="group">
          <Card className="h-full hover:border-violet-500/40 transition-all hover:shadow-md hover:shadow-violet-500/5">
            <CardContent className="flex items-center gap-4 p-5">
              <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-violet-500/15 group-hover:bg-violet-500/25 transition-colors">
                <Calendar className="h-5 w-5 text-violet-500" />
              </div>
              <div>
                <p className="font-medium text-sm">Manage Schedules</p>
                <p className="text-xs text-muted-foreground">Create and edit rotations</p>
              </div>
              <ChevronRight className="h-4 w-4 text-muted-foreground ml-auto opacity-0 group-hover:opacity-100 transition-opacity" />
            </CardContent>
          </Card>
        </Link>

        <Link to="/on-call/escalation-policies" className="group">
          <Card className="h-full hover:border-amber-500/40 transition-all hover:shadow-md hover:shadow-amber-500/5">
            <CardContent className="flex items-center gap-4 p-5">
              <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-amber-500/15 group-hover:bg-amber-500/25 transition-colors">
                <Clock className="h-5 w-5 text-amber-500" />
              </div>
              <div>
                <p className="font-medium text-sm">Escalation Policies</p>
                <p className="text-xs text-muted-foreground">Configure alerting rules</p>
              </div>
              <ChevronRight className="h-4 w-4 text-muted-foreground ml-auto opacity-0 group-hover:opacity-100 transition-opacity" />
            </CardContent>
          </Card>
        </Link>

        <Link to="/on-call/incidents" className="group">
          <Card className="h-full hover:border-red-500/40 transition-all hover:shadow-md hover:shadow-red-500/5">
            <CardContent className="flex items-center gap-4 p-5">
              <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-red-500/15 group-hover:bg-red-500/25 transition-colors">
                <AlertTriangle className="h-5 w-5 text-red-500" />
              </div>
              <div>
                <p className="font-medium text-sm">View Incidents</p>
                <p className="text-xs text-muted-foreground">Track and resolve issues</p>
              </div>
              <ChevronRight className="h-4 w-4 text-muted-foreground ml-auto opacity-0 group-hover:opacity-100 transition-opacity" />
            </CardContent>
          </Card>
        </Link>
      </div>
    </div>
  )
}
