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
import {CheckCircle2, Merge, XCircle} from 'lucide-react'

import {api} from '@/lib/api'
import type {OnCallIncident} from '@/lib/api/types'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {Label} from '@/components/ui/label'
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
import {cn} from '@/lib/utils'
import {
  buildAcceptInput,
  buildDeclineInput,
  buildMergeInput,
  canAcceptIncident,
  canDeclineIncident,
  canMergeIncident,
  describeIncidentActionError,
  eligibleMergeTargets,
} from './triage'
import {severityBadgeVariant} from './incident-modeling'

const SEVERITIES = ['SEV-0', 'SEV-1', 'SEV-2', 'SEV-3', 'SEV-4'] as const
const NO_TYPE = '__none__'
const DEFAULT_SEVERITY = 'SEV-2'

type ActiveDialog = 'accept' | 'merge' | 'decline' | null

interface IncidentTriageActionsProps {
  incident: OnCallIncident
  /** Extra refresh hook (e.g. refetch a detail view) run after any action succeeds. */
  onMutated?: () => void
  /** 'banner' for the detail action banner, 'inline' for compact list-row controls. */
  layout?: 'banner' | 'inline'
}

/**
 * Accept / Merge / Decline controls for a triage incident, wired to the real
 * triage command APIs with optimistic-concurrency versions. Each button is only
 * offered when the incident's status permits that transition, and backend
 * validation/conflict messages are surfaced verbatim so responders can act on
 * them. Shared by the incident list triage queue and the incident detail banner.
 */
export function IncidentTriageActions({
  incident,
  onMutated,
  layout = 'inline',
}: Readonly<IncidentTriageActionsProps>) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [dialog, setDialog] = useState<ActiveDialog>(null)

  const refresh = () => {
    queryClient.invalidateQueries({queryKey: ['on-call-incidents']})
    queryClient.invalidateQueries({queryKey: ['declared-incident', incident.id]})
    queryClient.invalidateQueries({queryKey: ['incident-canonical-timeline', incident.id]})
    onMutated?.()
  }

  const onActionError = (error: unknown) => {
    const info = describeIncidentActionError(error)
    // A conflict means our version is stale or the incident already moved on;
    // refresh so the next attempt sees the latest state.
    if (info.isConflict) refresh()
    toast({title: 'Action failed', description: info.message, variant: 'destructive'})
  }

  const acceptMutation = useMutation({
    mutationFn: (params: {severity: string; incidentTypeId?: string}) =>
      api.acceptOnCallIncident(
        incident.id,
        buildAcceptInput({...params, expectedVersion: incident.version}),
      ),
    onSuccess: () => {
      setDialog(null)
      refresh()
      toast({title: 'Incident accepted', description: 'The incident is now active.'})
    },
    onError: onActionError,
  })

  const declineMutation = useMutation({
    mutationFn: (reason: string) =>
      api.declineOnCallIncident(
        incident.id,
        buildDeclineInput({reason, expectedVersion: incident.version}),
      ),
    onSuccess: () => {
      setDialog(null)
      refresh()
      toast({title: 'Incident declined', description: 'The triage incident was dismissed.'})
    },
    onError: onActionError,
  })

  const mergeMutation = useMutation({
    mutationFn: (target: OnCallIncident) =>
      api.mergeOnCallIncident(target.id, buildMergeInput(target, incident)),
    onSuccess: () => {
      setDialog(null)
      refresh()
      toast({title: 'Incident merged', description: 'This incident was folded into the target.'})
    },
    onError: onActionError,
  })

  const showAccept = canAcceptIncident(incident.status)
  const showMerge = canMergeIncident(incident.status)
  const showDecline = canDeclineIncident(incident.status)
  if (!showAccept && !showMerge && !showDecline) return null

  const busy = acceptMutation.isPending || declineMutation.isPending || mergeMutation.isPending
  const size = layout === 'banner' ? 'default' : 'sm'

  return (
    <div className={cn('flex items-center gap-2', layout === 'inline' && 'flex-wrap')}>
      {showAccept && (
        <Button size={size} onClick={() => setDialog('accept')} disabled={busy}>
          <CheckCircle2 className="mr-1.5 h-4 w-4" />
          Accept
        </Button>
      )}
      {showMerge && (
        <Button variant="outline" size={size} onClick={() => setDialog('merge')} disabled={busy}>
          <Merge className="mr-1.5 h-4 w-4" />
          Merge
        </Button>
      )}
      {showDecline && (
        <Button variant="outline" size={size} onClick={() => setDialog('decline')} disabled={busy}>
          <XCircle className="mr-1.5 h-4 w-4" />
          Decline
        </Button>
      )}

      {showAccept && (
        <AcceptDialog
          open={dialog === 'accept'}
          incident={incident}
          isPending={acceptMutation.isPending}
          onOpenChange={(open) => !acceptMutation.isPending && setDialog(open ? 'accept' : null)}
          onConfirm={(params) => acceptMutation.mutate(params)}
        />
      )}
      {showMerge && (
        <MergeDialog
          open={dialog === 'merge'}
          incident={incident}
          isPending={mergeMutation.isPending}
          onOpenChange={(open) => !mergeMutation.isPending && setDialog(open ? 'merge' : null)}
          onConfirm={(target) => mergeMutation.mutate(target)}
        />
      )}
      {showDecline && (
        <DeclineDialog
          open={dialog === 'decline'}
          isPending={declineMutation.isPending}
          onOpenChange={(open) => !declineMutation.isPending && setDialog(open ? 'decline' : null)}
          onConfirm={(reason) => declineMutation.mutate(reason)}
        />
      )}
    </div>
  )
}

interface AcceptDialogProps {
  open: boolean
  incident: OnCallIncident
  isPending: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: (params: {severity: string; incidentTypeId?: string}) => void
}

function AcceptDialog({open, incident, isPending, onOpenChange, onConfirm}: Readonly<AcceptDialogProps>) {
  const [severity, setSeverity] = useState(incident.severity ?? DEFAULT_SEVERITY)
  const [incidentTypeId, setIncidentTypeId] = useState<string>(NO_TYPE)

  const {data: types} = useQuery({
    queryKey: ['incident-types'],
    queryFn: () => api.getIncidentTypes(),
    enabled: open,
  })

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Accept incident</DialogTitle>
          <DialogDescription>
            Classify this triage incident and move it into active response. A severity is required.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-2">
          <div className="space-y-1.5">
            <Label>
              Severity <span className="text-danger-fg">*</span>
            </Label>
            <div className="flex flex-wrap gap-2">
              {SEVERITIES.map((sev) => (
                <Button
                  key={sev}
                  type="button"
                  variant={severity === sev ? 'default' : 'outline'}
                  size="sm"
                  aria-pressed={severity === sev}
                  onClick={() => setSeverity(sev)}
                >
                  {sev}
                </Button>
              ))}
            </div>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="accept-incident-type">Incident type</Label>
            <Select value={incidentTypeId} onValueChange={setIncidentTypeId}>
              <SelectTrigger id="accept-incident-type" className="h-9">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={NO_TYPE}>Keep current type</SelectItem>
                {(types ?? [])
                  .filter((type) => type.enabled)
                  .map((type) => (
                    <SelectItem key={type.id} value={type.id}>
                      {type.name}
                    </SelectItem>
                  ))}
              </SelectContent>
            </Select>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isPending}>
            Cancel
          </Button>
          <Button
            onClick={() =>
              onConfirm({
                severity,
                incidentTypeId: incidentTypeId === NO_TYPE ? undefined : incidentTypeId,
              })
            }
            disabled={isPending || severity.length === 0}
          >
            {isPending ? 'Accepting…' : 'Accept incident'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

interface MergeDialogProps {
  open: boolean
  incident: OnCallIncident
  isPending: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: (target: OnCallIncident) => void
}

function MergeDialog({open, incident, isPending, onOpenChange, onConfirm}: Readonly<MergeDialogProps>) {
  const [targetId, setTargetId] = useState('')

  const incidentsQuery = useQuery({
    queryKey: ['on-call-incidents', 'merge-targets'],
    queryFn: () => api.getOnCallIncidents({}),
    enabled: open,
  })

  const targets = eligibleMergeTargets(incidentsQuery.data ?? [], incident.id)
  const selected = targets.find((target) => target.id === targetId)
  const noTargets = !incidentsQuery.isLoading && targets.length === 0

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Merge incident</DialogTitle>
          <DialogDescription>
            Fold this incident into another one. Its alerts and sources move to the target, and this
            incident is marked merged.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-1.5 py-2">
          <Label htmlFor="merge-target">Merge into</Label>
          <Select value={selected ? targetId : ''} onValueChange={setTargetId} disabled={noTargets}>
            <SelectTrigger id="merge-target" aria-label="Merge into">
              <SelectValue placeholder={noTargets ? 'No eligible incidents' : 'Select an incident'} />
            </SelectTrigger>
            <SelectContent>
              {targets.map((target) => (
                <SelectItem key={target.id} value={target.id}>
                  {target.title}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {noTargets && (
            <p className="text-xs text-muted-foreground">
              There are no other incidents this one can be merged into right now.
            </p>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isPending}>
            Cancel
          </Button>
          <Button onClick={() => selected && onConfirm(selected)} disabled={!selected || isPending}>
            {isPending ? 'Merging…' : 'Merge incident'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

interface DeclineDialogProps {
  open: boolean
  isPending: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: (reason: string) => void
}

function DeclineDialog({open, isPending, onOpenChange, onConfirm}: Readonly<DeclineDialogProps>) {
  const [reason, setReason] = useState('')

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Decline incident</DialogTitle>
          <DialogDescription>
            Dismiss this triage incident. It will not become an active incident. You can note why for
            the record.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-1.5 py-2">
          <Label htmlFor="decline-reason">Reason (optional)</Label>
          <Textarea
            id="decline-reason"
            rows={3}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder="Why is this not a real incident?"
          />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isPending}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={() => onConfirm(reason)} disabled={isPending}>
            {isPending ? 'Declining…' : 'Decline incident'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** Small triage severity badge used by the list queue rows. */
export function TriageSeverityBadge({severity}: Readonly<{severity?: string}>) {
  return (
    <Badge variant={severityBadgeVariant(severity)} size="sm">
      {severity ?? 'Unclassified'}
    </Badge>
  )
}
