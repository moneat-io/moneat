import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Calendar, Users, AlertTriangle, Clock} from 'lucide-react'
import {Link} from '@tanstack/react-router'

export const Route = createFileRoute('/on-call/')({
  component: OnCallOverview,
})

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

  return (
    <div className="space-y-6">
      {/* Stats */}
      <div className="grid gap-4 md:grid-cols-3">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">Active Incidents</CardTitle>
            <AlertTriangle className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{incidentsLoading ? '...' : activeIncidents.length}</div>
            <p className="text-xs text-muted-foreground">
              Requires immediate attention
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">On-Call Schedules</CardTitle>
            <Calendar className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{schedulesLoading ? '...' : schedules?.length || 0}</div>
            <p className="text-xs text-muted-foreground">
              Active rotation schedules
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">Escalation Policies</CardTitle>
            <Clock className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{policiesLoading ? '...' : policies?.length || 0}</div>
            <p className="text-xs text-muted-foreground">
              Configured policies
            </p>
          </CardContent>
        </Card>
      </div>

      {/* Who's On Call Now */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Users className="h-5 w-5" />
            Who's On Call Now
          </CardTitle>
          <CardDescription>Current on-call engineers for each schedule</CardDescription>
        </CardHeader>
        <CardContent>
          {schedulesLoading ? (
            <p className="text-sm text-muted-foreground">Loading schedules...</p>
          ) : schedules && schedules.length > 0 ? (
            <div className="space-y-3">
              {schedules.map((schedule) => (
                <div key={schedule.id} className="flex items-center justify-between p-3 border rounded-md">
                  <div>
                    <p className="font-medium">{schedule.name}</p>
                    <p className="text-sm text-muted-foreground">
                      {schedule.rotationType} rotation · {schedule.timezone}
                    </p>
                  </div>
                  {schedule.currentOnCall ? (
                    <Badge variant="default">{schedule.currentOnCall.userName}</Badge>
                  ) : (
                    <Badge variant="outline">No one assigned</Badge>
                  )}
                </div>
              ))}
            </div>
          ) : (
            <div className="text-center py-6">
              <Calendar className="h-12 w-12 mx-auto mb-3 text-muted-foreground" />
              <p className="text-sm text-muted-foreground mb-3">No schedules configured yet</p>
              <Button asChild>
                <Link to="/on-call/schedules">Create Schedule</Link>
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Active Incidents */}
      {activeIncidents.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <AlertTriangle className="h-5 w-5" />
              Active Incidents
            </CardTitle>
            <CardDescription>Incidents requiring attention</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-3">
              {activeIncidents.slice(0, 5).map((incident) => (
                <a
                  key={incident.id}
                  href={`/on-call/incidents/${incident.id}`}
                  className="flex items-center justify-between p-3 border rounded-md hover:bg-accent transition-colors"
                >
                  <div>
                    <p className="font-medium">{incident.title}</p>
                    <p className="text-sm text-muted-foreground">
                      {new Date(incident.triggeredAt).toLocaleString()}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <Badge variant={incident.priorityLevel.startsWith('P0') ? 'destructive' : 'default'}>
                      {incident.priorityLevel}
                    </Badge>
                    <Badge variant={incident.status === 'TRIGGERED' ? 'destructive' : 'default'}>
                      {incident.status}
                    </Badge>
                  </div>
                </a>
              ))}
            </div>
            {activeIncidents.length > 5 && (
              <div className="mt-3 text-center">
                <Button asChild variant="outline" size="sm">
                  <Link to="/on-call/incidents">View All Incidents</Link>
                </Button>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {/* Quick Actions */}
      <Card>
        <CardHeader>
          <CardTitle>Quick Actions</CardTitle>
          <CardDescription>Common on-call management tasks</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          <Button asChild variant="outline">
            <Link to="/on-call/schedules">
              <Calendar className="h-4 w-4 mr-2" />
              Manage Schedules
            </Link>
          </Button>
          <Button asChild variant="outline">
            <Link to="/on-call/escalation-policies">
              <Clock className="h-4 w-4 mr-2" />
              Configure Escalation
            </Link>
          </Button>
          <Button asChild variant="outline">
            <Link to="/settings" search={{tab: 'priorities'}}>
              <AlertTriangle className="h-4 w-4 mr-2" />
              Priority Settings
            </Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  )
}
