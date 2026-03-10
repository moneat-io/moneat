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
import {Check, ChevronsUpDown, Activity, Bug, FileText, Cpu, Box, Wifi, Brain, BarChart3, Database} from 'lucide-react'
import {cn} from '@/lib/utils'
import {Button} from '@/components/ui/button'
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command'
import {Popover, PopoverContent, PopoverTrigger} from '@/components/ui/popover'
import {DATA_SOURCE_TYPES} from './DataSourceTypes'
import type {DataSourceInfo} from '@/lib/api'

// Icons for built-in data sources
const BUILTIN_ICONS: Record<string, React.ReactNode> = {
  events: <Bug className="h-4 w-4 text-red-500" />,
  spans: <Activity className="h-4 w-4 text-violet-500" />,
  logs: <FileText className="h-4 w-4 text-blue-500" />,
  metrics: <Cpu className="h-4 w-4 text-green-500" />,
  containers: <Box className="h-4 w-4 text-orange-500" />,
  uptime_heartbeats: <Wifi className="h-4 w-4 text-teal-500" />,
  llm_generations: <Brain className="h-4 w-4 text-purple-500" />,
  analytics_events: <BarChart3 className="h-4 w-4 text-cyan-500" />,
}

function getIconForSource(source: DataSourceInfo): React.ReactNode {
  // Built-in source
  if (!source.name.startsWith('custom:')) {
    return BUILTIN_ICONS[source.name] || <Database className="h-4 w-4 text-muted-foreground" />
  }
  // Custom source — match by label to find the type logo
  const lowerLabel = source.label.toLowerCase()
  const typeMatch = DATA_SOURCE_TYPES.find((t) => lowerLabel.includes(t.value))
  if (typeMatch) {
    return <span className="flex h-4 w-4 items-center justify-center [&>svg]:h-4 [&>svg]:w-4">{typeMatch.logo}</span>
  }
  return <Database className="h-4 w-4 text-muted-foreground" />
}

interface DataSourcePickerProps {
  dataSources: DataSourceInfo[]
  value: string
  onChange: (value: string) => void
}

export function DataSourcePicker({dataSources, value, onChange}: DataSourcePickerProps) {
  const [open, setOpen] = React.useState(false)
  const buttonRef = React.useRef<HTMLButtonElement>(null)
  const [buttonWidth, setButtonWidth] = React.useState<number | undefined>(undefined)

  const selected = dataSources.find((ds) => ds.name === value)

  React.useEffect(() => {
    if (buttonRef.current) {
      setButtonWidth(buttonRef.current.offsetWidth)
    }
  }, [])

  const builtIn = dataSources.filter((ds) => !ds.name.startsWith('custom:'))
  const custom = dataSources.filter((ds) => ds.name.startsWith('custom:'))

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          ref={buttonRef}
          variant="outline"
          type="button"
          role="combobox"
          aria-expanded={open}
          className={cn(
            'h-8 w-full justify-between font-normal text-sm',
            !selected && 'text-muted-foreground'
          )}
        >
          {selected ? (
            <span className="flex items-center gap-2 truncate">
              {getIconForSource(selected)}
              <span>{selected.label}</span>
            </span>
          ) : (
            <span>Select data source...</span>
          )}
          <ChevronsUpDown className="ml-2 h-3.5 w-3.5 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="p-0" style={{width: buttonWidth ? Math.max(buttonWidth, 280) : 280}} align="start">
        <Command>
          <CommandInput placeholder="Search data sources..." className="h-8" />
          <CommandList>
            <CommandEmpty>No data source found.</CommandEmpty>
            <CommandGroup heading="Built-in">
              {builtIn.map((ds) => (
                <CommandItem
                  key={ds.name}
                  value={ds.label}
                  onSelect={() => {
                    onChange(ds.name)
                    setOpen(false)
                  }}
                  className="flex items-center gap-2 py-1.5"
                >
                  <Check
                    className={cn('h-3.5 w-3.5 shrink-0', value === ds.name ? 'opacity-100' : 'opacity-0')}
                  />
                  {getIconForSource(ds)}
                  <span className="text-sm">{ds.label}</span>
                </CommandItem>
              ))}
            </CommandGroup>
            {custom.length > 0 && (
              <CommandGroup heading="Custom Data Sources">
                {custom.map((ds) => (
                  <CommandItem
                    key={ds.name}
                    value={ds.label}
                    onSelect={() => {
                      onChange(ds.name)
                      setOpen(false)
                    }}
                    className="flex items-center gap-2 py-1.5"
                  >
                    <Check
                      className={cn('h-3.5 w-3.5 shrink-0', value === ds.name ? 'opacity-100' : 'opacity-0')}
                    />
                    {getIconForSource(ds)}
                    <span className="text-sm">{ds.label}</span>
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
