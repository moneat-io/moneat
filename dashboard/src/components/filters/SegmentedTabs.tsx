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

import {cn} from '@/lib/utils'

export type SegmentedOption<TValue extends string> = Readonly<{
  value: TValue
  label: string
  icon: ComponentType<{className?: string}>
}>

export type SegmentedTabsProps<TValue extends string> = Readonly<{
  value: TValue
  options: ReadonlyArray<SegmentedOption<TValue>>
  onChange: (value: TValue) => void
  ariaLabel: string
}>

/**
 * Segmented control / pill group for the explorer header — the canonical way to
 * carry section tabs or a mode switch immediately left of the search box. The
 * selected segment is raised on a card-colored chip; the rest read as muted
 * text. Shared by the monitoring map (as a mode switch) and faceted result
 * pages such as Issues (as section tabs), so the header reads as one tight row.
 */
export function SegmentedTabs<TValue extends string>({
  value,
  options,
  onChange,
  ariaLabel,
}: SegmentedTabsProps<TValue>) {
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
