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

import {useMemo, useState} from 'react'
import {createFileRoute, redirect} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {
  Activity,
  Bell,
  CheckCircle2,
  Clock3,
  Code2,
  Filter,
  GitBranch,
  Hash,
  Eye,
  Loader2,
  Mail,
  MessageSquare,
  Plus,
  Save,
  Send,
  Trash2,
  Workflow,
  X,
} from 'lucide-react'
import {api} from '@/lib/api'
import type {
  WorkflowCatalogResponse,
  WorkflowConditionConfig,
  WorkflowOperationDefinition,
  WorkflowPreviewResponse,
  WorkflowRequest,
  WorkflowResponse,
  WorkflowRunResponse,
  WorkflowScopeReferenceDefinition,
  WorkflowStepConfig,
  WorkflowStepDefinition,
  WorkflowStepPreview,
  WorkflowTestMessageResponse,
  WorkflowTriggerDefinition,
} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Switch} from '@/components/ui/switch'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Textarea} from '@/components/ui/textarea'
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip'
import {useToast} from '@/hooks/useToast'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/workflows')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: WorkflowsPage,
})

interface WorkflowFormState {
  name: string
  triggerName: string
  enabled: boolean
  conditions: WorkflowConditionConfig[]
  steps: WorkflowStepConfig[]
  onceForTemplate: string
}

type WorkflowEditorPanel = 'trigger' | 'conditions' | 'delay' | `step:${number}`

function isDefaultWorkflow(workflow: WorkflowResponse): boolean {
  return Boolean(workflow.system_key)
}

const defaultMessage = [
  '{{alert.title}}',
  '',
  'Priority: {{alert.priority}}',
  'Source: {{alert.source}}',
  'View: {{alert.url}}',
].join('\n')

function emptyForm(catalog?: WorkflowCatalogResponse): WorkflowFormState {
  const triggerName = catalog?.triggers[0]?.name ?? 'alert.triggered'
  const onceForTemplate = catalog?.triggers[0]?.default_once_for_template.join(', ') ?? 'alert.deduplication_key'
  return {
    name: '',
    triggerName,
    enabled: true,
    conditions: [],
    steps: [],
    onceForTemplate,
  }
}

function workflowToForm(workflow: WorkflowResponse): WorkflowFormState {
  return {
    name: workflow.name,
    triggerName: workflow.trigger_name,
    enabled: workflow.enabled,
    conditions: workflow.conditions,
    steps: workflow.steps,
    onceForTemplate: workflow.once_for_template.join(', '),
  }
}

function formToRequest(form: WorkflowFormState): WorkflowRequest {
  return {
    name: form.name.trim(),
    trigger_name: form.triggerName,
    enabled: form.enabled,
    conditions: form.conditions,
    steps: form.steps,
    once_for_template: splitReferences(form.onceForTemplate),
  }
}

function formToPreviewRequest(form: WorkflowFormState) {
  return {
    trigger_name: form.triggerName,
    steps: form.steps,
  }
}

function formToStepPreviewRequest(form: WorkflowFormState, step: WorkflowStepConfig) {
  return {
    trigger_name: form.triggerName,
    steps: [step],
  }
}

function workflowTestMessageSummary(response: WorkflowTestMessageResponse): string {
  const sentCount = response.results.filter((result) => result.status === 'sent').length
  const skippedCount = response.results.filter((result) => result.status === 'skipped').length
  const failed = response.results.filter((result) => result.status === 'failed')
  if (failed.length > 0) {
    return failed
      .map((result) => `${previewChannelLabel(result.channel)}: ${result.error_message ?? 'not sent'}`)
      .join('; ')
  }
  const parts = [`${sentCount} sent`]
  if (skippedCount > 0) parts.push(`${skippedCount} skipped`)
  return parts.join(', ')
}

function splitReferences(value: string): string[] {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

function triggerByName(
  catalog: WorkflowCatalogResponse | undefined,
  triggerName: string
): WorkflowTriggerDefinition | undefined {
  return catalog?.triggers.find((trigger) => trigger.name === triggerName)
}

function stepByName(
  catalog: WorkflowCatalogResponse | undefined,
  stepName: string
): WorkflowStepDefinition | undefined {
  return catalog?.steps.find((step) => step.name === stepName)
}

function referenceByName(
  trigger: WorkflowTriggerDefinition | undefined,
  reference: string
): WorkflowScopeReferenceDefinition | undefined {
  return trigger?.scope.find((item) => item.name === reference)
}

function operationsForReference(
  catalog: WorkflowCatalogResponse | undefined,
  reference: WorkflowScopeReferenceDefinition | undefined
): WorkflowOperationDefinition[] {
  const resource = catalog?.resources.find((item) => item.type === reference?.type)
  return resource?.operations ?? []
}

function operationRequiresValue(operation: string): boolean {
  return operation !== 'is_set' && operation !== 'is_not_set'
}

function defaultStepParams(step: WorkflowStepDefinition): Record<string, string> {
  return Object.fromEntries(
    step.params.map((param) => {
      if (param.name === 'subject') return [param.name, 'Moneat workflow: {{alert.title}}']
      if (param.name === 'title') return [param.name, 'Moneat workflow']
      return [param.name, defaultMessage]
    })
  )
}

function runStatusClasses(status: string): string {
  if (status === 'complete') return 'bg-emerald-500/15 text-emerald-500 border-emerald-500/30'
  if (status === 'failed') return 'bg-red-500/15 text-red-500 border-red-500/30'
  return 'bg-amber-500/15 text-amber-500 border-amber-500/30'
}

function StepIconGlyph({stepName, className}: {stepName: string; className?: string}) {
  if (stepName.includes('email')) return <Mail className={className} />
  if (stepName.includes('slack')) return <MessageSquare className={className} />
  if (stepName.includes('discord')) return <Bell className={className} />
  return <Code2 className={className} />
}

function formatDate(value?: string | null): string {
  if (!value) return 'Never'
  return new Date(value).toLocaleString()
}

function conditionLabel(
  condition: WorkflowConditionConfig,
  catalog?: WorkflowCatalogResponse,
  trigger?: WorkflowTriggerDefinition
): string {
  const reference = referenceByName(trigger, condition.reference)
  const operation = operationsForReference(catalog, reference)
    .find((item) => item.name === condition.operation)
  const base = `${reference?.label ?? condition.reference} ${operation?.label ?? condition.operation}`
  if (!operationRequiresValue(condition.operation)) return base
  return condition.value ? `${base} ${condition.value}` : `${base}...`
}

function conditionsSummary(
  conditions: WorkflowConditionConfig[],
  catalog?: WorkflowCatalogResponse,
  trigger?: WorkflowTriggerDefinition
): string {
  if (conditions.length === 0) return 'Then applies to all alerts'
  if (conditions.length === 1) return `Then applies when ${conditionLabel(conditions[0], catalog, trigger)}`
  return `Then applies when ${conditions.length} conditions match`
}

function WorkflowsPage() {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [selectedWorkflowId, setSelectedWorkflowId] = useState<number | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingWorkflow, setEditingWorkflow] = useState<WorkflowResponse | null>(null)
  const [form, setForm] = useState<WorkflowFormState>(() => emptyForm())

  const {data: catalog, isLoading: catalogLoading} = useQuery({
    queryKey: ['workflow-catalog'],
    queryFn: () => api.getWorkflowCatalog(),
  })

  const {data: workflows = [], isLoading: workflowsLoading} = useQuery({
    queryKey: ['workflows'],
    queryFn: () => api.getWorkflows(),
  })

  const selectedWorkflow = workflows.find((workflow) => workflow.id === selectedWorkflowId) ?? workflows[0] ?? null

  const {data: runs = [], isLoading: runsLoading} = useQuery({
    queryKey: ['workflow-runs', selectedWorkflow?.id],
    queryFn: () => api.getWorkflowRuns(selectedWorkflow?.id ?? 0),
    enabled: selectedWorkflow !== null,
    refetchInterval: 15000,
  })

  const createMutation = useMutation({
    mutationFn: (request: WorkflowRequest) => api.createWorkflow(request),
    onSuccess: (workflow) => {
      queryClient.invalidateQueries({queryKey: ['workflows']})
      setSelectedWorkflowId(workflow.id)
      setDialogOpen(false)
      toast({title: 'Workflow created'})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to create workflow', description: error.message, variant: 'destructive'})
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({id, request}: {id: number; request: WorkflowRequest}) => api.updateWorkflow(id, request),
    onSuccess: (workflow) => {
      queryClient.invalidateQueries({queryKey: ['workflows']})
      queryClient.invalidateQueries({queryKey: ['workflow-runs', workflow.id]})
      setSelectedWorkflowId(workflow.id)
      setDialogOpen(false)
      setEditingWorkflow(null)
      toast({title: 'Workflow updated'})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to update workflow', description: error.message, variant: 'destructive'})
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteWorkflow(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['workflows']})
      setSelectedWorkflowId(null)
      toast({title: 'Workflow deleted'})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to delete workflow', description: error.message, variant: 'destructive'})
    },
  })

  const activeTrigger = useMemo(
    () => triggerByName(catalog, form.triggerName),
    [catalog, form.triggerName]
  )

  const openCreateDialog = () => {
    setEditingWorkflow(null)
    setForm(emptyForm(catalog))
    setDialogOpen(true)
  }

  const openEditDialog = (workflow: WorkflowResponse) => {
    setEditingWorkflow(workflow)
    setForm(workflowToForm(workflow))
    setDialogOpen(true)
  }

  const submitForm = () => {
    const request = formToRequest(form)
    if (request.name.length === 0) {
      toast({title: 'Workflow name is required', variant: 'destructive'})
      return
    }
    if (request.steps.length === 0) {
      toast({title: 'Add at least one workflow step', variant: 'destructive'})
      return
    }
    if (editingWorkflow) {
      updateMutation.mutate({id: editingWorkflow.id, request})
    } else {
      createMutation.mutate(request)
    }
  }

  const isLoading = catalogLoading || workflowsLoading

  return (
    <div className="workflows-page">
      <div className="border-b bg-card/50">
        <div className="px-4 py-3 lg:px-6">
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex min-w-0 items-center gap-2">
              <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                <Workflow className="h-4 w-4" />
              </div>
              <div className="min-w-0">
                <h1 className="text-lg font-bold tracking-tight">Workflows</h1>
                <p className="text-xs text-muted-foreground">Automate alert routing and notifications</p>
              </div>
            </div>
            <Button onClick={openCreateDialog} size="sm" className="h-8 gap-1.5">
              <Plus className="h-3.5 w-3.5" />
              New Workflow
            </Button>
          </div>
        </div>
      </div>

      <div className="space-y-4 px-4 py-4 lg:px-6">
        <WorkflowStats workflows={workflows} catalog={catalog} />

        {isLoading ? (
          <div className="flex h-48 items-center justify-center">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        ) : workflows.length === 0 ? (
          <EmptyWorkflows onCreate={openCreateDialog} />
        ) : (
          <div className="grid gap-4 xl:grid-cols-[minmax(280px,380px)_1fr]">
            <WorkflowList
              workflows={workflows}
              selectedWorkflowId={selectedWorkflow?.id ?? null}
              catalog={catalog}
              onSelect={setSelectedWorkflowId}
            />
            <WorkflowDetail
              workflow={selectedWorkflow}
              catalog={catalog}
              runs={runs}
              runsLoading={runsLoading}
              onEdit={openEditDialog}
              onDelete={(workflow) => deleteMutation.mutate(workflow.id)}
              deletePending={deleteMutation.isPending}
            />
          </div>
        )}
      </div>

      <WorkflowDialog
        open={dialogOpen}
        catalog={catalog}
        form={form}
        editingWorkflow={editingWorkflow}
        activeTrigger={activeTrigger}
        pending={createMutation.isPending || updateMutation.isPending}
        onOpenChange={setDialogOpen}
        onFormChange={setForm}
        onSubmit={submitForm}
      />
    </div>
  )
}

function WorkflowStats({
  workflows,
  catalog,
}: {
  workflows: WorkflowResponse[]
  catalog?: WorkflowCatalogResponse
}) {
  const enabledCount = workflows.filter((workflow) => workflow.enabled).length
  const runCount = workflows.reduce((total, workflow) => total + workflow.run_count, 0)
  return (
    <div className="grid gap-2 sm:grid-cols-3">
      <div className="rounded-md border bg-background px-3 py-2">
        <p className="text-[11px] text-muted-foreground">Enabled</p>
        <p className="text-lg font-semibold">{enabledCount}</p>
      </div>
      <div className="rounded-md border bg-background px-3 py-2">
        <p className="text-[11px] text-muted-foreground">Runs</p>
        <p className="text-lg font-semibold">{runCount}</p>
      </div>
      <div className="rounded-md border bg-background px-3 py-2">
        <p className="text-[11px] text-muted-foreground">Catalog</p>
        <p className="text-lg font-semibold">
          {(catalog?.triggers.length ?? 0) + (catalog?.steps.length ?? 0)}
        </p>
      </div>
    </div>
  )
}

function EmptyWorkflows({onCreate}: {onCreate: () => void}) {
  return (
    <div className="rounded-lg border border-dashed bg-background p-8 text-center">
      <div className="mx-auto mb-3 flex h-10 w-10 items-center justify-center rounded-full bg-primary/10 text-primary">
        <Workflow className="h-5 w-5" />
      </div>
      <h2 className="text-base font-semibold">Create your first workflow</h2>
      <p className="mx-auto mt-1 max-w-lg text-sm text-muted-foreground">
        Start with alert-triggered automations that can evaluate conditions and send notifications.
      </p>
      <Button onClick={onCreate} size="sm" className="mt-4 gap-1.5">
        <Plus className="h-3.5 w-3.5" />
        New Workflow
      </Button>
    </div>
  )
}

function WorkflowList({
  workflows,
  selectedWorkflowId,
  catalog,
  onSelect,
}: {
  workflows: WorkflowResponse[]
  selectedWorkflowId: number | null
  catalog?: WorkflowCatalogResponse
  onSelect: (workflowId: number) => void
}) {
  return (
    <div className="space-y-2">
      {workflows.map((workflow) => {
        const trigger = triggerByName(catalog, workflow.trigger_name)
        return (
          <button
            key={workflow.id}
            type="button"
            onClick={() => onSelect(workflow.id)}
            className={cn(
              'w-full rounded-md border bg-background p-3 text-left transition-colors hover:bg-muted/50',
              selectedWorkflowId === workflow.id && 'border-primary/50 bg-primary/5'
            )}
          >
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold">{workflow.name}</p>
                <p className="mt-0.5 truncate text-xs text-muted-foreground">
                  {trigger?.label ?? workflow.trigger_name}
                </p>
              </div>
              <Badge variant={workflow.enabled ? 'default' : 'secondary'} className="shrink-0 text-[10px]">
                {workflow.enabled ? 'Enabled' : 'Paused'}
              </Badge>
            </div>
            <div className="mt-2 flex items-center gap-2 text-[11px] text-muted-foreground">
              <span>v{workflow.version}</span>
              <span>{workflow.steps.length} step{workflow.steps.length === 1 ? '' : 's'}</span>
              <span>{workflow.run_count} run{workflow.run_count === 1 ? '' : 's'}</span>
            </div>
          </button>
        )
      })}
    </div>
  )
}

function WorkflowDetail({
  workflow,
  catalog,
  runs,
  runsLoading,
  onEdit,
  onDelete,
  deletePending,
}: {
  workflow: WorkflowResponse | null
  catalog?: WorkflowCatalogResponse
  runs: WorkflowRunResponse[]
  runsLoading: boolean
  onEdit: (workflow: WorkflowResponse) => void
  onDelete: (workflow: WorkflowResponse) => void
  deletePending: boolean
}) {
  if (!workflow) return null
  const trigger = triggerByName(catalog, workflow.trigger_name)
  const defaultWorkflow = isDefaultWorkflow(workflow)
  return (
    <div className="space-y-3">
      <div className="rounded-lg border bg-background">
        <div className="flex flex-col gap-3 border-b p-4 sm:flex-row sm:items-start sm:justify-between">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-base font-semibold">{workflow.name}</h2>
              <Badge variant={workflow.enabled ? 'default' : 'secondary'}>
                {workflow.enabled ? 'Enabled' : 'Paused'}
              </Badge>
              {defaultWorkflow && <Badge variant="outline">Default</Badge>}
            </div>
            <p className="mt-1 text-sm text-muted-foreground">{trigger?.description ?? workflow.trigger_name}</p>
          </div>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => onEdit(workflow)}>
              Edit
            </Button>
            {!defaultWorkflow && (
              <Button
                variant="outline"
                size="sm"
                disabled={deletePending}
                onClick={() => onDelete(workflow)}
                className="gap-1.5 text-destructive hover:text-destructive"
              >
                <Trash2 className="h-3.5 w-3.5" />
                Delete
              </Button>
            )}
          </div>
        </div>

        <div className="grid gap-4 p-4 lg:grid-cols-2">
          <WorkflowConfigSummary workflow={workflow} catalog={catalog} trigger={trigger} />
          <WorkflowRuns runs={runs} loading={runsLoading} />
        </div>
      </div>
    </div>
  )
}

function WorkflowConfigSummary({
  workflow,
  catalog,
  trigger,
}: {
  workflow: WorkflowResponse
  catalog?: WorkflowCatalogResponse
  trigger?: WorkflowTriggerDefinition
}) {
  return (
    <div className="space-y-3">
      <div>
        <h3 className="mb-2 text-xs font-semibold uppercase text-muted-foreground">Conditions</h3>
        {workflow.conditions.length === 0 ? (
          <p className="rounded-md border bg-muted/30 px-3 py-2 text-sm text-muted-foreground">
            Runs whenever the trigger fires.
          </p>
        ) : (
          <div className="space-y-2">
            {workflow.conditions.map((condition, index) => {
              const reference = referenceByName(trigger, condition.reference)
              const operation = operationsForReference(catalog, reference)
                .find((item) => item.name === condition.operation)
              return (
                <div key={`${condition.reference}-${index}`} className="rounded-md border px-3 py-2 text-sm">
                  <span className="font-medium">{reference?.label ?? condition.reference}</span>{' '}
                  <span className="text-muted-foreground">{operation?.label ?? condition.operation}</span>{' '}
                  {condition.value && <code className="text-xs">{condition.value}</code>}
                </div>
              )
            })}
          </div>
        )}
      </div>

      <div>
        <h3 className="mb-2 text-xs font-semibold uppercase text-muted-foreground">Steps</h3>
        <div className="space-y-2">
          {workflow.steps.map((step, index) => {
            const definition = stepByName(catalog, step.name)
            return (
              <div key={`${step.name}-${index}`} className="rounded-md border px-3 py-2 text-sm">
                <div className="flex items-center gap-2">
                  <StepIconGlyph stepName={step.name} className="h-3.5 w-3.5 text-muted-foreground" />
                  <span className="font-medium">{definition?.label ?? step.name}</span>
                </div>
                <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">
                  {definition?.description ?? Object.values(step.params).join(' ')}
                </p>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

function WorkflowRuns({
  runs,
  loading,
}: {
  runs: WorkflowRunResponse[]
  loading: boolean
}) {
  return (
    <div>
      <h3 className="mb-2 text-xs font-semibold uppercase text-muted-foreground">Recent Runs</h3>
      {loading ? (
        <div className="flex h-28 items-center justify-center rounded-md border">
          <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
        </div>
      ) : runs.length === 0 ? (
        <p className="rounded-md border bg-muted/30 px-3 py-2 text-sm text-muted-foreground">
          No runs have matched this workflow yet.
        </p>
      ) : (
        <div className="space-y-2">
          {runs.map((run) => (
            <div key={run.id} className="rounded-md border px-3 py-2">
              <div className="flex items-center justify-between gap-2">
                <Badge variant="outline" className={runStatusClasses(run.status)}>
                  {run.status}
                </Badge>
                <span className="text-xs text-muted-foreground">{formatDate(run.created_at)}</span>
              </div>
              <p className="mt-1 truncate text-xs text-muted-foreground">{run.once_for}</p>
              {run.error_message && <p className="mt-1 text-xs text-destructive">{run.error_message}</p>}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function WorkflowDialog({
  open,
  catalog,
  form,
  editingWorkflow,
  activeTrigger,
  pending,
  onOpenChange,
  onFormChange,
  onSubmit,
}: {
  open: boolean
  catalog?: WorkflowCatalogResponse
  form: WorkflowFormState
  editingWorkflow: WorkflowResponse | null
  activeTrigger?: WorkflowTriggerDefinition
  pending: boolean
  onOpenChange: (open: boolean) => void
  onFormChange: (form: WorkflowFormState) => void
  onSubmit: () => void
}) {
  const {toast} = useToast()
  const [selectedPanel, setSelectedPanel] = useState<WorkflowEditorPanel>('trigger')
  const [preview, setPreview] = useState<WorkflowPreviewResponse | null>(null)
  const selectedStepIndex = selectedPanel.startsWith('step:') ? Number(selectedPanel.slice(5)) : null
  const activePanel: WorkflowEditorPanel = selectedStepIndex !== null && !form.steps[selectedStepIndex]
    ? 'trigger'
    : selectedPanel
  const previewMutation = useMutation({
    mutationFn: () => api.previewWorkflow(formToPreviewRequest(form)),
    onSuccess: (response) => {
      setPreview(response)
    },
    onError: (error: Error) => {
      toast({title: 'Failed to preview workflow', description: error.message, variant: 'destructive'})
    },
  })
  const testMessageMutation = useMutation({
    mutationFn: ({step}: {step: WorkflowStepConfig; index: number}) =>
      api.testWorkflowMessage(formToStepPreviewRequest(form, step)),
    onSuccess: (response) => {
      const failedCount = response.results.filter((result) => result.status === 'failed').length
      toast({
        title: failedCount > 0 ? 'Test message failed' : 'Test message sent',
        description: workflowTestMessageSummary(response),
        variant: failedCount > 0 ? 'destructive' : undefined,
      })
    },
    onError: (error: Error) => {
      toast({title: 'Failed to send test message', description: error.message, variant: 'destructive'})
    },
  })

  const handleFormChange = (nextForm: WorkflowFormState) => {
    setPreview(null)
    onFormChange(nextForm)
  }

  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setPreview(null)
    }
    onOpenChange(nextOpen)
  }

  const addCondition = () => {
    const firstReference = activeTrigger?.scope[0]
    const operation = operationsForReference(catalog, firstReference)[0]
    if (!firstReference || !operation) return
    handleFormChange({
      ...form,
      conditions: [...form.conditions, {reference: firstReference.name, operation: operation.name, value: ''}],
    })
    setSelectedPanel('conditions')
  }

  const addStep = () => {
    const step = catalog?.steps[0]
    if (!step) return
    handleFormChange({
      ...form,
      steps: [...form.steps, {name: step.name, params: defaultStepParams(step)}],
    })
    setSelectedPanel(`step:${form.steps.length}`)
  }

  return (
    <>
      <Dialog open={open} onOpenChange={handleOpenChange}>
        <DialogContent className="workflow-editor-dialog max-h-[92vh] max-w-6xl overflow-y-auto shadow-none">
          <DialogHeader>
            <DialogTitle>{editingWorkflow ? 'Edit Workflow' : 'New Workflow'}</DialogTitle>
            <DialogDescription className="sr-only">
              Configure trigger conditions, idempotency, and notification steps for alert automation.
            </DialogDescription>
          </DialogHeader>

        <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_360px]">
          <div className="mx-auto w-full max-w-3xl">
            <TriggerNode
              trigger={activeTrigger}
              form={form}
              selected={activePanel === 'trigger'}
              onSelect={() => setSelectedPanel('trigger')}
            />
            <FlowConnector />
            <ConditionNode
              catalog={catalog}
              trigger={activeTrigger}
              conditions={form.conditions}
              selected={activePanel === 'conditions'}
              onSelect={() => setSelectedPanel('conditions')}
              onAddCondition={addCondition}
            />
            <FlowConnector />
            <DelayNode selected={activePanel === 'delay'} onSelect={() => setSelectedPanel('delay')} />
            <FlowConnector />
            <StepEditor
              catalog={catalog}
              steps={form.steps}
              selectedStepIndex={activePanel.startsWith('step:') ? Number(activePanel.slice(5)) : null}
              testMessageStepIndex={testMessageMutation.variables?.index ?? null}
              testMessagePending={testMessageMutation.isPending}
              testMessageDisabled={testMessageMutation.isPending}
              onSelectStep={(index) => setSelectedPanel(`step:${index}`)}
              onTestStep={(step, index) => testMessageMutation.mutate({step, index})}
              onAddStep={addStep}
              onRemoveStep={(index) => {
                handleFormChange({...form, steps: form.steps.filter((_, itemIndex) => itemIndex !== index)})
                setSelectedPanel('trigger')
              }}
            />
          </div>

          <div className="space-y-3">
            <WorkflowInspector
              panel={activePanel}
              catalog={catalog}
              trigger={activeTrigger}
              form={form}
              editingWorkflow={editingWorkflow}
              onFormChange={handleFormChange}
              onSelectPanel={setSelectedPanel}
            />
            <WorkflowPreviewPanel
              preview={preview}
              pending={previewMutation.isPending}
              canPreview={form.steps.length > 0}
              onPreview={() => previewMutation.mutate()}
            />
            <CatalogPanel catalog={catalog} trigger={activeTrigger} />
          </div>
        </div>

        <DialogFooter className="mt-2 gap-2 sm:space-x-0">
          <Button variant="outline" onClick={() => handleOpenChange(false)}>
            <X className="mr-1.5 h-3.5 w-3.5" />
            Cancel
          </Button>
          <Button onClick={onSubmit} disabled={pending} className="gap-1.5">
            {pending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Save className="h-3.5 w-3.5" />}
            Save Workflow
          </Button>
        </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

function TriggerNode({
  trigger,
  form,
  selected,
  onSelect,
}: {
  trigger?: WorkflowTriggerDefinition
  form: WorkflowFormState
  selected: boolean
  onSelect: () => void
}) {
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onSelect}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          onSelect()
        }
      }}
      className={cn(
        'w-full rounded-lg border bg-background p-4 text-left transition-colors hover:bg-muted/30',
        selected && 'border-primary/60 ring-2 ring-primary/15'
      )}
    >
      <h3 className="mb-3 text-base font-semibold">Workflow is triggered when...</h3>
      <div className="rounded-lg bg-muted/30 px-4 py-3">
        <div className="flex min-w-0 items-center gap-3">
          <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-md border border-primary/20 bg-primary/10 text-primary">
            <Hash className="h-5 w-5" />
          </div>
          <div className="min-w-0">
            <p className="truncate font-semibold">{trigger?.label ?? form.triggerName}</p>
            <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">
              {trigger?.description ?? 'Select a workflow trigger'}
            </p>
          </div>
        </div>
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <Badge variant={form.enabled ? 'default' : 'secondary'}>{form.enabled ? 'Enabled' : 'Paused'}</Badge>
        {form.onceForTemplate && <span className="truncate">Once per {form.onceForTemplate}</span>}
      </div>
    </div>
  )
}

function TriggerInspector({
  catalog,
  trigger,
  form,
  editingWorkflow,
  onFormChange,
}: {
  catalog?: WorkflowCatalogResponse
  trigger?: WorkflowTriggerDefinition
  form: WorkflowFormState
  editingWorkflow: WorkflowResponse | null
  onFormChange: (form: WorkflowFormState) => void
}) {
  return (
    <div className="space-y-4">
      <div className="space-y-1.5">
        <Label>Trigger</Label>
        <Select
          value={form.triggerName}
          disabled={editingWorkflow !== null}
          onValueChange={(triggerName) => {
            const nextTrigger = triggerByName(catalog, triggerName)
            onFormChange({
              ...form,
              triggerName,
              conditions: [],
              onceForTemplate: nextTrigger?.default_once_for_template.join(', ') ?? '',
            })
          }}
        >
          <SelectTrigger
            aria-label="Trigger"
            className="h-auto min-h-14 gap-3 rounded-lg bg-background px-3 py-2 text-left [&>span]:line-clamp-none"
          >
          <div className="flex min-w-0 items-center gap-3">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md border border-primary/20 bg-primary/10 text-primary">
              <Hash className="h-4 w-4" />
            </div>
            <div className="min-w-0">
              <p className="truncate font-semibold">{trigger?.label ?? form.triggerName}</p>
              <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">
                {trigger?.description ?? 'Select a workflow trigger'}
              </p>
            </div>
          </div>
        </SelectTrigger>
        <SelectContent>
          {catalog?.triggers.map((catalogTrigger) => (
            <SelectItem key={catalogTrigger.name} value={catalogTrigger.name}>
              {catalogTrigger.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      </div>

      <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_auto]">
        <div className="space-y-1.5">
          <Label>Name</Label>
          <Input
            value={form.name}
            onChange={(event) => onFormChange({...form, name: event.target.value})}
            placeholder="Escalate critical uptime alerts"
          />
        </div>
        <div className="flex items-end gap-2 pb-2">
          <Switch
            checked={form.enabled}
            onCheckedChange={(enabled) => onFormChange({...form, enabled})}
          />
          <Label>{form.enabled ? 'Enabled' : 'Paused'}</Label>
        </div>
      </div>

      <div className="space-y-1.5">
        <Label>Run once per</Label>
        <Input
          value={form.onceForTemplate}
          onChange={(event) => onFormChange({...form, onceForTemplate: event.target.value})}
          placeholder="alert.deduplication_key"
        />
      </div>
    </div>
  )
}

function FlowConnector() {
  return <div className="mx-auto h-8 w-px bg-border" />
}

function DelayNode({
  selected,
  onSelect,
}: {
  selected: boolean
  onSelect: () => void
}) {
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onSelect}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          onSelect()
        }
      }}
      className={cn(
        'flex w-full items-center justify-between gap-3 rounded-lg border bg-background px-4 py-3 text-left transition-colors hover:bg-muted/30',
        selected && 'border-primary/60 ring-2 ring-primary/15'
      )}
    >
      <div className="flex min-w-0 items-center gap-3">
        <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md border bg-muted/40 text-muted-foreground">
          <Clock3 className="h-4 w-4" />
        </div>
        <p className="truncate font-medium">And runs immediately</p>
      </div>
      <Button type="button" variant="ghost" size="sm" disabled onClick={(event) => event.stopPropagation()}>
        Edit delay
      </Button>
    </div>
  )
}

function ConditionNode({
  catalog,
  trigger,
  conditions,
  selected,
  onSelect,
  onAddCondition,
}: {
  catalog?: WorkflowCatalogResponse
  trigger?: WorkflowTriggerDefinition
  conditions: WorkflowConditionConfig[]
  selected: boolean
  onSelect: () => void
  onAddCondition: () => void
}) {
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onSelect}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          onSelect()
        }
      }}
      className={cn(
        'overflow-hidden rounded-lg border bg-background text-left transition-colors hover:bg-muted/30',
        selected && 'border-primary/60 ring-2 ring-primary/15'
      )}
    >
      <div className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex min-w-0 items-center gap-3">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md border bg-muted/40 text-muted-foreground">
            <Filter className="h-4 w-4" />
          </div>
          <p className="min-w-0 break-words font-medium">
            {conditionsSummary(conditions, catalog, trigger)}
          </p>
        </div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={(event) => {
            event.stopPropagation()
            onAddCondition()
          }}
          className="gap-1.5"
        >
          <Plus className="h-3.5 w-3.5" />
          Add condition
        </Button>
      </div>

      {conditions.length > 0 && (
        <div className="flex flex-wrap gap-2 border-t bg-muted/20 p-3">
          {conditions.map((condition, index) => (
            <Badge
              key={`${condition.reference}-${index}`}
              variant="secondary"
              className="max-w-full gap-1.5 rounded-md px-2.5 py-1.5 text-xs"
            >
              <Filter className="h-3 w-3 shrink-0" />
              <span className="truncate">{conditionLabel(condition, catalog, trigger)}</span>
            </Badge>
          ))}
        </div>
      )}
    </div>
  )
}

function ConditionRow({
  catalog,
  trigger,
  condition,
  onChange,
  onRemove,
}: {
  catalog?: WorkflowCatalogResponse
  trigger?: WorkflowTriggerDefinition
  condition: WorkflowConditionConfig
  onChange: (condition: WorkflowConditionConfig) => void
  onRemove: () => void
}) {
  const reference = referenceByName(trigger, condition.reference)
  const operations = operationsForReference(catalog, reference)
  return (
    <div className="grid gap-2 rounded-md border p-2 sm:grid-cols-[1.2fr_1fr_1fr_auto]">
      <Select
        value={condition.reference}
        onValueChange={(referenceName) => {
          const nextReference = referenceByName(trigger, referenceName)
          const nextOperation = operationsForReference(catalog, nextReference)[0]
          onChange({reference: referenceName, operation: nextOperation?.name ?? 'eq', value: ''})
        }}
      >
        <SelectTrigger>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {trigger?.scope.map((scopeReference) => (
            <SelectItem key={scopeReference.name} value={scopeReference.name}>
              {scopeReference.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      <Select
        value={condition.operation}
        onValueChange={(operation) => onChange({...condition, operation})}
      >
        <SelectTrigger>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {operations.map((operation) => (
            <SelectItem key={operation.name} value={operation.name}>
              {operation.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      {operationRequiresValue(condition.operation) ? (
        <Input
          value={condition.value ?? ''}
          onChange={(event) => onChange({...condition, value: event.target.value})}
          placeholder="Value"
        />
      ) : (
        <div className="flex h-9 items-center rounded-md border px-3 text-xs text-muted-foreground">No value</div>
      )}

      <Tooltip>
        <TooltipTrigger asChild>
          <Button type="button" variant="ghost" size="icon" onClick={onRemove}>
            <Trash2 className="h-4 w-4" />
          </Button>
        </TooltipTrigger>
        <TooltipContent>Remove condition</TooltipContent>
      </Tooltip>
    </div>
  )
}

function StepEditor({
  catalog,
  steps,
  selectedStepIndex,
  testMessageStepIndex,
  testMessagePending,
  testMessageDisabled,
  onSelectStep,
  onTestStep,
  onAddStep,
  onRemoveStep,
}: {
  catalog?: WorkflowCatalogResponse
  steps: WorkflowStepConfig[]
  selectedStepIndex: number | null
  testMessageStepIndex: number | null
  testMessagePending: boolean
  testMessageDisabled: boolean
  onSelectStep: (index: number) => void
  onTestStep: (step: WorkflowStepConfig, index: number) => void
  onAddStep: () => void
  onRemoveStep: (index: number) => void
}) {
  return (
    <div>
      <div className="space-y-0">
        {steps.map((step, index) => (
          <div key={`${step.name}-${index}`}>
            <StepNode
              definition={stepByName(catalog, step.name)}
              step={step}
              index={index}
              selected={selectedStepIndex === index}
              testMessagePending={testMessagePending && testMessageStepIndex === index}
              testMessageDisabled={testMessageDisabled}
              onSelect={() => onSelectStep(index)}
              onTest={() => onTestStep(step, index)}
              onRemove={() => onRemoveStep(index)}
            />
            <FlowConnector />
          </div>
        ))}
      </div>
      <div className="flex justify-center">
        <Button type="button" variant="outline" size="sm" onClick={onAddStep} className="gap-1.5 rounded-lg">
          <Plus className="h-3.5 w-3.5" />
          Add step
        </Button>
      </div>
    </div>
  )
}

function StepNode({
  definition,
  step,
  index,
  selected,
  testMessagePending,
  testMessageDisabled,
  onSelect,
  onTest,
  onRemove,
}: {
  definition?: WorkflowStepDefinition
  step: WorkflowStepConfig
  index: number
  selected: boolean
  testMessagePending: boolean
  testMessageDisabled: boolean
  onSelect: () => void
  onTest: () => void
  onRemove: () => void
}) {
  const firstParam = definition?.params[0]
  const firstValue = firstParam ? step.params[firstParam.name] : undefined
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onSelect}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          onSelect()
        }
      }}
      className={cn(
        'overflow-hidden rounded-lg border bg-background text-left transition-colors hover:bg-muted/30',
        selected && 'border-primary/60 ring-2 ring-primary/15'
      )}
    >
      <div className="flex items-center gap-3 bg-muted/20 p-3">
        <Badge variant="secondary" className="w-fit shrink-0">Step {index + 1}</Badge>
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md border bg-background text-muted-foreground">
          <StepIconGlyph stepName={step.name} className="h-4 w-4" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold">{definition?.label ?? step.name}</p>
          {firstValue && <p className="mt-0.5 line-clamp-1 text-xs text-muted-foreground">{firstValue}</p>}
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label={`Send test message for ${definition?.label ?? step.name}`}
                disabled={testMessageDisabled}
                onClick={(event) => {
                  event.stopPropagation()
                  onTest()
                }}
                className="h-8 w-8"
              >
                {testMessagePending ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <Send className="h-3.5 w-3.5" />
                )}
              </Button>
            </TooltipTrigger>
            <TooltipContent>Send test message</TooltipContent>
          </Tooltip>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            onClick={(event) => {
              event.stopPropagation()
              onRemove()
            }}
          >
            <Trash2 className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </div>
  )
}

function StepConfigurator({
  catalog,
  step,
  index,
  onChange,
  onRemove,
}: {
  catalog?: WorkflowCatalogResponse
  step: WorkflowStepConfig
  index: number
  onChange: (step: WorkflowStepConfig) => void
  onRemove: () => void
}) {
  const definition = stepByName(catalog, step.name)
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <Badge variant="secondary" className="w-fit">Step {index + 1}</Badge>
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md border bg-background text-muted-foreground">
          <StepIconGlyph stepName={step.name} className="h-4 w-4" />
        </div>
        <Button type="button" variant="ghost" size="icon" onClick={onRemove} className="ml-auto">
          <Trash2 className="h-4 w-4" />
        </Button>
      </div>

      <div className="space-y-1.5">
        <Label>Step</Label>
        <Select
          value={step.name}
          onValueChange={(stepName) => {
            const nextDefinition = stepByName(catalog, stepName)
            onChange({
              name: stepName,
              params: nextDefinition ? defaultStepParams(nextDefinition) : {},
            })
          }}
        >
          <SelectTrigger className="min-h-10 bg-background">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {catalog?.steps.map((catalogStep) => (
              <SelectItem key={catalogStep.name} value={catalogStep.name}>
                {catalogStep.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="space-y-3 p-4">
        {definition?.params.map((param) => (
          <div key={param.name} className="space-y-1.5">
            <Label>{param.label}</Label>
            {param.type === 'Text' ? (
              <Textarea
                value={step.params[param.name] ?? ''}
                onChange={(event) => onChange({
                  ...step,
                  params: {...step.params, [param.name]: event.target.value},
                })}
                className="min-h-28"
              />
            ) : (
              <Input
                value={step.params[param.name] ?? ''}
                onChange={(event) => onChange({
                  ...step,
                  params: {...step.params, [param.name]: event.target.value},
                })}
              />
            )}
          </div>
        ))}
      </div>
    </div>
  )
}

function WorkflowInspector({
  panel,
  catalog,
  trigger,
  form,
  editingWorkflow,
  onFormChange,
  onSelectPanel,
}: {
  panel: WorkflowEditorPanel
  catalog?: WorkflowCatalogResponse
  trigger?: WorkflowTriggerDefinition
  form: WorkflowFormState
  editingWorkflow: WorkflowResponse | null
  onFormChange: (form: WorkflowFormState) => void
  onSelectPanel: (panel: WorkflowEditorPanel) => void
}) {
  const selectedStepIndex = panel.startsWith('step:') ? Number(panel.slice(5)) : null
  const selectedStep = selectedStepIndex !== null ? form.steps[selectedStepIndex] : undefined

  return (
    <div className="rounded-lg border bg-muted/20 p-3">
      <div className="mb-3 flex items-center justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold">{inspectorTitle(panel)}</h3>
          <p className="text-xs text-muted-foreground">{inspectorSubtitle(panel)}</p>
        </div>
      </div>

      {panel === 'trigger' && (
        <TriggerInspector
          catalog={catalog}
          trigger={trigger}
          form={form}
          editingWorkflow={editingWorkflow}
          onFormChange={onFormChange}
        />
      )}

      {panel === 'conditions' && (
        <div className="space-y-3">
          {form.conditions.length === 0 ? (
            <p className="rounded-md border bg-background px-3 py-2 text-sm text-muted-foreground">
              This workflow currently applies whenever the trigger fires.
            </p>
          ) : (
            form.conditions.map((condition, index) => (
              <ConditionRow
                key={`${condition.reference}-${index}`}
                catalog={catalog}
                trigger={trigger}
                condition={condition}
                onChange={(next) => {
                  onFormChange({
                    ...form,
                    conditions: form.conditions.map((item, itemIndex) => (itemIndex === index ? next : item)),
                  })
                }}
                onRemove={() => onFormChange({
                  ...form,
                  conditions: form.conditions.filter((_, itemIndex) => itemIndex !== index),
                })}
              />
            ))
          )}
        </div>
      )}

      {panel === 'delay' && (
        <div className="rounded-md border bg-background px-3 py-2 text-sm text-muted-foreground">
          Workflows run as soon as their trigger and conditions match. Delayed execution is shown in the canvas
          but is not available yet.
        </div>
      )}

      {selectedStepIndex !== null && selectedStep && (
        <StepConfigurator
          catalog={catalog}
          step={selectedStep}
          index={selectedStepIndex}
          onChange={(next) => {
            onFormChange({
              ...form,
              steps: form.steps.map((item, itemIndex) => (itemIndex === selectedStepIndex ? next : item)),
            })
          }}
          onRemove={() => {
            onFormChange({
              ...form,
              steps: form.steps.filter((_, itemIndex) => itemIndex !== selectedStepIndex),
            })
            onSelectPanel('trigger')
          }}
        />
      )}
    </div>
  )
}

function inspectorTitle(panel: WorkflowEditorPanel): string {
  if (panel === 'trigger') return 'Trigger settings'
  if (panel === 'conditions') return 'Condition builder'
  if (panel === 'delay') return 'Delay'
  return 'Step configuration'
}

function inspectorSubtitle(panel: WorkflowEditorPanel): string {
  if (panel === 'trigger') return 'Choose the event, name, and run identity.'
  if (panel === 'conditions') return 'Filter when this workflow should apply.'
  if (panel === 'delay') return 'Timing for execution after a match.'
  return 'Choose the action and fill in its parameters.'
}

function WorkflowPreviewPanel({
  preview,
  pending,
  canPreview,
  onPreview,
}: {
  preview: WorkflowPreviewResponse | null
  pending: boolean
  canPreview: boolean
  onPreview: () => void
}) {
  const previewItems = preview?.previews ?? []
  const hasPreview = previewItems.length > 0
  const firstTab = hasPreview ? previewTabValue(previewItems[0], 0) : ''
  return (
    <div className="rounded-lg border bg-muted/20 p-3">
      <div className="mb-3 flex flex-col gap-3">
        <div>
          <h3 className="text-sm font-semibold">Message preview</h3>
          <p className="text-xs text-muted-foreground">Representative alert sample</p>
        </div>
        <div>
          <Button
            variant="outline"
            size="sm"
            onClick={onPreview}
            disabled={!canPreview || pending}
            className="h-8 justify-center gap-1.5"
          >
            {pending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Eye className="h-3.5 w-3.5" />}
            Preview
          </Button>
        </div>
      </div>

      {pending ? (
        <div className="flex items-center gap-2 rounded-md border bg-background px-3 py-4 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />
          Rendering preview
        </div>
      ) : hasPreview ? (
        <Tabs defaultValue={firstTab} className="w-full">
          <TabsList className="grid h-auto w-full auto-cols-fr grid-flow-col">
            {previewItems.map((item, index) => (
              <TabsTrigger key={previewTabValue(item, index)} value={previewTabValue(item, index)}>
                {previewChannelLabel(item.channel)}
              </TabsTrigger>
            ))}
          </TabsList>
          {previewItems.map((item, index) => (
            <TabsContent key={previewTabValue(item, index)} value={previewTabValue(item, index)} className="mt-3">
              <WorkflowPreviewCard item={item} />
            </TabsContent>
          ))}
        </Tabs>
      ) : (
        <div className="rounded-md border border-dashed bg-background px-3 py-4 text-sm text-muted-foreground">
          Not rendered yet.
        </div>
      )}
    </div>
  )
}

function WorkflowPreviewCard({item}: {item: WorkflowStepPreview}) {
  if (item.channel === 'email') return <EmailPreview item={item} />
  if (item.channel === 'discord') return <DiscordPreview item={item} />
  return <SlackPreview item={item} />
}

function EmailPreview({item}: {item: WorkflowStepPreview}) {
  return (
    <div className="overflow-hidden rounded-md border bg-background">
      <div className="border-b px-3 py-2 text-xs text-muted-foreground">
        <span className="font-medium text-foreground">Subject:</span> {item.subject ?? item.title}
      </div>
      <div className="bg-muted/20 p-4">
        <div className="rounded-md border bg-card p-4 text-card-foreground">
          <div className="flex items-start gap-3">
            <MoneatMessageAvatar />
            <div className="min-w-0 flex-1">
              <p className="text-sm font-semibold">Moneat</p>
              <h4 className="mt-3 flex items-start gap-2 text-base font-semibold leading-snug">
                <span aria-hidden="true">{previewStatusEmoji(item)}</span>
                <span>{previewHeaderText(item)}</span>
              </h4>
              <p className="mt-3 whitespace-pre-wrap text-sm text-muted-foreground">{item.body}</p>
              <EmailFieldGrid fields={item.fields} />
              <PreviewCta item={item} />
              {item.footer && <p className="mt-4 text-xs text-muted-foreground">Added by Moneat</p>}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function SlackPreview({item}: {item: WorkflowStepPreview}) {
  return <AlertMessagePreview item={item} />
}

function DiscordPreview({item}: {item: WorkflowStepPreview}) {
  return <AlertMessagePreview item={item} />
}

function AlertMessagePreview({item}: {item: WorkflowStepPreview}) {
  return (
    <div className="rounded-md border bg-[#1f2227] p-3 text-[#d1d2d3]">
      <div className="flex items-start gap-3">
        <MoneatMessageAvatar />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-1.5 text-sm leading-none">
            <span className="font-bold text-[#f2f3f5]">Moneat</span>
            <span className="rounded bg-[#34373c] px-1 py-0.5 text-[10px] font-bold text-[#c9cbd0]">APP</span>
            <span className="text-[#a5a7aa]">now</span>
          </div>
          <div className="mt-3 flex items-center gap-2 text-sm font-bold text-[#f2f3f5]">
            <span>{previewStatusEmoji(item)}</span>
            <span>{previewHeaderText(item)}</span>
          </div>
          <div className="mt-3 border-l-4 py-1 pl-4" style={{borderLeftColor: item.color}}>
            <p className="mb-3 whitespace-pre-wrap text-sm text-[#d1d2d3]">{item.body}</p>
            <AlertFieldGrid fields={item.fields} />
            <PreviewCta item={item} dark />
            {item.footer && <p className="mt-3 text-xs text-[#a5a7aa]">Added by Moneat</p>}
          </div>
        </div>
      </div>
    </div>
  )
}

function MoneatMessageAvatar() {
  return (
    <div
      aria-hidden="true"
      className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-white"
    >
      <img src="/favicon.svg" alt="" className="h-8 w-8" />
    </div>
  )
}

function EmailFieldGrid({fields}: {fields: WorkflowStepPreview['fields']}) {
  if (fields.length === 0) return null
  return (
    <div className="mt-4 grid gap-x-6 gap-y-3 sm:grid-cols-2">
      {fields.map((field) => (
        <div key={field.label} className="min-w-0">
          <p className="text-sm font-semibold text-foreground">{field.label}:</p>
          <p className="break-words text-sm text-muted-foreground">{field.value}</p>
        </div>
      ))}
    </div>
  )
}

function AlertFieldGrid({fields}: {fields: WorkflowStepPreview['fields']}) {
  if (fields.length === 0) return null
  return (
    <div className="grid gap-x-6 gap-y-2 sm:grid-cols-2">
      {fields.map((field) => (
        <div key={field.label} className="min-w-0">
          <p className="text-sm font-bold text-[#d1d2d3]">{field.label}:</p>
          <p className="break-words text-sm text-[#d1d2d3]">{field.value}</p>
        </div>
      ))}
    </div>
  )
}

function PreviewCta({
  item,
  dark = false,
}: {
  item: WorkflowStepPreview
  dark?: boolean
}) {
  if (!item.cta_label || !item.cta_url) return null
  return (
    <div className="mt-3">
      <Button type="button" variant={dark ? 'secondary' : 'outline'} size="sm" className="h-8 px-3">
        {item.cta_label}
      </Button>
    </div>
  )
}

function previewStatusEmoji(item: WorkflowStepPreview): string {
  const status = previewFieldValue(item, 'Status')
  if (status.toLowerCase() === 'resolved') return '✅'
  return item.color.toLowerCase() === '#e01e5a' ? '🔴' : '⚠️'
}

function previewHeaderText(item: WorkflowStepPreview): string {
  return item.title
}

function previewFieldValue(
  item: WorkflowStepPreview,
  label: string
): string {
  return item.fields.find((field) => field.label.toLowerCase() === label.toLowerCase())?.value ?? ''
}

function previewTabValue(
  item: WorkflowStepPreview,
  index: number
): string {
  return item.channel + '-' + index
}

function previewChannelLabel(channel: string): string {
  if (channel === 'email') return 'Email'
  if (channel === 'discord') return 'Discord'
  if (channel === 'slack') return 'Slack'
  return channel
}

function CatalogPanel({
  catalog,
  trigger,
}: {
  catalog?: WorkflowCatalogResponse
  trigger?: WorkflowTriggerDefinition
}) {
  return (
    <div className="space-y-3">
      <div className="rounded-md border bg-muted/20 p-3">
        <div className="mb-2 flex items-center gap-2">
          <GitBranch className="h-4 w-4 text-muted-foreground" />
          <h3 className="text-sm font-semibold">Trigger Scope</h3>
        </div>
        <div className="space-y-1.5">
          {trigger?.scope.map((reference) => (
            <div key={reference.name} className="rounded border bg-background px-2 py-1.5">
              <p className="text-xs font-medium">{reference.label}</p>
              <p className="font-mono text-[11px] text-muted-foreground">{reference.name}</p>
            </div>
          ))}
        </div>
      </div>

      <div className="rounded-md border bg-muted/20 p-3">
        <div className="mb-2 flex items-center gap-2">
          <Activity className="h-4 w-4 text-muted-foreground" />
          <h3 className="text-sm font-semibold">Available Steps</h3>
        </div>
        <div className="space-y-1.5">
          {catalog?.steps.map((step) => (
            <div key={step.name} className="rounded border bg-background px-2 py-1.5">
              <div className="flex items-center gap-2">
                <StepIconGlyph stepName={step.name} className="h-3.5 w-3.5 text-muted-foreground" />
                <p className="text-xs font-medium">{step.label}</p>
              </div>
              <p className="mt-1 text-[11px] text-muted-foreground">{step.description}</p>
            </div>
          ))}
        </div>
      </div>

      <div className="rounded-md border bg-muted/20 p-3">
        <div className="mb-2 flex items-center gap-2">
          <CheckCircle2 className="h-4 w-4 text-muted-foreground" />
          <h3 className="text-sm font-semibold">Interpolation</h3>
        </div>
        <p className="text-xs leading-relaxed text-muted-foreground">
          Use scoped values in messages with double braces, for example {'{{alert.title}}'} or {'{{alert.url}}'}.
        </p>
      </div>
    </div>
  )
}
