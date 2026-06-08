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

import {useEffect, useMemo, useRef, useState} from 'react'
import {ChevronDown, Clock} from 'lucide-react'
import {format} from 'date-fns'

import {cn} from '@/lib/utils'
import {Button} from '@/components/ui/button'
import {DateTimePicker} from '@/components/ui/datetime-picker'
import {type TimeRangePreset, TIME_PRESETS} from '@/lib/filters/time'

interface TimeRangePickerProps {
  timePreset: string
  onTimePresetChange: (preset: string) => void
  customFrom: string
  customTo: string
  onCustomFromChange: (value: string) => void
  onCustomToChange: (value: string) => void
  /** Preset list; defaults to {@link TIME_PRESETS}. */
  presets?: TimeRangePreset[]
  /** Allow a custom from/to range. Defaults to true. */
  allowCustom?: boolean
}

/**
 * Controlled time-range selector — a button showing the active range and a
 * dropdown of presets plus an optional custom from/to range. Extracted verbatim
 * from the log search bar so every surface gets the same control.
 */
export function TimeRangePicker({
  timePreset,
  onTimePresetChange,
  customFrom,
  customTo,
  onCustomFromChange,
  onCustomToChange,
  presets = TIME_PRESETS,
  allowCustom = true,
}: TimeRangePickerProps) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (ref.current && !ref.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  const activeLabel = useMemo(() => {
    if (timePreset === 'custom') {
      if (!customFrom && !customTo) return 'Custom range'
      try {
        const fromDate = customFrom ? new Date(customFrom) : null
        const toDate = customTo ? new Date(customTo) : null
        if (fromDate && toDate) {
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
    const preset = presets.find((p) => p.value === timePreset)
    return preset?.label ?? presets[0]?.label ?? 'Custom range'
  }, [timePreset, customFrom, customTo, presets])

  return (
    <div className="relative" ref={ref}>
      <Button
        variant="outline"
        size="default"
        className="h-[30px] gap-1.5 whitespace-nowrap px-2 font-normal text-xs @min-[420px]/filterbar:px-3"
        onClick={() => setOpen(!open)}
      >
        <Clock className="h-3.5 w-3.5 text-muted-foreground" />
        <span className="hidden text-xs @min-[420px]/filterbar:inline">{activeLabel}</span>
        <ChevronDown className="hidden h-3 w-3 text-muted-foreground @min-[420px]/filterbar:inline" />
      </Button>

      {open && (
        <div className="absolute right-0 top-full z-50 mt-1 w-[260px] rounded-lg border bg-popover p-1">
          {presets.map((preset) => (
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
                setOpen(false)
              }}
            >
              {preset.label}
            </button>
          ))}
          {allowCustom && (
            <div className="border-t mt-1 pt-1">
              <button
                type="button"
                className={cn(
                  'flex w-full items-center rounded-md px-3 py-2 text-left text-sm transition-colors',
                  timePreset === 'custom'
                    ? 'bg-primary/10 text-primary font-medium'
                    : 'hover:bg-accent/50'
                )}
                onClick={() => onTimePresetChange('custom')}
              >
                Custom range...
              </button>
              {timePreset === 'custom' && (
                <div className="space-y-2 px-3 py-2">
                  <div>
                    <label className="mb-1 block text-[11px] uppercase tracking-wide text-muted-foreground">
                      From
                    </label>
                    <DateTimePicker
                      value={customFrom}
                      onChange={onCustomFromChange}
                      placeholder="Select start time"
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-[11px] uppercase tracking-wide text-muted-foreground">
                      To
                    </label>
                    <DateTimePicker
                      value={customTo}
                      onChange={onCustomToChange}
                      placeholder="Select end time"
                    />
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
