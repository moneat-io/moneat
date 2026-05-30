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

import type {WorkflowOverviewResponse} from '@/lib/api'
import {formatCount, formatRate} from './insightsFormat'

interface WorkflowOverviewCardsProps {
  overview: WorkflowOverviewResponse
}

interface Metric {
  label: string
  value: string
}

function buildMetrics(overview: WorkflowOverviewResponse): Metric[] {
  return [
    {label: 'Total automations', value: formatCount(overview.total_workflows)},
    {label: 'Enabled', value: formatCount(overview.enabled_workflows)},
    {label: 'Published', value: formatCount(overview.published_workflows)},
    {label: 'Runs (30d)', value: formatCount(overview.runs_last_30d)},
    {label: 'Success rate', value: formatRate(overview.success_rate)},
    {label: 'Failed (30d)', value: formatCount(overview.failed_last_30d)},
  ]
}

export function WorkflowOverviewCards({overview}: WorkflowOverviewCardsProps) {
  const metrics = buildMetrics(overview)
  return (
    <div className="space-y-3">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {metrics.map((metric) => (
          <div key={metric.label} className="rounded-md border bg-background p-3">
            <p className="text-xs text-muted-foreground">{metric.label}</p>
            <p className="mt-1 text-2xl font-semibold tabular-nums">{metric.value}</p>
          </div>
        ))}
      </div>
      <TopWorkflows overview={overview} />
    </div>
  )
}

function TopWorkflows({overview}: {overview: WorkflowOverviewResponse}) {
  return (
    <div className="rounded-md border bg-background p-3">
      <h3 className="text-sm font-semibold">Most active automations</h3>
      {overview.top_workflows.length === 0 ? (
        <p className="mt-2 text-sm text-muted-foreground">No runs recorded yet.</p>
      ) : (
        <ul className="mt-2 space-y-1.5">
          {overview.top_workflows.map((entry) => (
            <li
              key={entry.workflow_id}
              className="flex items-center justify-between gap-2 text-sm"
            >
              <span className="truncate">{entry.name}</span>
              <span className="shrink-0 tabular-nums text-muted-foreground">
                {formatCount(entry.run_count)} runs
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
