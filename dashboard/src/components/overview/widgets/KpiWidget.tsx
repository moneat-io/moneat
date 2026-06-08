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

import {ArrowDown, ArrowUp} from 'lucide-react'
import {cn} from '@/lib/utils'
import {StatusDot} from '@/components/ui/status-dot'
import {useKpis} from '../overviewMockData'
import {Sparkline} from '../OverviewPanel'
import {toneDot, toneText} from '../overviewTone'

/** A single KPI tile. The metric is chosen via display_config.kpiId. */
export function KpiWidget({displayConfig}: {displayConfig?: Record<string, string>}) {
  const kpis = useKpis()
  const kpi = kpis.find((k) => k.id === displayConfig?.kpiId) ?? kpis[0]
  if (!kpi) return null
  const {delta} = kpi
  return (
    <div
      data-testid="widget-kpi"
      className="flex h-full flex-col justify-between gap-1 rounded-lg border bg-card p-3"
    >
      <div className="flex items-center justify-between gap-2">
        <span className="truncate text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
          {kpi.label}
        </span>
        <StatusDot tone={toneDot[kpi.status]} />
      </div>
      <div className="flex items-baseline gap-1.5">
        <span className="font-mono text-xl font-semibold leading-none tabular-nums text-foreground">
          {kpi.value}
        </span>
        {kpi.unit && <span className="text-xs text-muted-foreground">{kpi.unit}</span>}
        <span
          className={cn(
            'ml-auto inline-flex items-center gap-0.5 text-[11px] font-semibold tabular-nums',
            toneText[delta.tone],
          )}
        >
          {delta.direction === 'up' && <ArrowUp className="h-3 w-3" />}
          {delta.direction === 'down' && <ArrowDown className="h-3 w-3" />}
          {delta.value}
        </span>
      </div>
      <Sparkline data={kpi.spark} className={cn('mt-0.5', toneText[kpi.status])} height={26} />
    </div>
  )
}
