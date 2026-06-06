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

import type {ReactNode} from 'react'

import {cn} from '@/lib/utils'

export type MapExplorerShellProps = Readonly<{
  icon?: ReactNode
  title: string
  /** Segmented mode switch, rendered immediately left of the search box. */
  modeSwitch: ReactNode
  /** Compact search field; stretches to fill the middle of the header. */
  search: ReactNode
  /** Inline `.ctl` dropdowns (range/env, or group/color/size). */
  controls?: ReactNode
  /** Far-right header actions (e.g. Views). */
  actions?: ReactNode
  /** The facet rail; already carries its own width + right border. */
  rail?: ReactNode
  children: ReactNode
  className?: string
}>

/**
 * The monitoring-map explorer chrome, rebuilt to the mockup: one dense header
 * row — `[icon · title] [mode switch] [search] [controls] [actions]` — over a
 * body of `[rail | content]`. Unlike the shared {@link ExplorerShell}, the
 * controls live in the same row as the search (no second toolbar strip) and the
 * mode switch sits left of the search, which is what makes the surface read as
 * compact. Dedicated to the map so the logs/traces explorers are untouched.
 */
export function MapExplorerShell({
  icon,
  title,
  modeSwitch,
  search,
  controls,
  actions,
  rail,
  children,
  className,
}: MapExplorerShellProps) {
  return (
    <div className={cn('flex h-full flex-col', className)}>
      <div className="flex flex-wrap items-center gap-2 border-b px-3 py-1.5">
        <div className="flex shrink-0 items-center gap-2">
          {icon}
          <h2 className="whitespace-nowrap text-sm font-semibold leading-tight">{title}</h2>
        </div>
        {modeSwitch}
        <div className="order-last min-w-[160px] flex-1 basis-full sm:order-none sm:basis-0">{search}</div>
        {controls && <div className="flex flex-wrap items-center gap-2">{controls}</div>}
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </div>

      <div className="flex min-h-0 flex-1">
        {rail && <div className="hidden w-[248px] shrink-0 border-r md:block">{rail}</div>}
        <div className="flex min-w-0 flex-1 flex-col">{children}</div>
      </div>
    </div>
  )
}
