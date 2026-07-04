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
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {ArrowLeft, ClipboardList, Gauge, LayoutGrid, Loader2} from 'lucide-react'
import {api} from '@/lib/api'
import type {WorkflowBlueprintSummary} from '@/lib/api'
import {BlueprintGallery} from '@/components/workflows/BlueprintGallery'
import {WorkflowAuditTimeline} from '@/components/workflows/WorkflowAuditTimeline'
import {WorkflowOverviewCards} from '@/components/workflows/WorkflowOverviewCards'
import {WorkflowUsagePanel} from '@/components/workflows/WorkflowUsagePanel'
import {Button} from '@/components/ui/button'
import {PageHeader} from '@/components/ui/page-header'
import {SectionCard} from '@/components/ui/section-card'
import {useToast} from '@/hooks/useToast'

const AUDIT_LIMIT = 25

export const Route = createFileRoute('/workflows/insights')({
  component: InsightsPage,
})

function InsightsPage() {
  return (
    <div className="workflows-insights-page">
      <div className="border-b bg-card/50 px-6 py-4">
        <PageHeader
          icon={Gauge}
          title="Automation insights"
          description="Overview, run usage, blueprints, and the audit trail for your automations."
          actions={
            <Button size="sm" variant="outline" asChild className="gap-1.5">
              <Link to="/workflows">
                <ArrowLeft className="h-4 w-4" />
                Back to workflows
              </Link>
            </Button>
          }
        />
      </div>
      <div className="space-y-4 px-6 py-4">
        <InsightsSection />
        <BlueprintsSection />
        <AuditSection />
      </div>
    </div>
  )
}

function LoadingBox() {
  return (
    <div className="flex h-32 items-center justify-center rounded-md border">
      <Loader2 className="h-5 w-5 animate-spin" />
    </div>
  )
}

function SectionError({message}: {message: string}) {
  return (
    <div className="rounded-md border bg-card/40 p-4 text-sm">
      <p className="font-semibold">Unable to load this section</p>
      <p className="mt-1 text-muted-foreground">{message}</p>
    </div>
  )
}

function InsightsSection() {
  const overviewQuery = useQuery({
    queryKey: ['workflow-overview'],
    queryFn: () => api.getWorkflowOverview(),
  })
  const usageQuery = useQuery({
    queryKey: ['workflow-usage'],
    queryFn: () => api.getWorkflowUsage(),
  })

  return (
    <SectionCard title="Overview" icon={Gauge} iconTone="accent">
      <div className="grid grid-cols-1 gap-3 xl:grid-cols-[minmax(0,1fr)_320px]">
        <OverviewBlock query={overviewQuery} />
        <UsageBlock query={usageQuery} />
      </div>
    </SectionCard>
  )
}

function OverviewBlock({query}: {query: ReturnType<typeof useQuery>}) {
  if (query.isLoading) return <LoadingBox />
  if (query.isError) return <SectionError message={(query.error as Error).message} />
  const overview = query.data as Parameters<typeof WorkflowOverviewCards>[0]['overview'] | undefined
  if (!overview) return <SectionError message="No overview data." />
  return <WorkflowOverviewCards overview={overview} />
}

function UsageBlock({query}: {query: ReturnType<typeof useQuery>}) {
  if (query.isLoading) return <LoadingBox />
  if (query.isError) return <SectionError message={(query.error as Error).message} />
  const usage = query.data as Parameters<typeof WorkflowUsagePanel>[0]['usage'] | undefined
  if (!usage) return <SectionError message="No usage data." />
  return <WorkflowUsagePanel usage={usage} />
}

function BlueprintsSection() {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const {data: blueprints = [], isLoading, isError, error} = useQuery({
    queryKey: ['workflow-blueprints'],
    queryFn: () => api.getWorkflowBlueprints(),
  })

  const instantiate = useMutation({
    mutationFn: (blueprint: WorkflowBlueprintSummary) => api.instantiateBlueprint(blueprint.key, {}),
    onSuccess: (workflow) => {
      queryClient.invalidateQueries({queryKey: ['workflows']})
      toast({title: 'Automation created from blueprint', description: workflow.name})
    },
    onError: (mutationError: Error) => {
      toast({title: 'Failed to use blueprint', description: mutationError.message, variant: 'destructive'})
    },
  })

  return (
    <SectionCard title="Blueprints" icon={LayoutGrid} iconTone="info">
      {isLoading ? (
        <LoadingBox />
      ) : isError ? (
        <SectionError message={error.message} />
      ) : (
        <BlueprintGallery
          blueprints={blueprints}
          pendingKey={instantiate.isPending ? instantiate.variables?.key : null}
          disabled={instantiate.isPending}
          onUseBlueprint={(blueprint) => instantiate.mutate(blueprint)}
        />
      )}
    </SectionCard>
  )
}

function AuditSection() {
  const {data: entries = [], isLoading, isError, error} = useQuery({
    queryKey: ['workflow-audit', AUDIT_LIMIT],
    queryFn: () => api.getWorkflowAudit(AUDIT_LIMIT),
  })

  return (
    <SectionCard title="Audit trail" icon={ClipboardList} iconTone="muted">
      {isLoading ? (
        <LoadingBox />
      ) : isError ? (
        <SectionError message={error.message} />
      ) : (
        <WorkflowAuditTimeline entries={entries} />
      )}
    </SectionCard>
  )
}
