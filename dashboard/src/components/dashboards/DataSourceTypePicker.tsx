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
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command'
import {Popover, PopoverContent, PopoverTrigger} from '@/components/ui/popover'

export interface DataSourceTypeOption {
  value: string
  label: string
  description: string
  logo: React.ReactNode
  category: 'database' | 'metrics' | 'coming-soon'
}

// SVG logos for each data source type
function PostgreSQLLogo({className}: {className?: string}) {
  return (
    <svg className={className} viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path
        d="M22.839 26.4c-.584.157-1.139.078-1.576-.128-.96-.452-1.356-1.298-1.407-2.642l-.031-.916c.002-.387-.047-.85-.177-1.264-.199-.631-.627-1.035-1.264-1.199a.658.658 0 00-.049-.012c.944-.571 1.77-1.293 2.388-2.185 1.09-1.575 1.694-3.532 1.76-5.677.007-.226.01-.458.01-.693v-.238c.01-2.2-.314-3.89-1.013-5.247-.507-.985-1.246-1.838-2.191-2.515a7.51 7.51 0 00-.685-.438c-.123-.068-.25-.135-.381-.2C17.125 2.498 15.795 2 14.218 2c-1.084 0-2.143.233-3.148.691l-.127.06A9.93 9.93 0 009.66 3.5c-.494.332-.94.72-1.328 1.157a7.58 7.58 0 00-1.368 2.19c-.548 1.287-.828 2.866-.828 4.7 0 .228.003.464.01.7.068 2.148.674 4.108 1.766 5.684.62.894 1.449 1.617 2.396 2.189a.585.585 0 00-.03.007c-.636.164-1.064.568-1.263 1.199-.13.414-.179.877-.178 1.264l-.03.916c-.052 1.344-.448 2.19-1.408 2.642-.437.206-.992.285-1.576.128-.498-.134-.933-.403-1.274-.73l-.092.099c.407.416.936.758 1.556.924.77.207 1.489.095 2.068-.179 1.19-.562 1.72-1.656 1.785-3.285l.03-.905c-.002-.352.04-.762.148-1.105.11-.348.292-.546.597-.625.334-.086.72-.142 1.127-.164.04.002.078.005.115.01.242.037.47.113.674.25.524.349.862.976 1.002 1.89.076.492.096 1.021.07 1.524-.04.819-.16 1.587-.205 2.365-.06 1.029.074 1.949.625 2.69.312.42.72.685 1.185.86a.614.614 0 00.056.013c.272.072.571.105.871.105.563 0 1.126-.14 1.576-.41l.003-.001c.003-.002.006-.003.008-.005.01-.006.019-.013.028-.02l-.053-.089c-.426.248-.972.388-1.51.388a2.12 2.12 0 01-.695-.086c-.382-.145-.7-.364-.95-.702-.462-.623-.574-1.412-.521-2.34.045-.77.164-1.527.204-2.339.026-.496.006-1.018-.068-1.503-.14-.921-.482-1.553-1.012-1.906a2.09 2.09 0 01-.675-.256.847.847 0 01-.116-.01c.408.022.793.078 1.127.164.306.079.488.277.598.625.108.343.15.753.148 1.105l.03.905c.065 1.63.595 2.723 1.785 3.285.579.274 1.298.386 2.068.179.62-.166 1.15-.508 1.556-.924l-.092-.099c-.341.327-.776.596-1.274.73z"
        fill="#336791"
      />
      <path
        d="M21.04 6.014c.783.563 1.393 1.28 1.83 2.13.613 1.193.915 2.741.905 4.791v.236c0 .232-.003.46-.01.682-.06 2-.622 3.813-1.627 5.264-.687.992-1.603 1.784-2.66 2.346l.015.004c.47.12.776.449.932.975.112.375.157.81.16 1.174l.03.934c.045 1.155.335 1.78 1.03 2.108.295.139.652.2 1.037.098.36-.097.68-.297.94-.55-.33.252-.713.387-1.116.387-.35 0-.667-.094-.922-.214-.694-.328-.984-.953-1.029-2.108l-.031-.934c.002-.363-.047-.799-.16-1.174-.155-.526-.462-.856-.932-.975a5.638 5.638 0 01-1.29-.194 8.065 8.065 0 01-.759-.264c.078.02.155.044.23.073.447.17.79.508.96 1.055.118.375.165.82.163 1.196l.031.86c.048 1.246.376 2.2 1.162 2.715.36.236.79.353 1.23.353.458 0 .917-.127 1.296-.354-1.082.506-2.382.227-2.98-.498-.39-.472-.577-1.11-.546-1.904.04-.795.155-1.539.195-2.312.027-.487.006-1.003-.069-1.488-.136-.884-.462-1.49-.971-1.83a1.874 1.874 0 00-.589-.223 4.96 4.96 0 01-1.064-.16c-.07-.024-.079-.067-.025-.122a.288.288 0 01.076-.055c1.001-.536 1.87-1.262 2.534-2.162 1.006-1.363 1.56-3.07 1.619-4.966.006-.22.01-.446.01-.676v-.236c.01-2.074-.293-3.642-.918-4.856-.452-.877-1.078-1.614-1.882-2.192a6.98 6.98 0 00-.646-.41c.12.06.236.123.35.19a7.27 7.27 0 01.648.412z"
        fill="#336791"
      />
    </svg>
  )
}

function PrometheusLogo({className}: {className?: string}) {
  return (
    <svg className={className} viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg">
      <circle cx="16" cy="16" r="14" fill="#E6522C" />
      <path
        d="M16 4.5c-6.35 0-11.5 5.15-11.5 11.5S9.65 27.5 16 27.5c1.35 0 2.65-.24 3.85-.67v-2.46h-3.1v-1.8h3.1v-1.73h-3.1v-1.8h3.93a9.72 9.72 0 001.45-3.29H16v-1.8h5.83c.02-.3.04-.6.04-.9 0-.38-.02-.75-.07-1.12H16v-1.8h5.48A9.54 9.54 0 0016 4.5z"
        fill="white"
      />
    </svg>
  )
}

function MySQLLogo({className}: {className?: string}) {
  return (
    <svg className={className} viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg">
      <rect x="4" y="6" width="24" height="20" rx="3" fill="#00758F" />
      <text x="16" y="19.5" textAnchor="middle" fill="white" fontSize="8" fontWeight="bold" fontFamily="sans-serif">
        SQL
      </text>
    </svg>
  )
}

function ClickHouseLogo({className}: {className?: string}) {
  return (
    <svg className={className} viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg">
      <rect x="5" y="4" width="3.5" height="24" rx="0.5" fill="#FFCC00" />
      <rect x="10" y="4" width="3.5" height="24" rx="0.5" fill="#FFCC00" />
      <rect x="15" y="4" width="3.5" height="24" rx="0.5" fill="#FFCC00" />
      <rect x="20" y="4" width="3.5" height="24" rx="0.5" fill="#FFCC00" />
      <rect x="25" y="10" width="3.5" height="12" rx="0.5" fill="#FFCC00" />
    </svg>
  )
}

function ElasticsearchLogo({className}: {className?: string}) {
  return (
    <svg className={className} viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg">
      <circle cx="16" cy="16" r="12" fill="none" stroke="#FEC514" strokeWidth="2.5" />
      <path d="M6 16h20" stroke="#00BFB3" strokeWidth="2.5" />
      <path d="M10.5 10.5l11 0" stroke="#F04E98" strokeWidth="2" />
      <path d="M10.5 21.5l11 0" stroke="#1BA9F5" strokeWidth="2" />
    </svg>
  )
}

function InfluxDBLogo({className}: {className?: string}) {
  return (
    <svg className={className} viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg">
      <rect x="4" y="4" width="24" height="24" rx="5" fill="#22ADF6" />
      <path d="M10 22L16 10l6 12H10z" fill="white" opacity="0.9" />
    </svg>
  )
}

export const DATA_SOURCE_TYPES: DataSourceTypeOption[] = [
  {
    value: 'postgresql',
    label: 'PostgreSQL',
    description: 'Open-source relational database',
    logo: <PostgreSQLLogo className="h-6 w-6" />,
    category: 'database',
  },
  {
    value: 'prometheus',
    label: 'Prometheus',
    description: 'Metrics & monitoring system',
    logo: <PrometheusLogo className="h-6 w-6" />,
    category: 'metrics',
  },
  {
    value: 'mysql',
    label: 'MySQL',
    description: 'Popular relational database',
    logo: <MySQLLogo className="h-6 w-6" />,
    category: 'coming-soon',
  },
  {
    value: 'clickhouse',
    label: 'ClickHouse',
    description: 'Column-oriented analytics database',
    logo: <ClickHouseLogo className="h-6 w-6" />,
    category: 'coming-soon',
  },
  {
    value: 'elasticsearch',
    label: 'Elasticsearch',
    description: 'Search & analytics engine',
    logo: <ElasticsearchLogo className="h-6 w-6" />,
    category: 'coming-soon',
  },
  {
    value: 'influxdb',
    label: 'InfluxDB',
    description: 'Time series database',
    logo: <InfluxDBLogo className="h-6 w-6" />,
    category: 'coming-soon',
  },
]

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
