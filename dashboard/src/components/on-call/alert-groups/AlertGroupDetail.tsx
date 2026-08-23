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

import {useState} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {useNavigate} from '@tanstack/react-router'
import {
  AlertTriangle,
  ArrowLeft,
  Boxes,
  History,
  Link2,
  ShieldAlert,
  Ticket,
} from 'lucide-react'
import {api} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {EmptyState} from '@/components/ui/empty-state'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {SectionCard} from '@/components/ui/section-card'
import {Textarea} from '@/components/ui/textarea'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {useToast} from '@/hooks/useToast'
import {isNotFoundError} from '@/lib/api/errors'
import {isUuidResourceId} from '@/lib/api/utils'
import type {AlertGroup} from '@/lib/api/types'
import {formatDateTime, shortResourceId, timeAgo} from '@/components/on-call/incident-modeling'
import {
  activeMembers,
  behaviorLabel,
  decisionTypeLabel,
  groupIncidentLinkage,
  groupStateMeta,
  memberStateMeta,
  pagingStateMeta,
  relativeWindow,
  windowKindLabel,
} from './alertGroupFormat'
import {ALERT_GROUPS_QUERY_KEY, alertGroupQueryKey} from './alertGroupQueries'

interface AlertGroupDetailProps {
  groupId: string
}

const STALE_GROUP_MESSAGE =
  'This group changed since you loaded it. The latest state has been reloaded — try again.'

export function AlertGroupDetail({groupId}: Readonly<AlertGroupDetailProps>) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const hasValidId = isUuidResourceId(groupId)
  const groupKey = alertGroupQueryKey(groupId)

  const [attachOpen, setAttachOpen] = useState(false)
  const [triageOpen, setTriageOpen] = useState(false)

  const {data: group, error: groupError, isLoading, isError, refetch} = useQuery({
    queryKey: groupKey,
    queryFn: () => api.getAlertGroup(groupId),
    enabled: hasValidId,
  })

  const refreshGroupList = () => queryClient.invalidateQueries({queryKey: ALERT_GROUPS_QUERY_KEY})
  const applyGroup = (updated: AlertGroup) => {
    queryClient.setQueryData(groupKey, updated)
    refreshGroupList()
  }

  const onError = (error: Error) => {
    if (isConflict(error)) {
      queryClient.invalidateQueries({queryKey: groupKey})
      refreshGroupList()
      toast({title: 'Group changed', description: STALE_GROUP_MESSAGE, variant: 'destructive'})
      return
    }
    toast({title: 'Error', description: error.message, variant: 'destructive'})
  }

  const version = group?.version ?? 0

  const unrelatedMutation = useMutation({
    mutationFn: (episodeId: string) =>
      api.markAlertGroupEpisodeUnrelated(groupId, episodeId, version),
    onSuccess: (updated) => {
      applyGroup(updated)
      toast({title: 'Marked unrelated', description: 'The alert was separated from this group.'})
    },
    onError,
  })

  const removeMutation = useMutation({
    mutationFn: (episodeId: string) => api.removeAlertGroupEpisode(groupId, episodeId, version),
    onSuccess: (updated) => {
      applyGroup(updated)
      toast({title: 'Alert removed', description: 'The alert was removed from this group.'})
    },
    onError,
  })

  const attachMutation = useMutation({
    mutationFn: (incidentId: string) =>
      api.attachAlertGroup(groupId, {expectedVersion: version, incidentId}),
    onSuccess: (updated) => {
      applyGroup(updated)
      setAttachOpen(false)
      toast({title: 'Group attached', description: 'This group is now linked to the incident.'})
    },
    onError,
  })

  const triageMutation = useMutation({
    mutationFn: (input: {title?: string; summary?: string}) =>
      api.createAlertGroupTriage(groupId, {expectedVersion: version, ...input}),
    onSuccess: (updated) => {
      applyGroup(updated)
      setTriageOpen(false)
      queryClient.invalidateQueries({queryKey: ['on-call-incidents']})
      toast({title: 'Triage incident created', description: 'A triage incident was opened for this group.'})
    },
    onError,
  })

  const isMutating =
    unrelatedMutation.isPending ||
    removeMutation.isPending ||
    attachMutation.isPending ||
    triageMutation.isPending

  if (!hasValidId) {
    return (
      <EmptyState
        icon={AlertTriangle}
        title="Invalid group ID"
        description="The alert group identifier in the URL is invalid."
      />
    )
  }
  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-muted border-t-primary" />
      </div>
    )
  }
  if (isError && !isNotFoundError(groupError)) {
    return (
      <EmptyState
        icon={AlertTriangle}
        title="Unable to load alert group"
        description="The group could not be loaded. Check your connection and try again."
        action={<Button size="sm" onClick={() => refetch()}>Try again</Button>}
      />
    )
  }
  if (!group) {
    return (
      <EmptyState
        icon={AlertTriangle}
        title="Alert group not found"
        description="This group may have closed or you may not have access to it."
      />
    )
  }

  const stateMeta = groupStateMeta(group.state)
  const linkage = groupIncidentLinkage(group)
  const members = [...group.members].sort((a, b) => (a.firstJoinedAt < b.firstJoinedAt ? -1 : 1))
  const decisions = [...group.decisions].sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
  const openMembers = activeMembers(group)
  const canOpenIncident = group.state === 'OPEN' && !group.incidentId

  return (
    <div className="space-y-6">
      <div className="flex items-start gap-4">
        <Button
          variant="ghost"
          size="icon"
          className="mt-1 flex-shrink-0"
          onClick={() => navigate({to: '/on-call/alert-groups'})}
          aria-label="Back to alert groups"
        >
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div className="min-w-0 flex-1">
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <Badge variant={stateMeta.variant} size="sm">
              {stateMeta.label}
            </Badge>
            <Badge variant="neutral" size="sm">
              {behaviorLabel(group.behavior)}
            </Badge>
            <Badge variant="neutral" size="sm">
              {group.singleton ? 'Singleton' : `${openMembers.length} active alerts`}
            </Badge>
            {linkage.kind === 'incident' && linkage.incidentId && (
              <IncidentLink incidentId={linkage.incidentId} label="Linked incident" />
            )}
            {linkage.kind === 'candidate' && linkage.incidentId && (
              <IncidentLink incidentId={linkage.incidentId} label="Candidate incident" />
            )}
          </div>
          <h2 className="text-2xl font-bold tracking-tight">Alert group {shortResourceId(group.id)}</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            Opened {timeAgo(group.openedAt)} · {windowKindLabel(group.windowKind)} window ·{' '}
            {relativeWindow(group.closesAt)}
          </p>
        </div>
        {canOpenIncident && (
          <div className="flex shrink-0 gap-2">
            <Button variant="outline" size="sm" onClick={() => setAttachOpen(true)} disabled={isMutating}>
              <Link2 className="mr-1.5 h-4 w-4" />
              Attach incident
            </Button>
            <Button size="sm" onClick={() => setTriageOpen(true)} disabled={isMutating}>
              <Ticket className="mr-1.5 h-4 w-4" />
              Create triage
            </Button>
          </div>
        )}
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <SectionCard title="Alerts in this group" icon={Boxes} iconTone="accent" count={members.length} bodyClassName="space-y-2">
            {members.length === 0 && <p className="text-xs text-muted-foreground">No alerts in this group.</p>}
            {members.map((member) => {
              const memberMeta = memberStateMeta(member.state)
              const pagingMeta = pagingStateMeta(member.pagingState)
              const removable = member.state === 'ACTIVE' && group.state === 'OPEN'
              return (
                <div key={member.id} className="rounded-lg border p-3">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="font-mono text-xs">{shortResourceId(member.episodeId)}</p>
                      <div className="mt-1 flex flex-wrap items-center gap-1.5">
                        <Badge variant={memberMeta.variant} size="sm">
                          {memberMeta.label}
                        </Badge>
                        <Badge variant={pagingMeta.variant} size="sm">
                          {pagingMeta.label}
                        </Badge>
                      </div>
                      <p className="mt-1 text-[11px] text-muted-foreground">
                        Joined {formatDateTime(member.firstJoinedAt)} · last seen {formatDateTime(member.lastSeenAt)}
                      </p>
                    </div>
                    {removable && (
                      <div className="flex shrink-0 gap-1">
                        <Button
                          variant="ghost"
                          size="sm"
                          className="h-7 px-2 text-xs"
                          disabled={isMutating}
                          onClick={() => unrelatedMutation.mutate(member.episodeId)}
                        >
                          Unrelated
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="h-7 px-2 text-xs"
                          disabled={isMutating}
                          onClick={() => removeMutation.mutate(member.episodeId)}
                        >
                          Remove
                        </Button>
                      </div>
                    )}
                  </div>
                </div>
              )
            })}
          </SectionCard>

          <SectionCard title="Decisions" icon={History} iconTone="muted" count={decisions.length} bodyClassName="space-y-2">
            {decisions.length === 0 && <p className="text-xs text-muted-foreground">No decisions recorded yet.</p>}
            {decisions.map((decision) => (
              <div key={decision.id} className="flex items-start gap-2 text-xs">
                <span className="mt-1 h-1.5 w-1.5 shrink-0 rounded-full bg-muted-foreground/50" />
                <div className="min-w-0">
                  <p className="font-medium">{decisionTypeLabel(decision.type)}</p>
                  <p className="mt-0.5 text-muted-foreground">
                    {formatDateTime(decision.createdAt)}
                    {decision.episodeId ? ` · alert ${shortResourceId(decision.episodeId)}` : ''}
                  </p>
                </div>
              </div>
            ))}
          </SectionCard>
        </div>

        <div className="space-y-4">
          <SectionCard title="Details" bodyClassName="space-y-3 text-sm">
            <DetailRow label="Route">
              <span className="truncate">{routeLabel(group)}</span>
            </DetailRow>
            <DetailRow label="Route revision">
              <span className="tabular-nums">{group.routeRevision}</span>
            </DetailRow>
            <DetailRow label="Paging">
              <span>{pagingStateMeta(group.pagingState).label}</span>
            </DetailRow>
            <DetailRow label="Window">
              <span>
                {windowKindLabel(group.windowKind)} · {relativeWindow(group.closesAt)}
              </span>
            </DetailRow>
            <DetailRow label="Last alert">
              <span>{formatDateTime(group.lastAlertAt)}</span>
            </DetailRow>
            <DetailRow label="Group version">
              <span className="tabular-nums">{group.version}</span>
            </DetailRow>
          </SectionCard>

          {!canOpenIncident && group.state === 'CLOSED' && (
            <div className="flex items-start gap-2 rounded-lg border bg-muted/30 p-3 text-xs text-muted-foreground">
              <ShieldAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              <span>This group is closed. Alerts can no longer be added or removed.</span>
            </div>
          )}
        </div>
      </div>

      <AttachIncidentDialog
        open={attachOpen}
        isPending={attachMutation.isPending}
        onOpenChange={setAttachOpen}
        onConfirm={(incidentId) => attachMutation.mutate(incidentId)}
      />
      <CreateTriageDialog
        open={triageOpen}
        isPending={triageMutation.isPending}
        onOpenChange={setTriageOpen}
        onConfirm={(input) => triageMutation.mutate(input)}
      />
    </div>
  )
}

const CONFLICT_STATUS = 409
function isConflict(error: unknown): boolean {
  return (
    typeof error === 'object' && error !== null && (error as {status?: number}).status === CONFLICT_STATUS
  )
}

function routeLabel(group: AlertGroup): string {
  const snapshot = group.routeSnapshot
  const name = typeof snapshot.name === 'string' ? snapshot.name : undefined
  const key = typeof snapshot.key === 'string' ? snapshot.key : undefined
  return name ?? key ?? shortResourceId(group.routeId)
}

function DetailRow({label, children}: Readonly<{label: string; children: React.ReactNode}>) {
  return (
    <div className="flex items-center justify-between gap-2">
      <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{label}</span>
      <span className="min-w-0 text-right">{children}</span>
    </div>
  )
}

function IncidentLink({incidentId, label}: Readonly<{incidentId: string; label: string}>) {
  const navigate = useNavigate()
  return (
    <button
      type="button"
      className="inline-flex items-center gap-1 rounded-md border border-info-border bg-info-bg px-1.5 py-0.5 text-[10px] font-semibold text-info-fg"
      onClick={() => navigate({to: '/on-call/incidents/$incidentId', params: {incidentId}})}
    >
      <Ticket className="h-3 w-3" />
      {label}
    </button>
  )
}

interface AttachIncidentDialogProps {
  open: boolean
  isPending: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: (incidentId: string) => void
}

function AttachIncidentDialog({open, isPending, onOpenChange, onConfirm}: Readonly<AttachIncidentDialogProps>) {
  const [incidentId, setIncidentId] = useState('')
  const incidentsQuery = useQuery({
    queryKey: ['on-call-incidents', 'open-for-attach'],
    queryFn: () => api.getOnCallIncidents({}),
    enabled: open,
  })
  const openIncidents = (incidentsQuery.data ?? []).filter(
    (incident) => incident.status === 'ACTIVE' || incident.status === 'TRIAGE'
  )
  const valid = isUuidResourceId(incidentId)

  return (
    <Dialog open={open} onOpenChange={(next) => !isPending && onOpenChange(next)}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Attach to an incident</DialogTitle>
          <DialogDescription>
            Link this group to an open incident so its alerts feed that incident.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-1.5 py-2">
          <Label htmlFor="attach-incident">Incident</Label>
          <Select value={valid ? incidentId : ''} onValueChange={setIncidentId} disabled={openIncidents.length === 0}>
            <SelectTrigger id="attach-incident" aria-label="Incident">
              <SelectValue placeholder={openIncidents.length === 0 ? 'No open incidents' : 'Select an incident'} />
            </SelectTrigger>
            <SelectContent>
              {openIncidents.map((incident) => (
                <SelectItem key={incident.id} value={incident.id}>
                  {incident.title}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isPending}>
            Cancel
          </Button>
          <Button onClick={() => onConfirm(incidentId)} disabled={!valid || isPending}>
            {isPending ? 'Attaching…' : 'Attach group'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

interface CreateTriageDialogProps {
  open: boolean
  isPending: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: (input: {title?: string; summary?: string}) => void
}

function CreateTriageDialog({open, isPending, onOpenChange, onConfirm}: Readonly<CreateTriageDialogProps>) {
  const [title, setTitle] = useState('')
  const [summary, setSummary] = useState('')

  return (
    <Dialog open={open} onOpenChange={(next) => !isPending && onOpenChange(next)}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create a triage incident</DialogTitle>
          <DialogDescription>
            Open a triage incident for this group so a responder can classify it. Leave the fields blank
            to use the route&apos;s template.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3 py-2">
          <div className="space-y-1.5">
            <Label htmlFor="triage-title">Title (optional)</Label>
            <Input id="triage-title" value={title} onChange={(event) => setTitle(event.target.value)} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="triage-summary">Summary (optional)</Label>
            <Textarea id="triage-summary" rows={3} value={summary} onChange={(event) => setSummary(event.target.value)} />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isPending}>
            Cancel
          </Button>
          <Button
            onClick={() => onConfirm({title: title.trim() || undefined, summary: summary.trim() || undefined})}
            disabled={isPending}
          >
            {isPending ? 'Creating…' : 'Create triage'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
