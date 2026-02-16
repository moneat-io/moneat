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
import {BarChart3, List, PieChart, Table2, TrendingUp} from 'lucide-react'

export type LogVizMode = 'list' | 'timeseries' | 'toplist' | 'table' | 'pie'

interface LogVizTabsProps {
  mode: LogVizMode
  onModeChange: (mode: LogVizMode) => void
}

const tabs: {value: LogVizMode; label: string; icon: React.ElementType}[] = [
  {value: 'list', label: 'List', icon: List},
  {value: 'timeseries', label: 'Timeseries', icon: TrendingUp},
  {value: 'toplist', label: 'Top List', icon: BarChart3},
  {value: 'table', label: 'Table', icon: Table2},
  {value: 'pie', label: 'Pie Chart', icon: PieChart},
]

export function LogVizTabs({mode, onModeChange}: LogVizTabsProps) {
  return (
    <div className="flex items-center gap-0.5 rounded-lg bg-muted/50 p-0.5">
      {tabs.map(({value, label, icon: Icon}) => (
        <button
          key={value}
          type="button"
          onClick={() => onModeChange(value)}
          className={cn(
            'flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium transition-colors',
            mode === value
              ? 'bg-background text-foreground shadow-sm'
              : 'text-muted-foreground hover:text-foreground'
          )}
        >
          <Icon className="h-3.5 w-3.5" />
          {label}
        </button>
      ))}
    </div>
  )
}
