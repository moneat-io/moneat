// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import {Card, CardContent} from '@/components/ui/card'
import {Users, Eye, ArrowDownUp, Clock, Layers} from 'lucide-react'
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
  {
    key: 'uniqueVisitors' as const,
    label: 'Unique Visitors',
    icon: Users,
    accent: 'blue',
    format: formatNumber,
  },
  {
    key: 'totalPageviews' as const,
    label: 'Total Pageviews',
    icon: Eye,
    accent: 'violet',
    format: formatNumber,
  },
  {
    key: 'bounceRate' as const,
    label: 'Bounce Rate',
    icon: ArrowDownUp,
    accent: 'amber',
    format: (v: number) => `${Math.round(v * 100)}%`,
    invertTrend: true,
  },
  {
    key: 'avgVisitDuration' as const,
    label: 'Visit Duration',
    icon: Clock,
    accent: 'emerald',
    format: formatDuration,
  },
  {
    key: 'viewsPerVisit' as const,
    label: 'Views / Visit',
    icon: Layers,
    accent: 'cyan',
    format: (v: number) => v.toFixed(1),
  },
]

const ACCENT_STYLES: Record<string, {bar: string; icon: string}> = {
  blue: {bar: 'bg-blue-500', icon: 'bg-blue-500/15 text-blue-600 dark:text-blue-400'},
  violet: {bar: 'bg-violet-500', icon: 'bg-violet-500/15 text-violet-600 dark:text-violet-400'},
  amber: {bar: 'bg-amber-500', icon: 'bg-amber-500/15 text-amber-600 dark:text-amber-400'},
  emerald: {bar: 'bg-emerald-500', icon: 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'},
  cyan: {bar: 'bg-cyan-500', icon: 'bg-cyan-500/15 text-cyan-600 dark:text-cyan-400'},
}

interface AnalyticsKpiCardsProps {
  data?: AnalyticsOverview
  isLoading: boolean
}

export function AnalyticsKpiCards({data, isLoading}: AnalyticsKpiCardsProps) {
  return (
    <div className="grid gap-3 grid-cols-2 lg:grid-cols-5">
      {KPI_CONFIG.map((kpi) => {
        const styles = ACCENT_STYLES[kpi.accent]
        const value = data?.[kpi.key]
        const compValue = data?.comparison?.[kpi.key]
        const trend = value != null && compValue != null ? calcChange(value, compValue) : null
        const displayTrend = trend && kpi.invertTrend ? {...trend, positive: !trend.positive} : trend

        return (
          <Card key={kpi.key} className="overflow-hidden">
            {styles && <div className={cn('h-1 w-full shrink-0', styles.bar)} aria-hidden />}
            <CardContent className="px-3 py-2 sm:px-4 sm:py-3">
              <div className="flex items-center gap-2 sm:gap-3">
                <div
                  className={cn(
                    'h-8 w-8 sm:h-9 sm:w-9 shrink-0 rounded-lg flex items-center justify-center',
                    styles?.icon || 'bg-primary/10 text-primary'
                  )}
                >
                  <kpi.icon className="h-4 w-4" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="text-xs font-medium text-muted-foreground truncate">{kpi.label}</p>
                  {isLoading ? (
                    <div className="h-5 w-12 bg-muted rounded animate-pulse mt-0.5" />
                  ) : (
                    <p className="text-lg font-bold leading-tight">
                      {value != null ? kpi.format(value) : '—'}
                    </p>
                  )}
                  {displayTrend && (
                    <p className={cn('text-[11px]', displayTrend.positive ? 'text-emerald-600' : 'text-rose-600')}>
                      {displayTrend.positive ? '↑' : '↓'} {displayTrend.value}%
                    </p>
                  )}
                </div>
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
    <div className="grid gap-3 grid-cols-2 lg:grid-cols-5">
      {KPI_CONFIG.map((kpi) => {
        const styles = ACCENT_STYLES[kpi.accent]
        return (
          <Card key={kpi.key} className="overflow-hidden">
            {styles && <div className={cn('h-1 w-full shrink-0', styles.bar)} aria-hidden />}
            <CardContent className="px-3 py-2 sm:px-4 sm:py-3">
              <div className="flex items-center gap-2 sm:gap-3">
                <div className={cn('h-8 w-8 sm:h-9 sm:w-9 shrink-0 rounded-lg animate-pulse', 'bg-muted')} />
                <div className="min-w-0 flex-1 space-y-2">
                  <div className="h-3 w-16 bg-muted rounded animate-pulse" />
                  <div className="h-5 w-12 bg-muted rounded animate-pulse" />
                </div>
              </div>
            </CardContent>
          </Card>
        )
      })}
    </div>
  )
}
