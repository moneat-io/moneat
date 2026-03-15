import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import type {AnalyticsTimeseriesPoint} from '@/lib/api'

export function AnalyticsChart({data, isLoading}: {data?: AnalyticsTimeseriesPoint[]; isLoading?: boolean}) {
  if (isLoading) {
    return (
      <Card>
        <CardHeader className="pb-1">
          <CardTitle className="text-xs font-medium">Visitors & Pageviews</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="h-[160px] w-full animate-pulse rounded bg-muted" />
        </CardContent>
      </Card>
    )
  }

  if (!data || data.length === 0) {
    return (
      <Card>
        <CardContent className="flex items-center justify-center h-[160px] text-xs text-muted-foreground">
          No data for the selected period
        </CardContent>
      </Card>
    )
  }

  const maxVal = Math.max(...data.map(d => Math.max(d.visitors, d.pageviews)), 1)

  return (
    <Card>
      <CardHeader className="pb-1">
        <CardTitle className="text-xs font-medium">Visitors & Pageviews</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="flex items-end gap-px h-[160px]">
          {data.map((point, i) => {
            const visitorsH = (point.visitors / maxVal) * 100
            const pageviewsH = (point.pageviews / maxVal) * 100
            return (
              <div key={i} className="flex-1 flex items-end gap-px" title={`${point.timestamp}: ${point.visitors} visitors, ${point.pageviews} pageviews`}>
                <div className="flex-1 bg-blue-500/70 rounded-t-sm transition-all" style={{height: `${visitorsH}%`, minHeight: point.visitors > 0 ? '2px' : 0}} />
                <div className="flex-1 bg-cyan-400/50 rounded-t-sm transition-all" style={{height: `${pageviewsH}%`, minHeight: point.pageviews > 0 ? '2px' : 0}} />
              </div>
            )
          })}
        </div>
        <div className="flex items-center gap-3 mt-1.5 text-[11px] text-muted-foreground">
          <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-full bg-blue-500/70" /> Visitors</span>
          <span className="flex items-center gap-1"><span className="h-2 w-2 rounded-full bg-cyan-400/50" /> Pageviews</span>
        </div>
      </CardContent>
    </Card>
  )
}
