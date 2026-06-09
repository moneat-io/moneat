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
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {cn} from '@/lib/utils'
import {logLevelBadgeClass} from '@/lib/severity'
import {ChevronDown, Clock, ListFilter, Search, X} from 'lucide-react'
import {DateTimePicker} from '@/components/ui/datetime-picker'
import {format} from 'date-fns'

export interface FacetFilter {
  key: string
  value: string
  exclude?: boolean
}

interface TimeRangePreset {
  label: string
  value: string
  minutes: number
}

const TIME_PRESETS: TimeRangePreset[] = [
  {label: 'Last 5 minutes', value: '5m', minutes: 5},
  {label: 'Last 15 minutes', value: '15m', minutes: 15},
  {label: 'Last 30 minutes', value: '30m', minutes: 30},
  {label: 'Last 1 hour', value: '1h', minutes: 60},
  {label: 'Last 4 hours', value: '4h', minutes: 240},
  {label: 'Last 12 hours', value: '12h', minutes: 720},
  {label: 'Last 24 hours', value: '24h', minutes: 1440},
  {label: 'Last 3 days', value: '3d', minutes: 4320},
  {label: 'Last 7 days', value: '7d', minutes: 10080},
  {label: 'Last 14 days', value: '14d', minutes: 20160},
  {label: 'Last 30 days', value: '30d', minutes: 43200},
]

const LEVEL_OPTIONS = ['trace', 'debug', 'info', 'warn', 'error', 'fatal']

// Active level chips reuse the shared soft-badge classes (@/lib/severity).
const facetChipColors: Record<string, string> = {
  service: 'bg-[hsl(var(--primary)/0.12)] text-primary border-[hsl(var(--primary)/0.3)]',
  environment: 'bg-success-bg text-success-fg border-success-border',
  host: 'bg-[hsl(var(--chart-6)/0.15)] text-[hsl(var(--chart-6))] border-[hsl(var(--chart-6)/0.3)]',
  source: 'bg-[hsl(var(--chart-7)/0.15)] text-[hsl(var(--chart-7))] border-[hsl(var(--chart-7)/0.3)]',
  trace_id: 'bg-[hsl(var(--chart-3)/0.15)] text-[hsl(var(--chart-3))] border-[hsl(var(--chart-3)/0.3)]',
  message_pattern: 'bg-[hsl(var(--chart-4)/0.15)] text-[hsl(var(--chart-4))] border-[hsl(var(--chart-4)/0.3)]',
}

const BUILT_IN_FACETS = ['service', 'environment', 'env', 'level', 'host', 'source', 'trace_id', 'message_pattern']

interface LogSearchBarProps {
  query: string
  onQueryChange: (value: string) => void
  facetFilters: FacetFilter[]
  onFacetFiltersChange: (filters: FacetFilter[]) => void
  levels: string[]
  onToggleLevel: (level: string) => void
  availableTagKeys: string[]
  availableServices: string[]
  availableEnvironments: string[]
  timePreset: string
  onTimePresetChange: (preset: string) => void
  customFrom: string
  customTo: string
  onCustomFromChange: (value: string) => void
  onCustomToChange: (value: string) => void
}

export function LogSearchBar({
  query,
  onQueryChange,
  facetFilters,
  onFacetFiltersChange,
  levels,
  onToggleLevel,
  availableTagKeys,
  availableServices,
  availableEnvironments,
  timePreset,
  onTimePresetChange,
  customFrom,
  customTo,
  onCustomFromChange,
  onCustomToChange,
}: LogSearchBarProps) {
  const [inputValue, setInputValue] = useState('')
  const [showSuggestions, setShowSuggestions] = useState(false)
  const [showTimeDropdown, setShowTimeDropdown] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const suggestionsRef = useRef<HTMLDivElement>(null)
  const timeDropdownRef = useRef<HTMLDivElement>(null)

  const allFacetKeys = useMemo(() => {
    const keys = new Set([...BUILT_IN_FACETS, ...availableTagKeys])
    return Array.from(keys).sort()
  }, [availableTagKeys])

  const suggestions = useMemo(() => {
    const trimmed = inputValue.trim()
    if (!trimmed) return []

    const colonIndex = trimmed.indexOf(':')
    if (colonIndex === -1) {
      // Suggest matching facet keys
      const matchingKeys = allFacetKeys.filter((key) =>
        key.toLowerCase().startsWith(trimmed.toLowerCase())
      )
      return matchingKeys.slice(0, 8).map((key) => ({
        type: 'key' as const,
        label: key,
        value: `${key}:`,
      }))
    }

    // After colon - suggest values for the key
    const key = trimmed.slice(0, colonIndex).trim().toLowerCase()
    const valuePrefix = trimmed.slice(colonIndex + 1).trim().toLowerCase()

    let possibleValues: string[] = []
    if (key === 'service') {
      possibleValues = availableServices
    } else if (key === 'environment' || key === 'env') {
      possibleValues = availableEnvironments
    } else if (key === 'level') {
      possibleValues = LEVEL_OPTIONS
    }

    if (possibleValues.length > 0) {
      const filtered = valuePrefix
        ? possibleValues.filter((v) => v.toLowerCase().includes(valuePrefix))
        : possibleValues
      return filtered.slice(0, 8).map((v) => ({
        type: 'value' as const,
        label: `${key}:${v}`,
        value: `${key}:${v}`,
      }))
    }

    return []
  }, [inputValue, allFacetKeys, availableServices, availableEnvironments])

  const [selectedSuggestionState, setSelectedSuggestionStateRaw] = useState<{
    suggestions: typeof suggestions
    index: number
  }>({ suggestions: [], index: -1 })
  const selectedSuggestionIndex = selectedSuggestionState.suggestions === suggestions
    ? selectedSuggestionState.index
    : -1
  const setSelectedSuggestionIndex = (updater: number | ((prev: number) => number)) => {
    setSelectedSuggestionStateRaw((prev) => {
      const prevIndex = prev.suggestions === suggestions ? prev.index : -1
      const newIndex = typeof updater === 'function' ? updater(prevIndex) : updater
      return { suggestions, index: newIndex }
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
      if (
        timeDropdownRef.current &&
        !timeDropdownRef.current.contains(event.target as Node)
      ) {
        setShowTimeDropdown(false)
      }
    }

    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const applyToken = useCallback(
    (token: string) => {
      // If the input contains Boolean operators, treat the entire input as a query
      const hasBooleanOps = /\b(AND|OR)\b/.test(token)
      if (hasBooleanOps) {
        const newQuery = query ? `${query} ${token}` : token
        onQueryChange(newQuery.trim())
        setInputValue('')
        setShowSuggestions(false)
        return
      }

      const colonIndex = token.indexOf(':')
      if (colonIndex > 0) {
        const isExclude = token.startsWith('-')
        const rawKey = isExclude ? token.slice(1, colonIndex).trim() : token.slice(0, colonIndex).trim()
        const value = token.slice(colonIndex + 1).trim()

        if (!rawKey) return
        // Empty value (trailing colon) or wildcards → route to query parser
        if (!value || value.includes('*') || value.includes('?')) {
          const newQuery = query ? `${query} ${token}` : token
          onQueryChange(newQuery.trim())
          setInputValue('')
          setShowSuggestions(false)
          return
        }

        // Handle special facets
        const key = rawKey.toLowerCase() === 'env' ? 'environment' : rawKey.toLowerCase()

        if (key === 'level') {
          if (!levels.includes(value.toLowerCase())) {
            onToggleLevel(value.toLowerCase())
          }
        } else if (!isExclude && ['service', 'environment', 'host', 'source', 'trace_id', 'message_pattern'].includes(key)) {
          // Only simple includes on known facet fields become facet filters
          const existing = facetFilters.filter((f) => f.key !== key || f.value !== value)
          onFacetFiltersChange([...existing, {key, value, exclude: false}])
        } else {
          // Negated fields, message:, and custom fields → route to query parser
          const newQuery = query ? `${query} ${token}` : token
          onQueryChange(newQuery.trim())
        }
      } else {
        // Free text - append to query
        const newQuery = query ? `${query} ${token}` : token
        onQueryChange(newQuery.trim())
      }
      setInputValue('')
      setShowSuggestions(false)
    },
    [query, onQueryChange, facetFilters, onFacetFiltersChange, levels, onToggleLevel]
  )

  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Enter') {
      event.preventDefault()
      if (selectedSuggestionIndex >= 0 && selectedSuggestionIndex < suggestions.length) {
        const selected = suggestions[selectedSuggestionIndex]
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
      setSelectedSuggestionIndex((prev) => Math.min(prev + 1, suggestions.length - 1))
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setSelectedSuggestionIndex((prev) => Math.max(prev - 1, -1))
    } else if (event.key === 'Escape') {
      setShowSuggestions(false)
    } else if (event.key === 'Backspace' && !inputValue) {
      // Remove last filter if input is empty
      if (query) {
        const words = query.trim().split(/\s+/)
        if (words.length > 0) {
          onQueryChange(words.slice(0, -1).join(' '))
        }
      } else if (facetFilters.length > 0) {
        onFacetFiltersChange(facetFilters.slice(0, -1))
      }
    }
  }

  const removeFacetFilter = (index: number) => {
    onFacetFiltersChange(facetFilters.filter((_, i) => i !== index))
  }

  const clearQuery = () => {
    onQueryChange('')
    setInputValue('')
  }

  const activeTimeLabel = useMemo(() => {
    if (timePreset === 'custom') {
      if (!customFrom && !customTo) return 'Custom range'
      
      try {
        const fromDate = customFrom ? new Date(customFrom) : null
        const toDate = customTo ? new Date(customTo) : null
        
        if (fromDate && toDate) {
          // Check if same day
          const sameDay = format(fromDate, 'yyyy-MM-dd') === format(toDate, 'yyyy-MM-dd')
          if (sameDay) {
            return `${format(fromDate, 'MMM d, HH:mm')} - ${format(toDate, 'HH:mm')}`
          }
          return `${format(fromDate, 'MMM d, HH:mm')} - ${format(toDate, 'MMM d, HH:mm')}`
        }
        
        if (fromDate) return `From ${format(fromDate, 'MMM d, HH:mm')}`
        if (toDate) return `Until ${format(toDate, 'MMM d, HH:mm')}`
      } catch {
        // Invalid date
      }
      
      return 'Custom range'
    }
    const preset = TIME_PRESETS.find((p) => p.value === timePreset)
    return preset?.label ?? 'Last 15 minutes'
  }, [timePreset, customFrom, customTo])

  const hasCustomLevelFilter = levels.length > 0 && levels.length < LEVEL_OPTIONS.length

  const resetLevelFilter = useCallback(() => {
    if (!hasCustomLevelFilter) return
    const selectedLevels = [...levels]
    selectedLevels.forEach((level) => onToggleLevel(level))
    LEVEL_OPTIONS.forEach((level) => onToggleLevel(level))
  }, [hasCustomLevelFilter, levels, onToggleLevel])

  const hasActiveFilters = query || facetFilters.length > 0 || hasCustomLevelFilter

  const [showLevelDropdown, setShowLevelDropdown] = useState(false)
  const levelDropdownRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handleLevelClickOutside(event: MouseEvent) {
      if (
        levelDropdownRef.current &&
        !levelDropdownRef.current.contains(event.target as Node)
      ) {
        setShowLevelDropdown(false)
      }
    }
    document.addEventListener('mousedown', handleLevelClickOutside)
    return () => document.removeEventListener('mousedown', handleLevelClickOutside)
  }, [])

  const levelSummary = useMemo(() => {
    if (levels.length === 0 || levels.length === LEVEL_OPTIONS.length) return 'All Levels'
    if (levels.length === 1) return levels[0].charAt(0).toUpperCase() + levels[0].slice(1)
    return `${levels.length} Levels`
  }, [levels])

  return (
    <div className="flex items-stretch gap-1.5">
      {/* Search input with chips */}
      <div className="relative flex-1 min-w-0">
        <div className="flex min-h-[30px] flex-wrap items-center gap-1 rounded-md border bg-card px-2 py-0.5 ring-offset-background transition-colors focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2">
          <Search className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />

            {/* Query text chip */}
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
                    clearQuery()
                  }}
                  className="ml-0.5 rounded-full hover:bg-info-border"
                >
                  <X className="h-3 w-3" />
                </button>
              </Badge>
            )}

            {/* Facet filter chips */}
            {facetFilters.map((filter, index) => (
              <Badge
                key={`${filter.key}-${filter.value}-${index}`}
                variant="outline"
                className={cn(
                  'gap-1 font-mono text-xs cursor-pointer hover:opacity-80',
                  filter.exclude && 'line-through opacity-75',
                  facetChipColors[filter.key] || 'bg-info-bg text-info-fg border-info-border'
                )}
                onClick={() => {
                  const token = `${filter.exclude ? '-' : ''}${filter.key}:${filter.value}`
                  setInputValue(token)
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
                >
                  <X className="h-3 w-3" />
                </button>
              </Badge>
            ))}

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
              placeholder={
                hasActiveFilters
                  ? 'Add...'
                  : 'Search...'
              }
              className="min-w-[80px] sm:min-w-[200px] flex-1 bg-transparent text-xs outline-none placeholder:text-muted-foreground"
            />

            {hasActiveFilters && (
              <button
                type="button"
                onClick={() => {
                  onQueryChange('')
                  onFacetFiltersChange([])
                  resetLevelFilter()
                  setInputValue('')
                }}
                className="shrink-0 rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            )}
          </div>

          {/* Autocomplete suggestions */}
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
                    index === selectedSuggestionIndex
                      ? 'bg-accent text-accent-foreground'
                      : 'hover:bg-accent/50'
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

        {/* Level selector dropdown */}
        <div className="relative" ref={levelDropdownRef}>
          <Button
            variant="outline"
            size="default"
            className={cn(
              'h-[30px] px-2 sm:px-3 gap-1.5 whitespace-nowrap font-normal text-xs',
              hasCustomLevelFilter && 'border-primary/40'
            )}
            onClick={() => setShowLevelDropdown(!showLevelDropdown)}
          >
            <ListFilter className="h-3.5 w-3.5 text-muted-foreground" />
            <span className="hidden sm:inline text-xs">{levelSummary}</span>
            <ChevronDown className="hidden sm:inline h-3 w-3 text-muted-foreground" />
          </Button>

          {showLevelDropdown && (
            <div className="absolute right-0 top-full z-50 mt-1 w-[200px] rounded-lg border bg-popover p-1">
              {LEVEL_OPTIONS.map((level) => {
                const active = levels.includes(level)
                return (
                  <button
                    key={level}
                    type="button"
                    onClick={() => onToggleLevel(level)}
                    className={cn(
                      'flex w-full items-center gap-2 rounded-md px-3 py-1.5 text-left text-sm transition-colors',
                      active
                        ? cn(logLevelBadgeClass(level), 'border')
                        : 'hover:bg-accent/50'
                    )}
                  >
                    <span className="font-mono text-xs uppercase">{level}</span>
                  </button>
                )
              })}
              {hasCustomLevelFilter && (
                <div className="border-t mt-1 pt-1">
                  <button
                    type="button"
                    onClick={() => {
                      resetLevelFilter()
                      setShowLevelDropdown(false)
                    }}
                    className="flex w-full items-center rounded-md px-3 py-1.5 text-left text-sm text-muted-foreground hover:bg-accent/50"
                  >
                    Reset to all levels
                  </button>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Time range selector */}
        <div className="relative" ref={timeDropdownRef}>
            <Button
              variant="outline"
              size="default"
              className="h-[30px] px-2 sm:px-3 gap-1.5 whitespace-nowrap font-normal text-xs"
              onClick={() => setShowTimeDropdown(!showTimeDropdown)}
            >
              <Clock className="h-3.5 w-3.5 text-muted-foreground" />
              <span className="hidden sm:inline text-xs">{activeTimeLabel}</span>
              <ChevronDown className="hidden sm:inline h-3 w-3 text-muted-foreground" />
            </Button>

          {showTimeDropdown && (
            <div className="absolute right-0 top-full z-50 mt-1 w-[260px] rounded-lg border bg-popover p-1">
              {TIME_PRESETS.map((preset) => (
                <button
                  key={preset.value}
                  type="button"
                  className={cn(
                    'flex w-full items-center rounded-md px-3 py-2 text-left text-sm transition-colors',
                    timePreset === preset.value
                      ? 'bg-primary/10 text-primary font-medium'
                      : 'hover:bg-accent/50'
                  )}
                  onClick={() => {
                    onTimePresetChange(preset.value)
                    setShowTimeDropdown(false)
                  }}
                >
                  {preset.label}
                </button>
              ))}
              <div className="border-t mt-1 pt-1">
                <button
                  type="button"
                  className={cn(
                    'flex w-full items-center rounded-md px-3 py-2 text-left text-sm transition-colors',
                    timePreset === 'custom'
                      ? 'bg-primary/10 text-primary font-medium'
                      : 'hover:bg-accent/50'
                  )}
                  onClick={() => {
                    onTimePresetChange('custom')
                  }}
                >
                  Custom range...
                </button>
                {timePreset === 'custom' && (
                  <div className="space-y-2 px-3 py-2">
                    <div>
                      <label className="mb-1 block text-[11px] uppercase tracking-wide text-muted-foreground">From</label>
                      <DateTimePicker
                        value={customFrom}
                        onChange={onCustomFromChange}
                        placeholder="Select start time"
                      />
                    </div>
                    <div>
                      <label className="mb-1 block text-[11px] uppercase tracking-wide text-muted-foreground">To</label>
                      <DateTimePicker
                        value={customTo}
                        onChange={onCustomToChange}
                        placeholder="Select end time"
                      />
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
  )
}

export {LEVEL_OPTIONS, TIME_PRESETS}
