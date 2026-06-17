import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import type {LucideIcon} from 'lucide-react'
import type {AnalyticsBreakdownItem} from '@/lib/api'

interface AnalyticsBreakdownTableProps {
  title: string
  icon: LucideIcon
  iconColor?: string
  data?: AnalyticsBreakdownItem[]
  isLoading?: boolean
  showBounceRate?: boolean
  showDuration?: boolean
  onRowClick?: (item: AnalyticsBreakdownItem) => void
}

export function AnalyticsBreakdownTable({
  title, icon: Icon, iconColor = 'text-chart-1', data, isLoading, showBounceRate, showDuration, onRowClick,
}: AnalyticsBreakdownTableProps) {
  if (isLoading) {
    return (
      <Card>
        <CardHeader className="pb-1">
          <CardTitle className="text-xs font-medium flex items-center gap-1.5">
            <Icon className={`h-3.5 w-3.5 ${iconColor}`} />
            {title}
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-1">
          {Array.from({length: 5}).map((_, i) => <div key={i} className="h-6 w-full animate-pulse rounded bg-muted" />)}
        </CardContent>
      </Card>
    )
  }

  if (!data || data.length === 0) {
    return (
      <Card>
        <CardHeader className="pb-1">
          <CardTitle className="text-xs font-medium flex items-center gap-1.5">
            <Icon className={`h-3.5 w-3.5 ${iconColor}`} />
            {title}
          </CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-xs text-muted-foreground text-center py-3">No data</p>
        </CardContent>
      </Card>
    )
  }

  const maxVisitors = Math.max(...data.map(d => d.visitors), 1)

  return (
    <Card>
      <CardHeader className="pb-1">
        <CardTitle className="text-xs font-medium flex items-center gap-1.5">
          <Icon className={`h-3.5 w-3.5 ${iconColor}`} />
          {title}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-0.5">
        {data.map((item) => (
          <div
            key={item.name}
            className={`relative flex items-center justify-between px-1.5 py-1 rounded text-[11px] ${onRowClick ? 'cursor-pointer hover:bg-muted/50' : ''}`}
            onClick={() => onRowClick?.(item)}
          >
            <div
              className="absolute inset-0 bg-chart-1/10 rounded"
              style={{width: `${(item.visitors / maxVisitors) * 100}%`}}
            />
            <span className="relative truncate flex-1 font-medium">{item.name || '(direct / none)'}</span>
            <div className="relative flex items-center gap-3 text-muted-foreground">
              <span>{item.visitors.toLocaleString()}</span>
              {showBounceRate && item.bounceRate != null && (
                <span className="w-12 text-right">{item.bounceRate.toFixed(0)}%</span>
              )}
              {showDuration && item.avgDuration != null && (
                <span className="w-10 text-right">{item.avgDuration < 60 ? `${Math.round(item.avgDuration)}s` : `${Math.floor(item.avgDuration / 60)}m`}</span>
              )}
            </div>
          </div>
        ))}
      </CardContent>
    </Card>
  )
}
