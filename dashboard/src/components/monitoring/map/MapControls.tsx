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

import type {ComponentType} from 'react'
import {Search} from 'lucide-react'

import {cn} from '@/lib/utils'
import {Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'

export interface SegmentedOption<TValue extends string> {
  value: TValue
  label: string
  icon: ComponentType<{className?: string}>
}

export interface MapModeSwitchProps<TValue extends string> {
  value: TValue
  options: ReadonlyArray<SegmentedOption<TValue>>
  onChange: (value: TValue) => void
  ariaLabel: string
}

/**
 * The mockup's `.seg` segmented control: a pill group that sits immediately
 * left of the search box. The selected segment is raised on a card-colored
 * chip; the rest read as muted text.
 */
export function MapModeSwitch<TValue extends string>({
  value,
  options,
  onChange,
  ariaLabel,
}: MapModeSwitchProps<TValue>) {
  return (
    <fieldset className="flex h-7 shrink-0 rounded-md border bg-muted/40 p-0.5">
      <legend className="sr-only">{ariaLabel}</legend>
      {options.map((option) => {
        const Icon = option.icon
        const active = option.value === value
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={active}
            title={option.label}
            onClick={() => onChange(option.value)}
            className={cn(
              'inline-flex items-center gap-1.5 rounded-sm px-2.5 text-xs font-medium transition-colors',
              active ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
            )}
          >
            <Icon className="h-3.5 w-3.5" />
            <span className="hidden sm:inline">{option.label}</span>
          </button>
        )
      })}
    </fieldset>
  )
}

export interface MapControlOption<TValue extends string> {
  value: TValue
  label: string
}

export interface MapControlSelectProps<TValue extends string> {
  label: string
  value: TValue
  options: ReadonlyArray<MapControlOption<TValue>>
  onChange: (value: TValue) => void
}

/**
 * The mockup's `.ctl`: a compact inline dropdown reading `<label> <value> ⌄`,
 * the label muted and the value emphasized. Backed by the shared Select so it
 * stays keyboard- and screen-reader-accessible.
 */
export function MapControlSelect<TValue extends string>({
  label,
  value,
  options,
  onChange,
}: MapControlSelectProps<TValue>) {
  return (
    <Select value={value} onValueChange={(next) => onChange(next as TValue)}>
      <SelectTrigger
        aria-label={label}
        className="h-7 w-auto gap-1.5 rounded-md border bg-card px-2.5 text-xs font-medium shadow-none"
      >
        <span className="text-muted-foreground">{label}</span>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectGroup>
          {options.map((option) => (
            <SelectItem key={option.value} value={option.value} className="text-xs">
              {option.label}
            </SelectItem>
          ))}
        </SelectGroup>
      </SelectContent>
    </Select>
  )
}

export interface MapSearchInputProps {
  value: string
  onChange: (value: string) => void
  placeholder: string
}

/**
 * The mockup's `.search`: a single compact, inset search field that fills the
 * space between the mode switch and the inline controls. Plain free text — no
 * chip stack — which is what makes the header read as one tight row.
 */
export function MapSearchInput({value, onChange, placeholder}: MapSearchInputProps) {
  return (
    <div className="flex h-7 min-w-0 items-center gap-2 rounded-md border bg-muted/40 px-2.5 text-xs focus-within:ring-1 focus-within:ring-ring">
      <Search className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
      <input
        type="search"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        aria-label={placeholder}
        className="min-w-0 flex-1 bg-transparent text-xs outline-none placeholder:text-muted-foreground"
      />
    </div>
  )
}
