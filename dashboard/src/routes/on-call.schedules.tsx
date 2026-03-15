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
import {api, type OnCallSchedule, type OrganizationIntegration} from '@/lib/api'
import {Card, CardContent} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {Avatar, AvatarFallback} from '@/components/ui/avatar'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {useToast} from '@/hooks/useToast'
import {Calendar, Plus, Users, Clock, Trash2, Pencil, RotateCcw, GripVertical, ChevronDown, ChevronUp, Globe, Slack} from 'lucide-react'
import {useState} from 'react'
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

const rotationColors: Record<string, string> = {
  DAILY: 'bg-blue-500/15 text-blue-400 border-blue-500/30',
  WEEKLY: 'bg-violet-500/15 text-violet-400 border-violet-500/30',
  CUSTOM: 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
}

const avatarColors = [
  'bg-blue-600', 'bg-violet-600', 'bg-emerald-600', 'bg-amber-600',
  'bg-rose-600', 'bg-cyan-600', 'bg-indigo-600', 'bg-pink-600',
]

function getInitials(name: string) {
  return name.split(' ').map(n => n[0]).join('').toUpperCase().slice(0, 2)
}

function OnCallSchedules() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [showEditor, setShowEditor] = useState(false)
  const [editingSchedule, setEditingSchedule] = useState<OnCallSchedule | null>(null)
  const [expandedId, setExpandedId] = useState<number | null>(null)

  const {data: schedules, isLoading} = useQuery({
    queryKey: ['on-call-schedules'],
    queryFn: () => api.getOnCallSchedules(),
  })

  const {data: orgMembers} = useQuery({
    queryKey: ['org-members'],
    queryFn: () => api.getOrgMembers(),
  })

  const {data: integrations} = useQuery({
    queryKey: ['org-integrations'],
    queryFn: () => api.getIntegrations(),
  })

  const {data: slackUsergroups} = useQuery({
    queryKey: ['slack-usergroups'],
    queryFn: () => api.getSlackUsergroups(),
    enabled: integrations?.some((i: OrganizationIntegration) => i.integrationType === 'slack' && i.enabled) ?? false,
  })

  const users = orgMembers?.members?.map(m => ({id: m.userId, name: m.name || m.email})) || []
  const slackEnabled = integrations?.some((i: OrganizationIntegration) => i.integrationType === 'slack' && i.enabled) ?? false

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
    mutationFn: ({id, data}: {id: number; data: OnCallScheduleData}) => api.updateOnCallSchedule(id, {
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
    mutationFn: (id: number) => api.deleteOnCallSchedule(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['on-call-schedules']})
      toast({title: 'Schedule Deleted', description: 'On-call schedule has been removed.'})
    },
    onError: (e: Error) => toast({title: 'Error', description: e.message, variant: 'destructive'}),
  })

  const setUsergroupMutation = useMutation({
    mutationFn: ({scheduleId, usergroupId, usergroupHandle}: {scheduleId: number; usergroupId: string; usergroupHandle: string}) =>
      api.setScheduleSlackUsergroup(scheduleId, usergroupId, usergroupHandle),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['on-call-schedules']})
      toast({title: 'Slack User Group Set', description: 'Schedule will sync to this Slack user group.'})
    },
    onError: (e: Error) => toast({title: 'Error', description: e.message, variant: 'destructive'}),
  })

  const removeUsergroupMutation = useMutation({
    mutationFn: (scheduleId: number) => api.removeScheduleSlackUsergroup(scheduleId),
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

  const handleDelete = (id: number) => {
    if (confirm('Are you sure you want to delete this schedule?')) {
      deleteMutation.mutate(id)
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-xl font-bold">On-Call Schedules</h2>
          <p className="text-muted-foreground text-xs">Manage rotation schedules and participants</p>
        </div>
        <Button size="sm" onClick={() => {setEditingSchedule(null); setShowEditor(true)}} className="bg-violet-600 hover:bg-violet-700 gap-1.5 shrink-0">
          <Plus className="h-3 w-3" />
          Create Schedule
        </Button>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-10">
          <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-violet-500" />
        </div>
      ) : schedules && schedules.length > 0 ? (
        <div className="space-y-3">
          {schedules.map((schedule, idx) => {
            const RotIcon = rotationIcons[schedule.rotationType] || RotateCcw
            const isExpanded = expandedId === schedule.id
            return (
              <Card key={schedule.id} className="overflow-hidden transition-all hover:border-violet-500/30">
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
                          <Badge variant="outline" className={cn('text-xs gap-1', rotationColors[schedule.rotationType])}>
                            <RotIcon className="h-3 w-3" />
                            {schedule.rotationType} rotation
                          </Badge>
                          <Badge variant="outline" className="text-xs gap-1 text-muted-foreground">
                            <Globe className="h-3 w-3" />
                            {schedule.timezone}
                          </Badge>
                          <Badge variant="outline" className="text-xs gap-1 text-muted-foreground">
                            <Clock className="h-3 w-3" />
                            Handoff at {schedule.handoffTime.slice(0, 5)}
                          </Badge>
                        </div>
                      </div>
                    </div>

                    <div className="flex items-center gap-2 flex-shrink-0">
                      {schedule.currentOnCall && (
                        <div className="flex items-center gap-1.5 px-2 py-1 rounded-full bg-green-500/10 border border-green-500/20">
                          <span className="relative flex h-2 w-2">
                            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75" />
                            <span className="relative inline-flex rounded-full h-2 w-2 bg-green-500" />
                          </span>
                          <span className="text-xs font-medium text-green-400">{schedule.currentOnCall.userName}</span>
                        </div>
                      )}
                      <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => handleEdit(schedule)}>
                        <Pencil className="h-3 w-3" />
                      </Button>
                      <Button variant="ghost" size="icon" className="h-7 w-7 text-destructive hover:text-destructive" onClick={() => handleDelete(schedule.id)}>
                        <Trash2 className="h-3 w-3" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-7 w-7"
                        onClick={() => setExpandedId(isExpanded ? null : schedule.id)}
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
                              <Badge variant="outline" className="h-6 w-6 flex items-center justify-center p-0 text-xs">
                                {pIdx + 1}
                              </Badge>
                              <Avatar className="h-6 w-6">
                                <AvatarFallback className={cn('text-[10px] text-white', avatarColors[pIdx % avatarColors.length])}>
                                  {getInitials(participant.userName)}
                                </AvatarFallback>
                              </Avatar>
                              <span className="text-xs font-medium">{participant.userName}</span>
                              {schedule.currentOnCall?.userId === participant.userId && (
                                <Badge className="text-xs bg-green-500/15 text-green-400 border-green-500/30 ml-auto" variant="outline">
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
                            <div key={override.id} className="flex items-center justify-between p-1.5 rounded-md bg-amber-500/5 border border-amber-500/20">
                              <div className="flex items-center gap-2">
                                <Badge variant="outline" className="text-xs bg-amber-500/15 text-amber-400 border-amber-500/30">
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

                    {/* Slack User Group Sync */}
                    {slackEnabled && (
                      <div className="mt-3">
                        <h4 className="text-xs font-medium mb-1.5 flex items-center gap-1.5">
                          <Slack className="h-3 w-3 text-muted-foreground" />
                          Slack User Group
                        </h4>
                        <div className="ml-4">
                          {schedule.slackUsergroupId ? (
                            <div className="flex items-center justify-between p-1.5 rounded-md bg-blue-500/5 border border-blue-500/20">
                              <div className="flex items-center gap-2">
                                <Badge variant="outline" className="text-xs bg-blue-500/15 text-blue-400 border-blue-500/30">
                                  @{schedule.slackUsergroupHandle}
                                </Badge>
                                <span className="text-xs text-muted-foreground">Auto-syncing current on-call user</span>
                              </div>
                              <Button
                                variant="ghost"
                                size="sm"
                                className="h-7 text-xs text-destructive hover:text-destructive"
                                onClick={() => removeUsergroupMutation.mutate(schedule.id)}
                                disabled={removeUsergroupMutation.isPending}
                              >
                                Remove
                              </Button>
                            </div>
                          ) : (
                            <div className="flex items-center gap-2">
                              <Select
                                onValueChange={(value) => {
                                  const ug = slackUsergroups?.find(u => u.id === value)
                                  if (ug) {
                                    setUsergroupMutation.mutate({
                                      scheduleId: schedule.id,
                                      usergroupId: ug.id,
                                      usergroupHandle: ug.handle
                                    })
                                  }
                                }}
                                disabled={setUsergroupMutation.isPending || !slackUsergroups || slackUsergroups.length === 0}
                              >
                                <SelectTrigger className="w-[260px] h-8">
                                  <SelectValue placeholder={slackUsergroups && slackUsergroups.length === 0 ? "No user groups available" : "Select a user group"} />
                                </SelectTrigger>
                                <SelectContent>
                                  {slackUsergroups?.map(ug => (
                                    <SelectItem key={ug.id} value={ug.id}>
                                      <div className="flex flex-col">
                                        <span className="font-medium">@{ug.handle}</span>
                                        <span className="text-xs text-muted-foreground">{ug.name}</span>
                                      </div>
                                    </SelectItem>
                                  ))}
                                </SelectContent>
                              </Select>
                              <span className="text-xs text-muted-foreground">
                                Sync current on-call user to a Slack user group
                              </span>
                            </div>
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </Card>
            )
          })}
        </div>
      ) : (
        <Card className="border-2 border-dashed">
          <CardContent className="flex flex-col items-center justify-center py-10">
            <div className="inline-flex items-center justify-center h-12 w-12 rounded-full bg-violet-500/10 mb-3">
              <Calendar className="h-6 w-6 text-violet-500" />
            </div>
            <h3 className="text-base font-semibold mb-0.5">No On-Call Schedules</h3>
            <p className="text-xs text-muted-foreground text-center mb-4 max-w-md">
              Create your first on-call schedule to define who's responsible for responding to incidents and when.
            </p>
            <Button size="sm" onClick={() => setShowEditor(true)} className="bg-violet-600 hover:bg-violet-700 gap-1.5">
              <Plus className="h-3 w-3" />
              Create Your First Schedule
            </Button>
          </CardContent>
        </Card>
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
                .map((p: {userId: number}) => p.userId),
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
