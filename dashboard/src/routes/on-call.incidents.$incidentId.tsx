import {createFileRoute, useNavigate} from '@tanstack/react-router'
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {Textarea} from '@/components/ui/textarea'
import {useToast} from '@/hooks/use-toast'
import {AlertTriangle, CheckCircle, Clock, MessageSquare, ArrowLeft} from 'lucide-react'
import {useState} from 'react'

export const Route = createFileRoute('/on-call/incidents/$incidentId')({
  component: IncidentDetailPage,
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

const getEventIcon = (eventType: string) => {
  switch (eventType) {
    case 'TRIGGERED':
      return AlertTriangle
    case 'ACKNOWLEDGED':
      return CheckCircle
    case 'RESOLVED':
      return CheckCircle
    case 'ESCALATED':
      return Clock
    case 'NOTE_ADDED':
      return MessageSquare
    default:
      return Clock
  }
}

function IncidentDetailPage() {
  const {incidentId} = Route.useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [note, setNote] = useState('')

  const {data: incident, isLoading} = useQuery({
    queryKey: ['incident', incidentId],
    queryFn: () => api.getIncident(Number(incidentId)),
  })

  const acknowledgeMutation = useMutation({
    mutationFn: () => api.acknowledgeIncident(Number(incidentId)),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['incident', incidentId]})
      queryClient.invalidateQueries({queryKey: ['incidents']})
      toast({
        title: 'Incident Acknowledged',
        description: 'You have been assigned to this incident.',
      })
    },
    onError: (error: any) => {
      toast({
        title: 'Error',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const resolveMutation = useMutation({
    mutationFn: () => api.resolveIncident(Number(incidentId)),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['incident', incidentId]})
      queryClient.invalidateQueries({queryKey: ['incidents']})
      toast({
        title: 'Incident Resolved',
        description: 'This incident has been marked as resolved.',
      })
    },
    onError: (error: any) => {
      toast({
        title: 'Error',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const addNoteMutation = useMutation({
    mutationFn: (note: string) => api.addIncidentNote(Number(incidentId), note),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['incident', incidentId]})
      setNote('')
      toast({
        title: 'Note Added',
        description: 'Your note has been added to the incident timeline.',
      })
    },
    onError: (error: any) => {
      toast({
        title: 'Error',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  if (isLoading) {
    return (
      <div className="space-y-6">
        <p>Loading incident...</p>
      </div>
    )
  }

  if (!incident) {
    return (
      <div className="space-y-6">
        <p>Incident not found</p>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <Button variant="ghost" size="icon" onClick={() => navigate({to: '/on-call/incidents'})}>
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div className="flex-1">
          <h2 className="text-2xl font-bold">{incident.title}</h2>
          <p className="text-muted-foreground">
            Incident #{incident.id} · Triggered {new Date(incident.triggeredAt).toLocaleString()}
          </p>
        </div>
        <div className="flex gap-2">
          <Badge variant={getPriorityVariant(incident.priorityLevel)}>
            {incident.priorityLevel}
          </Badge>
          <Badge variant={getStatusVariant(incident.status)}>
            {incident.status}
          </Badge>
        </div>
      </div>

      {/* Actions */}
      {incident.status !== 'RESOLVED' && (
        <Card>
          <CardHeader>
            <CardTitle>Actions</CardTitle>
          </CardHeader>
          <CardContent className="flex gap-2">
            {incident.status === 'TRIGGERED' && (
              <Button
                onClick={() => acknowledgeMutation.mutate()}
                disabled={acknowledgeMutation.isPending}
              >
                <CheckCircle className="h-4 w-4 mr-2" />
                Acknowledge
              </Button>
            )}
            {incident.status === 'ACKNOWLEDGED' && (
              <Button
                onClick={() => resolveMutation.mutate()}
                disabled={resolveMutation.isPending}
                variant="default"
              >
                <CheckCircle className="h-4 w-4 mr-2" />
                Resolve
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      {/* Incident Details */}
      <Card>
        <CardHeader>
          <CardTitle>Incident Details</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {incident.description && (
            <div>
              <h4 className="text-sm font-medium mb-1">Description</h4>
              <p className="text-sm text-muted-foreground">{incident.description}</p>
            </div>
          )}
          
          <div className="grid grid-cols-2 gap-4">
            <div>
              <h4 className="text-sm font-medium mb-1">Alert Source</h4>
              <p className="text-sm text-muted-foreground">{incident.alertSource}</p>
            </div>
            <div>
              <h4 className="text-sm font-medium mb-1">Priority</h4>
              <Badge variant={getPriorityVariant(incident.priorityLevel)}>
                {incident.priorityLevel}
              </Badge>
            </div>
          </div>

          {incident.acknowledgedBy && (
            <div>
              <h4 className="text-sm font-medium mb-1">Acknowledged By</h4>
              <p className="text-sm text-muted-foreground">
                {incident.acknowledgedByName} at {new Date(incident.acknowledgedAt!).toLocaleString()}
              </p>
            </div>
          )}

          {incident.resolvedBy && (
            <div>
              <h4 className="text-sm font-medium mb-1">Resolved By</h4>
              <p className="text-sm text-muted-foreground">
                {incident.resolvedByName} at {new Date(incident.resolvedAt!).toLocaleString()}
              </p>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Timeline */}
      <Card>
        <CardHeader>
          <CardTitle>Timeline</CardTitle>
          <CardDescription>Incident history and updates</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-4">
            {incident.timeline.map((event, idx) => {
              const Icon = getEventIcon(event.eventType)
              return (
                <div key={event.id} className="flex gap-3">
                  <div className="flex flex-col items-center">
                    <div className="rounded-full p-2 bg-primary/10">
                      <Icon className="h-4 w-4 text-primary" />
                    </div>
                    {idx < incident.timeline.length - 1 && (
                      <div className="w-0.5 h-full bg-border mt-2" />
                    )}
                  </div>
                  <div className="flex-1 pb-4">
                    <div className="flex items-start justify-between mb-1">
                      <h4 className="font-medium text-sm">
                        {event.eventType.replace('_', ' ')}
                      </h4>
                      <span className="text-xs text-muted-foreground">
                        {new Date(event.createdAt).toLocaleString()}
                      </span>
                    </div>
                    {event.actorUserName && (
                      <p className="text-sm text-muted-foreground">
                        by {event.actorUserName}
                      </p>
                    )}
                    {event.details && (
                      <p className="text-sm text-muted-foreground mt-1">
                        {JSON.stringify(event.details)}
                      </p>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        </CardContent>
      </Card>

      {/* Add Note */}
      {incident.status !== 'RESOLVED' && (
        <Card>
          <CardHeader>
            <CardTitle>Add Note</CardTitle>
            <CardDescription>Add a comment to the incident timeline</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <Textarea
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder="Enter your note..."
              rows={3}
            />
            <Button
              onClick={() => addNoteMutation.mutate(note)}
              disabled={!note.trim() || addNoteMutation.isPending}
            >
              <MessageSquare className="h-4 w-4 mr-2" />
              Add Note
            </Button>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
