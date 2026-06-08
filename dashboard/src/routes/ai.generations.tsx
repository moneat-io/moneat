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
import {useQuery} from '@tanstack/react-query'
import {useCallback, useMemo, useState} from 'react'
import {api, type LlmGenerationsParams, type LlmScopeParams} from '@/lib/api'
import {ExplorerShell} from '@/components/filters/ExplorerShell'
import {FacetRail} from '@/components/filters/FacetRail'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Dialog, DialogContent, DialogHeader, DialogTitle} from '@/components/ui/dialog'
import {Input} from '@/components/ui/input'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {Brain, ChevronLeft, ChevronRight} from 'lucide-react'
import {ProviderLogo} from '@/components/icons/AiProviders'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDateTime} from '@/lib/date-format'
import {
  facetValues,
  serviceNamesForQuery,
  serviceRailSections,
  serviceScopeKey,
} from '@/lib/service-facet-scope'
import type {FacetFilter} from '@/lib/filters/types'

export const Route = createFileRoute('/ai/generations')({
  component: GenerationsPage,
})

function formatDuration(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

function formatCost(usd: number): string {
  if (usd === 0) return '-'
  if (usd < 0.01) return `$${usd.toFixed(4)}`
  return `$${usd.toFixed(2)}`
}

function GenerationsPage() {
  const { timezone } = useTimezone()
  const [range, setRange] = useState('24h')
  const [page, setPage] = useState(1)
  const [modelFilter, setModelFilter] = useState<string>('')
  const [statusFilter, setStatusFilter] = useState<string>('')
  const [typeFilter, setTypeFilter] = useState<string>('')
  const [selectedGenId, setSelectedGenId] = useState<string | null>(null)
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
  const serviceScopeParams = useMemo((): LlmScopeParams => (
    serviceNames.length > 0 ? {services: [...serviceNames]} : {}
  ), [serviceNames])
  const llmParams = useMemo((): LlmGenerationsParams => ({
    range,
    page,
    pageSize: 25,
    model: modelFilter || undefined,
    status: statusFilter || undefined,
    type: typeFilter || undefined,
    ...serviceScopeParams,
  }), [modelFilter, page, range, serviceScopeParams, statusFilter, typeFilter])
  const railSections = useMemo(() => serviceRailSections(projectList), [projectList])

  const { data, isLoading } = useQuery({
    queryKey: ['llm-generations', 'organization', scopeKey, llmParams],
    queryFn: () => api.getLlmGenerations(llmParams),
    enabled: hasLlmScope,
  })

  const { data: detail } = useQuery({
    queryKey: ['llm-generation-detail', 'organization', scopeKey, selectedGenId],
    queryFn: () => api.getLlmGenerationDetail(selectedGenId!, serviceScopeParams),
    enabled: hasLlmScope && !!selectedGenId,
  })

  const handleFacetFiltersChange = useCallback((nextFilters: FacetFilter[]) => {
    setFacetFilters(nextFilters)
    setPage(1)
    setSelectedGenId(null)
  }, [])

  if (projectsLoading) {
    return <div className="p-8 text-sm text-muted-foreground">Loading generations...</div>
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
          description="Create a service to start viewing LLM generations."
        />
      </div>
    )
  }

  const totalPages = data ? Math.ceil(data.total / data.pageSize) : 0

  return (
    <ExplorerShell
      title="Generations"
      icon={<Brain className="h-4 w-4 text-muted-foreground" />}
      searchBar={<div />}
      actions={
        <div className="flex flex-wrap gap-1.5">
          <Input
            placeholder="Filter by model..."
            value={modelFilter}
            onChange={(e) => { setModelFilter(e.target.value); setPage(1) }}
            className="w-36 h-7 text-xs"
          />
          <Select
            value={typeFilter}
            onValueChange={(v) => { setTypeFilter(v === 'all' ? '' : v); setPage(1) }}
          >
            <SelectTrigger className="w-24 h-7 text-xs">
              <SelectValue placeholder="Type" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All Types</SelectItem>
              <SelectItem value="chat">Chat</SelectItem>
              <SelectItem value="completion">Completion</SelectItem>
              <SelectItem value="embedding">Embedding</SelectItem>
              <SelectItem value="tool_call">Tool Call</SelectItem>
              <SelectItem value="agent">Agent</SelectItem>
              <SelectItem value="chain">Chain</SelectItem>
            </SelectContent>
          </Select>
          <Select
            value={statusFilter}
            onValueChange={(v) => { setStatusFilter(v === 'all' ? '' : v); setPage(1) }}
          >
            <SelectTrigger className="w-24 h-7 text-xs">
              <SelectValue placeholder="Status" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All</SelectItem>
              <SelectItem value="success">Success</SelectItem>
              <SelectItem value="error">Error</SelectItem>
            </SelectContent>
          </Select>
          <Select value={range} onValueChange={(v) => { setRange(v); setPage(1) }}>
            <SelectTrigger className="w-24 h-7 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="1h">Last 1h</SelectItem>
              <SelectItem value="6h">Last 6h</SelectItem>
              <SelectItem value="24h">Last 24h</SelectItem>
              <SelectItem value="7d">Last 7d</SelectItem>
              <SelectItem value="30d">Last 30d</SelectItem>
            </SelectContent>
          </Select>
        </div>
      }
      rail={
        <FacetRail
          sections={railSections}
          facetFilters={facetFilters}
          onFacetFiltersChange={handleFacetFiltersChange}
          title="AI"
        />
      }
    >
      <div className="space-y-3 p-3">
        {hasLlmScope ? (
          <SectionCard title="Generations" icon={Brain} count={data?.total} flushBody bodyClassName="overflow-x-auto">
              <Table className="[&_th]:h-8 [&_th]:px-1.5 [&_th]:text-xs [&_td]:py-1.5 [&_td]:px-1.5">
                <TableHeader>
                  <TableRow>
                    <TableHead>Timestamp</TableHead>
                    <TableHead>Name</TableHead>
                    <TableHead>Model</TableHead>
                    <TableHead>Type</TableHead>
                    <TableHead className="text-right">Tokens</TableHead>
                    <TableHead className="text-right">Cost</TableHead>
                    <TableHead className="text-right">Duration</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead className="text-right">Trace</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data?.generations.map((gen) => (
                    <TableRow
                      key={gen.generationId}
                      className="cursor-pointer hover:bg-accent/50"
                      onClick={() => setSelectedGenId(gen.generationId)}
                    >
                      <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                        {formatDateTime(gen.timestamp, timezone)}
                      </TableCell>
                      <TableCell className="font-medium text-sm max-w-48 truncate">
                        {gen.name || '-'}
                      </TableCell>
                      <TableCell>
                        <div className="flex items-center gap-2">
                          <ProviderLogo provider={gen.provider} showName={false} className="shrink-0" />
                          <div>
                            <div className="text-sm">{gen.model || '-'}</div>
                            <div className="text-xs text-muted-foreground">{gen.provider}</div>
                          </div>
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline" className="text-xs">{gen.type}</Badge>
                      </TableCell>
                      <TableCell className="text-right text-sm">
                        {gen.totalTokens > 0 ? gen.totalTokens.toLocaleString() : '-'}
                      </TableCell>
                      <TableCell className="text-right text-sm">{formatCost(gen.costUsd)}</TableCell>
                      <TableCell className="text-right text-sm">{formatDuration(gen.durationMs)}</TableCell>
                      <TableCell>
                        <Badge
                          variant={gen.status === 'error' ? 'destructive' : 'secondary'}
                          className="text-xs"
                        >
                          {gen.status}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-right">
                        {gen.traceId && (
                          <Link
                            to="/ai/traces/$traceId"
                            params={{ traceId: gen.traceId }}
                            className="text-xs text-primary hover:underline"
                            onClick={(e) => e.stopPropagation()}
                          >
                            {gen.traceId.slice(0, 8)}...
                          </Link>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                  {(!data?.generations || data.generations.length === 0) && !isLoading && (
                    <TableRow>
                      <TableCell colSpan={9} className="text-center text-muted-foreground py-6">
                        No generations found for the selected filters.
                      </TableCell>
                    </TableRow>
                  )}
                </TableBody>
              </Table>
          </SectionCard>
        ) : (
          <div className="py-10">
            <EmptyState
              icon={Brain}
              title="No services match filters"
              description="Adjust the selected services to view generations."
            />
          </div>
        )}

        {/* Pagination */}
        {hasLlmScope && totalPages > 1 && (
          <div className="flex items-center justify-between py-1">
            <span className="text-xs text-muted-foreground">
              Page {page} of {totalPages} ({data?.total} total)
            </span>
            <div className="flex gap-1">
              <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage(p => p - 1)}>
                <ChevronLeft className="h-4 w-4" />
              </Button>
              <Button variant="outline" size="sm" disabled={page >= totalPages} onClick={() => setPage(p => p + 1)}>
                <ChevronRight className="h-4 w-4" />
              </Button>
            </div>
          </div>
        )}
      </div>

      {/* Detail Dialog */}
      <Dialog open={!!selectedGenId} onOpenChange={(open) => { if (!open) setSelectedGenId(null) }}>
        <DialogContent className="max-w-3xl max-h-[80vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>Generation Detail</DialogTitle>
          </DialogHeader>
          {detail && (
            <div className="space-y-2">
              <div className="grid grid-cols-2 md:grid-cols-4 gap-2 text-xs">
                <div><span className="text-muted-foreground">Model:</span> <span className="font-medium">{detail.model}</span></div>
                <div><span className="text-muted-foreground">Provider:</span> <span className="font-medium">{detail.provider}</span></div>
                <div><span className="text-muted-foreground">Type:</span> <Badge variant="outline">{detail.type}</Badge></div>
                <div><span className="text-muted-foreground">Status:</span> <Badge variant={detail.status === 'error' ? 'destructive' : 'secondary'}>{detail.status}</Badge></div>
                <div><span className="text-muted-foreground">Duration:</span> <span className="font-medium">{formatDuration(detail.durationMs)}</span></div>
                <div><span className="text-muted-foreground">Tokens:</span> <span className="font-medium">{detail.inputTokens} / {detail.outputTokens}</span></div>
                <div><span className="text-muted-foreground">Cost:</span> <span className="font-medium">{formatCost(detail.costUsd)}</span></div>
                <div><span className="text-muted-foreground">Temperature:</span> <span className="font-medium">{detail.temperature}</span></div>
              </div>
              {detail.errorMessage && (
                <div className="rounded-md bg-destructive/10 p-2 text-xs text-destructive">
                  <strong>Error:</strong> {detail.errorMessage}
                </div>
              )}
              <div>
                <h4 className="text-xs font-semibold mb-1">Input</h4>
                <pre className="bg-muted rounded-md p-2 text-xs overflow-x-auto max-h-48 overflow-y-auto whitespace-pre-wrap">
                  {tryFormatJson(detail.input)}
                </pre>
              </div>
              <div>
                <h4 className="text-xs font-semibold mb-1">Output</h4>
                <pre className="bg-muted rounded-md p-2 text-xs overflow-x-auto max-h-48 overflow-y-auto whitespace-pre-wrap">
                  {tryFormatJson(detail.output)}
                </pre>
              </div>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </ExplorerShell>
  )
}

function tryFormatJson(str: string): string {
  if (!str) return '-'
  try {
    return JSON.stringify(JSON.parse(str), null, 2)
  } catch {
    return str
  }
}
