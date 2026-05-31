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

import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {ArrowLeft, Gauge, Loader2} from 'lucide-react'
import {api} from '@/lib/api'
import type {WorkflowBlueprintSummary} from '@/lib/api'
import {BlueprintGallery} from '@/components/workflows/BlueprintGallery'
import {WorkflowAuditTimeline} from '@/components/workflows/WorkflowAuditTimeline'
import {WorkflowOverviewCards} from '@/components/workflows/WorkflowOverviewCards'
import {WorkflowUsagePanel} from '@/components/workflows/WorkflowUsagePanel'
import {Button} from '@/components/ui/button'
import {useToast} from '@/hooks/useToast'

const AUDIT_LIMIT = 25

export const Route = createFileRoute('/workflows/insights')({
  beforeLoad: async () => {
    const authenticated = api.isAuthenticated() || (await api.checkAuth())
    if (!authenticated) {
      throw redirect({to: '/login'})
    }
  },
  component: InsightsPage,
})

function InsightsPage() {
  return (
    <div className="workflows-insights-page">
      <PageHeader />
      <div className="space-y-6 px-4 py-4 lg:px-6">
        <InsightsSection />
        <BlueprintsSection />
        <AuditSection />
      </div>
    </div>
  )
}

function PageHeader() {
  return (
    <div className="border-b bg-card/50">
      <div className="flex flex-col gap-3 px-4 py-3 sm:flex-row sm:items-center sm:justify-between lg:px-6">
        <div className="flex min-w-0 items-center gap-2">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
            <Gauge className="h-4 w-4" />
          </div>
          <div>
            <h1 className="text-lg font-bold tracking-tight">Automation insights</h1>
            <p className="text-xs text-muted-foreground">
              Overview, run usage, blueprints, and the audit trail for your automations.
            </p>
          </div>
        </div>
        <Button size="sm" variant="outline" asChild className="gap-1.5">
          <Link to="/workflows">
            <ArrowLeft className="h-3.5 w-3.5" />
            Back to workflows
          </Link>
        </Button>
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
    <div className="rounded-md border p-4 text-sm">
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
    <section className="space-y-3">
      <h2 className="text-sm font-semibold">Overview</h2>
      <div className="grid gap-3 xl:grid-cols-[minmax(0,1fr)_320px]">
        <OverviewBlock query={overviewQuery} />
        <UsageBlock query={usageQuery} />
      </div>
    </section>
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
    <section className="space-y-3">
      <h2 className="text-sm font-semibold">Blueprints</h2>
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
    </section>
  )
}

function AuditSection() {
  const {data: entries = [], isLoading, isError, error} = useQuery({
    queryKey: ['workflow-audit', AUDIT_LIMIT],
    queryFn: () => api.getWorkflowAudit(AUDIT_LIMIT),
  })

  return (
    <section className="space-y-3">
      <h2 className="text-sm font-semibold">Audit trail</h2>
      {isLoading ? (
        <LoadingBox />
      ) : isError ? (
        <SectionError message={error.message} />
      ) : (
        <WorkflowAuditTimeline entries={entries} />
      )}
    </section>
  )
}
