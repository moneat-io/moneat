import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {cn} from '@/lib/utils'
import {CalendarDays} from 'lucide-react'
import type {AnalyticsRetentionResponse} from '@/lib/api'

interface AnalyticsRetentionTableProps {
  data?: AnalyticsRetentionResponse
  isLoading?: boolean
}

function formatPercent(value: number): string {
  return `${value.toFixed(1)}%`
}

function formatCohortWeek(value: string): string {
  return value.split(' ')[0] || value
}

function retentionTone(rate: number): string {
  if (rate >= 60) return 'bg-success-bg text-success-fg'
  if (rate >= 40) return 'bg-info-bg text-info-fg'
  if (rate >= 20) return 'bg-warning-bg text-warning-fg'
  if (rate > 0) return 'bg-danger-bg text-danger-fg'
  return 'bg-muted text-muted-foreground'
}

function retentionPeriods(data: AnalyticsRetentionResponse): number[] {
  const periods = data.cohorts.flatMap(cohort => cohort.periods.map(period => period.days))
  return [...new Set(periods)].sort((a, b) => a - b)
}

export function AnalyticsRetentionTable({data, isLoading}: AnalyticsRetentionTableProps) {
  const periods = data ? retentionPeriods(data) : [1, 7, 14, 30]

  if (isLoading) {
    return (
      <Card>
        <CardHeader className="pb-1">
          <CardTitle className="text-xs font-medium flex items-center gap-1.5">
            <CalendarDays className="h-3.5 w-3.5 text-chart-9" />
            Retention Cohorts
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="h-[180px] w-full animate-pulse rounded bg-muted" />
        </CardContent>
      </Card>
    )
  }

  if (!data || data.cohorts.length === 0) {
    return (
      <Card>
        <CardHeader className="pb-1">
          <CardTitle className="text-xs font-medium flex items-center gap-1.5">
            <CalendarDays className="h-3.5 w-3.5 text-chart-9" />
            Retention Cohorts
          </CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-xs text-muted-foreground text-center py-8">No retention data</p>
        </CardContent>
      </Card>
    )
  }

  return (
    <Card>
      <CardHeader className="pb-1">
        <CardTitle className="text-xs font-medium flex items-center gap-1.5">
          <CalendarDays className="h-3.5 w-3.5 text-chart-9" />
          Retention Cohorts
        </CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="text-xs">Cohort</TableHead>
              <TableHead className="text-right text-xs">Users</TableHead>
              {periods.map(period => (
                <TableHead key={period} className="text-right text-xs">D{period}</TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.cohorts.map(cohort => (
              <TableRow key={cohort.cohortWeek}>
                <TableCell className="text-xs font-medium">{formatCohortWeek(cohort.cohortWeek)}</TableCell>
                <TableCell className="text-right text-xs text-muted-foreground">
                  {cohort.users.toLocaleString()}
                </TableCell>
                {periods.map(period => {
                  const periodData = cohort.periods.find(item => item.days === period)
                  const isEligible = periodData !== undefined && (periodData.eligibleUsers ?? cohort.users) > 0
                  const rate = periodData?.retentionRate ?? 0
                  return (
                    <TableCell key={period} className="p-1 text-right">
                      <span className={cn('inline-flex min-w-14 justify-end rounded px-2 py-1 text-xs', retentionTone(rate))}>
                        {isEligible ? formatPercent(rate) : '-'}
                      </span>
                    </TableCell>
                  )
                })}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}
