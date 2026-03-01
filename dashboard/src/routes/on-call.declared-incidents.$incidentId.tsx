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

import {createFileRoute, useNavigate} from '@tanstack/react-router'
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {api, type IncidentTimeline} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {Textarea} from '@/components/ui/textarea'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { Label } from "@/components/ui/label"
import {useToast} from '@/hooks/use-toast'
import {AlertTriangle, CheckCircle2, Clock, ArrowLeft, Zap, User, Calendar, CheckCircle, Bell, UserPlus, MessageSquare, Eye, Send, Link as LinkIcon} from 'lucide-react'
import {useState} from 'react'
import {cn} from '@/lib/utils'

interface DeclaredIncidentDetail {
  id: number
  title: string
  description?: string
  severity: string
  status: string
  declaredAt: string
  declaredByName: string
  resolvedAt?: string
  resolvedBy?: number
  resolvedByName?: string
  alerts?: Array<{id: number; title: string; status: string}>
  priorityLevel: string
  [key: string]: unknown
}

interface DeclaredIncidentTimelineEvent extends Omit<IncidentTimeline, 'eventType'> {
  eventType: string
  source?: string
  alertTitle?: string
  actorName?: string
}

export const Route = createFileRoute('/on-call/declared-incidents/$incidentId')({
  component: DeclaredIncidentDetailComponent,
})

const getPriorityConfig = (priority: string) => {
  if (priority.startsWith('P0')) return {color: 'bg-red-500/15 text-red-400 border-red-500/30', label: 'Critical'}
  if (priority.startsWith('P1')) return {color: 'bg-orange-500/15 text-orange-400 border-orange-500/30', label: 'High'}
  if (priority.startsWith('P2')) return {color: 'bg-amber-500/15 text-amber-400 border-amber-500/30', label: 'Medium'}
  if (priority.startsWith('P3')) return {color: 'bg-blue-500/15 text-blue-400 border-blue-500/30', label: 'Low'}
  return {color: 'bg-muted text-muted-foreground', label: priority}
}

const getStatusConfig = (status: string) => {
  if (status === 'OPEN') return {color: 'bg-red-500/15 text-red-400 border-red-500/30', icon: Zap, label: 'Open', accent: 'text-red-500'}
  if (status === 'RESOLVED') return {color: 'bg-green-500/15 text-green-400 border-green-500/30', icon: CheckCircle2, label: 'Resolved', accent: 'text-green-500'}
  return {color: 'bg-muted text-muted-foreground', icon: Clock, label: status, accent: 'text-muted-foreground'}
}

const EVENT_CONFIG: Record<string, {icon: typeof Zap; color: string; bgColor: string; label: string}> = {
  DECLARED: {icon: Zap, color: 'text-red-500', bgColor: 'bg-red-500/15', label: 'Incident declared'},
  RESOLVED: {icon: CheckCircle2, color: 'text-green-500', bgColor: 'bg-green-500/15', label: 'Incident resolved'},
  NOTE_ADDED: {icon: MessageSquare, color: 'text-slate-400', bgColor: 'bg-slate-500/15', label: 'Note added'},
  ALERT_LINKED: {icon: LinkIcon, color: 'text-violet-500', bgColor: 'bg-violet-500/15', label: 'Alert linked'},
  // Alert-level events
  TRIGGERED: {icon: Zap, color: 'text-red-500', bgColor: 'bg-red-500/15', label: 'Alert triggered'},
  ESCALATED: {icon: Bell, color: 'text-orange-500', bgColor: 'bg-orange-500/15', label: 'Escalated'},
  ACKNOWLEDGED: {icon: CheckCircle, color: 'text-blue-500', bgColor: 'bg-blue-500/15', label: 'Acknowledged'},
  REASSIGNED: {icon: UserPlus, color: 'text-violet-500', bgColor: 'bg-violet-500/15', label: 'Reassigned'},
  STEP_TIMEOUT: {icon: Clock, color: 'text-orange-500', bgColor: 'bg-orange-500/15', label: 'Step timed out'},
  NOTIFICATION_SENT: {icon: Send, color: 'text-cyan-500', bgColor: 'bg-cyan-500/15', label: 'Notification sent'},
  VIEWED: {icon: Eye, color: 'text-slate-400', bgColor: 'bg-slate-500/10', label: 'Viewed'},
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

function getTimelineDescription(event: DeclaredIncidentTimelineEvent): string | null {
  if (event.eventType === 'NOTIFICATION_SENT' && event.details) {
    const toName = event.details.toUserName || event.actorUserName
    const channel = event.details.channel
    if (toName && channel) return `to ${toName} via ${channel}`
    if (toName) return `to ${toName}`
    if (channel) return `via ${channel}`
  }
  if (event.eventType === 'ESCALATED' && event.details?.stepNumber !== undefined) {
    return `to step ${Number(event.details.stepNumber) + 1}`
  }
  if (event.eventType === 'REASSIGNED' && event.details?.toUserName) {
    return `to ${event.details.toUserName}`
  }
  if (event.eventType === 'REASSIGNED' && event.details?.reason === 'unavailable') {
    return 'user marked as unavailable'
  }
  if (event.eventType === 'ALERT_LINKED' && event.details?.alertTitle) {
    return event.details.alertTitle as string
  }
  return null
}

function DeclaredIncidentDetailComponent() {
  const {incidentId} = Route.useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [resolveOpen, setResolveOpen] = useState(false)
  const [resolutionNote, setResolutionNote] = useState('')
  const [note, setNote] = useState('')

  const {data: incident, isLoading} = useQuery({
    queryKey: ['declared-incident', incidentId],
    queryFn: async () => {
      const result = await api.getOnCallIncident(Number(incidentId))
      return result as unknown as DeclaredIncidentDetail
    },
  })
  
  const {data: timeline} = useQuery({
    queryKey: ['declared-incident-timeline', incidentId],
    queryFn: () => api.getOnCallIncidentTimeline(Number(incidentId)) as Promise<DeclaredIncidentTimelineEvent[]>,
  })

  const resolveMutation = useMutation({
    mutationFn: () => api.resolveOnCallIncident(Number(incidentId)),
    onSuccess: () => {
      setResolveOpen(false)
      queryClient.invalidateQueries({queryKey: ['declared-incident', incidentId]})
      queryClient.invalidateQueries({queryKey: ['declared-incident-timeline', incidentId]})
      queryClient.invalidateQueries({queryKey: ['declared-incidents']})
      toast({
        title: 'Incident Resolved',
        description: 'This incident has been marked as resolved.',
      })
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })
  
  const addNoteMutation = useMutation({
    mutationFn: (note: string) => api.addOnCallIncidentNote(Number(incidentId), note),
    onSuccess: () => {
      setNote('')
      queryClient.invalidateQueries({queryKey: ['declared-incident-timeline', incidentId]})
      toast({
        title: 'Note Added',
        description: 'Your note has been added to the incident timeline.',
      })
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-red-500" />
      </div>
    )
  }

  if (!incident) {
    return (
      <div className="text-center py-16">
        <AlertTriangle className="h-12 w-12 mx-auto mb-3 text-muted-foreground" />
        <p className="text-lg font-medium">Incident not found</p>
      </div>
    )
  }

  const statusCfg = getStatusConfig(incident.status)
  const priorityCfg = getPriorityConfig(incident.priorityLevel)
  const StatusIcon = statusCfg.icon

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start gap-4">
        <Button variant="ghost" size="icon" className="mt-1 flex-shrink-0" onClick={() => navigate({to: '/on-call/declared-incidents'})}>
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-3 mb-2">
            <Badge variant="outline" className={cn('text-xs gap-1', statusCfg.color)}>
              <StatusIcon className="h-3 w-3" />
              {statusCfg.label}
            </Badge>
            <Badge variant="outline" className={cn('text-xs', priorityCfg.color)}>
              {incident.priorityLevel}
            </Badge>
            <span className="text-xs text-muted-foreground font-mono">#{incident.id}</span>
          </div>
          <h2 className="text-2xl font-bold tracking-tight">{incident.title}</h2>
          <p className="text-sm text-muted-foreground mt-1">
            Declared {timeAgo(incident.declaredAt)} by {incident.declaredByName}
          </p>
        </div>
      </div>

      {/* Action Banner */}
      {incident.status !== 'RESOLVED' && (
        <div className={cn(
          'flex items-center justify-between p-4 rounded-xl border',
          'bg-red-500/5 border-red-500/20'
        )}>
          <div className="flex items-center gap-3">
            <div className={cn(
              'flex items-center justify-center h-10 w-10 rounded-full',
              'bg-red-500/15'
            )}>
              <StatusIcon className={cn('h-5 w-5', statusCfg.accent)} />
            </div>
            <div>
              <p className="font-medium text-sm">
                This incident is currently open
              </p>
              <p className="text-xs text-muted-foreground">
                Investigate and resolve when complete
              </p>
            </div>
          </div>
          <div className="flex flex-col items-end gap-1">
             <Dialog open={resolveOpen} onOpenChange={setResolveOpen}>
              <DialogTrigger asChild>
                <Button
                  className="bg-green-600 hover:bg-green-700"
                  size="sm"
                >
                  <CheckCircle2 className="h-4 w-4 mr-2" />
                  Resolve Incident
                </Button>
              </DialogTrigger>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle>Resolve Incident</DialogTitle>
                  <DialogDescription>
                    Are you sure you want to resolve this incident? This action cannot be undone.
                  </DialogDescription>
                </DialogHeader>
                <div className="grid gap-2 py-2">
                    <Label htmlFor="resolutionNote">Resolution Note (Optional)</Label>
                    <Textarea
                        id="resolutionNote"
                        value={resolutionNote}
                        onChange={(e) => setResolutionNote(e.target.value)}
                        placeholder="What was the fix?"
                    />
                </div>
                <DialogFooter>
                  <Button variant="outline" onClick={() => setResolveOpen(false)}>Cancel</Button>
                  <Button onClick={() => resolveMutation.mutate()} disabled={resolveMutation.isPending}>
                    {resolveMutation.isPending ? 'Resolving...' : 'Resolve Incident'}
                  </Button>
                </DialogFooter>
              </DialogContent>
            </Dialog>
          </div>
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        {/* Main Column */}
        <div className="lg:col-span-2 space-y-6">
          {/* Description */}
          {incident.description && (
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-base">Description</CardTitle>
              </CardHeader>
              <CardContent>
                <p className="text-sm text-muted-foreground leading-relaxed">{incident.description}</p>
              </CardContent>
            </Card>
          )}

          {/* Linked Alerts */}
          {incident.alerts && incident.alerts.length > 0 && (
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-base">Linked Alerts</CardTitle>
                <CardDescription>{incident.alerts.length} alert{incident.alerts.length !== 1 ? 's' : ''} linked to this incident</CardDescription>
              </CardHeader>
              <CardContent className="space-y-2">
                {incident.alerts.map((alert: {id: number; title: string; status: string}) => (
                  <div key={alert.id} className="flex items-center justify-between p-3 border rounded-lg hover:bg-muted/50 transition-colors">
                    <div className="flex-1">
                      <p className="text-sm font-medium">{alert.title}</p>
                      <p className="text-xs text-muted-foreground mt-0.5">
                        Alert #{alert.id} · {alert.status}
                      </p>
                    </div>
                    <Button 
                      variant="ghost" 
                      size="sm"
                      onClick={() => navigate({to: '/on-call/incidents/$incidentId', params: {incidentId: String(alert.id)}})}
                    >
                      View
                    </Button>
                  </div>
                ))}
              </CardContent>
            </Card>
          )}

          {/* Timeline */}
          {timeline && timeline.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Timeline</CardTitle>
                <CardDescription>Incident history and linked alert events</CardDescription>
              </CardHeader>
              <CardContent>
                <div className="relative">
                  {timeline.map((event: DeclaredIncidentTimelineEvent, idx: number) => {
                    const config = EVENT_CONFIG[event.eventType] || {
                      icon: Clock,
                      color: 'text-muted-foreground',
                      bgColor: 'bg-muted',
                      label: event.eventType.replace(/_/g, ' '),
                    }
                    const Icon = config.icon
                    const description = getTimelineDescription(event)

                    return (
                      <div key={`${event.source}-${event.id}`} className="flex gap-3 pb-6 last:pb-0 relative">
                        {idx < timeline.length - 1 && (
                          <div className="absolute left-[15px] top-8 bottom-0 w-px bg-border" />
                        )}
                        <div className={cn(
                          'flex-shrink-0 flex items-center justify-center h-8 w-8 rounded-full z-10',
                          config.bgColor
                        )}>
                          <Icon className={cn('h-4 w-4', config.color)} />
                        </div>
                        <div className="flex-1 min-w-0 pt-0.5">
                          <div className="flex items-center justify-between gap-2">
                            <p className="text-sm font-medium">
                              {config.label}
                              {event.source === 'alert' && event.alertTitle && (
                                <span className="text-xs text-muted-foreground ml-2">
                                  from {event.alertTitle}
                                </span>
                              )}
                            </p>
                            <span className="text-xs text-muted-foreground flex-shrink-0">
                              {timeAgo(event.createdAt)}
                            </span>
                          </div>
                          {event.actorName && event.eventType !== 'NOTIFICATION_SENT' && (
                            <p className="text-xs text-muted-foreground mt-0.5">
                              by {event.actorName}
                            </p>
                          )}
                          {description && (
                            <p className="text-xs text-muted-foreground mt-0.5">{description}</p>
                          )}
                          {event.details && event.eventType === 'NOTE_ADDED' && !!event.details.note && (
                            <p className="italic bg-muted/50 rounded-lg p-2.5 mt-1.5 text-xs text-muted-foreground">&quot;{String(event.details.note)}&quot;</p>
                          )}
                        </div>
                      </div>
                    )
                  })}
                </div>
              </CardContent>
            </Card>
          )}

          {/* Add Note */}
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Add Note</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <Textarea
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="Enter your note..."
                rows={3}
                className="resize-none"
              />
              <Button
                onClick={() => addNoteMutation.mutate(note)}
                disabled={!note.trim() || addNoteMutation.isPending}
                size="sm"
              >
                <MessageSquare className="h-4 w-4 mr-2" />
                {addNoteMutation.isPending ? 'Adding...' : 'Add Note'}
              </Button>
            </CardContent>
          </Card>
        </div>

        {/* Sidebar */}
        <div className="space-y-4">
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Details</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Status</p>
                <Badge variant="outline" className={cn('gap-1', statusCfg.color)}>
                  <StatusIcon className="h-3 w-3" />
                  {statusCfg.label}
                </Badge>
              </div>
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Priority</p>
                <Badge variant="outline" className={cn(priorityCfg.color)}>
                  {incident.priorityLevel}
                </Badge>
              </div>
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Declared At</p>
                <div className="flex items-center gap-2 text-sm">
                    <Calendar className="h-3.5 w-3.5 text-muted-foreground" />
                    {new Date(incident.declaredAt).toLocaleString()}
                </div>
              </div>
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Declared By</p>
                <div className="flex items-center gap-2 text-sm">
                    <User className="h-3.5 w-3.5 text-muted-foreground" />
                    {incident.declaredByName}
                </div>
              </div>
              {incident.resolvedBy && (
                <div>
                  <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Resolved By</p>
                  <div className="flex items-center gap-2 text-sm">
                     <User className="h-3.5 w-3.5 text-muted-foreground" />
                    {incident.resolvedByName}
                  </div>
                  <span className="text-xs text-muted-foreground block mt-0.5 ml-5">
                      {new Date(incident.resolvedAt!).toLocaleString()}
                  </span>
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  )
}
