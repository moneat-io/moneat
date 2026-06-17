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

import {createFileRoute, Link} from '@tanstack/react-router'
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {useState} from 'react'
import {api, type OrganizationTeam} from '@/lib/api'
import {Card} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {PageHeader} from '@/components/ui/page-header'
import {EmptyState} from '@/components/ui/empty-state'
import {StatusDot} from '@/components/ui/status-dot'
import {Avatar, AvatarFallback} from '@/components/ui/avatar'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {useToast} from '@/hooks/useToast'
import {useTeamsEntitlement} from '@/hooks/useTeamsEntitlement'
import {ORG_TEAMS_QUERY_KEY} from '@/hooks/useOrganizationTeams'
import {TeamEditor, type TeamFormData} from '@/components/on-call/TeamEditor'
import {cn} from '@/lib/utils'
import {Users2, Plus, Trash2, Pencil, Lock, Hash, GitBranch, Calendar, ListChecks} from 'lucide-react'

export const Route = createFileRoute('/on-call/teams')({
  component: OnCallTeams,
})

const avatarColors = [
  'bg-chart-1', 'bg-chart-2', 'bg-chart-3', 'bg-chart-4',
  'bg-chart-5', 'bg-chart-6', 'bg-chart-7', 'bg-chart-8',
]

function getInitials(name: string) {
  return name.split(' ').map((part) => part[0]).join('').toUpperCase().slice(0, 2)
}

function teamToForm(team: OrganizationTeam): TeamFormData {
  return {
    name: team.name,
    description: team.description ?? '',
    slack: team.slack ?? '',
    repo: team.repo ?? '',
    memberIds: team.members.map((member) => member.userId),
    onCallScheduleId: team.onCallScheduleId ?? null,
    escalationPolicyId: team.escalationPolicyId ?? null,
  }
}

function formToRequest(data: TeamFormData) {
  return {
    name: data.name,
    description: data.description.trim() || null,
    slack: data.slack.trim() || null,
    repo: data.repo.trim() || null,
    onCallScheduleId: data.onCallScheduleId,
    escalationPolicyId: data.escalationPolicyId,
    memberIds: data.memberIds,
  }
}

function TeamsLockedState() {
  return (
    <EmptyState
      icon={Lock}
      title="Teams is a Team plan feature"
      description="Group engineers into teams, then assign team-based ownership across services and infrastructure. Available on the Team plan and above."
      action={
        <Button asChild size="sm">
          <Link to="/settings" search={{tab: 'billing'}}>
            Upgrade plan
          </Link>
        </Button>
      }
    />
  )
}

function MemberAvatars({team}: {readonly team: OrganizationTeam}) {
  if (team.members.length === 0) {
    return <span className="text-xs text-muted-foreground">No members</span>
  }
  return (
    <div className="flex items-center gap-1.5">
      <div className="flex -space-x-2">
        {team.members.slice(0, 5).map((member, idx) => (
          <Avatar key={member.userId} className="h-6 w-6 border-2 border-background">
            <AvatarFallback className={cn('text-[10px] text-white', avatarColors[idx % avatarColors.length])}>
              {getInitials(member.name || member.email)}
            </AvatarFallback>
          </Avatar>
        ))}
        {team.members.length > 5 && (
          <Avatar className="h-6 w-6 border-2 border-background">
            <AvatarFallback className="bg-muted text-[10px]">+{team.members.length - 5}</AvatarFallback>
          </Avatar>
        )}
      </div>
      <span className="text-xs text-muted-foreground">
        {team.members.length} member{team.members.length === 1 ? '' : 's'}
      </span>
    </div>
  )
}

function TeamCard({
  team,
  scheduleName,
  policyName,
  onEdit,
  onDelete,
  deleting,
}: {
  readonly team: OrganizationTeam
  readonly scheduleName?: string
  readonly policyName?: string
  readonly onEdit: () => void
  readonly onDelete: () => void
  readonly deleting: boolean
}) {
  return (
    <Card className="p-3 transition-colors hover:border-primary/30">
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-muted">
            <Users2 className="h-4 w-4 text-muted-foreground" />
          </div>
          <div className="min-w-0">
            <h3 className="text-base font-semibold">{team.name}</h3>
            {team.description && (
              <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">{team.description}</p>
            )}
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {team.currentOnCall && (
            <div className="flex items-center gap-1.5 rounded-full border border-success-border bg-success-bg px-2 py-1">
              <StatusDot tone="success" pulse size="sm" />
              <span className="text-xs font-medium text-success-fg">{team.currentOnCall.userName}</span>
            </div>
          )}
          <Button variant="ghost" size="icon" className="h-7 w-7" aria-label={`Edit ${team.name}`} onClick={onEdit}>
            <Pencil className="h-3 w-3" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="h-7 w-7 text-destructive hover:text-destructive"
            aria-label={`Delete ${team.name}`}
            disabled={deleting}
            onClick={onDelete}
          >
            <Trash2 className="h-3 w-3" />
          </Button>
        </div>
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-2 pl-12">
        <MemberAvatars team={team} />
        {scheduleName && (
          <Badge variant="neutral" size="sm" className="gap-1">
            <Calendar className="h-3 w-3" />
            {scheduleName}
          </Badge>
        )}
        {policyName && (
          <Badge variant="neutral" size="sm" className="gap-1">
            <ListChecks className="h-3 w-3" />
            {policyName}
          </Badge>
        )}
        {team.slack && (
          <Badge variant="info" size="sm" className="gap-1">
            <Hash className="h-3 w-3" />
            {team.slack.replace(/^#/, '')}
          </Badge>
        )}
        {team.repo && (
          <Badge variant="neutral" size="sm" className="gap-1">
            <GitBranch className="h-3 w-3" />
            {team.repo}
          </Badge>
        )}
      </div>
    </Card>
  )
}

function OnCallTeams() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const entitlement = useTeamsEntitlement()
  const shouldLoadTeams = entitlement.enabled || entitlement.isError
  const [showEditor, setShowEditor] = useState(false)
  const [editingTeam, setEditingTeam] = useState<OrganizationTeam | null>(null)

  const teamsQuery = useQuery({
    queryKey: ORG_TEAMS_QUERY_KEY,
    queryFn: () => api.getOrganizationTeams(),
    enabled: shouldLoadTeams,
  })

  const {data: orgMembers} = useQuery({
    queryKey: ['org-members'],
    queryFn: () => api.getOrgMembers(),
    enabled: shouldLoadTeams,
  })

  const {data: schedules} = useQuery({
    queryKey: ['on-call-schedules'],
    queryFn: () => api.getOnCallSchedules(),
    enabled: shouldLoadTeams,
  })

  const {data: policies} = useQuery({
    queryKey: ['escalation-policies'],
    queryFn: () => api.getEscalationPolicies(),
    enabled: shouldLoadTeams,
  })

  const memberOptions = (orgMembers?.members ?? []).map((member) => ({
    id: member.userId,
    name: member.name || member.email,
  }))
  const scheduleOptions = (schedules ?? []).map((schedule) => ({id: schedule.id, name: schedule.name}))
  const policyOptions = (policies ?? []).map((policy) => ({id: policy.id, name: policy.name}))
  const scheduleNameById = new Map(scheduleOptions.map((option) => [option.id, option.name]))
  const policyNameById = new Map(policyOptions.map((option) => [option.id, option.name]))

  const closeEditor = () => {
    setShowEditor(false)
    setEditingTeam(null)
  }

  const createMutation = useMutation({
    mutationFn: (data: TeamFormData) => api.createOrganizationTeam(formToRequest(data)),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ORG_TEAMS_QUERY_KEY})
      closeEditor()
      toast({title: 'Team created', description: 'The team is ready to own resources.'})
    },
    onError: (error: Error) => toast({title: 'Error', description: error.message, variant: 'destructive'}),
  })

  const updateMutation = useMutation({
    mutationFn: ({id, data}: {id: string; data: TeamFormData}) =>
      api.updateOrganizationTeam(id, formToRequest(data)),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ORG_TEAMS_QUERY_KEY})
      closeEditor()
      toast({title: 'Team updated', description: 'Team changes have been saved.'})
    },
    onError: (error: Error) => toast({title: 'Error', description: error.message, variant: 'destructive'}),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.deleteOrganizationTeam(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ORG_TEAMS_QUERY_KEY})
      toast({title: 'Team deleted', description: 'The team has been removed.'})
    },
    onError: (error: Error) => toast({title: 'Error', description: error.message, variant: 'destructive'}),
  })

  const handleSave = (data: TeamFormData) => {
    if (editingTeam) {
      updateMutation.mutate({id: editingTeam.id, data})
    } else {
      createMutation.mutate(data)
    }
  }

  const handleDelete = (team: OrganizationTeam) => {
    const confirmed = globalThis.window.confirm(
      `Delete the "${team.name}" team? Resources owned by it will become unowned.`
    )
    if (confirmed) {
      deleteMutation.mutate(team.id)
    }
  }

  const teams = teamsQuery.data ?? []
  const isSaving = createMutation.isPending || updateMutation.isPending

  let content
  if (!entitlement.enabled && !entitlement.isError) {
    content = entitlement.isLoading ? (
      <div className="flex items-center justify-center py-10">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-muted border-t-primary" />
      </div>
    ) : (
      <TeamsLockedState />
    )
  } else if (teamsQuery.isLoading) {
    content = (
      <div className="flex items-center justify-center py-10">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-muted border-t-primary" />
      </div>
    )
  } else if (teams.length > 0) {
    content = (
      <div className="space-y-3">
        {teams.map((team) => (
          <TeamCard
            key={team.id}
            team={team}
            scheduleName={team.onCallScheduleId ? scheduleNameById.get(team.onCallScheduleId) : undefined}
            policyName={team.escalationPolicyId ? policyNameById.get(team.escalationPolicyId) : undefined}
            onEdit={() => {
              setEditingTeam(team)
              setShowEditor(true)
            }}
            onDelete={() => handleDelete(team)}
            deleting={deleteMutation.isPending}
          />
        ))}
      </div>
    )
  } else {
    content = (
      <EmptyState
        icon={Users2}
        title="No teams yet"
        description="Create a team to group engineers and own services, hosts, and other resources from one place."
        action={
          <Button size="sm" className="gap-1.5" onClick={() => setShowEditor(true)}>
            <Plus className="h-4 w-4" />
            Create your first team
          </Button>
        }
      />
    )
  }

  return (
    <div className="space-y-4">
      <PageHeader
        icon={Users2}
        title="On-call teams"
        description="Group engineers into teams that own resources and carry a primary on-call schedule"
        actions={
          shouldLoadTeams ? (
            <Button
              size="sm"
              className="shrink-0 gap-1.5"
              onClick={() => {
                setEditingTeam(null)
                setShowEditor(true)
              }}
            >
              <Plus className="h-4 w-4" />
              Create team
            </Button>
          ) : undefined
        }
      />

      {content}

      <Dialog open={showEditor} onOpenChange={(open) => (open ? setShowEditor(true) : closeEditor())}>
        <DialogContent className="sm:max-w-[560px]">
          <DialogHeader>
            <DialogTitle>{editingTeam ? 'Edit team' : 'Create team'}</DialogTitle>
            <DialogDescription>
              {editingTeam
                ? 'Update the team’s details, members, and on-call defaults.'
                : 'Set up a team with members, a primary on-call schedule, and a default escalation policy.'}
            </DialogDescription>
          </DialogHeader>
          <TeamEditor
            initialData={editingTeam ? teamToForm(editingTeam) : undefined}
            members={memberOptions}
            schedules={scheduleOptions}
            policies={policyOptions}
            isSaving={isSaving}
            onSave={handleSave}
            onCancel={closeEditor}
          />
        </DialogContent>
      </Dialog>
    </div>
  )
}
