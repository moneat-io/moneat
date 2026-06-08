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

import {Activity, Zap} from 'lucide-react'
import {cn} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {useUptimeSummary} from '../overviewData'
import {OverviewPanel, PanelLink} from '../OverviewPanel'

function barClass(state: 'up' | 'warn' | 'down'): string {
  switch (state) {
    case 'down':
      return 'bg-danger-solid'
    case 'warn':
      return 'bg-warning-solid'
    default:
      return 'bg-success-solid/80'
  }
}

/** Uptime monitor heartbeats + synthetics summary. */
export function UptimeSummaryWidget() {
  const d = useUptimeSummary()
  return (
    <OverviewPanel
      testId="widget-uptime_summary"
      title="Uptime & Synthetics"
      icon={Activity}
      count={d.upLabel}
      actions={<PanelLink to="/uptime">All</PanelLink>}
    >
      <div className="space-y-1">
        {d.monitors.map((m) => (
          <div key={m.name} className="flex items-center gap-1.5">
            <span className="w-20 shrink-0 truncate text-[11px] text-foreground">{m.name}</span>
            <span className="flex flex-1 gap-px">
              {m.bars.map((b, i) => (
                <span key={i} className={cn('h-3 flex-1 rounded-[1px]', barClass(b))} />
              ))}
            </span>
            <span
              className={cn(
                'w-11 shrink-0 text-right font-mono text-[9px]',
                m.down ? 'font-semibold text-danger-fg' : 'text-muted-foreground',
              )}
            >
              {m.uptimeLabel}
            </span>
          </div>
        ))}
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-1.5 border-t border-border/40 pt-2 text-[11px]">
        {d.syntheticFailing && (
          <Badge variant="danger" className="gap-1 px-1 py-0 text-[9px]">
            <Zap className="h-2.5 w-2.5" />
            Synthetic failing · {d.syntheticFailing}
          </Badge>
        )}
        <span className="ml-auto inline-flex items-center gap-1 rounded-sm border px-1 py-0.5 text-[9px] text-muted-foreground">
          {d.statusPages}
        </span>
      </div>
    </OverviewPanel>
  )
}
