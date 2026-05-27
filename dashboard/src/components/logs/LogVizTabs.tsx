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

import {cn} from '@/lib/utils'
import {BarChart3, PieChart, Table2, TrendingUp} from 'lucide-react'

export type LogVizMode = 'timeseries' | 'toplist' | 'table' | 'pie'

interface LogVizTabsProps {
  mode: LogVizMode
  onModeChange: (mode: LogVizMode) => void
}

const tabs: {value: LogVizMode; label: string; icon: React.ElementType}[] = [
  {value: 'timeseries', label: 'Timeseries', icon: TrendingUp},
  {value: 'toplist', label: 'Top List', icon: BarChart3},
  {value: 'table', label: 'Table', icon: Table2},
  {value: 'pie', label: 'Pie Chart', icon: PieChart},
]

export function LogVizTabs({mode, onModeChange}: LogVizTabsProps) {
  return (
    <div className="flex items-center gap-0.5 rounded-md bg-muted/50 p-0.5">
      {tabs.map(({value, label, icon: Icon}) => (
        <button
          key={value}
          type="button"
          onClick={() => onModeChange(value)}
          className={cn(
            'flex items-center gap-1 rounded px-2 py-1 text-[11px] font-medium transition-colors',
            mode === value
              ? 'bg-background text-foreground'
              : 'text-muted-foreground hover:text-foreground'
          )}
        >
          <Icon className="h-3 w-3" />
          {label}
        </button>
      ))}
    </div>
  )
}
