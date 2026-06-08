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

import type {ComponentType} from 'react'
import {Clock, Flag, MessageSquare, PlayCircle, Rocket, Siren, Workflow} from 'lucide-react'
import {cn} from '@/lib/utils'
import {useActivity, type ActivityKind} from '../overviewData'
import {OverviewPanel, PanelLink} from '../OverviewPanel'

const KIND: Record<ActivityKind, {icon: ComponentType<{className?: string}>; cls: string}> = {
  incident: {icon: Siren, cls: 'bg-danger-bg text-danger-fg'},
  flag: {icon: Flag, cls: 'bg-[hsl(var(--primary)/0.12)] text-primary'},
  deploy: {icon: Rocket, cls: 'bg-info-bg text-info-fg'},
  workflow: {icon: Workflow, cls: 'bg-warning-bg text-warning-fg'},
  replay: {icon: PlayCircle, cls: 'bg-muted text-muted-foreground'},
  feedback: {icon: MessageSquare, cls: 'bg-muted text-muted-foreground'},
}

/** Recent activity feed across the workspace. */
export function ActivityWidget() {
  const items = useActivity()
  return (
    <OverviewPanel
      testId="widget-activity"
      title="Activity"
      icon={Clock}
      actions={<PanelLink to="/events">View log</PanelLink>}
    >
      <div>
        {items.map((it) => {
          const {icon: Icon, cls} = KIND[it.kind]
          const itemKey = `${it.kind}:${it.text}:${it.meta}`
          return (
            <div key={itemKey} className="flex gap-1.5 border-b border-border/40 py-1 last:border-0">
              <span className={cn('grid h-5 w-5 shrink-0 place-items-center rounded', cls)}>
                <Icon className="h-2.5 w-2.5" />
              </span>
              <div className="min-w-0 flex-1">
                <div className="text-[11px] text-foreground">{it.text}</div>
                <div className="text-[9px] text-muted-foreground/70">{it.meta}</div>
              </div>
            </div>
          )
        })}
      </div>
    </OverviewPanel>
  )
}
