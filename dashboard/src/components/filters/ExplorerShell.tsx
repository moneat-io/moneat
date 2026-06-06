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

import {useState} from 'react'
import type {ReactNode} from 'react'
import {PanelLeftClose, PanelLeftOpen} from 'lucide-react'

import {cn} from '@/lib/utils'

export interface ExplorerShellProps {
  /** The search/filter bar (typically a <SearchFilterBar/>), stretched to fill. */
  searchBar: ReactNode
  /** Optional left-of-search label (icon badge + title), the log-viewer style. */
  title?: string
  icon?: ReactNode
  /** Header right side — page actions (e.g. New Project). */
  actions?: ReactNode
  /** The collapsible facet rail (typically a <FacetRail/>). Omit for no rail. */
  rail?: ReactNode
  /** Row above the content (e.g. select-all, result count). */
  toolbar?: ReactNode
  defaultRailOpen?: boolean
  children: ReactNode
  className?: string
}

/**
 * Compact filter-view shell, generalized from the log explorer: a dense header
 * (title + search bar + actions) over a collapsible facet rail beside the
 * content, with a thin toolbar carrying the rail toggle. The standard compact
 * layout for result surfaces.
 */
export function ExplorerShell({
  searchBar,
  title,
  icon,
  actions,
  rail,
  toolbar,
  defaultRailOpen = true,
  children,
  className,
}: ExplorerShellProps) {
  const [railOpen, setRailOpen] = useState(defaultRailOpen)

  return (
    <div className={cn('flex h-full flex-col', className)}>
      {/* Header bar */}
      <div className="@container/header flex min-h-[42px] items-center gap-2 border-b px-2 py-1 sm:px-3">
        {(icon || title) && (
          <div className="flex shrink-0 items-center gap-2">
            {icon}
            {title && <h2 className="hidden text-xs font-semibold leading-tight sm:block">{title}</h2>}
          </div>
        )}
        <div className="min-w-0 flex-1">{searchBar}</div>
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </div>

      {/* Body: rail + content */}
      <div className="flex min-h-0 flex-1">
        {rail && (
          <div
            className={cn(
              'shrink-0 border-r transition-all duration-200',
              railOpen ? 'w-[220px]' : 'w-0 overflow-hidden border-r-0'
            )}
          >
            {railOpen && rail}
          </div>
        )}

        <div className="flex min-w-0 flex-1 flex-col">
          {(rail || toolbar) && (
            <div className="flex h-9 shrink-0 items-center gap-1.5 border-b bg-card/30 px-2">
              {rail && (
                <button
                  type="button"
                  onClick={() => setRailOpen((v) => !v)}
                  className="rounded-md p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
                  title={railOpen ? 'Hide facets' : 'Show facets'}
                  aria-label={railOpen ? 'Hide facets' : 'Show facets'}
                >
                  {railOpen ? <PanelLeftClose className="h-4 w-4" /> : <PanelLeftOpen className="h-4 w-4" />}
                </button>
              )}
              {toolbar}
            </div>
          )}
          <div className="flex-1 overflow-y-auto">{children}</div>
        </div>
      </div>
    </div>
  )
}
