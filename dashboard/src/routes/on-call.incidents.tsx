import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {AlertTriangle, Filter} from 'lucide-react'
import {useState} from 'react'

export const Route = createFileRoute('/on-call/incidents')({
  component: Incidents,
})

const getPriorityVariant = (priority: string): 'default' | 'destructive' | 'outline' => {
  if (priority.startsWith('P0') || priority.startsWith('P1')) return 'destructive'
  if (priority.startsWith('P2')) return 'default'
  return 'outline'
}

const getStatusVariant = (status: string): 'default' | 'destructive' | 'outline' => {
  if (status === 'TRIGGERED') return 'destructive'
  if (status === 'ACKNOWLEDGED') return 'default'
  return 'outline'
}

function Incidents() {
  const [statusFilter, setStatusFilter] = useState<string>('all')
  const [priorityFilter, setPriorityFilter] = useState<string>('all')

  const {data: incidents, isLoading} = useQuery({
    queryKey: ['incidents', statusFilter, priorityFilter],
    queryFn: () => {
      const filters: any = {}
      if (statusFilter !== 'all') filters.status = statusFilter
      if (priorityFilter !== 'all') filters.priorityLevel = priorityFilter
      return api.getIncidents(filters)
    },
  })

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold">Incidents</h2>
          <p className="text-muted-foreground">View and manage on-call incidents</p>
        </div>
      </div>

      {/* Filters */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Filter className="h-4 w-4" />
            Filters
          </CardTitle>
        </CardHeader>
        <CardContent className="flex gap-4">
          <div className="w-48">
            <label className="text-sm font-medium mb-2 block">Status</label>
            <Select value={statusFilter} onValueChange={setStatusFilter}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All Statuses</SelectItem>
                <SelectItem value="TRIGGERED">Triggered</SelectItem>
                <SelectItem value="ACKNOWLEDGED">Acknowledged</SelectItem>
                <SelectItem value="RESOLVED">Resolved</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div className="w-48">
            <label className="text-sm font-medium mb-2 block">Priority</label>
            <Select value={priorityFilter} onValueChange={setPriorityFilter}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All Priorities</SelectItem>
                <SelectItem value="P0">P0</SelectItem>
                <SelectItem value="P1">P1</SelectItem>
                <SelectItem value="P2">P2</SelectItem>
                <SelectItem value="P3">P3</SelectItem>
                <SelectItem value="P4">P4</SelectItem>
                <SelectItem value="P5">P5</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </CardContent>
      </Card>

      {/* Incidents List */}
      <Card>
        <CardHeader>
          <CardTitle>Incidents</CardTitle>
          <CardDescription>
            {isLoading ? 'Loading...' : `${incidents?.length || 0} incident(s)`}
          </CardDescription>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <p className="text-sm text-muted-foreground">Loading incidents...</p>
          ) : incidents && incidents.length > 0 ? (
            <div className="space-y-3">
              {incidents.map((incident) => (
                <a
                  key={incident.id}
                  href={`/on-call/incidents/${incident.id}`}
                  className="block p-4 border rounded-md hover:bg-accent transition-colors"
                >
                  <div className="flex items-start justify-between mb-2">
                    <div className="flex-1">
                      <h3 className="font-semibold text-base mb-1">{incident.title}</h3>
                      {incident.description && (
                        <p className="text-sm text-muted-foreground mb-2">{incident.description}</p>
                      )}
                      <div className="flex items-center gap-2 text-sm text-muted-foreground">
                        <span>{new Date(incident.triggeredAt).toLocaleString()}</span>
                        {incident.acknowledgedAt && (
                          <>
                            <span>·</span>
                            <span>Acknowledged by {incident.acknowledgedByName}</span>
                          </>
                        )}
                        {incident.resolvedAt && (
                          <>
                            <span>·</span>
                            <span>Resolved by {incident.resolvedByName}</span>
                          </>
                        )}
                      </div>
                    </div>
                    <div className="flex gap-2 ml-4">
                      <Badge variant={getPriorityVariant(incident.priorityLevel)}>
                        {incident.priorityLevel}
                      </Badge>
                      <Badge variant={getStatusVariant(incident.status)}>
                        {incident.status}
                      </Badge>
                    </div>
                  </div>
                </a>
              ))}
            </div>
          ) : (
            <div className="text-center py-8">
              <AlertTriangle className="h-12 w-12 mx-auto mb-3 text-muted-foreground" />
              <p className="text-sm text-muted-foreground">No incidents found</p>
              <p className="text-xs text-muted-foreground mt-1">
                Incidents will appear here when triggered
              </p>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
