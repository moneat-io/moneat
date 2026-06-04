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

import {useCallback, useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {cn} from '@/lib/utils'
import {Checkbox} from '@/components/ui/checkbox'
import {ChevronRight, Minus, Tag} from 'lucide-react'
import type {FacetFilter} from '@/lib/filters/types'

import type {LogFilterOptionWithCount} from '@/lib/api'

interface TagFacetsProps {
  availableTagKeys: string[]
  availableServices: LogFilterOptionWithCount[]
  availableEnvironments: LogFilterOptionWithCount[]
  facetFilters: FacetFilter[]
  onFacetFiltersChange: (filters: FacetFilter[]) => void
  from?: string
  to?: string
  /** Scope identifier (e.g. systemId for monitor mode) to avoid cache mixing between org and system views */
  scopeId?: string | null
}

function formatFacetCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  if (n === 0) return ''
  return String(n)
}

function FacetSection({
  title,
  values,
  facetKey,
  facetFilters,
  onFacetFiltersChange,
  color,
}: {
  title: string
  values: LogFilterOptionWithCount[]
  facetKey: string
  facetFilters: FacetFilter[]
  onFacetFiltersChange: (filters: FacetFilter[]) => void
  color?: string
}) {
  const [expanded, setExpanded] = useState(true)

  const getFilterState = useCallback(
    (value: string): 'include' | 'exclude' | 'none' => {
      const match = facetFilters.find((f) => f.key === facetKey && f.value === value)
      if (!match) return 'none'
      return match.exclude ? 'exclude' : 'include'
    },
    [facetFilters, facetKey]
  )

  const toggleFilter = useCallback(
    (value: string) => {
      const existing = facetFilters.find((f) => f.key === facetKey && f.value === value)
      if (!existing) {
        // Add as include
        onFacetFiltersChange([...facetFilters, {key: facetKey, value, exclude: false}])
      } else if (!existing.exclude) {
        // Remove
        onFacetFiltersChange(facetFilters.filter((f) => !(f.key === facetKey && f.value === value)))
      } else {
        // Remove
        onFacetFiltersChange(facetFilters.filter((f) => !(f.key === facetKey && f.value === value)))
      }
    },
    [facetFilters, facetKey, onFacetFiltersChange]
  )

  const excludeFilter = useCallback(
    (value: string, event: React.MouseEvent) => {
      event.stopPropagation()
      event.preventDefault()
      const existing = facetFilters.find((f) => f.key === facetKey && f.value === value)
      if (existing?.exclude) {
        // Remove exclude
        onFacetFiltersChange(facetFilters.filter((f) => !(f.key === facetKey && f.value === value)))
      } else {
        // Add as exclude (remove include if exists)
        const without = facetFilters.filter((f) => !(f.key === facetKey && f.value === value))
        onFacetFiltersChange([...without, {key: facetKey, value, exclude: true}])
      }
    },
    [facetFilters, facetKey, onFacetFiltersChange]
  )

  if (values.length === 0) return null

  return (
    <div className="border-b border-border/50 last:border-b-0">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center gap-1.5 px-2 py-1.5 text-left text-[11px] font-semibold uppercase tracking-wider text-muted-foreground transition-colors hover:text-foreground"
      >
        <ChevronRight
          className={cn('h-3.5 w-3.5 transition-transform', expanded && 'rotate-90')}
        />
        <span
          className={cn(
            'h-2 w-2 rounded-full',
            color || 'bg-muted-foreground/50'
          )}
        />
        {title}
        <span className="ml-auto font-normal text-muted-foreground/70">{values.length}</span>
      </button>

      {expanded && (
        <div className="space-y-0.5 px-2 pb-2">
          {values.map((item) => {
            const state = getFilterState(item.value)
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
                  onCheckedChange={() => toggleFilter(item.value)}
                  className={cn(
                    'h-3.5 w-3.5',
                    state === 'exclude' && 'border-danger-solid data-[state=checked]:bg-danger-solid'
                  )}
                />
                <button
                  type="button"
                  onClick={() => toggleFilter(item.value)}
                  className={cn(
                    'flex-1 truncate text-left font-mono text-xs',
                    state === 'exclude' && 'line-through text-muted-foreground'
                  )}
                  title={item.value}
                >
                  {item.value}
                </button>
                {item.count > 0 && (
                  <span className="shrink-0 font-mono text-[10px] text-muted-foreground/60">
                    {formatFacetCount(item.count)}
                  </span>
                )}
                <button
                  type="button"
                  onClick={(e) => excludeFilter(item.value, e)}
                  title={state === 'exclude' ? 'Remove exclusion' : 'Exclude this value'}
                  className={cn(
                    'shrink-0 rounded p-0.5 opacity-0 transition-opacity group-hover:opacity-100',
                    state === 'exclude'
                      ? 'text-danger-fg opacity-100'
                      : 'text-muted-foreground hover:text-danger-fg'
                  )}
                >
                  <Minus className="h-3 w-3" />
                </button>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

function TagKeySection({
  tagKey,
  facetFilters,
  onFacetFiltersChange,
  from,
  to,
  scopeId,
}: {
  tagKey: string
  facetFilters: FacetFilter[]
  onFacetFiltersChange: (filters: FacetFilter[]) => void
  from?: string
  to?: string
  scopeId?: string | null
}) {
  const [expanded, setExpanded] = useState(false)

  const {data: tagValues} = useQuery({
    queryKey: ['log-tag-values', scopeId ?? 'org', tagKey, from, to],
    queryFn: () => api.getLogTagValues(tagKey, {from, to, limit: 30}),
    enabled: expanded,
    staleTime: 60_000,
  })

  const values = tagValues?.values ?? []

  const getFilterState = useCallback(
    (value: string): 'include' | 'exclude' | 'none' => {
      const match = facetFilters.find((f) => f.key === tagKey && f.value === value)
      if (!match) return 'none'
      return match.exclude ? 'exclude' : 'include'
    },
    [facetFilters, tagKey]
  )

  const toggleFilter = useCallback(
    (value: string) => {
      const existing = facetFilters.find((f) => f.key === tagKey && f.value === value)
      if (!existing) {
        onFacetFiltersChange([...facetFilters, {key: tagKey, value, exclude: false}])
      } else {
        onFacetFiltersChange(facetFilters.filter((f) => !(f.key === tagKey && f.value === value)))
      }
    },
    [facetFilters, tagKey, onFacetFiltersChange]
  )

  const excludeFilter = useCallback(
    (value: string, event: React.MouseEvent) => {
      event.stopPropagation()
      event.preventDefault()
      const existing = facetFilters.find((f) => f.key === tagKey && f.value === value)
      if (existing?.exclude) {
        onFacetFiltersChange(facetFilters.filter((f) => !(f.key === tagKey && f.value === value)))
      } else {
        const without = facetFilters.filter((f) => !(f.key === tagKey && f.value === value))
        onFacetFiltersChange([...without, {key: tagKey, value, exclude: true}])
      }
    },
    [facetFilters, tagKey, onFacetFiltersChange]
  )

  const activeCount = facetFilters.filter((f) => f.key === tagKey).length

  return (
    <div className="border-b border-border/50 last:border-b-0">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center gap-1.5 px-2 py-1.5 text-left text-[11px] transition-colors hover:bg-accent/30"
      >
        <ChevronRight
          className={cn('h-3 w-3 shrink-0 text-muted-foreground transition-transform', expanded && 'rotate-90')}
        />
        <Tag className="h-3 w-3 shrink-0 text-primary/70" />
        <span className="flex-1 truncate font-mono text-xs">{tagKey}</span>
        {activeCount > 0 && (
          <span className="rounded-full bg-primary/15 px-1.5 text-[10px] font-medium text-primary">
            {activeCount}
          </span>
        )}
      </button>

      {expanded && (
        <div className="space-y-0.5 px-2 pb-2">
          {values.length === 0 ? (
            <div className="px-2 py-2 text-[11px] text-muted-foreground">Loading values...</div>
          ) : (
            values.map((value) => {
              const state = getFilterState(value)
              return (
                <div
                  key={value}
                  className={cn(
                    'group flex items-center gap-2 rounded-md px-2 py-1 transition-colors hover:bg-accent/50',
                    state === 'include' && 'bg-primary/5',
                    state === 'exclude' && 'bg-danger-bg/50'
                  )}
                >
                  <Checkbox
                    checked={state === 'include'}
                    onCheckedChange={() => toggleFilter(value)}
                    className="h-3.5 w-3.5"
                  />
                  <button
                    type="button"
                    onClick={() => toggleFilter(value)}
                    className={cn(
                      'flex-1 truncate text-left font-mono text-xs',
                      state === 'exclude' && 'line-through text-muted-foreground'
                    )}
                    title={value}
                  >
                    {value}
                  </button>
                  <button
                    type="button"
                    onClick={(e) => excludeFilter(value, e)}
                    title={state === 'exclude' ? 'Remove exclusion' : 'Exclude this value'}
                    className={cn(
                      'shrink-0 rounded p-0.5 opacity-0 transition-opacity group-hover:opacity-100',
                      state === 'exclude'
                        ? 'text-danger-fg opacity-100'
                        : 'text-muted-foreground hover:text-danger-fg'
                    )}
                  >
                    <Minus className="h-3 w-3" />
                  </button>
                </div>
              )
            })
          )}
        </div>
      )}
    </div>
  )
}

export function TagFacets({
  availableTagKeys,
  availableServices,
  availableEnvironments,
  facetFilters,
  onFacetFiltersChange,
  from,
  to,
  scopeId,
}: TagFacetsProps) {
  return (
    <div className="flex h-full flex-col overflow-hidden bg-card/50">
      <div className="flex h-8 items-center gap-1.5 border-b px-2">
        <Tag className="h-3.5 w-3.5 text-muted-foreground" />
        <span className="text-xs font-semibold uppercase tracking-wider">Facets</span>
        {facetFilters.length > 0 && (
          <button
            type="button"
            onClick={() => onFacetFiltersChange([])}
            className="ml-auto text-[11px] text-muted-foreground hover:text-foreground"
          >
            Clear all
          </button>
        )}
      </div>

      <div className="flex-1 overflow-y-auto">
        <FacetSection
          title="Service"
          facetKey="service"
          values={availableServices}
          facetFilters={facetFilters}
          onFacetFiltersChange={onFacetFiltersChange}
          color="bg-primary"
        />

        <FacetSection
          title="Environment"
          facetKey="environment"
          values={availableEnvironments}
          facetFilters={facetFilters}
          onFacetFiltersChange={onFacetFiltersChange}
          color="bg-success-solid"
        />

        {availableTagKeys.length > 0 && (
          <div className="border-b border-border/50 px-3 py-2">
            <span className="text-[10px] font-semibold uppercase tracking-widest text-muted-foreground/60">
              Tags
            </span>
          </div>
        )}

        {availableTagKeys.map((key) => (
          <TagKeySection
            key={key}
            tagKey={key}
            facetFilters={facetFilters}
            onFacetFiltersChange={onFacetFiltersChange}
            from={from}
            to={to}
            scopeId={scopeId}
          />
        ))}

        {availableTagKeys.length === 0 && availableServices.length === 0 && availableEnvironments.length === 0 && (
          <div className="px-4 py-8 text-center text-xs text-muted-foreground">
            No facets available yet. Start sending logs to see available filters.
          </div>
        )}
      </div>
    </div>
  )
}
