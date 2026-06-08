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

import {AlertTriangle} from 'lucide-react'
import {cn} from '@/lib/utils'
import {Button} from '@/components/ui/button'
import {useSystemStatus} from '../overviewMockData'
import {toneBgSolid, toneBorder, toneSoftBg, toneText} from '../overviewTone'

/** Hero status bar — the triage line + AI summary at the top of the overview. */
export function SystemStatusWidget() {
  const s = useSystemStatus()
  const sev = s.severity
  return (
    <div
      data-testid="widget-system_status"
      className={cn('flex h-full overflow-hidden rounded-lg border border-border/60 bg-card', toneBorder[sev])}
    >
      <div className={cn('w-1 shrink-0', toneBgSolid[sev])} />
      <div className="flex min-w-0 flex-1 items-center gap-3 px-3 py-2">
        <div
          className={cn(
            'grid h-7 w-7 shrink-0 place-items-center rounded-md border',
            toneSoftBg[sev],
            toneText[sev],
            toneBorder[sev],
          )}
        >
          <AlertTriangle className="h-3.5 w-3.5" />
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-semibold text-foreground">{s.state}</span>
            <span className="flex flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
              <span>
                <b className="tabular-nums text-foreground">{s.counts.incidents}</b> active incident
              </span>
              <span className="text-muted-foreground/60">·</span>
              <span>
                <b className="tabular-nums text-foreground">{s.counts.alerts}</b> alerts firing
              </span>
              <span className="text-muted-foreground/60">·</span>
              <span>
                <b className="tabular-nums text-foreground">{s.counts.degraded}</b> services degraded
              </span>
              <span className="text-muted-foreground/60">·</span>
              <span>
                <b className="tabular-nums text-foreground">{s.counts.hostsOffline}</b> host offline
              </span>
            </span>
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-1.5">
          <Button size="sm" className="h-6 px-2 text-[11px]">View incident</Button>
        </div>
      </div>
    </div>
  )
}
