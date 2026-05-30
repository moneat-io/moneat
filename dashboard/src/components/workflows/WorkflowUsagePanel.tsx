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

import {Infinity as InfinityIcon} from 'lucide-react'
import type {WorkflowUsageResponse} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {formatCount} from './insightsFormat'

interface WorkflowUsagePanelProps {
  usage: WorkflowUsageResponse
}

function usedPercent(used: number, limit: number | null): number | null {
  if (limit === null || limit <= 0) return null
  return Math.min(100, Math.round((used / limit) * 100))
}

export function WorkflowUsagePanel({usage}: WorkflowUsagePanelProps) {
  const percent = usage.unlimited ? null : usedPercent(usage.used, usage.limit)
  return (
    <div className="rounded-md border bg-background p-4">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold">Run usage</h3>
        <Badge variant="outline">{usage.period}</Badge>
      </div>
      <div className="mt-3 flex items-baseline gap-2">
        <span className="text-2xl font-semibold tabular-nums">{formatCount(usage.used)}</span>
        {usage.unlimited ? (
          <span className="flex items-center gap-1 text-sm text-muted-foreground">
            / <InfinityIcon className="h-4 w-4" aria-hidden="true" />
            <span>unlimited</span>
          </span>
        ) : (
          <span className="text-sm text-muted-foreground">
            / {usage.limit === null ? 'unlimited' : formatCount(usage.limit)}
          </span>
        )}
      </div>
      {percent !== null && (
        <div className="mt-3">
          <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
            <div
              className="h-full rounded-full bg-primary"
              style={{width: percent + '%'}}
              data-testid="usage-bar"
            />
          </div>
        </div>
      )}
      <p className="mt-2 text-xs text-muted-foreground">
        {usage.unlimited
          ? 'This plan has no run limit.'
          : usage.remaining === null
            ? 'Remaining runs are not tracked for this plan.'
            : formatCount(usage.remaining) + ' runs remaining this period.'}
      </p>
    </div>
  )
}
