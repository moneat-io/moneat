import {Card, CardContent} from '@/components/ui/card'
import {ArrowDown, ArrowUp} from 'lucide-react'
import type {AnalyticsOverview} from '@/lib/api'

function formatDuration(seconds: number): string {
  if (seconds < 60) return `${Math.round(seconds)}s`
  const m = Math.floor(seconds / 60)
  const s = Math.round(seconds % 60)
  return `${m}m ${s}s`
}

function ComparisonBadge({current, previous, invert}: {current: number; previous?: number; invert?: boolean}) {
  if (previous == null || previous === 0) return null
  const pct = ((current - previous) / previous) * 100
  const isPositive = invert ? pct < 0 : pct > 0
  return (
    <span className={`inline-flex items-center text-[10px] font-medium ${isPositive ? 'text-green-600' : 'text-red-500'}`}>
      {pct > 0 ? <ArrowUp className="h-3 w-3" /> : <ArrowDown className="h-3 w-3" />}
      {Math.abs(pct).toFixed(1)}%
    </span>
  )
}

interface KpiCardProps {
  label: string
  value: string
  comparison?: number
  previous?: number
  invert?: boolean
}

function KpiCard({label, value, comparison, previous, invert}: KpiCardProps) {
  return (
    <Card>
      <CardContent className="p-2.5">
        <p className="text-[11px] text-muted-foreground mb-0.5">{label}</p>
        <div className="flex items-baseline gap-1.5">
          <p className="text-lg font-semibold">{value}</p>
          {comparison != null && <ComparisonBadge current={comparison} previous={previous} invert={invert} />}
        </div>
      </CardContent>
    </Card>
  )
}

export function AnalyticsKpiCards({data}: {data?: AnalyticsOverview; isLoading?: boolean}) {
  if (!data) return null
  const c = data.comparison
  return (
    <div className="grid grid-cols-2 md:grid-cols-5 gap-1.5">
      <KpiCard label="Unique Visitors" value={data.uniqueVisitors.toLocaleString()} comparison={data.uniqueVisitors} previous={c?.uniqueVisitors} />
      <KpiCard label="Total Pageviews" value={data.totalPageviews.toLocaleString()} comparison={data.totalPageviews} previous={c?.totalPageviews} />
      <KpiCard label="Bounce Rate" value={`${data.bounceRate.toFixed(1)}%`} comparison={data.bounceRate} previous={c?.bounceRate} invert />
      <KpiCard label="Avg. Duration" value={formatDuration(data.avgVisitDuration)} comparison={data.avgVisitDuration} previous={c?.avgVisitDuration} />
      <KpiCard label="Views / Visit" value={data.viewsPerVisit.toFixed(1)} comparison={data.viewsPerVisit} previous={c?.viewsPerVisit} />
    </div>
  )
}

export function AnalyticsKpiCardsSkeleton() {
  return (
    <div className="grid grid-cols-2 md:grid-cols-5 gap-1.5">
      {Array.from({length: 5}).map((_, i) => (
        <Card key={i}>
          <CardContent className="p-2.5 space-y-1.5">
            <div className="h-2.5 w-16 animate-pulse rounded bg-muted" />
            <div className="h-5 w-12 animate-pulse rounded bg-muted" />
          </CardContent>
        </Card>
      ))}
    </div>
  )
}
