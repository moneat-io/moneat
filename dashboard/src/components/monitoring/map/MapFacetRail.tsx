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
import {Check, ChevronRight, ListFilter} from 'lucide-react'

import {cn} from '@/lib/utils'
import type {FacetFilter, FacetRailSection, FacetValue} from '@/lib/filters/types'

// Show this many values before collapsing the rest behind "Show more". Map
// facets are small (status/health/type), so this rarely triggers.
const INITIAL_VISIBLE = 10

/** Count formatting that matches the rest of the explorer chassis. */
function formatFacetCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  return String(n)
}

export interface MapFacetRailProps {
  sections: readonly FacetRailSection[]
  facetFilters: FacetFilter[]
  onFacetFiltersChange: (filters: FacetFilter[]) => void
  className?: string
}

/**
 * The map's filter rail, rebuilt to the monitoring-map mockup: a dense column of
 * collapsible sections, each a colored swatch + label + option count, whose
 * checkbox rows put the per-value count flush against the right edge (the count
 * is the last child after a `flex-1` label, so every count aligns in one
 * column). Include-only and `FacetFilter[]`-controlled by the owner. Distinct
 * from the shared {@link FacetRail} (which adds exclude + lazy loading) so the
 * map can stay pixel-faithful without disturbing the logs/traces explorers.
 */
export function MapFacetRail({sections, facetFilters, onFacetFiltersChange, className}: MapFacetRailProps) {
  const visibleSections = sections.filter((section) => (section.options?.length ?? 0) > 0)

  return (
    <div className={cn('flex h-full flex-col overflow-y-auto bg-card', className)}>
      <div className="flex h-8 shrink-0 items-center gap-1.5 border-b px-3 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
        <ListFilter className="h-3.5 w-3.5" />
        Filters
        {facetFilters.length > 0 && (
          <button
            type="button"
            onClick={() => onFacetFiltersChange([])}
            className="ml-auto font-normal normal-case tracking-normal text-muted-foreground transition-colors hover:text-foreground"
          >
            Clear
          </button>
        )}
      </div>

      {visibleSections.map((section) => (
        <MapRailSection
          key={section.key}
          section={section}
          facetFilters={facetFilters}
          onFacetFiltersChange={onFacetFiltersChange}
        />
      ))}

      {visibleSections.length === 0 && (
        <p className="px-3 py-8 text-center text-xs text-muted-foreground">No facets available yet.</p>
      )}
    </div>
  )
}

interface MapRailSectionProps {
  section: FacetRailSection
  facetFilters: FacetFilter[]
  onFacetFiltersChange: (filters: FacetFilter[]) => void
}

function MapRailSection({section, facetFilters, onFacetFiltersChange}: MapRailSectionProps) {
  const {key, label, color, singleSelect, options} = section
  const values: readonly FacetValue[] = options ?? []
  const [expanded, setExpanded] = useState(true)
  const [showAll, setShowAll] = useState(false)
  const shown = showAll ? values : values.slice(0, INITIAL_VISIBLE)

  function isOn(value: string): boolean {
    return facetFilters.some((filter) => filter.key === key && filter.value === value)
  }

  function toggle(value: string) {
    if (isOn(value)) {
      onFacetFiltersChange(facetFilters.filter((filter) => !(filter.key === key && filter.value === value)))
      return
    }
    const base = singleSelect ? facetFilters.filter((filter) => filter.key !== key) : facetFilters
    onFacetFiltersChange([...base, {key, value}])
  }

  return (
    <div className="border-b border-border/50 last:border-b-0">
      <button
        type="button"
        onClick={() => setExpanded((open) => !open)}
        aria-expanded={expanded}
        className="flex w-full items-center gap-1.5 px-3 py-2 text-left text-[11px] font-semibold uppercase tracking-wider text-muted-foreground transition-colors hover:text-foreground"
      >
        <ChevronRight className={cn('h-3 w-3 shrink-0 transition-transform', expanded && 'rotate-90')} />
        <span className={cn('h-2 w-2 shrink-0 rounded-full', color ?? 'bg-muted-foreground/50')} />
        <span className="truncate">{label}</span>
        <span className="ml-auto font-normal tabular-nums text-muted-foreground/70">{values.length}</span>
      </button>

      {expanded && (
        <div className="space-y-0.5 px-2 pb-2">
          {shown.map((item) => {
            const on = isOn(item.value)
            const display = item.label ?? item.value
            return (
              <button
                key={item.value}
                type="button"
                role="checkbox"
                aria-checked={on}
                aria-label={display}
                onClick={() => toggle(item.value)}
                title={display}
                className={cn(
                  'flex w-full items-center gap-2 rounded-sm px-1.5 py-1 text-left text-xs transition-colors hover:bg-accent/50',
                  on && 'bg-primary/10',
                )}
              >
                <span
                  className={cn(
                    'grid h-3.5 w-3.5 shrink-0 place-items-center rounded-[3px] border',
                    on ? 'border-primary bg-primary text-primary-foreground' : 'border-border',
                  )}
                >
                  {on && <Check className="h-2.5 w-2.5" strokeWidth={3} />}
                </span>
                <span className="min-w-0 flex-1 truncate font-mono">{display}</span>
                {item.count != null && (
                  <span className="shrink-0 font-mono text-[10px] tabular-nums text-muted-foreground/60">
                    {formatFacetCount(item.count)}
                  </span>
                )}
              </button>
            )
          })}

          {values.length > INITIAL_VISIBLE && (
            <button
              type="button"
              onClick={() => setShowAll((open) => !open)}
              className="px-1.5 pt-1 text-[11px] text-primary hover:underline focus:outline-none"
            >
              {showAll ? 'Show less' : `Show ${values.length - INITIAL_VISIBLE} more`}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
