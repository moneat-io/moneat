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

import {useCallback, useEffect, useState} from 'react'
import {ChevronRight, Minus, Search, SlidersHorizontal} from 'lucide-react'

import {cn} from '@/lib/utils'
import {Checkbox} from '@/components/ui/checkbox'
import type {FacetFilter, FacetRailSection, FacetValue} from '@/lib/filters/types'

/** Count formatting (matches the log viewer). */
function formatFacetCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  if (n === 0) return ''
  return String(n)
}

const SEARCH_THRESHOLD = 8
const INITIAL_VISIBLE = 8

export interface FacetRailProps {
  sections: readonly FacetRailSection[]
  facetFilters: FacetFilter[]
  onFacetFiltersChange: (filters: FacetFilter[]) => void
  title?: string
  icon?: React.ComponentType<{className?: string}>
  className?: string
}

/**
 * Facet-filter rail (generalized from the log viewer's TagFacets): a bordered
 * column of collapsible sections with checkboxes, counts, include/exclude
 * tri-state, per-section search, and "Clear all". `FacetFilter[]`-controlled by
 * the owner. Whole-rail show/hide is the shell's job, not the rail's.
 */
export function FacetRail({
  sections,
  facetFilters,
  onFacetFiltersChange,
  title = 'Filters',
  icon: Icon = SlidersHorizontal,
  className,
}: FacetRailProps) {
  const nonEmpty = sections.filter((s) => (s.options?.length ?? 0) > 0 || s.loadOptions)

  return (
    <div className={cn('flex h-full flex-col overflow-hidden bg-card/50', className)}>
      <div className="flex h-8 shrink-0 items-center gap-1.5 border-b px-2">
        <Icon className="h-3.5 w-3.5 text-muted-foreground" />
        <span className="text-xs font-semibold uppercase tracking-wider">{title}</span>
        {facetFilters.length > 0 && (
          <button
            type="button"
            onClick={() => onFacetFiltersChange([])}
            className="ml-auto text-[11px] text-muted-foreground transition-colors hover:text-foreground"
          >
            Clear all
          </button>
        )}
      </div>

      <div className="flex-1 overflow-y-auto">
        {nonEmpty.map((section) => (
          <RailSection
            key={section.key}
            section={section}
            facetFilters={facetFilters}
            onFacetFiltersChange={onFacetFiltersChange}
          />
        ))}
        {nonEmpty.length === 0 && (
          <div className="px-4 py-8 text-center text-xs text-muted-foreground">
            No facets available yet.
          </div>
        )}
      </div>
    </div>
  )
}

interface RailSectionProps {
  section: FacetRailSection
  facetFilters: FacetFilter[]
  onFacetFiltersChange: (filters: FacetFilter[]) => void
}

function RailSection({section, facetFilters, onFacetFiltersChange}: RailSectionProps) {
  const {key, label, color, singleSelect, allowExclude = true, options, loadOptions} = section
  // Eager sections open by default (values are ready); lazy ones stay closed
  // until opened, which also triggers their first load.
  const [expanded, setExpanded] = useState(options != null)
  const [query, setQuery] = useState('')
  const [showAll, setShowAll] = useState(false)
  const [loaded, setLoaded] = useState<readonly FacetValue[] | null>(null)

  // Lazily load values the first time the section is opened.
  useEffect(() => {
    if (expanded && !options && loadOptions && loaded === null) {
      let cancelled = false
      void loadOptions().then((values) => {
        if (!cancelled) setLoaded(values)
      })
      return () => {
        cancelled = true
      }
    }
  }, [expanded, options, loadOptions, loaded])

  const values = options ?? loaded ?? []
  const activeCount = facetFilters.filter((f) => f.key === key).length

  const getState = useCallback(
    (value: string): 'include' | 'exclude' | 'none' => {
      const match = facetFilters.find((f) => f.key === key && f.value === value)
      if (!match) return 'none'
      return match.exclude ? 'exclude' : 'include'
    },
    [facetFilters, key]
  )

  const toggleInclude = useCallback(
    (value: string) => {
      const state = getState(value)
      if (state === 'none') {
        const base = singleSelect ? facetFilters.filter((f) => f.key !== key) : facetFilters
        onFacetFiltersChange([...base, {key, value, exclude: false}])
      } else {
        onFacetFiltersChange(facetFilters.filter((f) => !(f.key === key && f.value === value)))
      }
    },
    [getState, singleSelect, facetFilters, key, onFacetFiltersChange]
  )

  const toggleExclude = useCallback(
    (value: string, event: React.MouseEvent) => {
      event.stopPropagation()
      event.preventDefault()
      const state = getState(value)
      if (state === 'exclude') {
        onFacetFiltersChange(facetFilters.filter((f) => !(f.key === key && f.value === value)))
      } else {
        const without = facetFilters.filter((f) => !(f.key === key && f.value === value))
        onFacetFiltersChange([...without, {key, value, exclude: true}])
      }
    },
    [getState, facetFilters, key, onFacetFiltersChange]
  )

  const normalizedQuery = query.trim().toLowerCase()
  const filtered = normalizedQuery
    ? values.filter((v) => (v.label ?? v.value).toLowerCase().includes(normalizedQuery))
    : values
  const searchable = values.length > SEARCH_THRESHOLD
  const shown = showAll ? filtered : filtered.slice(0, INITIAL_VISIBLE)

  return (
    <div className="border-b border-border/50 last:border-b-0">
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
        className="flex w-full items-center gap-1.5 px-2 py-1.5 text-left text-[11px] font-semibold uppercase tracking-wider text-muted-foreground transition-colors hover:text-foreground"
      >
        <ChevronRight className={cn('h-3.5 w-3.5 shrink-0 transition-transform', expanded && 'rotate-90')} />
        <span className={cn('h-2 w-2 shrink-0 rounded-full', color || 'bg-muted-foreground/50')} />
        <span className="truncate">{label}</span>
        <span className="ml-auto font-normal tabular-nums text-muted-foreground/70">
          {activeCount > 0 ? `${activeCount}/${values.length || '?'}` : values.length || ''}
        </span>
      </button>

      {expanded && (
        <div className="space-y-0.5 px-2 pb-2">
          {searchable && (
            <div className="relative mb-1">
              <Search className="pointer-events-none absolute left-2 top-1/2 h-3 w-3 -translate-y-1/2 text-muted-foreground" />
              <input
                type="search"
                value={query}
                onChange={(e) => {
                  setQuery(e.target.value)
                  setShowAll(true)
                }}
                placeholder={`Filter ${label.toLowerCase()}`}
                aria-label={`Filter ${label}`}
                className="h-7 w-full rounded-md border border-input bg-background pl-7 pr-2 text-xs placeholder:text-muted-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
            </div>
          )}

          {!options && loadOptions && loaded === null ? (
            <p className="px-2 py-1 text-xs text-muted-foreground">Loading values...</p>
          ) : shown.length === 0 ? (
            <p className="px-2 py-1 text-xs text-muted-foreground">No matches</p>
          ) : (
            shown.map((item) => {
              const state = getState(item.value)
              const display = item.label ?? item.value
              return (
                <div
                  key={item.value}
                  className={cn(
                    'group flex items-center gap-2 rounded-md px-2 py-1 transition-colors hover:bg-accent/50',
                    state === 'include' && 'bg-primary/5',
                    state === 'exclude' && 'bg-danger-bg/50'
                  )}
                >
                  <Checkbox
                    checked={state === 'include'}
                    onCheckedChange={() => toggleInclude(item.value)}
                    aria-label={display}
                    className={cn(
                      'h-3.5 w-3.5',
                      state === 'exclude' && 'border-danger-solid data-[state=checked]:bg-danger-solid'
                    )}
                  />
                  <button
                    type="button"
                    onClick={() => toggleInclude(item.value)}
                    title={display}
                    className={cn(
                      'min-w-0 flex-1 truncate text-left font-mono text-xs focus:outline-none',
                      state === 'exclude' && 'text-muted-foreground line-through'
                    )}
                  >
                    {display}
                  </button>
                  {item.count != null && item.count > 0 && (
                    <span className="shrink-0 font-mono text-[10px] tabular-nums text-muted-foreground/60">
                      {formatFacetCount(item.count)}
                    </span>
                  )}
                  {allowExclude && (
                    <button
                      type="button"
                      onClick={(e) => toggleExclude(item.value, e)}
                      title={state === 'exclude' ? 'Remove exclusion' : 'Exclude this value'}
                      className={cn(
                        'shrink-0 rounded p-0.5 opacity-0 transition-opacity group-hover:opacity-100',
                        state === 'exclude' ? 'text-danger-fg opacity-100' : 'text-muted-foreground hover:text-danger-fg'
                      )}
                    >
                      <Minus className="h-3 w-3" />
                    </button>
                  )}
                </div>
              )
            })
          )}

          {filtered.length > INITIAL_VISIBLE && (
            <button
              type="button"
              onClick={() => setShowAll((v) => !v)}
              className="rounded px-2 pt-1 text-[11px] text-primary hover:underline focus:outline-none"
            >
              {showAll ? 'Show less' : `Show ${filtered.length - INITIAL_VISIBLE} more`}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
