import {createFileRoute} from '@tanstack/react-router'
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Card, CardContent} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {useToast} from '@/hooks/use-toast'
import {ListChecks, Plus, Trash2, Pencil, Clock, User, Users, ArrowDown, Repeat, Zap} from 'lucide-react'
import {useState} from 'react'
import {EscalationPolicyEditor, type EscalationPolicyData} from '@/components/on-call/EscalationPolicyEditor'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/on-call/escalation-policies')({
  component: EscalationPolicies,
})

const stepColors = [
  'border-l-blue-500', 'border-l-violet-500', 'border-l-amber-500',
  'border-l-emerald-500', 'border-l-rose-500', 'border-l-cyan-500',
]
const stepBgColors = [
  'bg-blue-500/10', 'bg-violet-500/10', 'bg-amber-500/10',
  'bg-emerald-500/10', 'bg-rose-500/10', 'bg-cyan-500/10',
]
const stepTextColors = [
  'text-blue-500', 'text-violet-500', 'text-amber-500',
  'text-emerald-500', 'text-rose-500', 'text-cyan-500',
]

function EscalationPolicies() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [showEditor, setShowEditor] = useState(false)
  const [editingPolicy, setEditingPolicy] = useState<any>(null)

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
    onError: (e: any) => toast({title: 'Error', description: e.message, variant: 'destructive'}),
  })

  const updateMutation = useMutation({
    mutationFn: ({id, data}: {id: number; data: EscalationPolicyData}) => api.updateEscalationPolicy(id, {
      name: data.name,
      description: data.description || undefined,
      repeatCount: data.repeatCount,
      steps: data.steps.map((step, idx) => ({
        stepOrder: idx,
        timeoutMinutes: step.timeoutMinutes,
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
    onError: (e: any) => toast({title: 'Error', description: e.message, variant: 'destructive'}),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteEscalationPolicy(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['escalation-policies']})
      toast({title: 'Policy Deleted', description: 'Escalation policy has been removed.'})
    },
    onError: (e: any) => toast({title: 'Error', description: e.message, variant: 'destructive'}),
  })

  const handleSave = (data: EscalationPolicyData) => {
    if (editingPolicy) {
      updateMutation.mutate({id: editingPolicy.id, data})
    } else {
      createMutation.mutate(data)
    }
  }

  const handleEdit = (policy: any) => {
    setEditingPolicy(policy)
    setShowEditor(true)
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold">Escalation Policies</h2>
          <p className="text-muted-foreground text-sm">Define how incidents escalate through your team</p>
        </div>
        <Button onClick={() => {setEditingPolicy(null); setShowEditor(true)}} className="bg-amber-600 hover:bg-amber-700">
          <Plus className="h-4 w-4 mr-2" />
          Create Policy
        </Button>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-16">
          <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-amber-500" />
        </div>
      ) : policies && policies.length > 0 ? (
        <div className="space-y-4">
          {policies.map((policy) => (
            <Card key={policy.id} className="overflow-hidden hover:border-amber-500/30 transition-all">
              <div className="p-5">
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0">
                    <div className="flex items-center gap-3">
                      <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-amber-500/15 flex-shrink-0">
                        <Zap className="h-5 w-5 text-amber-500" />
                      </div>
                      <div>
                        <h3 className="font-semibold text-lg">{policy.name}</h3>
                        {policy.description && (
                          <p className="text-sm text-muted-foreground mt-0.5">{policy.description}</p>
                        )}
                      </div>
                    </div>
                    <div className="flex items-center gap-2 mt-3 ml-[52px]">
                      <Badge variant="outline" className="text-xs gap-1 bg-amber-500/10 text-amber-400 border-amber-500/30">
                        {policy.steps.length} step{policy.steps.length !== 1 ? 's' : ''}
                      </Badge>
                      {policy.repeatCount > 0 && (
                        <Badge variant="outline" className="text-xs gap-1 text-muted-foreground">
                          <Repeat className="h-3 w-3" />
                          Repeat {policy.repeatCount}x
                        </Badge>
                      )}
                    </div>
                  </div>
                  <div className="flex items-center gap-1">
                    <Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => handleEdit(policy)}>
                      <Pencil className="h-4 w-4" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-8 w-8 text-destructive hover:text-destructive"
                      onClick={() => {
                        if (confirm('Delete this escalation policy?')) deleteMutation.mutate(policy.id)
                      }}
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </div>
                </div>

                {/* Visual Step Flow */}
                {policy.steps.length > 0 && (
                  <div className="mt-4 ml-[52px] space-y-1">
                    {policy.steps
                      .sort((a, b) => a.stepOrder - b.stepOrder)
                      .map((step, stepIdx) => (
                        <div key={step.id}>
                          <div className={cn(
                            'flex items-center gap-3 p-3 rounded-lg border-l-4',
                            stepColors[stepIdx % stepColors.length],
                            'bg-card'
                          )}>
                            <div className={cn(
                              'flex items-center justify-center h-7 w-7 rounded-full text-xs font-bold',
                              stepBgColors[stepIdx % stepBgColors.length],
                              stepTextColors[stepIdx % stepTextColors.length],
                            )}>
                              {stepIdx + 1}
                            </div>
                            <div className="flex-1 min-w-0">
                              <div className="flex flex-wrap items-center gap-1.5">
                                <span className="text-sm font-medium">Notify</span>
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
                            </div>
                            <Badge variant="outline" className="text-xs gap-1 text-muted-foreground flex-shrink-0">
                              <Clock className="h-3 w-3" />
                              {step.timeoutMinutes}m
                            </Badge>
                          </div>
                          {stepIdx < policy.steps.length - 1 && (
                            <div className="flex items-center justify-center py-0.5">
                              <ArrowDown className="h-3 w-3 text-muted-foreground" />
                            </div>
                          )}
                        </div>
                      ))}
                  </div>
                )}
              </div>
            </Card>
          ))}
        </div>
      ) : (
        <Card className="border-2 border-dashed">
          <CardContent className="flex flex-col items-center justify-center py-16">
            <div className="inline-flex items-center justify-center h-16 w-16 rounded-full bg-amber-500/10 mb-4">
              <ListChecks className="h-8 w-8 text-amber-500" />
            </div>
            <h3 className="text-lg font-semibold mb-1">No Escalation Policies</h3>
            <p className="text-sm text-muted-foreground text-center mb-6 max-w-md">
              Define how incidents escalate through your team. Set notification chains with configurable timeouts.
            </p>
            <Button onClick={() => setShowEditor(true)} className="bg-amber-600 hover:bg-amber-700">
              <Plus className="h-4 w-4 mr-2" />
              Create Your First Policy
            </Button>
          </CardContent>
        </Card>
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
              steps: editingPolicy.steps.map((s: any) => ({
                id: `step_${s.id}`,
                stepOrder: s.stepOrder,
                timeoutMinutes: s.timeoutMinutes,
                targets: s.targets.map((t: any) => ({
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
