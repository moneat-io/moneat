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

import {useMemo, useState} from 'react'
import {Link} from '@tanstack/react-router'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Checkbox} from '@/components/ui/checkbox'
import {Separator} from '@/components/ui/separator'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {Popover, PopoverContent, PopoverTrigger} from '@/components/ui/popover'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import type {DashboardVariable, TimeRangeDef} from '@/lib/api'
import {cn, formatRelativeTime} from '@/lib/utils'
import {
  ArrowLeft, Check, ChevronDown, Clock, Copy, Download, Home, MoreHorizontal,
  Pencil, Plus, RefreshCw, Settings2, Share2, Star, Trash2,
} from 'lucide-react'
import {
  ALL_VALUE, MULTI_SEPARATOR, REFRESH_OPTIONS, TIME_RANGE_PRESETS,
  activePreset, effectiveValue, realOptions, refreshLabel, selectedValues, timeRangeLabel,
  timeRangeWindow, variableDisplay,
} from './dashboardToolbarHelpers'
import {buildDashboardShareUrl} from './dashboardShareLink'

interface DashboardToolbarProps {
  title: string
  /** ISO timestamp of the last edit, shown as "edited 2h ago". */
  updatedAt?: string
  isEditing: boolean
  isFavorited?: boolean
  isDefault?: boolean
  onToggleEdit: () => void
  onSave: () => void
  onTitleChange: (title: string) => void
  onAddWidget: () => void
  onExport: () => void
  onDuplicate: () => void
  onDelete: () => void
  onToggleFavorite: () => void
  onSetDefault: () => void
  timeRange: TimeRangeDef
  onTimeRangeChange: (range: TimeRangeDef) => void
  refreshMs: number
  onRefreshMsChange: (ms: number) => void
  onRefreshNow: () => void
  variables?: DashboardVariable[]
  variableValues: Record<string, string>
  onVariableChange: (name: string, value: string) => void
  onVariableSettings?: () => void
}

export function DashboardToolbar(props: DashboardToolbarProps) {
  const {
    title, updatedAt, isEditing, isFavorited, isDefault, onToggleEdit, onSave, onTitleChange,
    onAddWidget, onExport, onDuplicate, onDelete, onToggleFavorite, onSetDefault,
    timeRange, onTimeRangeChange, refreshMs, onRefreshMsChange, onRefreshNow,
    variables, variableValues, onVariableChange, onVariableSettings,
  } = props

  const hasVariables = !!variables && variables.length > 0
  const showVariables = hasVariables || (isEditing && !!onVariableSettings)
  const editedLabel = updatedAt ? formatRelativeTime(updatedAt) : undefined

  return (
    <TooltipProvider delayDuration={300}>
      <div className="sticky top-0 z-50 -mx-4 -mt-4 mb-4 flex flex-wrap items-center gap-x-2 gap-y-1.5 border-b bg-background/95 px-4 py-1.5 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        {/* Zone 1 — identity */}
        <div className="flex min-w-0 items-center gap-1">
          <Tooltip>
            <TooltipTrigger asChild>
              <Link
                to="/dashboards"
                aria-label="Back to dashboards"
                className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
              >
                <ArrowLeft className="h-4 w-4" />
              </Link>
            </TooltipTrigger>
            <TooltipContent>Back to dashboards</TooltipContent>
          </Tooltip>

          <FavoriteButton isFavorited={!!isFavorited} onToggle={onToggleFavorite} />

          <div className="min-w-0">
            <div className="flex items-center gap-1.5 text-[10px] font-medium uppercase leading-none tracking-wide text-muted-foreground">
              <span>Dashboards</span>
              {isDefault && (
                <span className="inline-flex items-center gap-0.5 rounded-sm bg-primary/10 px-1 py-px text-primary">
                  <Home className="h-2.5 w-2.5" />Home
                </span>
              )}
            </div>
            <TitleField title={title} isEditing={isEditing} onTitleChange={onTitleChange} />
          </div>

          {editedLabel && editedLabel !== 'unknown' && (
            <span className="hidden whitespace-nowrap text-[11px] text-muted-foreground lg:inline">
              · edited {editedLabel}
            </span>
          )}
        </div>

        {/* Zone 2 — template variables */}
        {showVariables && (
          <div className="flex min-w-0 flex-1 flex-wrap items-center gap-1">
            {variables?.map((variable) => (
              <VariablePill
                key={variable.name}
                variable={variable}
                value={variableValues[variable.name]}
                onChange={(value) => onVariableChange(variable.name, value)}
              />
            ))}
            {isEditing && onVariableSettings && (
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-7 w-7"
                    onClick={onVariableSettings}
                    aria-label="Manage variables"
                  >
                    <Settings2 className="h-3.5 w-3.5" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>Manage variables</TooltipContent>
              </Tooltip>
            )}
          </div>
        )}

        {/* Zone 3 + 4 — time, refresh, actions */}
        <div className="ml-auto flex shrink-0 items-center gap-1.5">
          <TimeRangeControl timeRange={timeRange} onChange={onTimeRangeChange} />
          <RefreshControl refreshMs={refreshMs} onChange={onRefreshMsChange} onRefreshNow={onRefreshNow} />

          <Separator orientation="vertical" className="mx-0.5 h-5" />

          {isEditing ? (
            <>
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button variant="outline" size="icon" className="h-7 w-7" onClick={onAddWidget} aria-label="Add widget">
                    <Plus className="h-4 w-4" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>Add widget</TooltipContent>
              </Tooltip>
              <Button size="sm" className="h-7 gap-1.5 px-2.5 text-xs" onClick={onSave}>
                <Check className="h-3.5 w-3.5" />
                Done
              </Button>
            </>
          ) : (
            <>
              <ShareButton timeRange={timeRange} variableValues={variableValues} />
              <Button
                variant="outline"
                size="sm"
                className="h-7 gap-1.5 px-2.5 text-xs"
                onClick={onToggleEdit}
                aria-label="Edit dashboard"
              >
                <Pencil className="h-3.5 w-3.5" />
                Edit
              </Button>
              <DashboardActionsMenu
                isDefault={!!isDefault}
                onDuplicate={onDuplicate}
                onExport={onExport}
                onSetDefault={onSetDefault}
                onVariableSettings={onVariableSettings}
                onDelete={onDelete}
              />
            </>
          )}
        </div>
      </div>
    </TooltipProvider>
  )
}

function FavoriteButton({isFavorited, onToggle}: {isFavorited: boolean; onToggle: () => void}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          type="button"
          onClick={onToggle}
          aria-label={isFavorited ? 'Remove from favorites' : 'Add to favorites'}
          aria-pressed={isFavorited}
          className={cn(
            'inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md transition-colors hover:bg-muted',
            isFavorited ? 'text-amber-500' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          <Star className={cn('h-3.5 w-3.5', isFavorited && 'fill-current')} />
        </button>
      </TooltipTrigger>
      <TooltipContent>{isFavorited ? 'Remove from favorites' : 'Add to favorites'}</TooltipContent>
    </Tooltip>
  )
}

function TitleField({
  title, isEditing, onTitleChange,
}: {title: string; isEditing: boolean; onTitleChange: (t: string) => void}) {
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState(title)

  const startEditing = () => {
    setValue(title)
    setEditing(true)
  }

  const commit = () => {
    if (value.trim() && value !== title) onTitleChange(value.trim())
    setEditing(false)
  }

  if (editing && isEditing) {
    return (
      <input
        className="min-w-0 border-b-2 border-primary bg-transparent text-sm font-semibold outline-none"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => e.key === 'Enter' && commit()}
        autoFocus
      />
    )
  }

  return (
    <h2
      className={cn(
        'truncate text-sm font-semibold leading-tight',
        isEditing && 'cursor-pointer hover:text-primary',
      )}
      onClick={() => isEditing && startEditing()}
    >
      {title}
    </h2>
  )
}

function VariablePill({
  variable, value, onChange,
}: {variable: DashboardVariable; value: string | undefined; onChange: (value: string) => void}) {
  const [open, setOpen] = useState(false)
  const label = variable.label || variable.name

  // Free-text variables stay an inline editable field.
  if (variable.type === 'textbox') {
    return (
      <div className="inline-flex h-7 items-center gap-1.5 rounded-md border bg-background px-2 text-xs focus-within:ring-1 focus-within:ring-ring">
        <span className="lowercase text-muted-foreground">{label}</span>
        <input
          className="w-20 min-w-0 bg-transparent font-medium text-foreground outline-none placeholder:font-normal placeholder:text-muted-foreground"
          value={effectiveValue(variable, value)}
          onChange={(e) => onChange(e.target.value)}
          placeholder={variable.name}
          aria-label={`Variable ${label}`}
        />
      </div>
    )
  }

  // Constants are fixed — show them, but don't offer a picker.
  if (variable.type === 'constant') {
    return (
      <div
        className="inline-flex h-7 items-center gap-1.5 rounded-md border bg-muted/40 px-2 text-xs"
        aria-label={`Variable ${label}`}
      >
        <span className="lowercase text-muted-foreground">{label}</span>
        <span className="font-mono font-medium text-foreground">
          {effectiveValue(variable, value) || '(none)'}
        </span>
      </div>
    )
  }

  const display = variableDisplay(variable, value)
  const isAll = display === 'all'

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={`Variable ${label}`}
          className={cn(
            'inline-flex h-7 items-center gap-1.5 rounded-md border bg-background px-2 text-xs transition-colors hover:bg-muted',
            open && 'border-ring ring-1 ring-ring',
          )}
        >
          <span className="lowercase text-muted-foreground">{label}</span>
          <span
            className={cn(
              'max-w-[11rem] truncate font-medium',
              isAll ? 'italic text-foreground' : 'font-mono text-primary',
            )}
          >
            {display}
          </span>
          <ChevronDown className="h-3 w-3 opacity-50" />
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-56 p-0">
        {variable.multi ? (
          <VariableMultiSelect
            variable={variable}
            value={value}
            onApply={(next) => {
              onChange(next)
              setOpen(false)
            }}
          />
        ) : (
          <VariableSingleSelect
            variable={variable}
            value={value}
            onSelect={(next) => {
              onChange(next)
              setOpen(false)
            }}
          />
        )}
      </PopoverContent>
    </Popover>
  )
}

function VariableSingleSelect({
  variable, value, onSelect,
}: {variable: DashboardVariable; value: string | undefined; onSelect: (value: string) => void}) {
  const [filter, setFilter] = useState('')
  const options = realOptions(variable)
  const visible = useMemo(
    () => options.filter((o) => o.toLowerCase().includes(filter.trim().toLowerCase())),
    [options, filter],
  )

  return (
    <div className="flex flex-col">
      <div className="border-b p-2">
        <Input
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder={`Filter ${variable.label || variable.name}…`}
          className="h-7 text-xs"
        />
      </div>
      <div className="max-h-56 overflow-y-auto p-1">
        {variable.include_all && (
          <button
            type="button"
            onClick={() => onSelect(ALL_VALUE)}
            className="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-xs hover:bg-muted"
          >
            <Check className={cn('h-3.5 w-3.5', value === ALL_VALUE ? 'opacity-100' : 'opacity-0')} />
            All
          </button>
        )}
        {visible.map((option) => (
          <button
            key={option}
            type="button"
            onClick={() => onSelect(option)}
            className="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-xs hover:bg-muted"
          >
            <Check className={cn('h-3.5 w-3.5', value === option ? 'opacity-100' : 'opacity-0')} />
            <span className="truncate">{option}</span>
          </button>
        ))}
        {visible.length === 0 && (
          <div className="px-2 py-3 text-center text-xs text-muted-foreground">No values</div>
        )}
      </div>
    </div>
  )
}

function VariableMultiSelect({
  variable, value, onApply,
}: {variable: DashboardVariable; value: string | undefined; onApply: (value: string) => void}) {
  const startAll = value === ALL_VALUE
  const [all, setAll] = useState(startAll)
  const [selected, setSelected] = useState<Set<string>>(() => new Set(startAll ? [] : selectedValues(value)))
  const [filter, setFilter] = useState('')
  const options = realOptions(variable)
  const visible = useMemo(
    () => options.filter((o) => o.toLowerCase().includes(filter.trim().toLowerCase())),
    [options, filter],
  )

  const toggle = (option: string) => {
    setAll(false)
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(option)) next.delete(option)
      else next.add(option)
      return next
    })
  }

  const apply = () => {
    if (all && variable.include_all) onApply(ALL_VALUE)
    else onApply(Array.from(selected).join(MULTI_SEPARATOR))
  }

  return (
    <div className="flex flex-col">
      <div className="border-b p-2">
        <Input
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="Filter values…"
          className="h-7 text-xs"
        />
      </div>
      <div className="max-h-56 overflow-y-auto p-1">
        {variable.include_all && (
          <label className="flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-xs hover:bg-muted">
            <Checkbox
              checked={all}
              onCheckedChange={(checked) => {
                const next = checked === true
                setAll(next)
                if (next) setSelected(new Set())
              }}
            />
            All
          </label>
        )}
        {visible.map((option) => (
          <label key={option} className="flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-xs hover:bg-muted">
            <Checkbox checked={!all && selected.has(option)} onCheckedChange={() => toggle(option)} />
            <span className="truncate">{option}</span>
          </label>
        ))}
        {visible.length === 0 && (
          <div className="px-2 py-3 text-center text-xs text-muted-foreground">No values</div>
        )}
      </div>
      <div className="flex gap-2 border-t p-2">
        <Button
          variant="ghost"
          size="sm"
          className="h-7 flex-1 text-xs"
          onClick={() => {
            setAll(false)
            setSelected(new Set())
          }}
        >
          Clear
        </Button>
        <Button size="sm" className="h-7 flex-1 text-xs" onClick={apply}>
          Apply
        </Button>
      </div>
    </div>
  )
}

function TimeRangeControl({
  timeRange, onChange,
}: {timeRange: TimeRangeDef; onChange: (range: TimeRangeDef) => void}) {
  const [open, setOpen] = useState(false)
  const [from, setFrom] = useState(timeRange.from)
  const [to, setTo] = useState(timeRange.to)
  const preset = activePreset(timeRange)

  // Seed the custom inputs from the live range each time the popover opens.
  const handleOpenChange = (next: boolean) => {
    if (next) {
      setFrom(timeRange.from)
      setTo(timeRange.to)
    }
    setOpen(next)
  }

  const applyPreset = (range: TimeRangeDef) => {
    onChange(range)
    setOpen(false)
  }

  return (
    <Popover open={open} onOpenChange={handleOpenChange}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label="Time range"
          className="inline-flex h-7 items-center gap-1.5 rounded-md border bg-background px-2 text-xs transition-colors hover:bg-muted"
        >
          <Clock className="h-3.5 w-3.5 text-muted-foreground" />
          <span className="font-medium">{timeRangeLabel(timeRange)}</span>
          <span className="hidden border-l pl-1.5 font-mono text-[11px] text-muted-foreground sm:inline">
            {timeRangeWindow(timeRange)}
          </span>
          <ChevronDown className="h-3.5 w-3.5 opacity-50" />
        </button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-[420px] p-0">
        <div className="grid grid-cols-[150px_1fr]">
          <div className="max-h-[280px] overflow-y-auto border-r p-1">
            {TIME_RANGE_PRESETS.map((option) => (
              <button
                key={option.label}
                type="button"
                onClick={() => applyPreset({from: option.from, to: option.to})}
                className={cn(
                  'block w-full rounded-sm px-2 py-1.5 text-left text-xs transition-colors hover:bg-muted',
                  preset?.label === option.label && 'bg-primary/10 font-medium text-primary',
                )}
              >
                {option.full}
              </button>
            ))}
          </div>
          <div className="flex flex-col gap-2 p-3">
            <span className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
              Custom range
            </span>
            <label htmlFor="dashboard-time-from" className="text-xs text-muted-foreground">From</label>
            <Input
              id="dashboard-time-from"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
              className="h-7 font-mono text-xs"
            />
            <label htmlFor="dashboard-time-to" className="text-xs text-muted-foreground">To</label>
            <Input
              id="dashboard-time-to"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              className="h-7 font-mono text-xs"
            />
            <Button
              size="sm"
              className="mt-1 h-7 text-xs"
              onClick={() => applyPreset({from: from.trim(), to: to.trim()})}
            >
              Apply
            </Button>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}

function RefreshControl({
  refreshMs, onChange, onRefreshNow,
}: {refreshMs: number; onChange: (ms: number) => void; onRefreshNow: () => void}) {
  return (
    <div className="inline-flex h-7 items-stretch overflow-hidden rounded-md border bg-background">
      <Tooltip>
        <TooltipTrigger asChild>
          <button
            type="button"
            aria-label="Refresh now"
            onClick={onRefreshNow}
            className="grid w-7 place-items-center text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          >
            <RefreshCw className="h-3.5 w-3.5" />
          </button>
        </TooltipTrigger>
        <TooltipContent>Refresh now</TooltipContent>
      </Tooltip>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            aria-label="Auto-refresh interval"
            className={cn(
              'flex items-center gap-1 border-l px-2 text-xs transition-colors hover:bg-muted',
              refreshMs > 0 ? 'font-medium text-primary' : 'text-muted-foreground',
            )}
          >
            {refreshLabel(refreshMs)}
            <ChevronDown className="h-3 w-3 opacity-50" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-[7rem]">
          {REFRESH_OPTIONS.map((option) => (
            <DropdownMenuItem
              key={option.ms}
              onClick={() => onChange(option.ms)}
              className="justify-between gap-6 text-xs"
            >
              {option.label}
              {refreshMs === option.ms && <Check className="h-4 w-4" />}
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )
}

function ShareButton({
  timeRange, variableValues,
}: {timeRange: TimeRangeDef; variableValues: Record<string, string>}) {
  const [copied, setCopied] = useState(false)

  const share = () => {
    const base = typeof window === 'undefined' ? '' : window.location.origin + window.location.pathname
    const url = buildDashboardShareUrl(base, timeRange, variableValues)
    void navigator.clipboard?.writeText(url)
    setCopied(true)
    window.setTimeout(() => setCopied(false), 1600)
  }

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button variant="ghost" size="icon" className="h-7 w-7" aria-label="Share dashboard" onClick={share}>
          {copied ? <Check className="h-3.5 w-3.5 text-emerald-500" /> : <Share2 className="h-3.5 w-3.5" />}
        </Button>
      </TooltipTrigger>
      <TooltipContent>{copied ? 'Link copied' : 'Copy share link'}</TooltipContent>
    </Tooltip>
  )
}

function DashboardActionsMenu({
  isDefault, onDuplicate, onExport, onSetDefault, onVariableSettings, onDelete,
}: {
  isDefault: boolean
  onDuplicate: () => void
  onExport: () => void
  onSetDefault: () => void
  onVariableSettings?: () => void
  onDelete: () => void
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="icon" className="h-7 w-7" aria-label="More actions">
          <MoreHorizontal className="h-3.5 w-3.5" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-[12rem]">
        <DropdownMenuItem onClick={onDuplicate} className="gap-2 text-xs">
          <Copy className="h-3.5 w-3.5" />
          Duplicate
        </DropdownMenuItem>
        <DropdownMenuItem onClick={onExport} className="gap-2 text-xs">
          <Download className="h-3.5 w-3.5" />
          Export JSON
        </DropdownMenuItem>
        <DropdownMenuItem onClick={onSetDefault} className="gap-2 text-xs">
          <Home className="h-3.5 w-3.5" />
          Set as home
          {isDefault && <Check className="ml-auto h-3.5 w-3.5 text-primary" />}
        </DropdownMenuItem>
        {onVariableSettings && (
          <DropdownMenuItem onClick={onVariableSettings} className="gap-2 text-xs">
            <Settings2 className="h-3.5 w-3.5" />
            Manage variables
          </DropdownMenuItem>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem
          onClick={onDelete}
          className="gap-2 text-xs text-destructive focus:text-destructive"
        >
          <Trash2 className="h-3.5 w-3.5" />
          Delete dashboard
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
