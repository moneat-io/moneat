// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

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
import {useToast} from '@/hooks/use-toast'
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
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold">On-Call Schedules</h2>
          <p className="text-muted-foreground text-sm">Manage rotation schedules and participants</p>
        </div>
        <Button onClick={() => {setEditingSchedule(null); setShowEditor(true)}} className="bg-violet-600 hover:bg-violet-700">
          <Plus className="h-4 w-4 mr-2" />
          Create Schedule
        </Button>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16">
          <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-violet-500" />
        </div>
      ) : schedules && schedules.length > 0 ? (
        <div className="space-y-4">
          {schedules.map((schedule, idx) => {
            const RotIcon = rotationIcons[schedule.rotationType] || RotateCcw
            const isExpanded = expandedId === schedule.id
            return (
              <Card key={schedule.id} className="overflow-hidden transition-all hover:border-violet-500/30">
                <div className="p-5">
                  <div className="flex items-start justify-between gap-4">
                    <div className="flex items-start gap-4 min-w-0">
                      <div className={cn(
                        'flex-shrink-0 flex items-center justify-center h-12 w-12 rounded-xl text-white text-sm font-bold',
                        avatarColors[idx % avatarColors.length]
                      )}>
                        <Calendar className="h-5 w-5" />
                      </div>
                      <div className="min-w-0">
                        <h3 className="font-semibold text-lg">{schedule.name}</h3>
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
                        <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-green-500/10 border border-green-500/20">
                          <span className="relative flex h-2 w-2">
                            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75" />
                            <span className="relative inline-flex rounded-full h-2 w-2 bg-green-500" />
                          </span>
                          <span className="text-sm font-medium text-green-400">{schedule.currentOnCall.userName}</span>
                        </div>
                      )}
                      <Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => handleEdit(schedule)}>
                        <Pencil className="h-4 w-4" />
                      </Button>
                      <Button variant="ghost" size="icon" className="h-8 w-8 text-destructive hover:text-destructive" onClick={() => handleDelete(schedule.id)}>
                        <Trash2 className="h-4 w-4" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-8 w-8"
                        onClick={() => setExpandedId(isExpanded ? null : schedule.id)}
                      >
                        {isExpanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                      </Button>
                    </div>
                  </div>

                  {/* Participant Avatars */}
                  {schedule.participants.length > 0 && !isExpanded && (
                    <div className="flex items-center gap-2 mt-4 ml-16">
                      <Users className="h-4 w-4 text-muted-foreground" />
                      <div className="flex -space-x-2">
                        {schedule.participants.slice(0, 5).map((p, pIdx) => (
                          <Avatar key={p.id} className="h-7 w-7 border-2 border-background">
                            <AvatarFallback className={cn('text-[10px] text-white', avatarColors[pIdx % avatarColors.length])}>
                              {getInitials(p.userName)}
                            </AvatarFallback>
                          </Avatar>
                        ))}
                        {schedule.participants.length > 5 && (
                          <Avatar className="h-7 w-7 border-2 border-background">
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
                  <div className="border-t bg-muted/30 px-5 py-4">
                    <h4 className="text-sm font-medium mb-3 flex items-center gap-2">
                      <GripVertical className="h-4 w-4 text-muted-foreground" />
                      Rotation Order
                    </h4>
                    {schedule.participants.length > 0 ? (
                      <div className="space-y-2 ml-6">
                        {schedule.participants
                          .sort((a, b) => a.position - b.position)
                          .map((participant, pIdx) => (
                            <div key={participant.id} className="flex items-center gap-3 p-2 rounded-md bg-background border">
                              <Badge variant="outline" className="h-6 w-6 flex items-center justify-center p-0 text-xs">
                                {pIdx + 1}
                              </Badge>
                              <Avatar className="h-7 w-7">
                                <AvatarFallback className={cn('text-[10px] text-white', avatarColors[pIdx % avatarColors.length])}>
                                  {getInitials(participant.userName)}
                                </AvatarFallback>
                              </Avatar>
                              <span className="text-sm font-medium">{participant.userName}</span>
                              {schedule.currentOnCall?.userId === participant.userId && (
                                <Badge className="text-xs bg-green-500/15 text-green-400 border-green-500/30 ml-auto" variant="outline">
                                  On call
                                </Badge>
                              )}
                            </div>
                          ))}
                      </div>
                    ) : (
                      <p className="text-sm text-muted-foreground ml-6">No participants added yet</p>
                    )}

                    {schedule.overrides && schedule.overrides.length > 0 && (
                      <div className="mt-4">
                        <h4 className="text-sm font-medium mb-2">Active Overrides</h4>
                        <div className="space-y-2 ml-6">
                          {schedule.overrides.map((override) => (
                            <div key={override.id} className="flex items-center justify-between p-2 rounded-md bg-amber-500/5 border border-amber-500/20">
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
                      <div className="mt-4">
                        <h4 className="text-sm font-medium mb-2 flex items-center gap-2">
                          <Slack className="h-4 w-4 text-muted-foreground" />
                          Slack User Group
                        </h4>
                        <div className="ml-6">
                          {schedule.slackUsergroupId ? (
                            <div className="flex items-center justify-between p-2 rounded-md bg-blue-500/5 border border-blue-500/20">
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
                                <SelectTrigger className="w-[300px] h-9">
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
          <CardContent className="flex flex-col items-center justify-center py-16">
            <div className="inline-flex items-center justify-center h-16 w-16 rounded-full bg-violet-500/10 mb-4">
              <Calendar className="h-8 w-8 text-violet-500" />
            </div>
            <h3 className="text-lg font-semibold mb-1">No On-Call Schedules</h3>
            <p className="text-sm text-muted-foreground text-center mb-6 max-w-md">
              Create your first on-call schedule to define who's responsible for responding to incidents and when.
            </p>
            <Button onClick={() => setShowEditor(true)} className="bg-violet-600 hover:bg-violet-700">
              <Plus className="h-4 w-4 mr-2" />
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
