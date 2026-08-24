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
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {Textarea} from '@/components/ui/textarea'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import {Label} from '@/components/ui/label'
import {useToast} from '@/hooks/useToast'
import {
  AlertTriangle,
  ArrowLeft,
  Calendar,
  CheckCircle2,
  Eye,
  Link as LinkIcon,
  MessageSquare,
  Tag,
  User,
} from 'lucide-react'
import {useState} from 'react'
import type {ReactNode} from 'react'
import {isUuidResourceId} from '@/lib/api/utils'
import {
  nativeIncidentUnavailableCopy,
  useNativeIncidentCapabilities,
} from '@/hooks/useNativeIncidentCapabilities'
import {incidentStatusConfig, isResolvableIncidentStatus} from '@/lib/incident-status'
import {IncidentTriageActions} from '@/components/on-call/IncidentTriageActions'
import {isTriageIncident} from '@/components/on-call/triage'
import {IncidentTimelinePanel} from '@/components/on-call/IncidentTimelinePanel'
import {IncidentRolesPanel} from '@/components/on-call/IncidentRolesPanel'
import {IncidentSourcesPanel} from '@/components/on-call/IncidentSourcesPanel'
import {
  incidentModeMeta,
  incidentVisibilityMeta,
  severityBadgeVariant,
  timeAgo,
} from '@/components/on-call/incident-modeling'

export const Route = createFileRoute('/on-call/incidents/$incidentId')({
  component: DeclaredIncidentDetailComponent,
})

function DeclaredIncidentDetailComponent() {
  const {incidentId} = Route.useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [resolveOpen, setResolveOpen] = useState(false)
  const [resolutionNote, setResolutionNote] = useState('')
  const [note, setNote] = useState('')
  const capabilities = useNativeIncidentCapabilities()
  const normalizedIncidentId = incidentId.trim()
  const hasValidIncidentId = isUuidResourceId(normalizedIncidentId)
  const incidentKey = ['declared-incident', normalizedIncidentId]

  const {data: incident, isLoading} = useQuery({
    queryKey: incidentKey,
    queryFn: () => api.getOnCallIncident(normalizedIncidentId),
    // Hold the incident fetch (and the child panels' native queries) until the
    // entitlement is confirmed.
    enabled: hasValidIncidentId && capabilities.enabled,
  })

  const refreshIncident = () => queryClient.invalidateQueries({queryKey: incidentKey})

  const resolveMutation = useMutation({
    mutationFn: () =>
      api.resolveOnCallIncident(
        normalizedIncidentId,
        resolutionNote.trim() ? resolutionNote.trim() : undefined,
      ),
    onSuccess: () => {
      setResolveOpen(false)
      setResolutionNote('')
      refreshIncident()
      queryClient.invalidateQueries({queryKey: ['incident-canonical-timeline', normalizedIncidentId]})
      queryClient.invalidateQueries({queryKey: ['on-call-incidents']})
      toast({title: 'Incident resolved', description: 'This incident has been marked as resolved.'})
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  const addNoteMutation = useMutation({
    mutationFn: (value: string) => api.addOnCallIncidentNote(normalizedIncidentId, value),
    onSuccess: () => {
      setNote('')
      queryClient.invalidateQueries({queryKey: ['incident-canonical-timeline', normalizedIncidentId]})
      toast({title: 'Note added', description: 'Your note has been added to the incident timeline.'})
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  if (capabilities.isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-muted border-t-primary" />
      </div>
    )
  }

  if (!capabilities.enabled) {
    const copy = nativeIncidentUnavailableCopy(capabilities)
    return <EmptyState icon={AlertTriangle} title={copy.title} description={copy.description} />
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-muted border-t-primary" />
      </div>
    )
  }

  if (!hasValidIncidentId) {
    return (
      <EmptyState
        icon={AlertTriangle}
        title="Invalid incident ID"
        description="The incident identifier in the URL is invalid."
      />
    )
  }

  if (!incident) {
    return (
      <EmptyState
        icon={AlertTriangle}
        title="Incident not found"
        description="This incident may have been removed or you may not have access to it."
      />
    )
  }

  const statusCfg = incidentStatusConfig(incident.status)
  const StatusIcon = statusCfg.icon
  const mode = incident.mode ?? 'LIVE'
  const modeMeta = incidentModeMeta(mode)
  const visibilityMeta = incidentVisibilityMeta(incident.visibility ?? 'ORGANIZATION')

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start gap-4">
        <Button
          variant="ghost"
          size="icon"
          className="mt-1 flex-shrink-0"
          onClick={() => navigate({to: '/on-call/incidents'})}
        >
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div className="min-w-0 flex-1">
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <Badge variant={statusCfg.variant} size="sm" className="gap-1">
              <StatusIcon className="h-3 w-3" />
              {statusCfg.label}
            </Badge>
            <Badge variant={severityBadgeVariant(incident.severity)} size="sm">
              {incident.severity ?? 'Unclassified'}
            </Badge>
            {mode !== 'LIVE' && (
              <Badge variant={modeMeta.variant} size="sm">
                {modeMeta.label}
              </Badge>
            )}
            {incident.incidentType && (
              <Badge variant="neutral" size="sm" className="gap-1">
                <Tag className="h-3 w-3" />
                {incident.incidentType}
              </Badge>
            )}
          </div>
          <h2 className="text-2xl font-bold tracking-tight">{incident.title}</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            Declared {timeAgo(incident.declaredAt)} by {incident.declaredByName ?? 'a responder'}
          </p>
        </div>
      </div>

      {/* Test / retrospective notice */}
      {mode !== 'LIVE' && (
        <div className="flex items-center gap-2 rounded-xl border border-info-border bg-info-bg px-4 py-2.5 text-sm">
          <AlertTriangle className="h-4 w-4 text-info-fg" />
          <span>
            This is a <span className="font-medium">{modeMeta.label.toLowerCase()}</span> incident — {modeMeta.description.toLowerCase()}.
          </span>
        </div>
      )}

      {/* Triage Banner */}
      {isTriageIncident(incident.status) && (
        <div className="flex flex-col gap-3 rounded-xl border border-warning-border bg-warning-bg p-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-full bg-warning-bg">
              <StatusIcon className="h-5 w-5 text-warning-fg" />
            </div>
            <div>
              <p className="text-sm font-medium">This incident is awaiting triage</p>
              <p className="text-xs text-muted-foreground">
                Accept it into active response, merge it into another incident, or decline it.
              </p>
            </div>
          </div>
          <IncidentTriageActions incident={incident} layout="banner" onMutated={refreshIncident} />
        </div>
      )}

      {/* Merged notice */}
      {incident.status === 'MERGED' && incident.mergedIntoIncidentId && (
        <div className="flex items-center justify-between gap-3 rounded-xl border bg-muted/30 p-4">
          <p className="text-sm text-muted-foreground">
            This incident was merged into another incident.
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={() =>
              navigate({
                to: '/on-call/incidents/$incidentId',
                params: {incidentId: incident.mergedIntoIncidentId!},
              })
            }
          >
            View incident
          </Button>
        </div>
      )}

      {/* Action Banner */}
      {isResolvableIncidentStatus(incident.status) && (
        <div className="flex items-center justify-between rounded-xl border border-danger-border bg-danger-bg p-4">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-full bg-danger-bg">
              <StatusIcon className="h-5 w-5 text-danger-fg" />
            </div>
            <div>
              <p className="text-sm font-medium">This incident is currently active</p>
              <p className="text-xs text-muted-foreground">Investigate and resolve when complete</p>
            </div>
          </div>
          <Dialog open={resolveOpen} onOpenChange={setResolveOpen}>
            <DialogTrigger asChild>
              <Button size="sm">
                <CheckCircle2 className="mr-2 h-4 w-4" />
                Resolve incident
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>Resolve incident</DialogTitle>
                <DialogDescription>
                  Mark this incident as resolved. You can add a resolution note for the record.
                </DialogDescription>
              </DialogHeader>
              <div className="grid gap-2 py-2">
                <Label htmlFor="resolutionNote">Resolution note (optional)</Label>
                <Textarea
                  id="resolutionNote"
                  value={resolutionNote}
                  onChange={(e) => setResolutionNote(e.target.value)}
                  placeholder="What was the fix?"
                />
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setResolveOpen(false)}>
                  Cancel
                </Button>
                <Button onClick={() => resolveMutation.mutate()} disabled={resolveMutation.isPending}>
                  {resolveMutation.isPending ? 'Resolving…' : 'Resolve incident'}
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        {/* Main Column */}
        <div className="space-y-6 lg:col-span-2">
          {incident.summary && (
            <SectionCard title="Summary" icon={MessageSquare} iconTone="muted">
              <p className="text-sm leading-relaxed text-muted-foreground">{incident.summary}</p>
            </SectionCard>
          )}

          {incident.description && (
            <SectionCard title="Description" icon={MessageSquare} iconTone="muted">
              <p className="text-sm leading-relaxed text-muted-foreground">{incident.description}</p>
            </SectionCard>
          )}

          {incident.alerts && incident.alerts.length > 0 && (
            <SectionCard
              title="Linked alerts"
              icon={LinkIcon}
              iconTone="accent"
              count={incident.alerts.length}
              bodyClassName="space-y-2"
            >
              {incident.alerts.map((alert) => (
                <div
                  key={alert.id}
                  className="flex items-center justify-between rounded-lg border p-3 transition-colors hover:bg-accent/40"
                >
                  <div className="flex-1">
                    <p className="text-sm font-medium">{alert.title}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      Alert #{alert.id} · {alert.status}
                    </p>
                  </div>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() =>
                      navigate({to: '/on-call/alerts/$alertId', params: {alertId: String(alert.id)}})
                    }
                  >
                    View
                  </Button>
                </div>
              ))}
            </SectionCard>
          )}

          <IncidentRolesPanel
            incidentId={normalizedIncidentId}
            incidentVersion={incident.version}
            onMutated={refreshIncident}
          />

          <IncidentTimelinePanel incidentId={normalizedIncidentId} />

          <SectionCard title="Add note" icon={MessageSquare} iconTone="accent" bodyClassName="space-y-3">
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
              <MessageSquare className="mr-2 h-4 w-4" />
              {addNoteMutation.isPending ? 'Adding…' : 'Add note'}
            </Button>
          </SectionCard>
        </div>

        {/* Sidebar */}
        <div className="space-y-4">
          <SectionCard title="Details" bodyClassName="space-y-4">
            <DetailRow label="Status">
              <Badge variant={statusCfg.variant} className="gap-1">
                <StatusIcon className="h-3 w-3" />
                {statusCfg.label}
              </Badge>
            </DetailRow>
            <DetailRow label="Severity">
              <Badge variant={severityBadgeVariant(incident.severity)}>
                {incident.severity ?? 'Unclassified'}
              </Badge>
            </DetailRow>
            <DetailRow label="Mode">
              <Badge variant={modeMeta.variant}>{modeMeta.label}</Badge>
            </DetailRow>
            <DetailRow label="Visibility">
              <div className="flex items-center gap-2 text-sm">
                <Eye className="h-3.5 w-3.5 text-muted-foreground" />
                {visibilityMeta.label}
              </div>
            </DetailRow>
            {incident.incidentType && (
              <DetailRow label="Type">
                <span className="text-sm">{incident.incidentType}</span>
              </DetailRow>
            )}
            <DetailRow label="Declared at">
              <div className="flex items-center gap-2 text-sm">
                <Calendar className="h-3.5 w-3.5 text-muted-foreground" />
                {new Date(incident.declaredAt).toLocaleString()}
              </div>
            </DetailRow>
            <DetailRow label="Declared by">
              <div className="flex items-center gap-2 text-sm">
                <User className="h-3.5 w-3.5 text-muted-foreground" />
                {incident.declaredByName ?? 'a responder'}
              </div>
            </DetailRow>
            {incident.resolvedBy && (
              <DetailRow label="Resolved by">
                <div className="flex items-center gap-2 text-sm">
                  <User className="h-3.5 w-3.5 text-muted-foreground" />
                  {incident.resolvedByName}
                </div>
                {incident.resolvedAt && (
                  <span className="mt-0.5 block text-xs text-muted-foreground">
                    {new Date(incident.resolvedAt).toLocaleString()}
                  </span>
                )}
              </DetailRow>
            )}
          </SectionCard>

          <IncidentSourcesPanel incidentId={normalizedIncidentId} />
        </div>
      </div>
    </div>
  )
}

function DetailRow({label, children}: Readonly<{label: string; children: ReactNode}>) {
  return (
    <div>
      <p className="mb-1.5 text-xs font-medium uppercase tracking-wider text-muted-foreground">{label}</p>
      {children}
    </div>
  )
}
