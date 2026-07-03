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
import {useIsMobile} from '@/hooks/useIsMobile'

export interface ExplorerShellProps {
  /** The search/filter bar (typically a <SearchFilterBar/>), stretched to fill. */
  searchBar: ReactNode
  /** Optional left-of-search label (icon badge + title), the log-viewer style. */
  title?: string
  icon?: ReactNode
  /** Section tabs / mode switch (a <SegmentedTabs/>), rendered left of the search. */
  tabs?: ReactNode
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
 *
 * The root is bound to the app shell's fixed content region height
 * (`--header-height` is set on that region in `__root.tsx`), and the rail and
 * content each scroll on their own (`min-h-0` down the flex chain). So the
 * header and facet rail stay put while a long result list scrolls — the page
 * must not wrap this in its own `100vh`/`overflow` box.
 */
export function ExplorerShell({
  searchBar,
  title,
  icon,
  tabs,
  actions,
  rail,
  toolbar,
  defaultRailOpen = true,
  children,
  className,
}: Readonly<ExplorerShellProps>) {
  const isMobile = useIsMobile()
  const [railOpen, setRailOpen] = useState(defaultRailOpen)
  // On mobile the rail is an overlay drawer that defaults closed, so it never
  // squeezes the result list; on desktop it stays inline and pushes content.
  const [mobileRailOpen, setMobileRailOpen] = useState(false)
  const open = isMobile ? mobileRailOpen : railOpen
  const toggleRail = () => (isMobile ? setMobileRailOpen((v) => !v) : setRailOpen((v) => !v))

  return (
    <div className={cn('flex h-[calc(100dvh-var(--header-height,0px))] flex-col overflow-x-clip', className)}>
      {/* Header bar */}
      <div className="@container/header flex min-h-[var(--app-header-h)] items-center gap-2 border-b px-2 py-1 sm:px-3">
        {(icon || title) && (
          <div className="flex shrink-0 items-center gap-2">
            {icon}
            {title && <h2 className="hidden text-xs font-semibold leading-tight sm:block">{title}</h2>}
          </div>
        )}
        {tabs && <div className="flex min-w-0 shrink items-center">{tabs}</div>}
        <div className="min-w-0 flex-1">{searchBar}</div>
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </div>

      {/* Body: rail + content */}
      <div className="relative flex min-h-0 flex-1">
        {rail && !isMobile && (
          <div
            className={cn(
              'shrink-0 self-stretch overflow-y-auto border-r transition-all duration-200',
              railOpen ? 'w-[220px]' : 'w-0 overflow-hidden border-r-0'
            )}
          >
            {railOpen && rail}
          </div>
        )}

        {/* Mobile facet overlay */}
        {rail && isMobile && (
          <>
            <button
              type="button"
              tabIndex={-1}
              aria-hidden={!open}
              onClick={() => setMobileRailOpen(false)}
              className={cn(
                'absolute inset-0 z-20 bg-black/50 transition-opacity duration-200',
                open ? 'opacity-100' : 'pointer-events-none opacity-0'
              )}
            />
            <div
              className={cn(
                'absolute inset-y-0 left-0 z-30 w-[min(80%,280px)] overflow-y-auto border-r bg-card shadow-xl transition-transform duration-200',
                open ? 'translate-x-0' : '-translate-x-full'
              )}
              inert={open ? undefined : true}
            >
              {rail}
            </div>
          </>
        )}

        <div className="flex min-h-0 min-w-0 flex-1 flex-col">
          {(rail || toolbar) && (
            <div className="flex h-[var(--app-subheader-h)] shrink-0 items-center gap-1.5 border-b bg-card/30 px-2">
              {rail && (
                <button
                  type="button"
                  onClick={toggleRail}
                  className="rounded-md p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
                  title={open ? 'Hide facets' : 'Show facets'}
                  aria-label={open ? 'Hide facets' : 'Show facets'}
                >
                  {open ? <PanelLeftClose className="h-4 w-4" /> : <PanelLeftOpen className="h-4 w-4" />}
                </button>
              )}
              {toolbar}
            </div>
          )}
          <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
        </div>
      </div>
    </div>
  )
}
