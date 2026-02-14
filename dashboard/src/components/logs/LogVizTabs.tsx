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
