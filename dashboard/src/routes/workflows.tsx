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
import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {
  Bug,
  Gauge,
  KeyRound,
  Loader2,
  PlayCircle,
  Plus,
  Save,
  Share2,
  Trash2,
  Workflow as WorkflowIcon,
} from 'lucide-react'
import {api} from '@/lib/api'
import type {
  WorkflowCatalogResponse,
  WorkflowGraphConfig,
  WorkflowGraphNode,
  WorkflowResponse,
  WorkflowRunResponse,
} from '@/lib/api'
import {NodeConfigPanel} from '@/components/workflows/NodeConfigPanel'
import {NodePalette} from '@/components/workflows/NodePalette'
import {WorkflowActivityDrawer} from '@/components/workflows/WorkflowActivityDrawer'
import {WorkflowCanvas} from '@/components/workflows/WorkflowCanvas'
import {
  PublishToggle,
  RunDebugDrawer,
  ValidationPanel,
} from '@/components/workflows/WorkflowPanels'
import {
  addGraphNode,
  draftFromWorkflow,
  draftToRequest,
  emptyWorkflowDraft,
  formatDate,
  nextNodeId,
  removeGraphNode,
  statusClass,
  triggerNodeId,
  updateGraphNode,
  validateDraft,
  type WorkflowDraft,
} from '@/components/workflows/workflowGraph'
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

const noop = () => undefined

function WorkflowsPage() {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [selectedWorkflowId, setSelectedWorkflowId] = useState<number | null>(null)
  const [editorOpen, setEditorOpen] = useState(false)
  const [draft, setDraft] = useState<WorkflowDraft>(() => emptyWorkflowDraft())
  const [debugRun, setDebugRun] = useState<WorkflowRunResponse | null>(null)
  const [activityOpen, setActivityOpen] = useState(false)

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
    mutationFn: (request: ReturnType<typeof draftToRequest>) => api.createWorkflow(request),
    onSuccess: (workflow) => {
      queryClient.invalidateQueries({queryKey: ['workflows']})
      setSelectedWorkflowId(workflow.id)
      setEditorOpen(false)
      toast({title: 'Workflow saved'})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to save workflow', description: error.message, variant: 'destructive'})
    },
  })
  const updateMutation = useMutation({
    mutationFn: ({id, request}: {id: number; request: ReturnType<typeof draftToRequest>}) =>
      api.updateWorkflow(id, request),
    onSuccess: (workflow) => {
      queryClient.invalidateQueries({queryKey: ['workflows']})
      queryClient.invalidateQueries({queryKey: ['workflow-runs', workflow.id]})
      setSelectedWorkflowId(workflow.id)
      setEditorOpen(false)
      toast({title: 'Workflow saved'})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to save workflow', description: error.message, variant: 'destructive'})
    },
  })
  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteWorkflow(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['workflows']})
      setSelectedWorkflowId(null)
      toast({title: 'Workflow deleted'})
    },
  })
  const publishMutation = useMutation({
    mutationFn: ({id, published}: {id: number; published: boolean}) =>
      published ? api.unpublishWorkflow(id) : api.publishWorkflow(id),
    onSuccess: (workflow) => {
      queryClient.invalidateQueries({queryKey: ['workflows']})
      setSelectedWorkflowId(workflow.id)
      toast({title: workflow.published ? 'Workflow published' : 'Workflow unpublished'})
    },
    onError: (error: Error) => {
      toast({title: 'Publish change failed', description: error.message, variant: 'destructive'})
    },
  })
  const runMutation = useMutation({
    mutationFn: (id: number) => api.runWorkflow(id),
    onSuccess: (run) => {
      queryClient.invalidateQueries({queryKey: ['workflow-runs', run.workflow_id]})
      setDebugRun(run)
      toast({title: 'Manual run started'})
    },
    onError: (error: Error) => {
      toast({title: 'Manual run failed', description: error.message, variant: 'destructive'})
    },
  })

  const openCreate = () => {
    setDraft(emptyWorkflowDraft(catalog))
    setEditorOpen(true)
  }
  const openEdit = (workflow: WorkflowResponse) => {
    setDraft(draftFromWorkflow(workflow))
    setEditorOpen(true)
  }
  const saveDraft = () => {
    const issues = validateDraft(draft, catalog)
    if (issues.some((issue) => issue.level === 'error')) {
      toast({title: 'Resolve validation errors before saving', variant: 'destructive'})
      return
    }
    const request = draftToRequest(draft)
    if (draft.id) {
      updateMutation.mutate({id: draft.id, request})
    } else {
      createMutation.mutate(request)
    }
  }

  return (
    <div className="workflows-page">
      <PageHeader onCreate={openCreate} />
      <div className="grid gap-4 px-4 py-4 lg:grid-cols-[320px_minmax(0,1fr)] lg:px-6">
        <WorkflowList
          workflows={workflows}
          catalog={catalog}
          selectedWorkflowId={selectedWorkflow?.id ?? null}
          loading={catalogLoading || workflowsLoading}
          onSelect={setSelectedWorkflowId}
        />
        <WorkflowDetail
          workflow={selectedWorkflow}
          catalog={catalog}
          runs={runs}
          runsLoading={runsLoading}
          publishPending={publishMutation.isPending}
          runPending={runMutation.isPending}
          deletePending={deleteMutation.isPending}
          onEdit={openEdit}
          onDelete={(workflow) => deleteMutation.mutate(workflow.id)}
          onPublishToggle={(workflow) => publishMutation.mutate({id: workflow.id, published: workflow.published})}
          onRun={(workflow) => runMutation.mutate(workflow.id)}
          onDebugRun={setDebugRun}
          onOpenActivity={() => setActivityOpen(true)}
        />
      </div>
      <WorkflowEditorDialog
        open={editorOpen}
        draft={draft}
        catalog={catalog}
        pending={createMutation.isPending || updateMutation.isPending}
        onDraftChange={setDraft}
        onOpenChange={setEditorOpen}
        onSave={saveDraft}
      />
      <RunDebugDrawer
        run={debugRun}
        open={debugRun !== null}
        onOpenChange={(open) => {
          if (!open) setDebugRun(null)
        }}
      />
      <WorkflowActivityDrawer
        workflow={selectedWorkflow}
        open={activityOpen}
        onOpenChange={setActivityOpen}
      />
    </div>
  )
}

function PageHeader({onCreate}: {onCreate: () => void}) {
  return (
    <div className="border-b bg-card/50">
      <div className="flex flex-col gap-3 px-4 py-3 sm:flex-row sm:items-center sm:justify-between lg:px-6">
        <div className="flex min-w-0 items-center gap-2">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
            <WorkflowIcon className="h-4 w-4" />
          </div>
          <div>
            <h1 className="text-lg font-bold tracking-tight">Workflows</h1>
            <p className="text-xs text-muted-foreground">Build automation graphs for telemetry-driven response.</p>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="outline" asChild className="gap-1.5">
            <Link to="/workflows/insights">
              <Gauge className="h-3.5 w-3.5" />
              Insights
            </Link>
          </Button>
          <Button size="sm" variant="outline" asChild className="gap-1.5">
            <Link to="/workflows/connections">
              <KeyRound className="h-3.5 w-3.5" />
              Connections
              <Badge variant="outline" className="ml-1 text-[10px]">
                Enterprise
              </Badge>
            </Link>
          </Button>
          <Button size="sm" onClick={onCreate} className="gap-1.5">
            <Plus className="h-3.5 w-3.5" />
            New Workflow
          </Button>
        </div>
      </div>
    </div>
  )
}

function WorkflowList({
  workflows,
  catalog,
  selectedWorkflowId,
  loading,
  onSelect,
}: {
  workflows: WorkflowResponse[]
  catalog?: WorkflowCatalogResponse
  selectedWorkflowId: number | null
  loading: boolean
  onSelect: (workflowId: number) => void
}) {
  if (loading) {
    return <div className="flex h-40 items-center justify-center rounded-md border"><Loader2 className="h-5 w-5 animate-spin" /></div>
  }
  if (workflows.length === 0) {
    return (
      <div className="rounded-md border border-dashed bg-background p-6 text-sm text-muted-foreground">
        No workflows yet.
      </div>
    )
  }
  return (
    <div className="space-y-2">
      {workflows.map((workflow) => {
        const trigger = catalog?.triggers.find((item) => item.name === workflow.trigger_name)
        return (
          <button
            key={workflow.id}
            type="button"
            onClick={() => onSelect(workflow.id)}
            className={cn(
              'w-full rounded-md border bg-background p-3 text-left transition-colors hover:bg-muted/40',
              selectedWorkflowId === workflow.id && 'border-primary/50 bg-primary/5'
            )}
          >
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold">{workflow.name}</p>
                <p className="mt-1 truncate text-xs text-muted-foreground">
                  {trigger?.label ?? workflow.trigger_name}
                </p>
              </div>
              <Badge variant={workflow.published ? 'default' : 'secondary'} className="shrink-0">
                {workflow.published ? 'Published' : 'Draft'}
              </Badge>
            </div>
            <div className="mt-2 flex flex-wrap gap-2 text-[11px] text-muted-foreground">
              <span>v{workflow.version}</span>
              <span>{workflow.graph.nodes.length} nodes</span>
              <span>{workflow.run_count} runs</span>
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
  publishPending,
  runPending,
  deletePending,
  onEdit,
  onDelete,
  onPublishToggle,
  onRun,
  onDebugRun,
  onOpenActivity,
}: {
  workflow: WorkflowResponse | null
  catalog?: WorkflowCatalogResponse
  runs: WorkflowRunResponse[]
  runsLoading: boolean
  publishPending: boolean
  runPending: boolean
  deletePending: boolean
  onEdit: (workflow: WorkflowResponse) => void
  onDelete: (workflow: WorkflowResponse) => void
  onPublishToggle: (workflow: WorkflowResponse) => void
  onRun: (workflow: WorkflowResponse) => void
  onDebugRun: (run: WorkflowRunResponse) => void
  onOpenActivity: () => void
}) {
  if (!workflow) {
    return <div className="rounded-md border bg-background p-6 text-sm text-muted-foreground">Select a workflow.</div>
  }
  const trigger = catalog?.triggers.find((item) => item.name === workflow.trigger_name)
  return (
    <div className="space-y-3">
      <div className="rounded-md border bg-background">
        <div className="flex flex-col gap-3 border-b p-4 xl:flex-row xl:items-start xl:justify-between">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-base font-semibold">{workflow.name}</h2>
              <Badge variant={workflow.enabled ? 'default' : 'secondary'}>{workflow.enabled ? 'Enabled' : 'Paused'}</Badge>
              {workflow.system_key && <Badge variant="outline">Default</Badge>}
            </div>
            <p className="mt-1 text-sm text-muted-foreground">{trigger?.description ?? workflow.trigger_name}</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <PublishToggle
              published={workflow.published}
              pending={publishPending}
              onPublish={() => onPublishToggle(workflow)}
              onUnpublish={() => onPublishToggle(workflow)}
            />
            <Button size="sm" variant="outline" disabled={runPending} onClick={() => onRun(workflow)} className="gap-1.5">
              {runPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <PlayCircle className="h-3.5 w-3.5" />}
              Run
            </Button>
            <Button size="sm" variant="outline" onClick={() => onEdit(workflow)}>Edit</Button>
            <Button size="sm" variant="outline" onClick={onOpenActivity} className="gap-1.5">
              <Share2 className="h-3.5 w-3.5" />
              Share &amp; activity
            </Button>
            {!workflow.system_key && (
              <Button
                size="sm"
                variant="outline"
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
        <div className="grid gap-4 p-4 xl:grid-cols-[minmax(0,1fr)_360px]">
          <WorkflowCanvas
            graph={workflow.graph}
            catalog={catalog}
            selectedNodeId={null}
            onSelectNode={noop}
            onGraphChange={noop}
          />
          <RunsPanel runs={runs} loading={runsLoading} onDebugRun={onDebugRun} />
        </div>
      </div>
    </div>
  )
}

function RunsPanel({
  runs,
  loading,
  onDebugRun,
}: {
  runs: WorkflowRunResponse[]
  loading: boolean
  onDebugRun: (run: WorkflowRunResponse) => void
}) {
  if (loading) {
    return <div className="flex h-40 items-center justify-center rounded-md border"><Loader2 className="h-5 w-5 animate-spin" /></div>
  }
  return (
    <div className="space-y-2">
      <h3 className="text-sm font-semibold">Recent runs</h3>
      {runs.length === 0 ? (
        <p className="rounded-md border bg-muted/20 px-3 py-2 text-sm text-muted-foreground">No runs recorded.</p>
      ) : (
        runs.map((run) => (
          <button
            key={run.id}
            type="button"
            onClick={() => onDebugRun(run)}
            className="w-full rounded-md border bg-background p-3 text-left hover:bg-muted/40"
          >
            <div className="flex items-center justify-between gap-2">
              <Badge variant="outline" className={statusClass(run.status)}>{run.status}</Badge>
              <Bug className="h-4 w-4 text-muted-foreground" />
            </div>
            <p className="mt-2 truncate text-xs text-muted-foreground">{run.once_for}</p>
            <p className="mt-1 text-xs text-muted-foreground">{formatDate(run.created_at)}</p>
          </button>
        ))
      )}
    </div>
  )
}

function WorkflowEditorDialog({
  open,
  draft,
  catalog,
  pending,
  onDraftChange,
  onOpenChange,
  onSave,
}: {
  open: boolean
  draft: WorkflowDraft
  catalog?: WorkflowCatalogResponse
  pending: boolean
  onDraftChange: (draft: WorkflowDraft) => void
  onOpenChange: (open: boolean) => void
  onSave: () => void
}) {
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(triggerNodeId)
  const issues = useMemo(() => validateDraft(draft, catalog), [catalog, draft])
  const selectedNode = draft.graph.nodes.find((node) => node.id === selectedNodeId)
  const updateGraph = (graph: WorkflowGraphConfig) => onDraftChange({...draft, graph})
  const addNode = (node: Omit<WorkflowGraphNode, 'id'>, prefix: string) => {
    const nextNode = {...node, id: nextNodeId(draft.graph, prefix)}
    updateGraph(addGraphNode(draft.graph, nextNode))
    setSelectedNodeId(nextNode.id)
  }
  const changeTrigger = (triggerName: string) => {
    const graph = updateGraphTrigger(draft.graph, triggerName)
    const trigger = catalog?.triggers.find((item) => item.name === triggerName)
    onDraftChange({
      ...draft,
      triggerName,
      graph,
      onceForTemplate: trigger?.default_once_for_template.join(', ') ?? draft.onceForTemplate,
    })
  }
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[92vh] max-w-[1400px] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{draft.id ? 'Edit workflow' : 'New workflow'}</DialogTitle>
          <DialogDescription className="sr-only">
            Configure a workflow automation graph with triggers, conditions, controls, and actions.
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-4 xl:grid-cols-[240px_minmax(0,1fr)_360px]">
          <div className="space-y-3">
            <WorkflowDraftFields draft={draft} catalog={catalog} onDraftChange={onDraftChange} onTriggerChange={changeTrigger} />
            <NodePalette catalog={catalog} onAddNode={addNode} />
            <ValidationPanel issues={issues} />
          </div>
          <WorkflowCanvas
            graph={draft.graph}
            catalog={catalog}
            selectedNodeId={selectedNodeId}
            onSelectNode={setSelectedNodeId}
            onGraphChange={updateGraph}
          />
          <NodeConfigPanel
            node={selectedNode}
            catalog={catalog}
            triggerName={draft.triggerName}
            onChange={(node) => updateGraph(updateGraphNode(draft.graph, node))}
            onRemove={(nodeId) => {
              updateGraph(removeGraphNode(draft.graph, nodeId))
              setSelectedNodeId(triggerNodeId)
            }}
          />
        </div>
        <DialogFooter className="gap-2">
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="button" onClick={onSave} disabled={pending} className="gap-1.5">
            {pending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Save className="h-3.5 w-3.5" />}
            Save
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function WorkflowDraftFields({
  draft,
  catalog,
  onDraftChange,
  onTriggerChange,
}: {
  draft: WorkflowDraft
  catalog?: WorkflowCatalogResponse
  onDraftChange: (draft: WorkflowDraft) => void
  onTriggerChange: (triggerName: string) => void
}) {
  return (
    <div className="space-y-3 rounded-md border bg-muted/20 p-3">
      <div className="space-y-1.5">
        <Label>Name</Label>
        <Input value={draft.name} onChange={(event) => onDraftChange({...draft, name: event.target.value})} />
      </div>
      <div className="space-y-1.5">
        <Label>Trigger</Label>
        <Select value={draft.triggerName} onValueChange={onTriggerChange}>
          <SelectTrigger>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {catalog?.triggers.map((trigger) => (
              <SelectItem key={trigger.name} value={trigger.name}>
                {trigger.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      <div className="space-y-1.5">
        <Label>Run once per</Label>
        <Input
          value={draft.onceForTemplate}
          onChange={(event) => onDraftChange({...draft, onceForTemplate: event.target.value})}
        />
      </div>
      <div className="flex items-center gap-2">
        <Switch checked={draft.enabled} onCheckedChange={(enabled) => onDraftChange({...draft, enabled})} />
        <Label>{draft.enabled ? 'Enabled' : 'Paused'}</Label>
      </div>
    </div>
  )
}

function updateGraphTrigger(
  graph: WorkflowGraphConfig,
  triggerName: string
): WorkflowGraphConfig {
  return {
    ...graph,
    nodes: graph.nodes.map((node) => (
      node.id === triggerNodeId ? {...node, trigger: triggerName} : node
    )),
  }
}
