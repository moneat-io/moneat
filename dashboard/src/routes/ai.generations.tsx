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
import { useState } from 'react'
import { api } from '@/lib/api'
import { useProject } from '@/contexts/project-context'
import { Card, CardContent } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { ChevronLeft, ChevronRight, Brain, Eye } from 'lucide-react'

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
  const { selectedProjectId } = useProject()
  const [range, setRange] = useState('24h')
  const [page, setPage] = useState(1)
  const [modelFilter, setModelFilter] = useState<string>('')
  const [statusFilter, setStatusFilter] = useState<string>('')
  const [typeFilter, setTypeFilter] = useState<string>('')
  const [selectedGenId, setSelectedGenId] = useState<string | null>(null)

  const { data, isLoading } = useQuery({
    queryKey: ['llm-generations', selectedProjectId, range, page, modelFilter, statusFilter, typeFilter],
    queryFn: () => api.getLlmGenerations(selectedProjectId!, {
      range,
      page,
      pageSize: 25,
      model: modelFilter || undefined,
      status: statusFilter || undefined,
      type: typeFilter || undefined,
    }),
    enabled: !!selectedProjectId,
  })

  const { data: detail } = useQuery({
    queryKey: ['llm-generation-detail', selectedProjectId, selectedGenId],
    queryFn: () => api.getLlmGenerationDetail(selectedProjectId!, selectedGenId!),
    enabled: !!selectedProjectId && !!selectedGenId,
  })

  if (!selectedProjectId) {
    return (
      <div className="flex items-center justify-center h-64">
        <p className="text-muted-foreground">Select a project to view generations.</p>
      </div>
    )
  }

  const totalPages = data ? Math.ceil(data.total / data.pageSize) : 0

  return (
    <div className="space-y-4 p-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <h1 className="text-2xl font-bold flex items-center gap-2">
          <Brain className="h-6 w-6" />
          Generations
        </h1>
        <div className="flex flex-wrap gap-2">
          <Input
            placeholder="Filter by model..."
            value={modelFilter}
            onChange={(e) => { setModelFilter(e.target.value); setPage(1) }}
            className="w-40"
          />
          <Select value={typeFilter} onValueChange={(v) => { setTypeFilter(v === 'all' ? '' : v); setPage(1) }}>
            <SelectTrigger className="w-28">
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
          <Select value={statusFilter} onValueChange={(v) => { setStatusFilter(v === 'all' ? '' : v); setPage(1) }}>
            <SelectTrigger className="w-28">
              <SelectValue placeholder="Status" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All</SelectItem>
              <SelectItem value="success">Success</SelectItem>
              <SelectItem value="error">Error</SelectItem>
            </SelectContent>
          </Select>
          <Select value={range} onValueChange={(v) => { setRange(v); setPage(1) }}>
            <SelectTrigger className="w-28">
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
      </div>

      <Card>
        <CardContent className="p-0 overflow-x-auto">
          <Table>
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
                    {new Date(gen.timestamp).toLocaleString()}
                  </TableCell>
                  <TableCell className="font-medium text-sm max-w-48 truncate">
                    {gen.name || '-'}
                  </TableCell>
                  <TableCell>
                    <div>
                      <div className="text-sm">{gen.model || '-'}</div>
                      <div className="text-xs text-muted-foreground">{gen.provider}</div>
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
                        search={{ projectId: selectedProjectId }}
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
                  <TableCell colSpan={9} className="text-center text-muted-foreground py-12">
                    No generations found for the selected filters.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between">
          <span className="text-sm text-muted-foreground">
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

      {/* Detail Dialog */}
      <Dialog open={!!selectedGenId} onOpenChange={(open) => { if (!open) setSelectedGenId(null) }}>
        <DialogContent className="max-w-3xl max-h-[80vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>Generation Detail</DialogTitle>
          </DialogHeader>
          {detail && (
            <div className="space-y-4">
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm">
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
                <div className="rounded-md bg-destructive/10 p-3 text-sm text-destructive">
                  <strong>Error:</strong> {detail.errorMessage}
                </div>
              )}
              <div>
                <h4 className="text-sm font-semibold mb-2">Input</h4>
                <pre className="bg-muted rounded-md p-3 text-xs overflow-x-auto max-h-64 overflow-y-auto whitespace-pre-wrap">
                  {tryFormatJson(detail.input)}
                </pre>
              </div>
              <div>
                <h4 className="text-sm font-semibold mb-2">Output</h4>
                <pre className="bg-muted rounded-md p-3 text-xs overflow-x-auto max-h-64 overflow-y-auto whitespace-pre-wrap">
                  {tryFormatJson(detail.output)}
                </pre>
              </div>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
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
