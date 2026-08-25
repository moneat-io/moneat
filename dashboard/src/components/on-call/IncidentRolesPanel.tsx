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
import {Eye, ShieldCheck, UserCog, UserPlus, Users} from 'lucide-react'

import {api} from '@/lib/api'
import type {IncidentParticipant, IncidentRoleDefinition} from '@/lib/api/types'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {SectionCard} from '@/components/ui/section-card'
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
import {useAuth} from '@/hooks/useAuth'
import {formatDateTime} from './incident-modeling'

interface IncidentRolesPanelProps {
  incidentId: string
  incidentVersion?: number
  // Returns a promise that resolves once the incident (and its version) has been
  // refetched, so versioned actions can wait for a fresh expectedVersion.
  onMutated: () => Promise<unknown> | void
}

interface MemberOption {
  userId: string
  label: string
}

export function IncidentRolesPanel({
  incidentId,
  incidentVersion,
  onMutated,
}: Readonly<IncidentRolesPanelProps>) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const {user} = useAuth()
  const currentUserId = user?.id

  const assignmentsQuery = useQuery({
    queryKey: ['incident-roles', incidentId],
    queryFn: () => api.getIncidentRoleAssignments(incidentId),
  })
  const participantsQuery = useQuery({
    queryKey: ['incident-participants', incidentId],
    queryFn: () => api.getIncidentParticipants(incidentId),
  })
  const rolesQuery = useQuery({
    queryKey: ['incident-role-definitions'],
    queryFn: () => api.getIncidentRoles(),
  })
  const membersQuery = useQuery({
    queryKey: ['org-members'],
    queryFn: () => api.getOrgMembers(),
  })

  const memberOptions: MemberOption[] = (membersQuery.data?.members ?? []).map((member) => ({
    userId: member.userId,
    label: member.name ?? member.email,
  }))
  const memberName = (userId: string): string =>
    memberOptions.find((member) => member.userId === userId)?.label ?? 'Unknown member'

  const [picker, setPicker] = useState<{
    mode: 'assign' | 'handover'
    role: IncidentRoleDefinition
  } | null>(null)

  const onError = (error: Error) =>
    toast({title: 'Error', description: error.message, variant: 'destructive'})

  // Versioned commands must not re-enable until the incident (and its version)
  // has refetched — otherwise a second command could reuse a stale
  // expectedVersion and be rejected or clobber the wrong revision.
  const [isSyncing, setIsSyncing] = useState(false)
  const syncVersioned = async (apply: () => void) => {
    setIsSyncing(true)
    try {
      apply()
      await Promise.resolve(onMutated())
    } finally {
      setIsSyncing(false)
    }
  }
  const rolesKey = ['incident-roles', incidentId]
  const participantsKey = ['incident-participants', incidentId]

  const claimMutation = useMutation({
    mutationFn: (roleId: string) => api.claimIncidentRole(incidentId, roleId, incidentVersion),
    onSuccess: async (data) => {
      await syncVersioned(() => queryClient.setQueryData(rolesKey, data))
      toast({title: 'Role claimed', description: 'You now own this incident role.'})
    },
    onError,
  })
  const unassignMutation = useMutation({
    mutationFn: (roleId: string) => api.unassignIncidentRole(incidentId, roleId),
    onSuccess: async () => {
      // Unassigning still bumps the incident version server-side, so hold the
      // gate until the incident refetches — a follow-up versioned action must
      // not reuse the now-stale expectedVersion.
      await syncVersioned(() => queryClient.invalidateQueries({queryKey: rolesKey}))
      toast({title: 'Role unassigned'})
    },
    onError,
  })
  const assignMutation = useMutation({
    mutationFn: ({roleId, userId}: {roleId: string; userId: string}) =>
      api.assignIncidentRole(incidentId, roleId, userId, incidentVersion),
    onSuccess: async (data) => {
      setPicker(null)
      await syncVersioned(() => queryClient.setQueryData(rolesKey, data))
      toast({title: 'Role assigned'})
    },
    onError,
  })
  const handoverMutation = useMutation({
    mutationFn: ({roleId, userId, note}: {roleId: string; userId: string; note?: string}) =>
      api.handoverIncidentRole(incidentId, roleId, {userId, note, expectedVersion: incidentVersion}),
    onSuccess: async (data) => {
      setPicker(null)
      await syncVersioned(() => queryClient.setQueryData(rolesKey, data))
      toast({title: 'Role handed over'})
    },
    onError,
  })
  const joinMutation = useMutation({
    mutationFn: (kind: 'participant' | 'observer') =>
      kind === 'participant'
        ? api.joinIncident(incidentId, {expectedVersion: incidentVersion})
        : api.observeIncident(incidentId, {expectedVersion: incidentVersion}),
    onSuccess: async (data) => {
      await syncVersioned(() => queryClient.setQueryData(participantsKey, data))
      toast({title: 'Membership updated'})
    },
    onError,
  })
  const leaveMutation = useMutation({
    mutationFn: (userId: string) => api.leaveIncident(incidentId, userId),
    onSuccess: async () => {
      // Leaving also bumps the incident version, so use the same refresh gate.
      await syncVersioned(() => queryClient.invalidateQueries({queryKey: participantsKey}))
      toast({title: 'Removed from incident'})
    },
    onError,
  })

  const assignments = assignmentsQuery.data ?? []
  const assignmentByRoleId = new Map(assignments.map((assignment) => [assignment.role.id, assignment]))
  const roleDefinitions = rolesQuery.data ?? []
  const participants = participantsQuery.data ?? []
  const activeParticipants = participants.filter((member) => member.type === 'PARTICIPANT')
  const observers = participants.filter((member) => member.type === 'OBSERVER')
  const isMutating =
    isSyncing ||
    claimMutation.isPending ||
    unassignMutation.isPending ||
    assignMutation.isPending ||
    handoverMutation.isPending ||
    joinMutation.isPending ||
    leaveMutation.isPending
  const selfMembership = participants.find((member) => member.userId === currentUserId)

  return (
    <SectionCard title="Responders" icon={ShieldCheck} iconTone="accent" bodyClassName="space-y-5">
      {/* Roles */}
      <div className="space-y-2">
        <p className="text-xs font-medium uppercase tracking-wider text-muted-foreground">Roles</p>
        {rolesQuery.isLoading && <p className="text-xs text-muted-foreground">Loading roles…</p>}
        {!rolesQuery.isLoading && roleDefinitions.length === 0 && (
          <p className="text-xs text-muted-foreground">No incident roles are configured yet.</p>
        )}
        <div className="space-y-2">
          {roleDefinitions.map((role) => {
            const assignment = assignmentByRoleId.get(role.id)
            const assignedToSelf = assignment?.assigneeUserId === currentUserId
            const privateInstructions = assignedToSelf ? assignment?.role.privateInstructions : undefined
            return (
              <div key={role.id} className="rounded-lg border p-3">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium">{role.name}</span>
                      {role.required && (
                        <Badge variant="neutral" size="sm">
                          Required
                        </Badge>
                      )}
                    </div>
                    {role.description && (
                      <p className="mt-0.5 text-xs text-muted-foreground">{role.description}</p>
                    )}
                    {role.responsibilities.length > 0 && (
                      <ul className="mt-1.5 flex flex-wrap gap-1">
                        {role.responsibilities.map((item) => (
                          <li
                            key={item}
                            className="rounded bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground"
                          >
                            {item}
                          </li>
                        ))}
                      </ul>
                    )}
                    {privateInstructions && (
                      <div className="mt-2 rounded-md border border-accent/30 bg-accent/5 p-2">
                        <p className="text-[11px] font-medium uppercase tracking-wider text-accent-foreground">
                          Private role instructions
                        </p>
                        <p className="mt-1 whitespace-pre-wrap text-xs text-muted-foreground">
                          {privateInstructions}
                        </p>
                      </div>
                    )}
                  </div>
                  <div className="flex-shrink-0 text-right">
                    {assignment ? (
                      <div className="flex flex-col items-end gap-1">
                        <Badge variant="info" size="sm" className="gap-1">
                          <UserCog className="h-3 w-3" />
                          {memberName(assignment.assigneeUserId)}
                          {assignedToSelf && ' (you)'}
                        </Badge>
                        <div className="flex gap-1">
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-7 px-2 text-xs"
                            disabled={isMutating}
                            onClick={() => setPicker({mode: 'handover', role})}
                          >
                            Handover
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-7 px-2 text-xs"
                            disabled={isMutating}
                            onClick={() => unassignMutation.mutate(role.id)}
                          >
                            Unassign
                          </Button>
                        </div>
                      </div>
                    ) : (
                      <div className="flex gap-1">
                        <Button
                          variant="outline"
                          size="sm"
                          className="h-7 px-2 text-xs"
                          disabled={isMutating}
                          onClick={() => claimMutation.mutate(role.id)}
                        >
                          Claim
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="h-7 px-2 text-xs"
                          disabled={isMutating}
                          onClick={() => setPicker({mode: 'assign', role})}
                        >
                          Assign…
                        </Button>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      </div>

      {/* Participants & observers */}
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <p className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
            Participants &amp; observers
          </p>
          <div className="flex gap-1">
            <Button
              variant="outline"
              size="sm"
              className="h-7 px-2 text-xs"
              disabled={isMutating || selfMembership?.type === 'PARTICIPANT'}
              onClick={() => joinMutation.mutate('participant')}
            >
              <UserPlus className="mr-1 h-3 w-3" /> Join
            </Button>
            <Button
              variant="ghost"
              size="sm"
              className="h-7 px-2 text-xs"
              disabled={isMutating || selfMembership?.type === 'OBSERVER'}
              onClick={() => joinMutation.mutate('observer')}
            >
              <Eye className="mr-1 h-3 w-3" /> Observe
            </Button>
          </div>
        </div>

        <MembershipList
          icon={Users}
          title="Participants"
          members={activeParticipants}
          currentUserId={currentUserId}
          memberName={memberName}
          onLeave={(userId) => leaveMutation.mutate(userId)}
          disabled={isMutating}
        />
        <MembershipList
          icon={Eye}
          title="Observers"
          members={observers}
          currentUserId={currentUserId}
          memberName={memberName}
          onLeave={(userId) => leaveMutation.mutate(userId)}
          disabled={isMutating}
        />
      </div>

      {picker && (
        <UserPickerDialog
          mode={picker.mode}
          roleName={picker.role.name}
          members={memberOptions}
          isPending={assignMutation.isPending || handoverMutation.isPending}
          onCancel={() => setPicker(null)}
          onConfirm={(userId, note) => {
            if (picker.mode === 'assign') assignMutation.mutate({roleId: picker.role.id, userId})
            else handoverMutation.mutate({roleId: picker.role.id, userId, note})
          }}
        />
      )}
    </SectionCard>
  )
}

interface MembershipListProps {
  icon: typeof Users
  title: string
  members: IncidentParticipant[]
  currentUserId?: string
  memberName: (userId: string) => string
  onLeave: (userId: string) => void
  disabled: boolean
}

function MembershipList({
  icon: Icon,
  title,
  members,
  currentUserId,
  memberName,
  onLeave,
  disabled,
}: Readonly<MembershipListProps>) {
  return (
    <div className="rounded-lg border p-3">
      <div className="mb-1.5 flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <Icon className="h-3.5 w-3.5" />
        {title}
        <span className="text-muted-foreground/60">· {members.length}</span>
      </div>
      {members.length === 0 ? (
        <p className="text-xs text-muted-foreground">None yet.</p>
      ) : (
        <ul className="space-y-1">
          {members.map((member) => (
            <li key={member.id} className="flex items-center justify-between gap-2 text-sm">
              <span className="min-w-0 truncate">
                {memberName(member.userId)}
                {member.userId === currentUserId && (
                  <span className="text-muted-foreground"> (you)</span>
                )}
              </span>
              <span className="flex items-center gap-2">
                <span className="text-[11px] text-muted-foreground">
                  {formatDateTime(member.joinedAt)}
                </span>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-6 px-2 text-xs"
                  disabled={disabled}
                  onClick={() => onLeave(member.userId)}
                >
                  {member.userId === currentUserId ? 'Leave' : 'Remove'}
                </Button>
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

interface UserPickerDialogProps {
  mode: 'assign' | 'handover'
  roleName: string
  members: MemberOption[]
  isPending: boolean
  onCancel: () => void
  onConfirm: (userId: string, note?: string) => void
}

function UserPickerDialog({
  mode,
  roleName,
  members,
  isPending,
  onCancel,
  onConfirm,
}: Readonly<UserPickerDialogProps>) {
  const [userId, setUserId] = useState('')
  const [note, setNote] = useState('')
  const actionLabel = mode === 'assign' ? 'Assign' : 'Hand over'
  const submitLabel = isPending ? 'Saving…' : actionLabel

  return (
    <Dialog open onOpenChange={(open) => !open && onCancel()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {mode === 'assign' ? 'Assign' : 'Hand over'} {roleName}
          </DialogTitle>
          <DialogDescription>
            {mode === 'assign'
              ? 'Choose a responder to own this role.'
              : 'Choose who takes over this role. A handover note is recorded on the timeline.'}
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-3 py-2">
          <div className="grid gap-1.5">
            <Label htmlFor="role-assignee">Responder</Label>
            <Select value={userId} onValueChange={setUserId}>
              <SelectTrigger id="role-assignee">
                <SelectValue placeholder="Select a member" />
              </SelectTrigger>
              <SelectContent>
                {members.map((member) => (
                  <SelectItem key={member.userId} value={member.userId}>
                    {member.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          {mode === 'handover' && (
            <div className="grid gap-1.5">
              <Label htmlFor="handover-note">Handover note</Label>
              <Textarea
                id="handover-note"
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="Context for the incoming responder"
                rows={3}
              />
            </div>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onCancel} disabled={isPending}>
            Cancel
          </Button>
          <Button
            onClick={() => onConfirm(userId, note.trim() || undefined)}
            disabled={!userId || isPending}
          >
            {submitLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
