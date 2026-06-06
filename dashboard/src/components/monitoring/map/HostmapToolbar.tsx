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

// The legend gradient + no-data chip reuse the app's threshold solids (there is
// no dedicated --scale-* set), so the bar reads low→high as green→amber→red.
const HEAT_GRADIENT =
  'linear-gradient(90deg, hsl(var(--success-solid)), hsl(var(--warning-solid)), hsl(var(--danger-solid)))'

export interface HostmapToolbarProps {
  resourceNoun: string
  totalResources: number
  healthyResources: number
  unhealthyResources: number
  fillLabel: string
  limitNotice?: string | null
}

/**
 * The thin strip above the hostmap (mockup `.toolbar`): a mono count summary on
 * the left and the utilization heat legend on the right, so the packed tiles
 * read as "size = capacity, color = {fill}".
 */
export function HostmapToolbar({
  resourceNoun,
  totalResources,
  healthyResources,
  unhealthyResources,
  fillLabel,
  limitNotice,
}: HostmapToolbarProps) {
  return (
    <div
      data-testid="hostmap-toolbar"
      className="flex min-h-8 flex-wrap items-center gap-x-3 gap-y-1 border-b bg-muted/30 px-3 py-1 text-[11px] text-muted-foreground"
    >
      <span className="flex items-center gap-3 font-mono">
        <span>
          <b className="font-semibold text-foreground">{totalResources}</b> {resourceNoun}
        </span>
        <span className="text-success-fg">
          <b className="font-semibold">{healthyResources}</b> healthy
        </span>
        <span className="text-danger-fg">
          <b className="font-semibold">{unhealthyResources}</b> attention
        </span>
      </span>

      {limitNotice && (
        <span className="inline-flex items-center gap-1 text-warning-fg">
          <AlertTriangle className="h-3 w-3" />
          {limitNotice}
        </span>
      )}

      <span className="ml-auto flex items-center gap-2">
        <span className="font-medium">{fillLabel}</span>
        <span className="text-muted-foreground/70">low</span>
        <span className="h-2 w-[120px] rounded-full" style={{background: HEAT_GRADIENT}} aria-hidden />
        <span className="text-muted-foreground/70">high</span>
        <span className="ml-2 inline-flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-sm bg-muted-foreground/40" aria-hidden />
          no data
        </span>
      </span>
    </div>
  )
}
