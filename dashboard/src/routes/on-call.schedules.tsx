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

import {createFileRoute} from '@tanstack/react-router'
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {api, type OnCallSchedule, type SlackInstallationSummary} from '@/lib/api'
import {Card} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Badge, type BadgeProps} from '@/components/ui/badge'
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
import {Calendar, Plus, Users, Clock, Trash2, Pencil, RotateCcw, GripVertical, ChevronDown, ChevronUp, Globe, Slack, Loader2, AlertTriangle} from 'lucide-react'
import {useState, type ReactNode} from 'react'
import {ScheduleEditor, type OnCallScheduleData} from '@/components/on-call/ScheduleEditor'
import {cn} from '@/lib/utils'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

export const Route = createFileRoute('/on-call/schedules')({
  component: OnCallSchedules,
})

const rotationIcons: Record<string, typeof RotateCcw> = {
  DAILY: Clock,
  WEEKLY: Calendar,
  CUSTOM: RotateCcw,
}

// Rotation type is a neutral categorical label — use the accent/info tones so it
// reads as informational metadata, not a health state.
const rotationVariants: Record<string, BadgeProps['variant']> = {
  DAILY: 'info',
  WEEKLY: 'accent',
  CUSTOM: 'neutral',
}

// Categorical avatar tints from the shared chart palette (literal classes).
const avatarColors = [
  'bg-chart-1', 'bg-chart-2', 'bg-chart-3', 'bg-chart-4',
  'bg-chart-5', 'bg-chart-6', 'bg-chart-7', 'bg-chart-8',
]

function getInitials(name: string) {
  return name.split(' ').map(n => n[0]).join('').toUpperCase().slice(0, 2)
}

function OnCallSchedules() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [showEditor, setShowEditor] = useState(false)
  const [editingSchedule, setEditingSchedule] = useState<OnCallSchedule | null>(null)
  const [expandedId, setExpandedId] = useState<string | null>(null)

  const {data: schedules, isLoading} = useQuery({
    queryKey: ['on-call-schedules'],
    queryFn: () => api.getOnCallSchedules(),
  })

  const {data: orgMembers} = useQuery({
    queryKey: ['org-members'],
    queryFn: () => api.getOrgMembers(),
  })

  // Installation-aware Slack context. A Moneat Slack app can live in
  // several workspaces, so on-call sync is scoped to a chosen workspace rather
  // than one org-wide user-group list. Per-workspace user groups load lazily in
  // ScheduleSlackSync once a workspace is selected.
  const slackInstallationsQuery = useQuery({
    queryKey: ['slack-installations'],
    queryFn: () => api.getSlackInstallations(),
  })
  const slackInstallations = slackInstallationsQuery.data ?? []

  const users = orgMembers?.members?.map(m => ({id: m.userId, name: m.name || m.email})) || []

  const createMutation = useMutation({
    mutationFn: (data: OnCallScheduleData) => api.createOnCallSchedule({
      name: data.name,
      rotationType: data.rotationType,
      handoffTime: data.handoffTime,
      timezone: data.timezone,
      participants: data.participantIds.map((id, idx) => ({userId: id, position: idx})),
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['on-call-schedules']})
      setShowEditor(false)
      toast({title: 'Schedule Created', description: 'On-call schedule has been created.'})
    },
    onError: (e: Error) => toast({title: 'Error', description: e.message, variant: 'destructive'}),
  })

  const updateMutation = useMutation({
    mutationFn: ({id, data}: {id: string; data: OnCallScheduleData}) => api.updateOnCallSchedule(id, {
      name: data.name,
      rotationType: data.rotationType,
      handoffTime: data.handoffTime,
      timezone: data.timezone,
      participants: data.participantIds.map((uid, idx) => ({userId: uid, position: idx})),
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['on-call-schedules']})
      setShowEditor(false)
      setEditingSchedule(null)
      toast({title: 'Schedule Updated', description: 'On-call schedule has been updated.'})
    },
    onError: (e: Error) => toast({title: 'Error', description: e.message, variant: 'destructive'}),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.deleteOnCallSchedule(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['on-call-schedules']})
      toast({title: 'Schedule Deleted', description: 'On-call schedule has been removed.'})
    },
    onError: (e: Error) => toast({title: 'Error', description: e.message, variant: 'destructive'}),
  })

  const setUsergroupMutation = useMutation({
    mutationFn: ({scheduleId, usergroupId, usergroupHandle, slackInstallationId}: {
      scheduleId: string
      usergroupId: string
      usergroupHandle: string
      slackInstallationId: string
    }) =>
      api.setScheduleSlackUsergroup(scheduleId, usergroupId, usergroupHandle, slackInstallationId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['on-call-schedules']})
      toast({title: 'Slack user group set', description: 'Schedule will sync to this Slack user group.'})
    },
    onError: (e: Error) => toast({title: 'Error', description: e.message, variant: 'destructive'}),
  })

  const removeUsergroupMutation = useMutation({
    mutationFn: (scheduleId: string) => api.removeScheduleSlackUsergroup(scheduleId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['on-call-schedules']})
      toast({title: 'Slack User Group Removed', description: 'Schedule will no longer sync.'})
    },
    onError: (e: Error) => toast({title: 'Error', description: e.message, variant: 'destructive'}),
  })

  const handleSave = (data: OnCallScheduleData) => {
    if (editingSchedule) {
      updateMutation.mutate({id: editingSchedule.id, data})
    } else {
      createMutation.mutate(data)
    }
  }

  const handleEdit = (schedule: OnCallSchedule) => {
    setEditingSchedule(schedule)
    setShowEditor(true)
  }

  const handleDelete = (id: string) => {
    if (confirm('Are you sure you want to delete this schedule?')) {
      deleteMutation.mutate(id)
    }
  }

  return (
    <div className="space-y-4">
      <PageHeader
        icon={Calendar}
        title="On-call schedules"
        description="Manage rotation schedules and participants"
        actions={
          <Button size="sm" onClick={() => {setEditingSchedule(null); setShowEditor(true)}} className="gap-1.5 shrink-0">
            <Plus className="h-4 w-4" />
            Create schedule
          </Button>
        }
      />

      {isLoading ? (
        <div className="flex items-center justify-center py-10">
          <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-primary" />
        </div>
      ) : schedules && schedules.length > 0 ? (
        <div className="space-y-3">
          {schedules.map((schedule, idx) => {
            const RotIcon = rotationIcons[schedule.rotationType] || RotateCcw
            const isExpanded = expandedId === schedule.id
            const hasSlackMapping = !!schedule.slackUsergroupId
            // Show the Slack section when it can act: an existing mapping (even to a
            // now-removed/disabled workspace), any connected workspace to link, or
            // while the workspace list is still loading/errored so we surface state.
            const showSlack =
              hasSlackMapping ||
              slackInstallations.length > 0 ||
              slackInstallationsQuery.isLoading ||
              slackInstallationsQuery.isError
            return (
              <Card key={schedule.id} className="overflow-hidden transition-colors hover:border-primary/30">
                <div className="p-3">
                  <div className="flex items-start justify-between gap-3">
                    <div className="flex items-start gap-3 min-w-0">
                      <div className={cn(
                        'flex-shrink-0 flex items-center justify-center h-9 w-9 rounded-lg text-white text-xs font-bold',
                        avatarColors[idx % avatarColors.length]
                      )}>
                        <Calendar className="h-4 w-4" />
                      </div>
                      <div className="min-w-0">
                        <h3 className="font-semibold text-base">{schedule.name}</h3>
                        <div className="flex flex-wrap items-center gap-2 mt-1.5">
                          <Badge variant={rotationVariants[schedule.rotationType] ?? 'neutral'} size="sm" className="gap-1">
                            <RotIcon className="h-3 w-3" />
                            {schedule.rotationType} rotation
                          </Badge>
                          <Badge variant="neutral" size="sm" className="gap-1">
                            <Globe className="h-3 w-3" />
                            {schedule.timezone}
                          </Badge>
                          <Badge variant="neutral" size="sm" className="gap-1">
                            <Clock className="h-3 w-3" />
                            Handoff at {schedule.handoffTime.slice(0, 5)}
                          </Badge>
                        </div>
                      </div>
                    </div>

                    <div className="flex items-center gap-2 flex-shrink-0">
                      {schedule.currentOnCall && (
                        <div className="flex items-center gap-1.5 px-2 py-1 rounded-full bg-success-bg border border-success-border">
                          <StatusDot tone="success" pulse />
                          <span className="text-xs font-medium text-success-fg">{schedule.currentOnCall.userName}</span>
                        </div>
                      )}
                      <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => handleEdit(schedule)} aria-label={`Edit ${schedule.name}`}>
                        <Pencil className="h-3 w-3" />
                      </Button>
                      <Button variant="ghost" size="icon" className="h-7 w-7 text-destructive hover:text-destructive" onClick={() => handleDelete(schedule.id)} aria-label={`Delete ${schedule.name}`}>
                        <Trash2 className="h-3 w-3" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-7 w-7"
                        onClick={() => setExpandedId(isExpanded ? null : schedule.id)}
                        aria-label={`${isExpanded ? 'Collapse' : 'Expand'} ${schedule.name} details`}
                        aria-expanded={isExpanded}
                      >
                        {isExpanded ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
                      </Button>
                    </div>
                  </div>

                  {/* Participant Avatars */}
                  {schedule.participants.length > 0 && !isExpanded && (
                    <div className="flex items-center gap-1.5 mt-3 ml-12">
                      <Users className="h-4 w-4 text-muted-foreground" />
                      <div className="flex -space-x-2">
                        {schedule.participants.slice(0, 5).map((p, pIdx) => (
                          <Avatar key={p.id} className="h-6 w-6 border-2 border-background">
                            <AvatarFallback className={cn('text-[10px] text-white', avatarColors[pIdx % avatarColors.length])}>
                              {getInitials(p.userName)}
                            </AvatarFallback>
                          </Avatar>
                        ))}
                        {schedule.participants.length > 5 && (
                          <Avatar className="h-6 w-6 border-2 border-background">
                            <AvatarFallback className="text-[10px] bg-muted">
                              +{schedule.participants.length - 5}
                            </AvatarFallback>
                          </Avatar>
                        )}
                      </div>
                      <span className="text-xs text-muted-foreground">
                        {schedule.participants.length} participant{schedule.participants.length !== 1 ? 's' : ''}
                      </span>
                    </div>
                  )}
                </div>

                {/* Expanded Details */}
                {isExpanded && (
                  <div className="border-t bg-muted/30 px-3 py-3">
                    <h4 className="text-xs font-medium mb-2 flex items-center gap-1.5">
                      <GripVertical className="h-3 w-3 text-muted-foreground" />
                      Rotation Order
                    </h4>
                    {schedule.participants.length > 0 ? (
                      <div className="space-y-1.5 ml-4">
                        {schedule.participants
                          .sort((a, b) => a.position - b.position)
                          .map((participant, pIdx) => (
                            <div key={participant.id} className="flex items-center gap-2 p-1.5 rounded-md bg-background border">
                              <Badge variant="neutral" size="sm" className="h-6 w-6 flex items-center justify-center p-0">
                                {pIdx + 1}
                              </Badge>
                              <Avatar className="h-6 w-6">
                                <AvatarFallback className={cn('text-[10px] text-white', avatarColors[pIdx % avatarColors.length])}>
                                  {getInitials(participant.userName)}
                                </AvatarFallback>
                              </Avatar>
                              <span className="text-xs font-medium">{participant.userName}</span>
                              {schedule.currentOnCall?.userId === participant.userId && (
                                <Badge variant="success" size="sm" className="ml-auto">
                                  On call
                                </Badge>
                              )}
                            </div>
                          ))}
                      </div>
                    ) : (
                      <p className="text-xs text-muted-foreground ml-4">No participants added yet</p>
                    )}

                    {schedule.overrides && schedule.overrides.length > 0 && (
                      <div className="mt-3">
                        <h4 className="text-xs font-medium mb-1.5">Active Overrides</h4>
                        <div className="space-y-1.5 ml-4">
                          {schedule.overrides.map((override) => (
                            <div key={override.id} className="flex items-center justify-between p-1.5 rounded-md bg-warning-bg border border-warning-border">
                              <div className="flex items-center gap-2">
                                <Badge variant="warning" size="sm">
                                  Override
                                </Badge>
                                <span className="text-sm">{override.userName}</span>
                              </div>
                              <span className="text-xs text-muted-foreground">
                                {new Date(override.startAt).toLocaleDateString()} – {new Date(override.endAt).toLocaleDateString()}
                              </span>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}

                    {/* Slack user-group sync (installation-aware) */}
                    {showSlack && (
                      <ScheduleSlackSync
                        schedule={schedule}
                        installations={slackInstallations}
                        isLoading={slackInstallationsQuery.isLoading}
                        isError={slackInstallationsQuery.isError}
                        onSet={(args) => setUsergroupMutation.mutate(args)}
                        onRemove={(id) => removeUsergroupMutation.mutate(id)}
                        isSetting={setUsergroupMutation.isPending}
                        isRemoving={removeUsergroupMutation.isPending}
                      />
                    )}
                  </div>
                )}
              </Card>
            )
          })}
        </div>
      ) : (
        <EmptyState
          icon={Calendar}
          title="No on-call schedules"
          description="Create your first on-call schedule to define who's responsible for responding to incidents and when."
          action={
            <Button size="sm" onClick={() => setShowEditor(true)} className="gap-1.5">
              <Plus className="h-4 w-4" />
              Create your first schedule
            </Button>
          }
        />
      )}

      {/* Schedule Editor Dialog */}
      <Dialog open={showEditor} onOpenChange={(open) => {
        setShowEditor(open)
        if (!open) setEditingSchedule(null)
      }}>
        <DialogContent className="sm:max-w-[550px]">
          <DialogHeader>
            <DialogTitle>{editingSchedule ? 'Edit Schedule' : 'Create Schedule'}</DialogTitle>
            <DialogDescription>
              {editingSchedule
                ? 'Update the schedule configuration and rotation order.'
                : 'Set up a new on-call rotation schedule for your team.'}
            </DialogDescription>
          </DialogHeader>
          <ScheduleEditor
            initialData={editingSchedule ? {
              name: editingSchedule.name,
              rotationType: editingSchedule.rotationType,
              handoffTime: editingSchedule.handoffTime,
              timezone: editingSchedule.timezone,
              participantIds: editingSchedule.participants
                .sort((a: {position: number}, b: {position: number}) => a.position - b.position)
                .map((p: {userId: string}) => p.userId),
            } : undefined}
            users={users}
            onSave={handleSave}
            onCancel={() => {setShowEditor(false); setEditingSchedule(null)}}
          />
        </DialogContent>
      </Dialog>
    </div>
  )
}

// Human-readable workspace name, falling back through team → enterprise → ids so a
// workspace is always identifiable even before names are backfilled.
function workspaceName(installation: SlackInstallationSummary): string {
  return (
    installation.teamName ??
    installation.enterpriseName ??
    installation.teamId ??
    installation.enterpriseId ??
    'Slack workspace'
  )
}

// Per-schedule Slack user-group sync. Kept out of the main list to hold the
// two-step (workspace → user group) selection state and lazy per-workspace
// user-group query local to the one expanded schedule.
function ScheduleSlackSync({
  schedule,
  installations,
  isLoading,
  isError,
  onSet,
  onRemove,
  isSetting,
  isRemoving,
}: {
  readonly schedule: OnCallSchedule
  readonly installations: readonly SlackInstallationSummary[]
  readonly isLoading: boolean
  readonly isError: boolean
  readonly onSet: (args: {
    scheduleId: string
    usergroupId: string
    usergroupHandle: string
    slackInstallationId: string
  }) => void
  readonly onRemove: (scheduleId: string) => void
  readonly isSetting: boolean
  readonly isRemoving: boolean
}) {
  const hasMapping = !!schedule.slackUsergroupId
  const enabledInstallations = installations.filter(i => i.enabled)
  // Auto-pick the only enabled workspace, but keep it a selectable value so the
  // chosen workspace stays visible (and switchable when there are several).
  const onlyEnabledId = enabledInstallations.length === 1 ? enabledInstallations[0].id : ''
  const [chosenInstallationId, setChosenInstallationId] = useState('')
  const effectiveInstallationId = chosenInstallationId || onlyEnabledId

  const usergroupsQuery = useQuery({
    queryKey: ['slack-installation-usergroups', effectiveInstallationId],
    queryFn: () => api.getSlackInstallationUsergroups(effectiveInstallationId),
    enabled: !hasMapping && effectiveInstallationId !== '',
  })

  let body: ReactNode
  if (hasMapping) {
    const mapped = installations.find(i => i.id === schedule.slackInstallationId)
    let workspaceLabel: ReactNode
    let helper: ReactNode = null
    if (isLoading) {
      workspaceLabel = <span className="text-muted-foreground">Checking workspace…</span>
    } else if (mapped && mapped.enabled) {
      workspaceLabel = <span className="text-muted-foreground">in {workspaceName(mapped)}</span>
      helper = <p className="mt-1 text-xs text-muted-foreground">Auto-syncing the current on-call user to this group.</p>
    } else if (mapped && !mapped.enabled) {
      workspaceLabel = (
        <span className="inline-flex items-center gap-1 text-warning-fg">
          <AlertTriangle className="h-3 w-3" aria-hidden="true" />
          {workspaceName(mapped)} · delivery disabled
        </span>
      )
      helper = <p className="mt-1 text-xs text-muted-foreground">Enable this workspace in Settings to resume syncing.</p>
    } else if (schedule.slackInstallationId) {
      workspaceLabel = (
        <span className="inline-flex items-center gap-1 text-warning-fg">
          <AlertTriangle className="h-3 w-3" aria-hidden="true" />
          Workspace no longer connected
        </span>
      )
      helper = <p className="mt-1 text-xs text-muted-foreground">Reconnect the workspace in Settings, or remove this mapping.</p>
    } else {
      // Legacy mapping created before installations were tracked.
      workspaceLabel = <span className="text-muted-foreground">Workspace not recorded</span>
    }

    body = (
      <div className="p-1.5 rounded-md bg-info-bg border border-info-border">
        <div className="flex items-start justify-between gap-2">
          <div className="flex flex-wrap items-center gap-2 min-w-0">
            <Badge variant="info" size="sm">@{schedule.slackUsergroupHandle}</Badge>
            <span className="text-xs">{workspaceLabel}</span>
          </div>
          <Button
            variant="ghost"
            size="sm"
            className="h-7 text-xs text-destructive hover:text-destructive shrink-0"
            onClick={() => onRemove(schedule.id)}
            disabled={isRemoving}
            aria-label={`Remove Slack user group from ${schedule.name}`}
          >
            Remove
          </Button>
        </div>
        {helper}
      </div>
    )
  } else if (isLoading) {
    body = (
      <div className="flex items-center gap-2 text-xs text-muted-foreground" role="status" aria-live="polite">
        <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
        Loading Slack workspaces…
      </div>
    )
  } else if (isError) {
    body = (
      <p className="text-xs text-warning-fg">
        Couldn’t load Slack workspaces. Try again shortly.
      </p>
    )
  } else if (enabledInstallations.length === 0) {
    body = (
      <p className="text-xs text-muted-foreground">
        {installations.length > 0
          ? 'All connected Slack workspaces are disabled. Enable one in Settings to sync on-call.'
          : 'Connect a Slack workspace in Settings to sync the on-call user to a group.'}
      </p>
    )
  } else {
    const usergroupPlaceholder = effectiveInstallationId === ''
      ? 'Select a workspace first'
      : usergroupsQuery.isLoading
        ? 'Loading user groups…'
        : usergroupsQuery.isError
          ? 'Couldn’t load user groups'
          : (usergroupsQuery.data?.length ?? 0) === 0
            ? 'No user groups available'
            : 'Select a user group'

    body = (
      <div className="space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <Select value={effectiveInstallationId} onValueChange={setChosenInstallationId}>
            <SelectTrigger className="w-[200px] h-8" aria-label="Slack workspace">
              <SelectValue placeholder="Select a workspace" />
            </SelectTrigger>
            <SelectContent>
              {enabledInstallations.map(inst => (
                <SelectItem key={inst.id} value={inst.id}>{workspaceName(inst)}</SelectItem>
              ))}
            </SelectContent>
          </Select>

          <Select
            value=""
            onValueChange={(value) => {
              const ug = usergroupsQuery.data?.find(u => u.id === value)
              if (ug && effectiveInstallationId !== '') {
                onSet({
                  scheduleId: schedule.id,
                  usergroupId: ug.id,
                  usergroupHandle: ug.handle,
                  slackInstallationId: effectiveInstallationId,
                })
              }
            }}
            disabled={
              isSetting ||
              effectiveInstallationId === '' ||
              usergroupsQuery.isLoading ||
              usergroupsQuery.isError ||
              (usergroupsQuery.data?.length ?? 0) === 0
            }
          >
            <SelectTrigger className="w-[240px] h-8" aria-label="Slack user group">
              <SelectValue placeholder={usergroupPlaceholder} />
            </SelectTrigger>
            <SelectContent>
              {usergroupsQuery.data?.map(ug => (
                <SelectItem key={ug.id} value={ug.id}>
                  <div className="flex flex-col">
                    <span className="font-medium">@{ug.handle}</span>
                    <span className="text-xs text-muted-foreground">{ug.name}</span>
                  </div>
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        {usergroupsQuery.isError ? (
          <p className="text-xs text-warning-fg">Couldn’t load user groups for this workspace.</p>
        ) : (
          <p className="text-xs text-muted-foreground">
            {enabledInstallations.length > 1
              ? 'Choose a workspace, then a user group to sync the current on-call user.'
              : 'Sync the current on-call user to a Slack user group.'}
          </p>
        )}
      </div>
    )
  }

  return (
    <div className="mt-3">
      <h4 className="text-xs font-medium mb-1.5 flex items-center gap-1.5">
        <Slack className="h-3 w-3 text-muted-foreground" aria-hidden="true" />
        Slack user group
      </h4>
      <div className="ml-4">{body}</div>
    </div>
  )
}
