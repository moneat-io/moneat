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

import {Search} from 'lucide-react'

import {Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'

// The map's mode switch is the shared SegmentedTabs control, re-exported under
// its original name so existing map imports keep working.
export {SegmentedTabs as MapModeSwitch} from '@/components/filters/SegmentedTabs'
export type {SegmentedOption} from '@/components/filters/SegmentedTabs'

export type MapControlOption<TValue extends string> = Readonly<{
  value: TValue
  label: string
}>

export type MapControlSelectProps<TValue extends string> = Readonly<{
  label: string
  value: TValue
  options: ReadonlyArray<MapControlOption<TValue>>
  onChange: (value: TValue) => void
}>

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

export type MapSearchInputProps = Readonly<{
  value: string
  onChange: (value: string) => void
  placeholder: string
}>

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
