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

import {Boxes} from 'lucide-react'
import {Badge} from '@/components/ui/badge'
import {StatusDot} from '@/components/ui/status-dot'
import {useInfraSummary} from '../overviewMockData'
import {MiniBar, OverviewPanel, PanelLink} from '../OverviewPanel'

/** Infrastructure resource gauges + container/pod counts. */
export function InfraSummaryWidget() {
  const d = useInfraSummary()
  return (
    <OverviewPanel
      testId="widget-infra_summary"
      title="Infrastructure"
      icon={Boxes}
      count={d.upLabel}
      actions={<PanelLink>Map</PanelLink>}
    >
      <div className="space-y-2">
        {d.gauges.map((g) => (
          <div key={g.label} className="grid grid-cols-[42px_1fr_38px] items-center gap-2 text-xs">
            <span className="text-muted-foreground">{g.label}</span>
            <MiniBar pct={g.pct} tone={g.tone} className="h-2" />
            <span className="text-right font-mono tabular-nums text-foreground">{g.pct}%</span>
          </div>
        ))}
      </div>
      <div className="mt-2.5 flex flex-wrap items-center gap-2 border-t pt-2.5 text-xs text-muted-foreground">
        <span>
          <b className="font-mono tabular-nums text-foreground">{d.containers}</b> containers
        </span>
        <span className="text-muted-foreground/60">·</span>
        <span>
          <b className="font-mono tabular-nums text-foreground">{d.pods}</b> pods
        </span>
        {d.offlineNode && (
          <Badge variant="danger" className="ml-auto gap-1 px-1.5 py-0 text-[10px]">
            <StatusDot tone="danger" size="sm" />
            {d.offlineNode} offline
          </Badge>
        )}
      </div>
    </OverviewPanel>
  )
}
