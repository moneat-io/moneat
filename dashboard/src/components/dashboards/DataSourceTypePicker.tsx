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

import * as React from 'react'
import {Check, ChevronsUpDown} from 'lucide-react'
import {cn} from '@/lib/utils'
import {Button} from '@/components/ui/button'
import {Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList,} from '@/components/ui/command'
import {Popover, PopoverContent, PopoverTrigger} from '@/components/ui/popover'
import {DATA_SOURCE_TYPES} from './dataSourceTypes'

interface DataSourceTypePickerProps {
  value: string
  onChange: (value: string) => void
  disabled?: boolean
}

export function DataSourceTypePicker({value, onChange, disabled}: DataSourceTypePickerProps) {
  const [open, setOpen] = React.useState(false)
  const buttonRef = React.useRef<HTMLButtonElement>(null)
  const [buttonWidth, setButtonWidth] = React.useState<number | undefined>(undefined)

  const selected = DATA_SOURCE_TYPES.find((t) => t.value === value)

  React.useEffect(() => {
    if (buttonRef.current) {
      setButtonWidth(buttonRef.current.offsetWidth)
    }
  }, [])

  const availableTypes = DATA_SOURCE_TYPES.filter((t) => t.category !== 'coming-soon')
  const comingSoonTypes = DATA_SOURCE_TYPES.filter((t) => t.category === 'coming-soon')

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          ref={buttonRef}
          variant="outline"
          type="button"
          role="combobox"
          aria-expanded={open}
          disabled={disabled}
          className={cn(
            'h-11 w-full justify-between font-normal',
            !selected && 'text-muted-foreground'
          )}
        >
          {selected ? (
            <span className="flex items-center gap-2.5">
              {selected.logo}
              <span className="font-medium">{selected.label}</span>
            </span>
          ) : (
            <span>Select data source type...</span>
          )}
          <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="p-0" style={{width: buttonWidth ? Math.max(buttonWidth, 320) : 320}} align="start">
        <Command>
          <CommandInput placeholder="Search data sources..." />
          <CommandList>
            <CommandEmpty>No data source found.</CommandEmpty>
            <CommandGroup heading="Available">
              {availableTypes.map((type) => (
                <CommandItem
                  key={type.value}
                  value={type.label}
                  onSelect={() => {
                    onChange(type.value)
                    setOpen(false)
                  }}
                  className="flex items-center gap-3 py-2.5"
                >
                  <Check
                    className={cn('h-4 w-4 shrink-0', value === type.value ? 'opacity-100' : 'opacity-0')}
                  />
                  <span className="shrink-0">{type.logo}</span>
                  <div className="min-w-0">
                    <div className="font-medium">{type.label}</div>
                    <div className="text-muted-foreground text-xs">{type.description}</div>
                  </div>
                </CommandItem>
              ))}
            </CommandGroup>
            {comingSoonTypes.length > 0 && (
              <CommandGroup heading="Coming Soon">
                {comingSoonTypes.map((type) => (
                  <CommandItem
                    key={type.value}
                    value={type.label}
                    disabled
                    className="flex items-center gap-3 py-2.5 opacity-50"
                  >
                    <Check className="h-4 w-4 shrink-0 opacity-0" />
                    <span className="shrink-0">{type.logo}</span>
                    <div className="min-w-0">
                      <div className="font-medium">{type.label}</div>
                      <div className="text-muted-foreground text-xs">{type.description}</div>
                    </div>
                  </CommandItem>
                ))}
              </CommandGroup>
            )}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}
