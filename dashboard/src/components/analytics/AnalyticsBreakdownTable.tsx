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
  title, icon: Icon, iconColor = 'text-blue-500', data, isLoading, showBounceRate, showDuration, onRowClick,
}: AnalyticsBreakdownTableProps) {
  if (isLoading) {
    return (
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-medium flex items-center gap-2">
            <Icon className={`h-4 w-4 ${iconColor}`} />
            {title}
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {Array.from({length: 5}).map((_, i) => <div key={i} className="h-8 w-full animate-pulse rounded bg-muted" />)}
        </CardContent>
      </Card>
    )
  }

  if (!data || data.length === 0) {
    return (
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-medium flex items-center gap-2">
            <Icon className={`h-4 w-4 ${iconColor}`} />
            {title}
          </CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground text-center py-4">No data</p>
        </CardContent>
      </Card>
    )
  }

  const maxVisitors = Math.max(...data.map(d => d.visitors), 1)

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium flex items-center gap-2">
          <Icon className={`h-4 w-4 ${iconColor}`} />
          {title}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-1">
        {data.map((item) => (
          <div
            key={item.name}
            className={`relative flex items-center justify-between px-2 py-1.5 rounded text-xs ${onRowClick ? 'cursor-pointer hover:bg-muted/50' : ''}`}
            onClick={() => onRowClick?.(item)}
          >
            <div
              className="absolute inset-0 bg-blue-500/10 rounded"
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
