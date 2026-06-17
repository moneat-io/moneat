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

import {AlertTriangle, CheckCircle} from 'lucide-react'
import {Link} from '@tanstack/react-router'
import {cn} from '@/lib/utils'
import {Button} from '@/components/ui/button'
import type {OverviewCounts} from '@/lib/api/types'
import {useSystemStatus} from '../overviewData'
import {toneBgSolid, toneBorder, toneSoftBg, toneText} from '../overviewTone'

type StatusAction = Readonly<{
  to: string
  label: string
  ariaLabel: string
}>

function nounLabel(count: number, singular: string, plural = `${singular}s`) {
  return count === 1 ? singular : plural
}

function statusAction(counts: OverviewCounts): StatusAction | null {
  if (counts.incidents > 0) {
    return {to: '/on-call/incidents', label: 'View incidents', ariaLabel: 'View active incidents'}
  }
  if (counts.alerts > 0) {
    return {to: '/on-call/alerts', label: 'View alerts', ariaLabel: 'View firing alerts'}
  }
  if (counts.hostsOffline > 0) {
    return {to: '/monitoring/hosts', label: 'View hosts', ariaLabel: 'View offline hosts'}
  }
  if (counts.degraded > 0) {
    return {to: '/services', label: 'View services', ariaLabel: 'View degraded services'}
  }
  return null
}

/** Hero status bar — the triage line + AI summary at the top of the overview. */
export function SystemStatusWidget() {
  const s = useSystemStatus()
  const sev = s.severity
  const action = statusAction(s.counts)
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
          {sev === 'good' ? <CheckCircle className="h-3.5 w-3.5" /> : <AlertTriangle className="h-3.5 w-3.5" />}
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-semibold text-foreground">{s.state}</span>
            <span className="flex flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
              <span>
                <b className="tabular-nums text-foreground">{s.counts.incidents}</b>{' '}
                {nounLabel(s.counts.incidents, 'active incident')}
              </span>
              <span className="text-muted-foreground/60">·</span>
              <span>
                <b className="tabular-nums text-foreground">{s.counts.alerts}</b>{' '}
                {nounLabel(s.counts.alerts, 'alert')} firing
              </span>
              <span className="text-muted-foreground/60">·</span>
              <span>
                <b className="tabular-nums text-foreground">{s.counts.degraded}</b>{' '}
                {nounLabel(s.counts.degraded, 'service')} degraded
              </span>
              <span className="text-muted-foreground/60">·</span>
              <span>
                <b className="tabular-nums text-foreground">{s.counts.hostsOffline}</b>{' '}
                {nounLabel(s.counts.hostsOffline, 'host')} offline
              </span>
            </span>
          </div>
        </div>
        {action && (
          <div className="flex shrink-0 items-center gap-1.5">
            <Button asChild size="sm" className="h-6 px-2 text-[11px]">
              <Link to={action.to} aria-label={action.ariaLabel}>
                {action.label}
              </Link>
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}
