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
import {api, type EscalationPolicy, type EscalationStep, type EscalationTarget} from '@/lib/api'
import {Card} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {PageHeader} from '@/components/ui/page-header'
import {EmptyState} from '@/components/ui/empty-state'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {useToast} from '@/hooks/useToast'
import {ListChecks, Plus, Trash2, Pencil, Clock, User, Users, ArrowDown, Repeat, Zap} from 'lucide-react'
import {useState} from 'react'
import {EscalationPolicyEditor, type EscalationPolicyData} from '@/components/on-call/EscalationPolicyEditor'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/on-call/escalation-policies')({
  component: EscalationPolicies,
})

// Step numbers are a categorical sequence — draw from the shared chart palette
// (literal classes so Tailwind emits them).
const stepBgColors = [
  'bg-chart-1/15', 'bg-chart-2/15', 'bg-chart-3/15',
  'bg-chart-4/15', 'bg-chart-5/15', 'bg-chart-6/15',
]
const stepTextColors = [
  'text-chart-1', 'text-chart-2', 'text-chart-3',
  'text-chart-4', 'text-chart-5', 'text-chart-6',
]

function EscalationPolicies() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [showEditor, setShowEditor] = useState(false)
  const [editingPolicy, setEditingPolicy] = useState<EscalationPolicy | null>(null)

  const {data: policies, isLoading} = useQuery({
    queryKey: ['escalation-policies'],
    queryFn: () => api.getEscalationPolicies(),
  })

  const {data: schedules} = useQuery({
    queryKey: ['on-call-schedules'],
    queryFn: () => api.getOnCallSchedules(),
  })

  const {data: orgMembers} = useQuery({
    queryKey: ['org-members'],
    queryFn: () => api.getOrgMembers(),
  })

  const users = orgMembers?.members?.map(m => ({id: m.userId, name: m.name || m.email})) || []
  const schedulesList = schedules?.map(s => ({id: s.id, name: s.name})) || []

  const createMutation = useMutation({
    mutationFn: (data: EscalationPolicyData) => api.createEscalationPolicy({
      name: data.name,
      description: data.description || undefined,
      repeatCount: data.repeatCount,
      steps: data.steps.map((step, idx) => ({
        stepOrder: idx,
        timeoutMinutes: step.timeoutMinutes,
        smsFallbackDelayMinutes: step.smsFallbackDelayMinutes ?? 2,
        targets: step.targets.map(t => ({
          targetType: t.targetType,
          targetId: t.targetId,
        })),
      })),
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['escalation-policies']})
      setShowEditor(false)
      toast({title: 'Policy Created', description: 'Escalation policy has been created.'})
    },
    onError: (e: Error) => toast({title: 'Error', description: e.message, variant: 'destructive'}),
  })

  const updateMutation = useMutation({
    mutationFn: ({id, data}: {id: string; data: EscalationPolicyData}) => api.updateEscalationPolicy(id, {
      name: data.name,
      description: data.description || undefined,
      repeatCount: data.repeatCount,
      steps: data.steps.map((step, idx) => ({
        stepOrder: idx,
        timeoutMinutes: step.timeoutMinutes,
        smsFallbackDelayMinutes: step.smsFallbackDelayMinutes ?? 2,
        targets: step.targets.map(t => ({
          targetType: t.targetType,
          targetId: t.targetId,
        })),
      })),
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['escalation-policies']})
      setShowEditor(false)
      setEditingPolicy(null)
      toast({title: 'Policy Updated', description: 'Escalation policy has been updated.'})
    },
    onError: (e: Error) => toast({title: 'Error', description: e.message, variant: 'destructive'}),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.deleteEscalationPolicy(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['escalation-policies']})
      toast({title: 'Policy Deleted', description: 'Escalation policy has been removed.'})
    },
    onError: (e: Error) => toast({title: 'Error', description: e.message, variant: 'destructive'}),
  })

  const handleSave = (data: EscalationPolicyData) => {
    if (editingPolicy) {
      updateMutation.mutate({id: editingPolicy.id, data})
    } else {
      createMutation.mutate(data)
    }
  }

  const handleEdit = (policy: EscalationPolicy) => {
    setEditingPolicy(policy)
    setShowEditor(true)
  }

  return (
    <div className="space-y-4">
      <PageHeader
        icon={ListChecks}
        title="Escalation policies"
        description="Define how incidents escalate through your team"
        actions={
          <Button size="sm" onClick={() => {setEditingPolicy(null); setShowEditor(true)}} className="gap-1.5 shrink-0">
            <Plus className="h-4 w-4" />
            Create policy
          </Button>
        }
      />

      {isLoading ? (
        <div className="flex items-center justify-center py-10">
          <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-primary" />
        </div>
      ) : policies && policies.length > 0 ? (
        <div className="space-y-3">
          {policies.map((policy) => (
            <Card key={policy.id} className="overflow-hidden hover:border-primary/30 transition-colors">
              <div className="p-3">
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-warning-bg flex-shrink-0">
                        <Zap className="h-4 w-4 text-warning-fg" />
                      </div>
                      <div className="min-w-0">
                        <h3 className="font-semibold text-base">{policy.name}</h3>
                        {policy.description && (
                          <p className="text-xs text-muted-foreground mt-0.5">{policy.description}</p>
                        )}
                      </div>
                    </div>
                    <div className="flex items-center gap-1.5 mt-2 ml-10">
                      <Badge variant="warning" size="sm" className="gap-1">
                        {policy.steps.length} step{policy.steps.length !== 1 ? 's' : ''}
                      </Badge>
                      {policy.repeatCount > 0 && (
                        <Badge variant="neutral" size="sm" className="gap-1">
                          <Repeat className="h-3 w-3" />
                          Repeat {policy.repeatCount}x
                        </Badge>
                      )}
                    </div>
                  </div>
                  <div className="flex items-center gap-1">
                    <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => handleEdit(policy)}>
                      <Pencil className="h-3 w-3" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-7 w-7 text-destructive hover:text-destructive"
                      onClick={() => {
                        if (confirm('Delete this escalation policy?')) deleteMutation.mutate(policy.id)
                      }}
                    >
                      <Trash2 className="h-3 w-3" />
                    </Button>
                  </div>
                </div>

                {/* Visual Step Flow */}
                {policy.steps.length > 0 && (
                  <div className="mt-3 ml-10">
                    {policy.steps
                      .sort((a, b) => a.stepOrder - b.stepOrder)
                      .map((step, stepIdx) => {
                        const isLast = stepIdx === policy.steps.length - 1
                        const isFirst = stepIdx === 0
                        const stepDescription = isFirst
                          ? 'First responders — notified immediately when an incident is triggered'
                          : isLast
                            ? `Final escalation — reached if step ${stepIdx} is not acknowledged`
                            : `Escalated to if step ${stepIdx} is not acknowledged`

                        return (
                          <div key={step.id} className="relative">
                            {/* Step card */}
                            <div className={cn(
                              'relative flex items-start gap-2 p-2.5 rounded-lg border',
                              'border-border/60 bg-card/60',
                            )}>
                              <div className={cn(
                                'flex items-center justify-center h-6 w-6 rounded-full text-[10px] font-bold flex-shrink-0 mt-0.5',
                                stepBgColors[stepIdx % stepBgColors.length],
                                stepTextColors[stepIdx % stepTextColors.length],
                              )}>
                                {stepIdx + 1}
                              </div>
                              <div className="flex-1 min-w-0">
                                <div className="flex flex-wrap items-center gap-1">
                                  <span className="text-xs font-medium">Notify</span>
                                  {step.targets.map((target) => (
                                    <Badge key={target.id} variant="secondary" className="text-xs gap-1">
                                      {target.targetType === 'USER' ? (
                                        <User className="h-3 w-3" />
                                      ) : (
                                        <Users className="h-3 w-3" />
                                      )}
                                      {target.targetName}
                                    </Badge>
                                  ))}
                                </div>
                                <p className="text-[11px] text-muted-foreground mt-1 leading-relaxed">
                                  {stepDescription}
                                </p>
                              </div>
                              <Badge variant="outline" className="text-xs gap-1 text-muted-foreground flex-shrink-0 mt-0.5">
                                <Clock className="h-3 w-3" />
                                {step.timeoutMinutes}m
                              </Badge>
                            </div>

                            {/* Connector between steps */}
                            {!isLast && (
                              <div className="flex items-center gap-2 py-1 pl-[11px]">
                                <div className="flex flex-col items-center w-2">
                                  <div className="w-px h-1.5 bg-muted-foreground/25" />
                                  <div className="w-px h-1 bg-muted-foreground/20" />
                                  <ArrowDown className="h-3.5 w-3.5 text-muted-foreground/40 -my-0.5" />
                                  <div className="w-px h-1 bg-muted-foreground/20" />
                                  <div className="w-px h-1.5 bg-muted-foreground/25" />
                                </div>
                                <span className="text-[11px] text-muted-foreground/60 italic">
                                  Wait {step.timeoutMinutes} min for acknowledgement...
                                </span>
                              </div>
                            )}
                          </div>
                        )
                      })}
                  </div>
                )}
              </div>
            </Card>
          ))}
        </div>
      ) : (
        <EmptyState
          icon={ListChecks}
          title="No escalation policies"
          description="Define how incidents escalate through your team. Set notification chains with configurable timeouts."
          action={
            <Button size="sm" onClick={() => setShowEditor(true)} className="gap-1.5">
              <Plus className="h-4 w-4" />
              Create your first policy
            </Button>
          }
        />
      )}

      {/* Policy Editor Dialog */}
      <Dialog open={showEditor} onOpenChange={(open) => {
        setShowEditor(open)
        if (!open) setEditingPolicy(null)
      }}>
        <DialogContent className="sm:max-w-[600px]">
          <DialogHeader>
            <DialogTitle>{editingPolicy ? 'Edit Policy' : 'Create Escalation Policy'}</DialogTitle>
            <DialogDescription>
              {editingPolicy
                ? 'Update the escalation steps and targets.'
                : 'Define who gets notified and when incidents escalate.'}
            </DialogDescription>
          </DialogHeader>
          <EscalationPolicyEditor
            initialData={editingPolicy ? {
              name: editingPolicy.name,
              description: editingPolicy.description || '',
              repeatCount: editingPolicy.repeatCount,
              steps: editingPolicy.steps.map((s: EscalationStep) => ({
                id: `step_${s.id}`,
                stepOrder: s.stepOrder,
                timeoutMinutes: s.timeoutMinutes,
                smsFallbackDelayMinutes: s.smsFallbackDelayMinutes ?? 2,
                targets: s.targets.map((t: EscalationTarget) => ({
                  id: `${t.targetType}_${t.targetId}_${t.id}`,
                  targetType: t.targetType,
                  targetId: t.targetId,
                  targetName: t.targetName,
                })),
              })),
            } : undefined}
            users={users}
            schedules={schedulesList}
            onSave={handleSave}
            onCancel={() => {setShowEditor(false); setEditingPolicy(null)}}
          />
        </DialogContent>
      </Dialog>
    </div>
  )
}
