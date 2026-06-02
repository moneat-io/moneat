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

import { createFileRoute } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api, type LlmGenerationDetail } from '@/lib/api'
import { useProject } from '@/contexts/ProjectContext'
import { Badge } from '@/components/ui/badge'
import { StatsCard } from '@/components/charts/StatsCard'
import { PageHeader } from '@/components/ui/page-header'
import { SectionCard } from '@/components/ui/section-card'
import { EmptyState } from '@/components/ui/empty-state'
import { Brain, Clock, Coins, Hash, AlertTriangle } from 'lucide-react'
import { cn } from '@/lib/utils'

export const Route = createFileRoute('/ai/traces/$traceId')({
  validateSearch: (search: Record<string, unknown>) => {
    const rawProjectId = search.projectId
    return {
      projectId: typeof rawProjectId === 'string' && rawProjectId.length > 0 ? rawProjectId : undefined,
    }
  },
  component: TraceDetailPage,
})

function formatDuration(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

function formatCost(usd: number): string {
  if (usd === 0) return '$0'
  if (usd < 0.01) return `$${usd.toFixed(4)}`
  return `$${usd.toFixed(2)}`
}

// Categorical span-type hues from the shared chart palette (literal classes so
// Tailwind emits them); these encode type, not status.
function typeColor(type: string): string {
  switch (type) {
    case 'chat': return 'bg-chart-1'
    case 'completion': return 'bg-chart-2'
    case 'embedding': return 'bg-chart-3'
    case 'tool_call': return 'bg-chart-4'
    case 'agent': return 'bg-chart-5'
    case 'chain': return 'bg-chart-6'
    case 'retriever': return 'bg-chart-7'
    default: return 'bg-muted-foreground'
  }
}

type GenerationNode = LlmGenerationDetail & { children: GenerationNode[] }

function buildTree(generations: LlmGenerationDetail[]): GenerationNode[] {
  const nodes = generations.map((gen) => ({ ...gen, children: [] as GenerationNode[] }))
  const spanIndex = new Map<string, GenerationNode[]>()
  const roots: GenerationNode[] = []

  for (const node of nodes) {
    if (!node.spanId) continue
    const bucket = spanIndex.get(node.spanId)
    if (bucket) {
      bucket.push(node)
    } else {
      spanIndex.set(node.spanId, [node])
    }
  }

  for (const bucket of spanIndex.values()) {
    bucket.sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime())
  }

  for (const node of nodes) {
    const parent = findParentBySpanId(spanIndex.get(node.parentSpanId), node)
    if (parent) {
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  }

  const sortNodes = (items: GenerationNode[]) => {
    items.sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime())
    for (const item of items) sortNodes(item.children)
  }
  sortNodes(roots)
  return roots
}

function findParentBySpanId(candidates: GenerationNode[] | undefined, child: GenerationNode): GenerationNode | undefined {
  if (!candidates || candidates.length === 0) return undefined

  const childTs = new Date(child.timestamp).getTime()
  let fallback: GenerationNode | undefined

  for (const candidate of candidates) {
    if (candidate.generationId === child.generationId) continue
    fallback = candidate
    if (new Date(candidate.timestamp).getTime() > childTs) {
      break
    }
  }

  return fallback
}

function TraceDetailPage() {
  const { traceId } = Route.useParams()
  const search = Route.useSearch()
  const { selectedProjectId } = useProject()
  const [selectedGen, setSelectedGen] = useState<LlmGenerationDetail | null>(null)

  const projectId = search.projectId ?? selectedProjectId

  const { data: trace, isLoading } = useQuery({
    queryKey: ['llm-trace', projectId, traceId],
    queryFn: () => api.getLlmTrace(projectId!, traceId),
    enabled: projectId !== null && projectId !== undefined,
  })

  if (projectId === null || projectId === undefined) {
    return (
      <div className="p-6">
        <EmptyState icon={Brain} title="No project selected" description="Select a project to view this trace." />
      </div>
    )
  }

  if (isLoading) {
    return <div className="p-6 text-muted-foreground">Loading trace...</div>
  }

  if (!trace) {
    return (
      <div className="p-6">
        <EmptyState icon={AlertTriangle} title="Trace not found" description="This trace could not be found." />
      </div>
    )
  }

  const tree = buildTree(trace.generations)

  // Calculate waterfall timings
  const minTs = Math.min(...trace.generations.map(g => new Date(g.timestamp).getTime() - g.durationMs))
  const maxTs = Math.max(...trace.generations.map(g => new Date(g.timestamp).getTime()))
  const totalSpan = maxTs - minTs || 1

  return (
    <div className="space-y-4 p-6">
      <PageHeader
        icon={Brain}
        eyebrow="AI Trace"
        title={<span className="font-mono text-lg">{traceId.slice(0, 16)}…</span>}
        description={`${trace.generations.length} generation(s) in this trace`}
      />

      {/* Summary Stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <StatsCard title="Generations" value={trace.generations.length} icon={Brain} accent="blue" />
        <StatsCard title="Total Duration" value={formatDuration(trace.totalDurationMs)} icon={Clock} accent="amber" />
        <StatsCard title="Total Tokens" value={Number(trace.totalTokens).toLocaleString()} icon={Hash} accent="violet" />
        <StatsCard title="Total Cost" value={formatCost(trace.totalCost)} icon={Coins} accent="emerald" />
      </div>

      {/* Waterfall + Detail Panel */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* Waterfall */}
        <SectionCard title="Span Waterfall" className="lg:col-span-2">
            <div className="space-y-0.5">
              {renderNodes(tree, 0, minTs, totalSpan, selectedGen, setSelectedGen)}
            </div>
            {trace.generations.length === 0 && (
              <p className="text-center text-muted-foreground py-8">No generations in this trace.</p>
            )}
        </SectionCard>

        {/* Detail Panel */}
        <SectionCard title={selectedGen ? selectedGen.name || 'Generation Detail' : 'Select a span'}>
            {selectedGen ? (
              <div className="space-y-3 text-sm">
                <div className="grid grid-cols-2 gap-2">
                  <div><span className="text-muted-foreground">Model:</span> <span className="font-medium">{selectedGen.model}</span></div>
                  <div><span className="text-muted-foreground">Provider:</span> <span className="font-medium">{selectedGen.provider}</span></div>
                  <div><span className="text-muted-foreground">Type:</span> <Badge variant="outline">{selectedGen.type}</Badge></div>
                  <div><span className="text-muted-foreground">Status:</span> <Badge variant={selectedGen.status === 'error' ? 'destructive' : 'secondary'}>{selectedGen.status}</Badge></div>
                  <div><span className="text-muted-foreground">Duration:</span> {formatDuration(selectedGen.durationMs)}</div>
                  <div><span className="text-muted-foreground">Tokens:</span> {selectedGen.inputTokens}/{selectedGen.outputTokens}</div>
                  <div><span className="text-muted-foreground">Cost:</span> {formatCost(selectedGen.costUsd)}</div>
                </div>
                {selectedGen.errorMessage && (
                  <div className="bg-destructive/10 text-destructive rounded p-2 text-xs">
                    {selectedGen.errorMessage}
                  </div>
                )}
                <div>
                  <h4 className="text-xs font-semibold mb-1 text-muted-foreground">Input</h4>
                  <pre className="bg-muted rounded p-2 text-xs max-h-48 overflow-auto whitespace-pre-wrap">
                    {tryFormatJson(selectedGen.input)}
                  </pre>
                </div>
                <div>
                  <h4 className="text-xs font-semibold mb-1 text-muted-foreground">Output</h4>
                  <pre className="bg-muted rounded p-2 text-xs max-h-48 overflow-auto whitespace-pre-wrap">
                    {tryFormatJson(selectedGen.output)}
                  </pre>
                </div>
              </div>
            ) : (
              <p className="text-muted-foreground text-sm py-4">Click a span in the waterfall to see details.</p>
            )}
        </SectionCard>
      </div>
    </div>
  )
}

function renderNodes(
  nodes: GenerationNode[],
  depth: number,
  minTs: number,
  totalSpan: number,
  selectedGen: LlmGenerationDetail | null,
  setSelectedGen: (gen: LlmGenerationDetail) => void
): React.ReactNode[] {
  return nodes.flatMap(node => {
    const startMs = new Date(node.timestamp).getTime() - node.durationMs
    const leftPct = ((startMs - minTs) / totalSpan) * 100
    const widthPct = Math.max((node.durationMs / totalSpan) * 100, 0.5)
    const isSelected = selectedGen?.generationId === node.generationId

    return [
      <div
        key={node.generationId}
        className={cn(
          'flex items-center gap-2 px-2 py-1.5 rounded-sm cursor-pointer transition-colors text-sm',
          isSelected ? 'bg-primary/10' : 'hover:bg-accent/50'
        )}
        style={{ paddingLeft: `${depth * 20 + 8}px` }}
        onClick={() => setSelectedGen(node)}
      >
        <div className="w-32 shrink-0 truncate">
          <span className="font-medium text-xs">{node.name || node.type}</span>
          <span className="text-[10px] text-muted-foreground ml-1">{node.model}</span>
        </div>
        <div className="flex-1 relative h-5 bg-muted/40 rounded overflow-hidden">
          <div
            className={cn('absolute top-0 h-full rounded', typeColor(node.type))}
            style={{
              left: `${leftPct}%`,
              width: `${widthPct}%`,
              minWidth: '2px',
              opacity: 0.8,
            }}
          />
        </div>
        <div className="w-16 text-right text-xs text-muted-foreground shrink-0">
          {formatDuration(node.durationMs)}
        </div>
      </div>,
      ...renderNodes(node.children, depth + 1, minTs, totalSpan, selectedGen, setSelectedGen),
    ]
  })
}

function tryFormatJson(str: string): string {
  if (!str) return '-'
  try {
    return JSON.stringify(JSON.parse(str), null, 2)
  } catch {
    return str
  }
}
