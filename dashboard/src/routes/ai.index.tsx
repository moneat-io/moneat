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
import { StatsCard } from '@/components/charts/stats-card'
import { EventsChart } from '@/components/charts/events-chart'
import { BarChart } from '@/components/charts/bar-chart'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Badge } from '@/components/ui/badge'
import { Brain, Coins, Clock, AlertTriangle, Hash, Zap, BookOpen, ArrowRight, TrendingUp } from 'lucide-react'
import { ProviderLogo } from '@/components/icons/ai-providers'

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
  const { selectedProjectId } = useProject()
  const [range, setRange] = useState('24h')

  const { data: overview, isLoading } = useQuery({
    queryKey: ['llm-overview', selectedProjectId, range],
    queryFn: () => api.getLlmOverview(selectedProjectId!, range),
    enabled: !!selectedProjectId,
  })

  if (!selectedProjectId) {
    return (
      <div className="flex items-center justify-center h-64">
        <p className="text-muted-foreground">Select a project to view AI observability data.</p>
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
    <div className="space-y-6 p-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold flex items-center gap-2 whitespace-nowrap">
            <Brain className="h-6 w-6" />
            AI Observability
          </h1>
          <p className="text-muted-foreground text-sm mt-1">
            Monitor your LLM applications, trace agent executions, and track costs.
          </p>
          <a
            href="/docs/ai-observability"
            className="inline-flex items-center gap-2 mt-3 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-sm font-medium hover:bg-primary/90 transition-colors"
          >
            <BookOpen className="h-4 w-4" />
            Get Started
            <ArrowRight className="h-4 w-4" />
          </a>
        </div>
        <div className="w-full sm:w-auto">
          <Select value={range} onValueChange={setRange}>
            <SelectTrigger className="w-full sm:w-32">
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
        </div>
      </div>

      {/* Stats Row */}
      <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
        <StatsCard
          title="Total Generations"
          value={isLoading ? '...' : formatTokens(overview?.totalGenerations ?? 0)}
          icon={Zap}
          accent="blue"
        />
        <StatsCard
          title="Total Tokens"
          value={isLoading ? '...' : formatTokens(overview?.totalTokens ?? 0)}
          icon={Hash}
          accent="violet"
        />
        <StatsCard
          title="Total Cost"
          value={isLoading ? '...' : formatCost(overview?.totalCost ?? 0)}
          icon={Coins}
          accent="emerald"
        />
        <StatsCard
          title="Avg Latency"
          value={isLoading ? '...' : formatDuration(overview?.avgDurationMs ?? 0)}
          icon={Clock}
          accent="amber"
        />
        <StatsCard
          title="Error Rate"
          value={isLoading ? '...' : `${(overview?.errorRate ?? 0).toFixed(1)}%`}
          icon={AlertTriangle}
          accent="rose"
        />
      </div>

      {/* Charts Row */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <EventsChart data={timelineData} title="LLM Calls Over Time" height={250} />
        <BarChart data={modelBreakdown} title="Calls by Model" height={250} />
      </div>

      {/* Token Breakdown & Cost */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <BarChart data={tokenBreakdown} title="Tokens by Model" height={250} />
        <BarChart data={costBreakdown} title="Cost by Model" height={250} />
      </div>

      {/* Top Models Table */}
      <Card>
        <CardHeader className="px-4 py-3 flex flex-row items-center gap-2">
          <TrendingUp className="h-4 w-4 text-muted-foreground" />
          <CardTitle className="text-sm">Top Models</CardTitle>
        </CardHeader>
        <CardContent className="px-4 pb-3 pt-0 overflow-x-auto">
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
                  <TableCell className="text-right tabular-nums">{formatTokens(Number(m.totalTokens))}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatCost(m.totalCost)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatDuration(m.avgDurationMs)}</TableCell>
                  <TableCell className="text-right">
                    <Badge variant={m.errorRate > 5 ? 'destructive' : 'secondary'} className="text-xs tabular-nums">
                      {m.errorRate.toFixed(1)}%
                    </Badge>
                  </TableCell>
                </TableRow>
              ))}
              {(!overview?.topModels || overview.topModels.length === 0) && (
                <TableRow>
                  <TableCell colSpan={6} className="text-center text-muted-foreground py-8">
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
        </CardContent>
      </Card>

      {/* Quick Links */}
      <div className="flex gap-3">
        <Link to="/ai/generations" className="text-sm text-primary hover:underline">
          View All Generations →
        </Link>
        <a href="/docs/ai-observability" className="text-sm text-muted-foreground hover:text-primary hover:underline transition-colors">
          Documentation →
        </a>
      </div>
    </div>
  )
}
