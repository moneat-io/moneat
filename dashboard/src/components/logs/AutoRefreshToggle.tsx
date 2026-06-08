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

import {Button} from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {cn} from '@/lib/utils'
import {Check, ChevronDown, RefreshCw} from 'lucide-react'

export type RefreshInterval = null | 1000 | 5000 | 10000 | 30000

const INTERVAL_OPTIONS: {label: string; value: RefreshInterval}[] = [
  {label: 'Off', value: null},
  {label: '1s', value: 1000},
  {label: '5s', value: 5000},
  {label: '10s', value: 10000},
  {label: '30s', value: 30000},
]

interface AutoRefreshToggleProps {
  interval: RefreshInterval
  onIntervalChange: (interval: RefreshInterval) => void
}

export function AutoRefreshToggle({interval, onIntervalChange}: AutoRefreshToggleProps) {
  const active = interval !== null
  const activeLabel = INTERVAL_OPTIONS.find((option) => option.value === interval)?.label ?? 'Off'

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          aria-label="Auto-refresh interval"
          title="Auto-refresh interval"
          className={cn(
            'h-[30px] gap-1.5 px-2 font-normal text-xs',
            active && 'border-primary/40'
          )}
        >
          <RefreshCw
            className={cn('h-3.5 w-3.5 text-muted-foreground', active && 'animate-spin text-primary')}
            style={active ? {animationDuration: '2s'} : undefined}
          />
          {active && <span className="hidden font-mono @min-[640px]/header:inline">{activeLabel}</span>}
          <ChevronDown className="h-3 w-3 text-muted-foreground" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-36">
        {INTERVAL_OPTIONS.map((option) => (
          <DropdownMenuItem
            key={option.label}
            onClick={() => onIntervalChange(option.value)}
            className="justify-between gap-4 text-xs"
          >
            <span className="font-mono">{option.label}</span>
            {interval === option.value && <Check className="h-3.5 w-3.5 text-primary" />}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
