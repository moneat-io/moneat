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

import {Rocket} from 'lucide-react'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {StatusDot} from '@/components/ui/status-dot'
import {useDeploys, type Tone} from '../overviewData'
import {OverviewPanel, PanelLink} from '../OverviewPanel'
import {toneDot} from '../overviewTone'

function deployBadgeVariant(tone: Tone): BadgeProps['variant'] {
  switch (tone) {
    case 'bad':
      return 'danger'
    case 'good':
      return 'success'
    case 'warn':
      return 'warning'
    default:
      return 'neutral'
  }
}

/** Recent deploys with their post-deploy status. */
export function DeploysWidget() {
  const rows = useDeploys()
  return (
    <OverviewPanel
      testId="widget-deploys"
      title="Deploys"
      icon={Rocket}
      count="last 24h"
      actions={<PanelLink to="/releases">Releases</PanelLink>}
    >
      <div>
        {rows.map((r, i) => (
          <div key={i} className="flex items-center gap-1.5 border-b border-border/40 py-1 text-[11px] last:border-0">
            <StatusDot tone={toneDot[r.status]} size="sm" />
            <span className="font-mono font-medium text-foreground">{r.version}</span>
            <span className="min-w-0 flex-1 truncate text-muted-foreground">{r.service}</span>
            <Badge variant={deployBadgeVariant(r.status)} className="px-1 py-0 text-[9px]">
              {r.label}
            </Badge>
            <span className="font-mono text-[9px] text-muted-foreground/70">{r.ageLabel}</span>
          </div>
        ))}
      </div>
    </OverviewPanel>
  )
}
