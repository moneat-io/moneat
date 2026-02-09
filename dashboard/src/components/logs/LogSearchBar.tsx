import {useCallback, useEffect, useMemo, useRef, useState} from 'react'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {cn} from '@/lib/utils'
import {ChevronDown, Clock, Search, X} from 'lucide-react'

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
]

const LEVEL_OPTIONS = ['trace', 'debug', 'info', 'warn', 'error', 'fatal']

const levelColors: Record<string, string> = {
  trace: 'bg-zinc-500/15 text-zinc-600 dark:text-zinc-300 hover:bg-zinc-500/25',
  debug: 'bg-cyan-500/15 text-cyan-600 dark:text-cyan-300 hover:bg-cyan-500/25',
  info: 'bg-blue-500/15 text-blue-600 dark:text-blue-300 hover:bg-blue-500/25',
  warn: 'bg-amber-500/15 text-amber-600 dark:text-amber-300 hover:bg-amber-500/25',
  error: 'bg-red-500/15 text-red-600 dark:text-red-300 hover:bg-red-500/25',
  fatal: 'bg-rose-500/20 text-rose-600 dark:text-rose-300 hover:bg-rose-500/25',
}

const levelActiveColors: Record<string, string> = {
  trace: 'bg-zinc-500/30 text-zinc-700 dark:text-zinc-200 border-zinc-500/50 ring-1 ring-zinc-500/20',
  debug: 'bg-cyan-500/30 text-cyan-700 dark:text-cyan-200 border-cyan-500/50 ring-1 ring-cyan-500/20',
  info: 'bg-blue-500/30 text-blue-700 dark:text-blue-200 border-blue-500/50 ring-1 ring-blue-500/20',
  warn: 'bg-amber-500/30 text-amber-700 dark:text-amber-200 border-amber-500/50 ring-1 ring-amber-500/20',
  error: 'bg-red-500/30 text-red-700 dark:text-red-200 border-red-500/50 ring-1 ring-red-500/20',
  fatal: 'bg-rose-500/40 text-rose-700 dark:text-rose-200 border-rose-500/50 ring-1 ring-rose-500/20',
}

const facetChipColors: Record<string, string> = {
  service: 'bg-violet-500/15 text-violet-700 dark:text-violet-300 border-violet-500/30',
  environment: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/30',
  host: 'bg-orange-500/15 text-orange-700 dark:text-orange-300 border-orange-500/30',
  source: 'bg-pink-500/15 text-pink-700 dark:text-pink-300 border-pink-500/30',
}

const BUILT_IN_FACETS = ['service', 'environment', 'env', 'level', 'host', 'source']

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
  const [selectedSuggestionIndex, setSelectedSuggestionIndex] = useState(-1)
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

  useEffect(() => {
    setSelectedSuggestionIndex(-1)
  }, [suggestions])

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
      const colonIndex = token.indexOf(':')
      if (colonIndex > 0) {
        const isExclude = token.startsWith('-')
        const rawKey = isExclude ? token.slice(1, colonIndex).trim() : token.slice(0, colonIndex).trim()
        const value = token.slice(colonIndex + 1).trim()

        if (!rawKey || !value) return

        // Handle special facets
        const key = rawKey.toLowerCase() === 'env' ? 'environment' : rawKey.toLowerCase()

        if (key === 'level') {
          if (!levels.includes(value.toLowerCase())) {
            onToggleLevel(value.toLowerCase())
          }
        } else if (['service', 'environment', 'host', 'source'].includes(key)) {
          const existing = facetFilters.filter((f) => f.key !== key || f.value !== value)
          onFacetFiltersChange([...existing, {key, value, exclude: isExclude}])
        } else {
          const existing = facetFilters.filter((f) => f.key !== rawKey || f.value !== value)
          onFacetFiltersChange([...existing, {key: rawKey, value, exclude: isExclude}])
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
    if (timePreset === 'custom') return 'Custom'
    const preset = TIME_PRESETS.find((p) => p.value === timePreset)
    return preset?.label ?? 'Last 15 minutes'
  }, [timePreset])

  const hasActiveFilters = query || facetFilters.length > 0 || levels.length > 0

  return (
    <div className="space-y-2.5">
      {/* Main search row */}
      <div className="flex items-stretch gap-2">
        {/* Search input with chips */}
        <div className="relative flex-1">
          <div className="flex min-h-[40px] flex-wrap items-center gap-1.5 rounded-lg border bg-card px-3 py-1.5 ring-offset-background transition-colors focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2">
            <Search className="h-4 w-4 shrink-0 text-muted-foreground" />

            {/* Query text chip */}
            {query && (
              <Badge
                variant="secondary"
                className="gap-1 font-mono text-xs bg-blue-500/10 text-blue-700 dark:text-blue-300 border border-blue-500/20"
              >
                {query}
                <button
                  type="button"
                  onClick={clearQuery}
                  className="ml-0.5 rounded-full hover:bg-blue-500/20"
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
                  'gap-1 font-mono text-xs',
                  filter.exclude && 'line-through opacity-75',
                  facetChipColors[filter.key] || 'bg-indigo-500/10 text-indigo-700 dark:text-indigo-300 border-indigo-500/20'
                )}
              >
                {filter.exclude && '- '}
                {filter.key}:{filter.value}
                <button
                  type="button"
                  onClick={() => removeFacetFilter(index)}
                  className="ml-0.5 rounded-full hover:bg-foreground/10"
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
                  ? 'Add more filters...'
                  : 'Search logs... (try service:api or tag:value)'
              }
              className="min-w-[200px] flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
            />

            {hasActiveFilters && (
              <button
                type="button"
                onClick={() => {
                  onQueryChange('')
                  onFacetFiltersChange([])
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
              className="absolute left-0 right-0 top-full z-50 mt-1 rounded-lg border bg-popover p-1 shadow-lg"
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

        {/* Time range selector */}
        <div className="relative" ref={timeDropdownRef}>
          <Button
            variant="outline"
            size="default"
            className="h-[40px] gap-2 whitespace-nowrap font-normal"
            onClick={() => setShowTimeDropdown(!showTimeDropdown)}
          >
            <Clock className="h-4 w-4 text-muted-foreground" />
            <span className="text-sm">{activeTimeLabel}</span>
            <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
          </Button>

          {showTimeDropdown && (
            <div className="absolute right-0 top-full z-50 mt-1 w-[260px] rounded-lg border bg-popover p-1 shadow-lg">
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
                      <input
                        type="datetime-local"
                        value={customFrom}
                        onChange={(e) => onCustomFromChange(e.target.value)}
                        className="w-full rounded-md border bg-background px-2 py-1.5 text-xs"
                      />
                    </div>
                    <div>
                      <label className="mb-1 block text-[11px] uppercase tracking-wide text-muted-foreground">To</label>
                      <input
                        type="datetime-local"
                        value={customTo}
                        onChange={(e) => onCustomToChange(e.target.value)}
                        className="w-full rounded-md border bg-background px-2 py-1.5 text-xs"
                      />
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Level pills */}
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="mr-1 text-[11px] font-medium uppercase tracking-wider text-muted-foreground">Levels</span>
        {LEVEL_OPTIONS.map((level) => {
          const active = levels.includes(level)
          return (
            <button
              key={level}
              type="button"
              onClick={() => onToggleLevel(level)}
              aria-pressed={active}
              className={cn(
                'rounded-md px-2.5 py-1 font-mono text-[11px] uppercase transition-all',
                active
                  ? levelActiveColors[level]
                  : levelColors[level]
              )}
            >
              {level}
            </button>
          )
        })}
        {levels.length > 0 && (
          <button
            type="button"
            onClick={() => levels.forEach((l) => onToggleLevel(l))}
            className="ml-1 text-[11px] text-muted-foreground hover:text-foreground"
          >
            Clear
          </button>
        )}
      </div>
    </div>
  )
}

export {TIME_PRESETS}
