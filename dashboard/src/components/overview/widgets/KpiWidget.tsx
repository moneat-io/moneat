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
      className="flex h-full flex-col justify-between gap-0.5 rounded-lg border border-border/60 bg-card p-2.5"
    >
      <div className="flex items-center justify-between gap-2">
        <span className="truncate text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
          {kpi.label}
        </span>
        <StatusDot tone={toneDot[kpi.status]} />
      </div>
      <div className="flex items-baseline gap-1">
        <span className="font-mono text-lg font-semibold leading-none tabular-nums text-foreground">
          {kpi.value}
        </span>
        {kpi.unit && <span className="text-[11px] text-muted-foreground">{kpi.unit}</span>}
        <span
          className={cn(
            'ml-auto inline-flex items-center gap-0.5 text-[10px] font-semibold tabular-nums',
            toneText[delta.tone],
          )}
        >
          {delta.direction === 'up' && <ArrowUp className="h-2.5 w-2.5" />}
          {delta.direction === 'down' && <ArrowDown className="h-2.5 w-2.5" />}
          {delta.value}
        </span>
      </div>
      <Sparkline data={kpi.spark} className={cn(toneText[kpi.status])} height={22} />
    </div>
  )
}
