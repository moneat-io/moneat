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

import {useCallback, useEffect, useMemo, useRef, useState} from 'react'
import type {ReactNode} from 'react'
import {Search, X} from 'lucide-react'

import {cn} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {
  type FacetFilter,
  type FacetSchema,
  applyFacetFilter,
  resolveFacetDef,
} from '@/lib/filters/types'

const DEFAULT_CHIP_COLOR = 'bg-info-bg text-info-fg border-info-border'

interface Suggestion {
  type: 'key' | 'value'
  label: string
  value: string
}

export interface SearchFilterBarProps {
  query: string
  onQueryChange: (value: string) => void
  facetFilters: FacetFilter[]
  onFacetFiltersChange: (filters: FacetFilter[]) => void
  /** Facets this surface understands — drives typeahead + chip routing. */
  schema: FacetSchema
  /** Extra keys to suggest that are NOT facet-routable (typed values → query). */
  suggestKeys?: readonly string[]
  /**
   * Intercept a parsed `key:value` token before it becomes a chip/query (e.g.
   * the log viewer routes `level:x` to its level toggle). Return true if handled.
   */
  onInterceptToken?: (key: string, value: string, exclude: boolean) => boolean
  /** Surface-specific controls rendered after the search box (time, levels…). */
  trailing?: ReactNode
  placeholder?: string
  /** Whether a surface-managed filter (e.g. levels) is active, for clear-all. */
  hasExtraActiveFilters?: boolean
  /** Reset the surface-managed filter when the bar's clear-all is pressed. */
  onClearExtra?: () => void
  className?: string
}

/**
 * Unified search/filter bar in the house style (generalized from the log
 * viewer): a free-text query plus inline removable facet chips, with
 * `key:value` / `-key:value` typeahead. Schema-driven so any surface reuses it;
 * surface-specific controls (time range, log levels) go in `trailing`.
 *
 * Dropdowns are plain positioned elements with click-outside (not Radix), so
 * the bar — including its suggestions — is exercisable under jsdom.
 */
export function SearchFilterBar({
  query,
  onQueryChange,
  facetFilters,
  onFacetFiltersChange,
  schema,
  suggestKeys = [],
  onInterceptToken,
  trailing,
  placeholder,
  hasExtraActiveFilters = false,
  onClearExtra,
  className,
}: SearchFilterBarProps) {
  const [inputValue, setInputValue] = useState('')
  const [showSuggestions, setShowSuggestions] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const suggestionsRef = useRef<HTMLDivElement>(null)

  const allKeys = useMemo(() => {
    const keys = new Set<string>()
    for (const def of schema) {
      keys.add(def.key)
      def.aliases?.forEach((a) => keys.add(a))
    }
    suggestKeys.forEach((k) => keys.add(k))
    return Array.from(keys).sort()
  }, [schema, suggestKeys])

  const suggestions = useMemo<Suggestion[]>(() => {
    const trimmed = inputValue.trim()
    if (!trimmed) return []

    const colonIndex = trimmed.indexOf(':')
    if (colonIndex === -1) {
      return allKeys
        .filter((key) => key.toLowerCase().startsWith(trimmed.toLowerCase()))
        .slice(0, 8)
        .map((key) => ({type: 'key' as const, label: key, value: `${key}:`}))
    }

    const rawKey = trimmed.slice(0, colonIndex).trim().toLowerCase()
    const valuePrefix = trimmed.slice(colonIndex + 1).trim().toLowerCase()
    const def = resolveFacetDef(schema, rawKey)
    const possibleValues = def?.suggestions ?? []

    if (possibleValues.length > 0) {
      const filtered = valuePrefix
        ? possibleValues.filter((v) => v.toLowerCase().includes(valuePrefix))
        : possibleValues
      return filtered.slice(0, 8).map((v) => ({
        type: 'value' as const,
        label: `${rawKey}:${v}`,
        value: `${rawKey}:${v}`,
      }))
    }
    return []
  }, [inputValue, allKeys, schema])

  // Track the selected suggestion against the suggestion set it belongs to, so a
  // changing list never leaves a stale highlight (ported from the log bar).
  const [selectedState, setSelectedState] = useState<{suggestions: Suggestion[]; index: number}>({
    suggestions: [],
    index: -1,
  })
  const selectedIndex = selectedState.suggestions === suggestions ? selectedState.index : -1
  const setSelectedIndex = (updater: number | ((prev: number) => number)) => {
    setSelectedState((prev) => {
      const prevIndex = prev.suggestions === suggestions ? prev.index : -1
      const newIndex = typeof updater === 'function' ? updater(prevIndex) : updater
      return {suggestions, index: newIndex}
    })
  }

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (
        suggestionsRef.current &&
        !suggestionsRef.current.contains(event.target as Node) &&
        inputRef.current &&
        !inputRef.current.contains(event.target as Node)
      ) {
        setShowSuggestions(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const appendToQuery = useCallback(
    (token: string) => {
      const newQuery = query ? `${query} ${token}` : token
      onQueryChange(newQuery.trim())
    },
    [query, onQueryChange]
  )

  const applyToken = useCallback(
    (token: string) => {
      // Boolean operators → treat the whole thing as a free-text query.
      if (/\b(AND|OR)\b/.test(token)) {
        appendToQuery(token)
        setInputValue('')
        setShowSuggestions(false)
        return
      }

      const colonIndex = token.indexOf(':')
      if (colonIndex > 0) {
        const isExclude = token.startsWith('-')
        const rawKey = (isExclude ? token.slice(1, colonIndex) : token.slice(0, colonIndex)).trim()
        const value = token.slice(colonIndex + 1).trim()

        if (!rawKey) return
        // Empty value (trailing colon) or wildcards → query parser.
        if (!value || value.includes('*') || value.includes('?')) {
          appendToQuery(token)
          setInputValue('')
          setShowSuggestions(false)
          return
        }

        const def = resolveFacetDef(schema, rawKey)
        const canonicalKey = def?.key ?? rawKey.toLowerCase()

        if (onInterceptToken?.(canonicalKey, value, isExclude)) {
          setInputValue('')
          setShowSuggestions(false)
          return
        }

        if (def && (!isExclude || def.allowExclude)) {
          onFacetFiltersChange(
            applyFacetFilter(
              facetFilters,
              {key: def.key, value, exclude: isExclude},
              {singleSelect: def.singleSelect}
            )
          )
        } else {
          // Unknown key, or an exclude on a non-excludable facet → query parser.
          appendToQuery(token)
        }
      } else {
        appendToQuery(token)
      }
      setInputValue('')
      setShowSuggestions(false)
    },
    [appendToQuery, schema, onInterceptToken, facetFilters, onFacetFiltersChange]
  )

  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Enter') {
      event.preventDefault()
      if (selectedIndex >= 0 && selectedIndex < suggestions.length) {
        const selected = suggestions[selectedIndex]
        if (selected.type === 'key') {
          setInputValue(selected.value)
        } else {
          applyToken(selected.value)
        }
      } else if (inputValue.trim()) {
        applyToken(inputValue.trim())
      }
    } else if (event.key === 'ArrowDown') {
      event.preventDefault()
      setSelectedIndex((prev) => Math.min(prev + 1, suggestions.length - 1))
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setSelectedIndex((prev) => Math.max(prev - 1, -1))
    } else if (event.key === 'Escape') {
      setShowSuggestions(false)
    } else if (event.key === 'Backspace' && !inputValue) {
      // Empty input → peel off the last query word, then the last facet chip.
      if (query) {
        const words = query.trim().split(/\s+/)
        onQueryChange(words.slice(0, -1).join(' '))
      } else if (facetFilters.length > 0) {
        onFacetFiltersChange(facetFilters.slice(0, -1))
      }
    }
  }

  const removeFacetFilter = (index: number) => {
    onFacetFiltersChange(facetFilters.filter((_, i) => i !== index))
  }

  const hasInlineTokens = Boolean(query) || facetFilters.length > 0
  const hasActiveFilters = hasInlineTokens || hasExtraActiveFilters

  const clearAll = () => {
    onQueryChange('')
    onFacetFiltersChange([])
    onClearExtra?.()
    setInputValue('')
  }

  return (
    <div className={cn('@container/filterbar flex items-stretch gap-1.5', className)}>
      <div className="relative min-w-[120px] flex-1">
        <div className="flex min-h-[30px] flex-wrap items-center gap-1 rounded-md border bg-card px-2 py-0.5 ring-offset-background transition-colors focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2">
          <Search className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />

          {query && (
            <Badge
              variant="secondary"
              className="gap-1 font-mono text-xs bg-info-bg text-info-fg border border-info-border cursor-pointer hover:opacity-80"
              onClick={() => {
                setInputValue(query)
                onQueryChange('')
                inputRef.current?.focus()
              }}
            >
              {query}
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation()
                  onQueryChange('')
                  setInputValue('')
                }}
                className="ml-0.5 rounded-full hover:bg-info-border"
                aria-label="Remove search text"
              >
                <X className="h-3 w-3" />
              </button>
            </Badge>
          )}

          {facetFilters.map((filter, index) => {
            const def = resolveFacetDef(schema, filter.key)
            return (
              <Badge
                key={`${filter.key}-${filter.value}-${index}`}
                variant="outline"
                className={cn(
                  'gap-1 font-mono text-xs cursor-pointer hover:opacity-80',
                  filter.exclude && 'line-through opacity-75',
                  def?.color || DEFAULT_CHIP_COLOR
                )}
                onClick={() => {
                  setInputValue(`${filter.exclude ? '-' : ''}${filter.key}:${filter.value}`)
                  removeFacetFilter(index)
                  inputRef.current?.focus()
                }}
              >
                {filter.exclude && '- '}
                {filter.key}:{filter.value}
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation()
                    removeFacetFilter(index)
                  }}
                  className="ml-0.5 rounded-full hover:bg-foreground/20"
                  aria-label={`Remove ${filter.key}:${filter.value}`}
                >
                  <X className="h-3 w-3" />
                </button>
              </Badge>
            )
          })}

          <input
            ref={inputRef}
            type="text"
            value={inputValue}
            onChange={(e) => {
              setInputValue(e.target.value)
              setShowSuggestions(true)
            }}
            onFocus={() => setShowSuggestions(true)}
            onKeyDown={handleKeyDown}
            placeholder={hasActiveFilters ? 'Add...' : placeholder ?? 'Search...'}
            className={cn(
              'flex-1 bg-transparent text-xs outline-none placeholder:text-muted-foreground',
              // Empty: shrink to the icon so a narrow bar keeps the placeholder inline.
              // With chips: hold a min-width so the field wraps to its own row when tight.
              hasInlineTokens ? 'min-w-[96px]' : 'min-w-0'
            )}
          />

          {hasActiveFilters && (
            <button
              type="button"
              onClick={clearAll}
              className="shrink-0 rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
              aria-label="Clear all filters"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          )}
        </div>

        {showSuggestions && suggestions.length > 0 && (
          <div
            ref={suggestionsRef}
            className="absolute left-0 right-0 top-full z-50 mt-1 rounded-lg border bg-popover p-1"
          >
            {suggestions.map((suggestion, index) => (
              <button
                key={suggestion.value}
                type="button"
                className={cn(
                  'flex w-full items-center gap-2 rounded-md px-3 py-2 text-left text-sm transition-colors',
                  index === selectedIndex ? 'bg-accent text-accent-foreground' : 'hover:bg-accent/50'
                )}
                onMouseDown={(e) => {
                  e.preventDefault()
                  if (suggestion.type === 'key') {
                    setInputValue(suggestion.value)
                    inputRef.current?.focus()
                  } else {
                    applyToken(suggestion.value)
                  }
                }}
              >
                <span className="font-mono text-xs text-muted-foreground">
                  {suggestion.type === 'key' ? 'facet' : 'filter'}
                </span>
                <span className="font-mono">{suggestion.label}</span>
              </button>
            ))}
            <div className="border-t mt-1 pt-1 px-3 py-1.5 text-[11px] text-muted-foreground">
              Use <code className="rounded bg-muted px-1">key:value</code> for facet filters,{' '}
              <code className="rounded bg-muted px-1">-key:value</code> to exclude
            </div>
          </div>
        )}
      </div>

      {trailing}
    </div>
  )
}
