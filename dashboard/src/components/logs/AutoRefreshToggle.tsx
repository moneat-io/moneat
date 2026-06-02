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
import {cn} from '@/lib/utils'
import {RefreshCw} from 'lucide-react'

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
  return (
    <div className="flex items-center gap-1.5">
      <RefreshCw className={cn('h-3.5 w-3.5 text-muted-foreground', interval && 'animate-spin text-success-fg')} style={interval ? {animationDuration: '2s'} : undefined} />
      <span className="text-xs text-muted-foreground hidden sm:inline">Refresh</span>
      <div className="flex items-center rounded-md border bg-card/80">
        {INTERVAL_OPTIONS.map((option) => (
          <Button
            key={option.label}
            variant="ghost"
            size="sm"
            onClick={() => onIntervalChange(option.value)}
            className={cn(
              'h-7 rounded-none px-2 text-xs font-mono',
              'first:rounded-l-md last:rounded-r-md',
              interval === option.value
                ? 'bg-success-solid text-white hover:bg-success-solid/90 hover:text-white'
                : 'text-muted-foreground hover:text-foreground'
            )}
          >
            {option.label}
          </Button>
        ))}
      </div>
    </div>
  )
}
