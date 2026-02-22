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

import {Loader2, CheckCircle2, XCircle, Database} from 'lucide-react'

interface SourceStatus {
  source: string
  status: 'pending' | 'in_progress' | 'done' | 'error'
  count?: number
}

interface ContextAggregationProgressProps {
  sources: SourceStatus[]
  totalTokens?: number
}

const SOURCE_LABELS: Record<string, string> = {
  logs: 'Logs',
  spans: 'Traces / Spans',
  events: 'Error Events',
  metrics: 'Server Metrics',
  containers: 'Containers',
}

export function ContextAggregationProgress({sources, totalTokens}: ContextAggregationProgressProps) {
  const allDone = sources.length > 0 && sources.every(s => s.status === 'done')

  return (
    <div className="ml-9 mt-2 border border-border rounded-lg p-3 bg-card text-sm space-y-2">
      <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground mb-1">
        <Database className="h-3 w-3" />
        <span>Searching observability data…</span>
      </div>

      {sources.map(s => (
        <div key={s.source} className="flex items-center gap-2 text-xs">
          {s.status === 'in_progress' && <Loader2 className="h-3 w-3 animate-spin text-primary" />}
          {s.status === 'done' && <CheckCircle2 className="h-3 w-3 text-green-500" />}
          {s.status === 'error' && <XCircle className="h-3 w-3 text-red-500" />}
          {s.status === 'pending' && <div className="h-3 w-3 rounded-full border border-muted-foreground/30" />}
          <span>{SOURCE_LABELS[s.source] ?? s.source}</span>
          {s.status === 'done' && s.count !== undefined && (
            <span className="text-muted-foreground">({s.count.toLocaleString()} results)</span>
          )}
        </div>
      ))}

      {allDone && (
        <div className="pt-2 border-t border-border mt-2">
          {totalTokens !== undefined ? (
            <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <Loader2 className="h-3 w-3 animate-spin" />
              <span>Analyzing with AI…</span>
            </div>
          ) : null}
        </div>
      )}
    </div>
  )
}
