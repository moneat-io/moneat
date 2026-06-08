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

import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { api, type LlmRangeParams } from '@/lib/api'
import { StatsCard } from '@/components/charts/StatsCard'
import { EventsChart } from '@/components/charts/EventsChart'
import { BarChart } from '@/components/charts/BarChart'
import { ExplorerShell } from '@/components/filters/ExplorerShell'
import { FacetRail } from '@/components/filters/FacetRail'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { SectionCard } from '@/components/ui/section-card'
import { EmptyState } from '@/components/ui/empty-state'
import { Brain, Coins, Clock, AlertTriangle, Hash, Zap, BookOpen, ArrowRight, TrendingUp } from 'lucide-react'
import { ProviderLogo } from '@/components/icons/AiProviders'
import {
  facetValues,
  serviceNamesForQuery,
  serviceRailSections,
  serviceScopeKey,
} from '@/lib/service-facet-scope'
import type {FacetFilter} from '@/lib/filters/types'

export const Route = createFileRoute('/ai/')({
  component: AiOverviewPage,
})

function formatDuration(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

function formatCost(usd: number): string {
  if (usd < 0.01) return `$${usd.toFixed(4)}`
  return `$${usd.toFixed(2)}`
}

function formatTokens(tokens: number): string {
  if (tokens >= 1_000_000) return `${(tokens / 1_000_000).toFixed(1)}M`
  if (tokens >= 1_000) return `${(tokens / 1_000).toFixed(1)}K`
  return String(tokens)
}

function AiOverviewPage() {
  const [range, setRange] = useState('24h')
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>([])

  const {data: projects, isLoading: projectsLoading, error: projectsError} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectList = projects ?? []
  const includedServices = useMemo(
    () => facetValues(facetFilters, 'service', false),
    [facetFilters]
  )
  const excludedServices = useMemo(
    () => facetValues(facetFilters, 'service', true),
    [facetFilters]
  )
  const hasServiceFilters = includedServices.length > 0 || excludedServices.length > 0
  const serviceNames = useMemo(
    () => serviceNamesForQuery(projectList, includedServices, excludedServices),
    [projectList, includedServices, excludedServices]
  )
  const scopeKey = serviceScopeKey(serviceNames, hasServiceFilters)
  const hasLlmScope = projectList.length > 0 && (!hasServiceFilters || serviceNames.length > 0)
  const llmParams = useMemo((): LlmRangeParams => ({
    range,
    ...(serviceNames.length > 0 ? {services: [...serviceNames]} : {}),
  }), [range, serviceNames])
  const railSections = useMemo(() => serviceRailSections(projectList), [projectList])

  const { data: overview, isLoading } = useQuery({
    queryKey: ['llm-overview', 'organization', scopeKey, llmParams],
    queryFn: () => api.getLlmOverview(llmParams),
    enabled: hasLlmScope,
  })

  if (projectsLoading) {
    return <div className="p-8 text-sm text-muted-foreground">Loading AI observability...</div>
  }

  if (projectsError) {
    return (
      <div className="p-8 text-destructive">
        Failed to load services: {projectsError instanceof Error ? projectsError.message : 'Unknown error'}
      </div>
    )
  }

  if (projectList.length === 0) {
    return (
      <div className="py-10">
        <EmptyState
          icon={Brain}
          title="No services yet"
          description="Create a service to start monitoring AI and LLM telemetry."
        />
      </div>
    )
  }

  const timelineData = overview?.timeline.map(p => ({
    timestamp: p.timestamp,
    count: Number(p.count),
  })) ?? []

  const modelBreakdown: Record<string, number> = {}
  const tokenBreakdown: Record<string, number> = {}
  const costBreakdown: Record<string, number> = {}
  overview?.topModels.forEach(m => {
    const label = m.model || 'unknown'
    modelBreakdown[label] = Number(m.callCount)
    tokenBreakdown[label] = Number(m.totalTokens)
    costBreakdown[label] = m.totalCost
  })

  return (
    <ExplorerShell
      title="AI Observability"
      icon={<Brain className="h-4 w-4 text-muted-foreground" />}
      searchBar={<div />}
      actions={
        <>
          <Link to="/ai/generations" className="text-xs text-primary hover:underline">
            View all generations
          </Link>
          <Button asChild size="sm">
            <a href="/docs/ai-observability">
              <BookOpen className="h-3.5 w-3.5" />
              Get started
              <ArrowRight className="h-3.5 w-3.5" />
            </a>
          </Button>
          <Select value={range} onValueChange={setRange}>
            <SelectTrigger className="w-full sm:w-28 h-7 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="1h">Last 1h</SelectItem>
              <SelectItem value="6h">Last 6h</SelectItem>
              <SelectItem value="24h">Last 24h</SelectItem>
              <SelectItem value="7d">Last 7d</SelectItem>
              <SelectItem value="14d">Last 14d</SelectItem>
              <SelectItem value="30d">Last 30d</SelectItem>
            </SelectContent>
          </Select>
        </>
      }
      rail={
        <FacetRail
          sections={railSections}
          facetFilters={facetFilters}
          onFacetFiltersChange={setFacetFilters}
          title="AI"
        />
      }
    >
      <div className="space-y-3 p-3">
        {!hasLlmScope ? (
          <div className="py-10">
            <EmptyState
              icon={Brain}
              title="No services match filters"
              description="Adjust the selected services to view AI observability data."
            />
          </div>
        ) : (
          <>
            {/* Stats Row */}
            <div className="grid grid-cols-2 md:grid-cols-5 gap-1.5">
              <StatsCard
                compact
                title="Total Generations"
                value={isLoading ? '...' : formatTokens(overview?.totalGenerations ?? 0)}
                icon={Zap}
                accent="blue"
              />
              <StatsCard
                compact
                title="Total Tokens"
                value={isLoading ? '...' : formatTokens(overview?.totalTokens ?? 0)}
                icon={Hash}
                accent="violet"
              />
              <StatsCard
                compact
                title="Total Cost"
                value={isLoading ? '...' : formatCost(overview?.totalCost ?? 0)}
                icon={Coins}
                accent="emerald"
              />
              <StatsCard
                compact
                title="Avg Latency"
                value={isLoading ? '...' : formatDuration(overview?.avgDurationMs ?? 0)}
                icon={Clock}
                accent="amber"
              />
              <StatsCard
                compact
                title="Error Rate"
                value={isLoading ? '...' : `${(overview?.errorRate ?? 0).toFixed(1)}%`}
                icon={AlertTriangle}
                accent="rose"
              />
            </div>

            {/* Charts Row */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-2">
              <EventsChart data={timelineData} title="LLM Calls Over Time" height={160} />
              <BarChart data={modelBreakdown} title="Calls by Model" height={160} />
            </div>

            {/* Token Breakdown & Cost */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-2">
              <BarChart data={tokenBreakdown} title="Tokens by Model" height={160} />
              <BarChart data={costBreakdown} title="Cost by Model" height={160} />
            </div>

            {/* Top Models Table */}
            <SectionCard title="Top Models" icon={TrendingUp} flushBody bodyClassName="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Model</TableHead>
                      <TableHead className="text-right">Calls</TableHead>
                      <TableHead className="text-right">Tokens</TableHead>
                      <TableHead className="text-right">Cost</TableHead>
                      <TableHead className="text-right">Avg Latency</TableHead>
                      <TableHead className="text-right">Errors</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {overview?.topModels.map((m) => (
                      <TableRow key={`${m.provider}-${m.model}`}>
                        <TableCell>
                          <div className="flex items-center gap-2.5">
                            <ProviderLogo provider={m.provider} showName={false} className="shrink-0" />
                            <div className="min-w-0">
                              <div className="font-medium text-sm">{m.model || 'unknown'}</div>
                              <div className="text-xs text-muted-foreground">{m.provider}</div>
                            </div>
                          </div>
                        </TableCell>
                        <TableCell className="text-right tabular-nums">{m.callCount}</TableCell>
                        <TableCell className="text-right tabular-nums">
                          {formatTokens(Number(m.totalTokens))}
                        </TableCell>
                        <TableCell className="text-right tabular-nums">{formatCost(m.totalCost)}</TableCell>
                        <TableCell className="text-right tabular-nums">{formatDuration(m.avgDurationMs)}</TableCell>
                        <TableCell className="text-right">
                          <Badge
                            variant={m.errorRate > 5 ? 'destructive' : 'secondary'}
                            className="text-xs tabular-nums"
                          >
                            {m.errorRate.toFixed(1)}%
                          </Badge>
                        </TableCell>
                      </TableRow>
                    ))}
                    {(!overview?.topModels || overview.topModels.length === 0) && (
                      <TableRow>
                        <TableCell colSpan={6} className="text-center text-muted-foreground py-6">
                          No LLM data yet.{' '}
                          <a href="/docs/ai-observability" className="text-primary hover:underline">
                            Read the docs
                          </a>{' '}
                          to get started.
                        </TableCell>
                      </TableRow>
                    )}
                  </TableBody>
                </Table>
            </SectionCard>
          </>
        )}
      </div>
    </ExplorerShell>
  )
}
