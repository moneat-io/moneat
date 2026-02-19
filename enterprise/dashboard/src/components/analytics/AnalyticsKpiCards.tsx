// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import {Card, CardContent} from '@/components/ui/card'
import {cn} from '@/lib/utils'
import type {AnalyticsOverview} from '@/lib/api'

function formatDuration(seconds: number): string {
  if (seconds < 60) return `${Math.round(seconds)}s`
  const minutes = Math.floor(seconds / 60)
  const secs = Math.round(seconds % 60)
  return `${minutes}m ${secs}s`
}

function formatNumber(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`
  return n.toLocaleString()
}

function calcChange(current: number, previous: number): {value: number; positive: boolean} | null {
  if (previous === 0) return null
  const change = ((current - previous) / previous) * 100
  return {value: Math.abs(Math.round(change * 10) / 10), positive: change >= 0}
}

const KPI_CONFIG = [
  {key: 'uniqueVisitors' as const, label: 'Unique Visitors', accent: 'blue', format: formatNumber},
  {key: 'totalPageviews' as const, label: 'Total Pageviews', accent: 'violet', format: formatNumber},
  {key: 'bounceRate' as const, label: 'Bounce Rate', accent: 'amber', format: (v: number) => `${Math.round(v * 100)}%`, invertTrend: true},
  {key: 'avgVisitDuration' as const, label: 'Visit Duration', accent: 'emerald', format: formatDuration},
  {key: 'viewsPerVisit' as const, label: 'Views / Visit', accent: 'cyan', format: (v: number) => v.toFixed(1)},
]

const ACCENT_BAR: Record<string, string> = {
  blue: 'bg-blue-500',
  violet: 'bg-violet-500',
  amber: 'bg-amber-500',
  emerald: 'bg-emerald-500',
  cyan: 'bg-cyan-500',
}

interface AnalyticsKpiCardsProps {
  data?: AnalyticsOverview
  isLoading: boolean
}

export function AnalyticsKpiCards({data, isLoading}: AnalyticsKpiCardsProps) {
  return (
    <div className="grid gap-2.5 grid-cols-2 lg:grid-cols-5">
      {KPI_CONFIG.map((kpi) => {
        const barColor = ACCENT_BAR[kpi.accent]
        const value = data?.[kpi.key]
        const compValue = data?.comparison?.[kpi.key]
        const trend = value != null && compValue != null ? calcChange(value, compValue) : null
        const displayTrend = trend && kpi.invertTrend ? {...trend, positive: !trend.positive} : trend

        return (
          <Card key={kpi.key} className="overflow-hidden">
            {barColor && <div className={cn('h-0.5 w-full shrink-0', barColor)} aria-hidden />}
            <CardContent className="px-3 py-2.5">
              <p className="text-[11px] font-medium text-muted-foreground truncate mb-1">{kpi.label}</p>
              <div className="flex items-baseline gap-2">
                {isLoading ? (
                  <div className="h-5 w-14 bg-muted rounded animate-pulse" />
                ) : (
                  <p className="text-lg font-semibold leading-none tabular-nums">
                    {value != null ? kpi.format(value) : '—'}
                  </p>
                )}
                {displayTrend && (
                  <span className={cn(
                    'text-[11px] font-medium',
                    displayTrend.positive ? 'text-emerald-600 dark:text-emerald-400' : 'text-rose-600 dark:text-rose-400'
                  )}>
                    {displayTrend.positive ? '↑' : '↓'} {displayTrend.value}%
                  </span>
                )}
              </div>
            </CardContent>
          </Card>
        )
      })}
    </div>
  )
}

export function AnalyticsKpiCardsSkeleton() {
  return (
    <div className="grid gap-2.5 grid-cols-2 lg:grid-cols-5">
      {KPI_CONFIG.map((kpi) => {
        const barColor = ACCENT_BAR[kpi.accent]
        return (
          <Card key={kpi.key} className="overflow-hidden">
            {barColor && <div className={cn('h-0.5 w-full shrink-0', barColor)} aria-hidden />}
            <CardContent className="px-3 py-2.5">
              <div className="h-3 w-16 bg-muted rounded animate-pulse mb-2" />
              <div className="h-5 w-14 bg-muted rounded animate-pulse" />
            </CardContent>
          </Card>
        )
      })}
    </div>
  )
}
