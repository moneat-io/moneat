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

import * as React from "react"
import { format } from "date-fns"
import { Calendar as CalendarIcon } from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Calendar } from "@/components/ui/calendar"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"

type DateTimePickerProps = Readonly<{
  id?: string
  value: string // ISO datetime-local format (YYYY-MM-DDTHH:mm)
  onChange: (value: string) => void
  placeholder?: string
  className?: string
}>

export function DateTimePicker({
  id,
  value,
  onChange,
  placeholder = "Pick a date and time",
  className,
}: DateTimePickerProps) {
  const [open, setOpen] = React.useState(false)

  // Parse the datetime-local string to Date
  const dateValue = value ? new Date(value) : undefined

  // Extract time parts
  const hours = value ? value.split('T')[1]?.split(':')[0] || '00' : '00'
  const minutes = value ? value.split('T')[1]?.split(':')[1] || '00' : '00'

  const handleDateSelect = (date: Date | undefined) => {
    if (!date) return
    
    // Combine selected date with current time
    const dateStr = format(date, 'yyyy-MM-dd')
    const newValue = `${dateStr}T${hours}:${minutes}`
    onChange(newValue)
    // Don't close the popover - let user set time too
  }

  const handleTimeChange = (type: 'hours' | 'minutes', newValue: string) => {
    if (!value) {
      // If no date selected, use today
      const today = format(new Date(), 'yyyy-MM-dd')
      const hrs = type === 'hours' ? newValue.padStart(2, '0') : hours
      const mins = type === 'minutes' ? newValue.padStart(2, '0') : minutes
      onChange(`${today}T${hrs}:${mins}`)
    } else {
      const dateStr = value.split('T')[0]
      const hrs = type === 'hours' ? newValue.padStart(2, '0') : hours
      const mins = type === 'minutes' ? newValue.padStart(2, '0') : minutes
      onChange(`${dateStr}T${hrs}:${mins}`)
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen} modal={false}>
      <PopoverTrigger asChild>
        <Button
          id={id}
          variant="outline"
          className={cn(
            "w-full justify-start text-left font-normal",
            !value && "text-muted-foreground",
            className
          )}
        >
          <CalendarIcon className="mr-2 h-4 w-4" />
          {value ? (
            <span className="text-xs">
              {format(new Date(value), "MMM d, yyyy HH:mm")}
            </span>
          ) : (
            <span className="text-xs">{placeholder}</span>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-auto p-0" align="start" disablePortal onOpenAutoFocus={(e) => e.preventDefault()}>
        <Calendar
          mode="single"
          selected={dateValue}
          onSelect={handleDateSelect}
        />
        <div className="border-t p-3 space-y-2">
          <div className="text-xs font-medium text-muted-foreground mb-2">Time</div>
          <div className="flex items-center gap-2">
            <input
              type="number"
              min="0"
              max="23"
              value={hours}
              onChange={(e) => {
                const val = Math.max(0, Math.min(23, parseInt(e.target.value) || 0))
                handleTimeChange('hours', val.toString())
              }}
              onClick={(e) => e.stopPropagation()}
              className="w-16 rounded-md border bg-background px-2 py-1.5 text-center text-sm"
              placeholder="HH"
            />
            <span className="text-sm">:</span>
            <input
              type="number"
              min="0"
              max="59"
              value={minutes}
              onChange={(e) => {
                const val = Math.max(0, Math.min(59, parseInt(e.target.value) || 0))
                handleTimeChange('minutes', val.toString())
              }}
              onClick={(e) => e.stopPropagation()}
              className="w-16 rounded-md border bg-background px-2 py-1.5 text-center text-sm"
              placeholder="MM"
            />
          </div>
          <Button
            variant="outline"
            size="sm"
            className="w-full mt-2"
            onClick={() => setOpen(false)}
          >
            Done
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  )
}
