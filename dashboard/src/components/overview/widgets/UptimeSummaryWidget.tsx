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
import {useUptimeSummary} from '../overviewMockData'
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
      actions={<PanelLink>All</PanelLink>}
    >
      <div className="space-y-1.5">
        {d.monitors.map((m) => (
          <div key={m.name} className="flex items-center gap-2">
            <span className="w-24 shrink-0 truncate text-xs text-foreground">{m.name}</span>
            <span className="flex flex-1 gap-0.5">
              {m.bars.map((b, i) => (
                <span key={i} className={cn('h-3.5 flex-1 rounded-[1px]', barClass(b))} />
              ))}
            </span>
            <span
              className={cn(
                'w-12 shrink-0 text-right font-mono text-[10px]',
                m.down ? 'font-semibold text-danger-fg' : 'text-muted-foreground',
              )}
            >
              {m.uptimeLabel}
            </span>
          </div>
        ))}
      </div>
      <div className="mt-2.5 flex flex-wrap items-center gap-2 border-t pt-2.5 text-xs">
        {d.syntheticFailing && (
          <Badge variant="danger" className="gap-1 px-1.5 py-0 text-[10px]">
            <Zap className="h-3 w-3" />
            Synthetic failing · {d.syntheticFailing}
          </Badge>
        )}
        <span className="ml-auto inline-flex items-center gap-1 rounded-sm border px-1.5 py-0.5 text-[10px] text-muted-foreground">
          {d.statusPages}
        </span>
      </div>
    </OverviewPanel>
  )
}
