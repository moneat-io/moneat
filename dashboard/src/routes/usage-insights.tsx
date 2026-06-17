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

import {type ComponentType} from 'react'
import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {
  AlertTriangle,
  ArrowUpRight,
  BarChart3,
  CalendarClock,
  Gauge,
  Layers,
  LineChart,
  TrendingUp,
} from 'lucide-react'
import {api, type BillingContributor, type BillingInsightDimension} from '@/lib/api'
import {useIsSelfHosted} from '@/hooks/useEnterpriseFeatures'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDate} from '@/lib/date-format'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {PageHeader} from '@/components/ui/page-header'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Alert, AlertDescription, AlertTitle} from '@/components/ui/alert'
import {cn} from '@/lib/utils'

const BYTES_PER_GB = 1024 * 1024 * 1024
const MAX_BAR_PERCENT = 100
const MONEY_DIVISOR = 100
const DATE_ONLY_PATTERN = /^\d{4}-\d{2}-\d{2}$/

export const Route = createFileRoute('/usage-insights')({
  beforeLoad: async ({location}) => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login', search: {redirect: location.href}})
    }
  },
  component: UsageInsightsPage,
})

function UsageInsightsPage() {
  const isSelfHosted = useIsSelfHosted()
  const {timezone} = useTimezone()
  const {data, isLoading, error} = useQuery({
    queryKey: ['billingUsageInsights'],
    queryFn: () => api.getBillingUsageInsights(),
    enabled: api.isAuthenticated() && !isSelfHosted,
  })

  if (isSelfHosted) {
    return <CloudOnlyState />
  }

  if (isLoading) {
    return (
      <div className="container mx-auto flex max-w-7xl flex-col gap-4 px-4 py-6">
        <p className="text-sm text-muted-foreground">Loading usage insights...</p>
      </div>
    )
  }

  if (error || !data) {
    return (
      <div className="container mx-auto flex max-w-7xl flex-col gap-4 px-4 py-6">
        <Alert variant="destructive">
          <AlertTriangle className="h-4 w-4" />
          <AlertTitle>Unable to load Usage Insights</AlertTitle>
          <AlertDescription>Refresh the page or check billing settings.</AlertDescription>
        </Alert>
      </div>
    )
  }

  const periodLabel =
    `${formatBillingDate(data.periodStart, timezone)} - ${formatBillingDate(data.periodEnd, timezone)}`
  const sortedDimensions = [...data.dimensions].sort(
    (a, b) => riskRank(b.forecast.riskLevel) - riskRank(a.forecast.riskLevel)
  )
  const topRisk = sortedDimensions[0]
  const nextLimitHit = earliestLimitHit(sortedDimensions)
  const totalProjectedOverage = data.dimensions.reduce(
    (sum, dimension) => sum + dimension.forecast.projectedOverageCents,
    0
  )
  const topContributors = sortedDimensions
    .flatMap((dimension) =>
      dimension.contributors.map((contributor) => ({dimension, contributor}))
    )
    .sort((a, b) => contributorRankValue(b) - contributorRankValue(a))
    .slice(0, 8)
  const apmGroups = data.apmSpanDebug?.groups ?? []

  return (
    <div className="container mx-auto flex max-w-7xl flex-col gap-6 px-4 py-6">
      <PageHeader
        icon={LineChart}
        title="Usage Insights"
        description={periodLabel}
        actions={
          <>
            <Button asChild variant="outline" size="sm">
              <Link to="/settings" search={{tab: 'usage'}}>
                <Layers data-icon="inline-start" />
                Usage Summary
              </Link>
            </Button>
            <Button asChild size="sm">
              <Link to="/settings" search={{tab: 'billing'}}>
                <ArrowUpRight data-icon="inline-start" />
                Billing
              </Link>
            </Button>
          </>
        }
      />

      <div className="grid gap-4 lg:grid-cols-3">
        <SummaryMetric
          icon={Gauge}
          label="Highest risk"
          value={topRisk ? topRisk.label : 'None'}
          detail={topRisk ? forecastDetail(topRisk, timezone) : 'No billable usage yet.'}
        />
        <SummaryMetric
          icon={TrendingUp}
          label="Projected overage"
          value={formatCurrency(totalProjectedOverage)}
          detail="Projected at the current adaptive rate."
        />
        <SummaryMetric
          icon={CalendarClock}
          label="Next limit hit"
          value={nextLimitHit ? formatBillingDate(nextLimitHit.date, timezone) : 'Not projected'}
          detail={
            nextLimitHit
              ? `${nextLimitHit.dimension.label} - ${nextLimitHit.label}`
              : 'No included limits projected this period.'
          }
        />
      </div>

      <div className="grid gap-4 xl:grid-cols-5">
        {sortedDimensions.map((dimension) => (
          <ForecastCard key={dimension.key} dimension={dimension} timezone={timezone} />
        ))}
      </div>

      <div className="grid gap-4 xl:grid-cols-[minmax(0,1.2fr)_minmax(360px,0.8fr)]">
        <SectionCard title="Forecast Timeline" icon={LineChart} flushBody>
            <div className="w-full overflow-x-auto">
              <Table className="min-w-[640px]">
                <TableHeader>
                  <TableRow>
                    <TableHead>Dimension</TableHead>
                    <TableHead className="text-right">Current</TableHead>
                    <TableHead className="text-right">Projected</TableHead>
                    <TableHead className="text-right">Threshold</TableHead>
                    <TableHead className="text-right">Confidence</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {sortedDimensions.map((dimension) => (
                    <TableRow key={dimension.key}>
                      <TableCell>
                        <div className="flex flex-col gap-1">
                          <span className="font-medium">{dimension.label}</span>
                          <span className="text-xs text-muted-foreground">{forecastDetail(dimension, timezone)}</span>
                        </div>
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {formatUsageValue(dimension.used, dimension.unit)}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {formatUsageValue(dimension.forecast.projectedPeriodEndUsage, dimension.unit)}
                      </TableCell>
                      <TableCell className="text-right text-xs">
                        {limitHitLabel(dimension, timezone)}
                      </TableCell>
                      <TableCell className="text-right">
                        <Badge variant="outline" className="capitalize">
                          {dimension.forecast.confidence}
                        </Badge>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
        </SectionCard>

        <SectionCard title="Top Contributors" icon={TrendingUp}>
            <div className="flex flex-col divide-y">
              {topContributors.length > 0 ? topContributors.map(({dimension, contributor}) => (
                <ContributorRow
                  key={`${dimension.key}:${contributor.key}`}
                  contributor={contributor}
                  dimension={dimension}
                />
              )) : (
                <p className="text-sm text-muted-foreground">No billable usage recorded for this period.</p>
              )}
            </div>
        </SectionCard>
      </div>

      <SectionCard title="APM Span Sources" icon={Layers} flushBody>
          {apmGroups.length > 0 ? (
            <div className="w-full overflow-x-auto">
              <Table className="min-w-[760px]">
                <TableHeader>
                  <TableRow>
                    <TableHead>Source</TableHead>
                    <TableHead>Service</TableHead>
                    <TableHead>Operation</TableHead>
                    <TableHead>Moneat service</TableHead>
                    <TableHead className="text-right">Spans</TableHead>
                    <TableHead className="text-right">Share</TableHead>
                    <TableHead className="text-right">Errors</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {apmGroups.map((group) => (
                    <TableRow key={apmGroupKey(group)}>
                      <TableCell>
                        <Badge variant="outline">{formatApmSource(group.source)}</Badge>
                      </TableCell>
                      <TableCell className="max-w-[180px] truncate font-medium">
                        {group.service || 'Unknown service'}
                      </TableCell>
                      <TableCell className="max-w-[240px] truncate">
                        {group.operation || group.resource || 'Unknown operation'}
                      </TableCell>
                      <TableCell className="max-w-[180px] truncate text-muted-foreground">
                        {group.projectName ?? group.projectSlug ?? 'Organization'}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {formatNumber(group.spanCount)}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {boundedPercent(group.percentage).toFixed(1)}%
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {formatNumber(group.errorCount)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          ) : (
            <div className="px-4 py-3">
              <EmptyState icon={Layers} title="No APM spans" description="No APM spans stored for this billing period." />
            </div>
          )}
      </SectionCard>
    </div>
  )
}

function CloudOnlyState() {
  return (
    <div className="container mx-auto flex max-w-3xl flex-col gap-4 px-4 py-10">
      <Alert>
        <BarChart3 className="h-4 w-4" />
        <AlertTitle>Usage Insights is a Moneat Cloud feature</AlertTitle>
        <AlertDescription>
          Self-hosted deployments keep the compact Settings usage summary. Cloud workspaces get forecasts and
          billing risk alerts.
        </AlertDescription>
      </Alert>
      <Button asChild variant="outline" className="w-fit">
        <Link to="/settings" search={{tab: 'usage'}}>Back to usage summary</Link>
      </Button>
    </div>
  )
}

function SummaryMetric({
  icon: Icon,
  label,
  value,
  detail,
}: {
  readonly icon: ComponentType<{className?: string}>
  readonly label: string
  readonly value: string
  readonly detail: string
}) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
          <Icon className="h-4 w-4" />
          {label}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-1">
        <p className="text-2xl font-bold">{value}</p>
        <p className="text-xs text-muted-foreground">{detail}</p>
      </CardContent>
    </Card>
  )
}

function ForecastCard({
  dimension,
  timezone,
}: {
  readonly dimension: BillingInsightDimension
  readonly timezone: string
}) {
  const percent = boundedPercent(dimension.percentOfEffective ?? dimension.percentOfBase)
  const risk = dimension.forecast.riskLevel
  const limit = dimension.effectiveLimit ?? dimension.baseLimit
  const hasEffectiveLimit = dimension.effectiveLimit !== null && dimension.effectiveLimit !== undefined

  return (
    <Card className="min-h-[220px]">
      <CardHeader className="pb-3">
        <div className="flex items-start justify-between gap-2">
          <div className="flex flex-col gap-1">
            <CardTitle className="text-sm">{dimension.label}</CardTitle>
            <CardDescription>{forecastWindowLabel(dimension.forecast.window)}</CardDescription>
          </div>
          <Badge variant={riskBadgeVariant(risk)} className="shrink-0 whitespace-nowrap">{riskLabel(risk)}</Badge>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="flex items-baseline justify-between gap-2">
          <span className="text-xl font-bold tabular-nums">{formatUsageValue(dimension.used, dimension.unit)}</span>
          <span className="text-right text-xs text-muted-foreground">
            of {formatLimit(limit, dimension.unit)} {hasEffectiveLimit ? 'effective' : 'included'}
          </span>
        </div>
        {hasEffectiveLimit && (
          <p className="text-xs text-muted-foreground">
            Included {formatLimit(dimension.baseLimit, dimension.unit)}
          </p>
        )}
        <div className="h-2 overflow-hidden rounded-full bg-secondary">
          <div className={cn('h-full rounded-full', barClass(percent))} style={{width: `${percent}%`}} />
        </div>
        <p className="text-xs text-muted-foreground">{forecastDetail(dimension, timezone)}</p>
        <div className="grid grid-cols-2 gap-4 border-t pt-3 text-xs">
          <div>
            <p className="text-muted-foreground">Period end</p>
            <p className="font-semibold tabular-nums">
              {formatUsageValue(dimension.forecast.projectedPeriodEndUsage, dimension.unit)}
            </p>
          </div>
          <div>
            <p className="text-muted-foreground">Projected overage</p>
            <p className="font-semibold tabular-nums">
              {formatCurrency(dimension.forecast.projectedOverageCents)}
            </p>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

function ContributorRow({
  contributor,
  dimension,
}: {
  readonly contributor: BillingContributor
  readonly dimension: BillingInsightDimension
}) {
  const amount = dimension.unit === 'bytes'
    ? formatUsageValue(contributor.bytes, dimension.unit)
    : formatUsageValue(contributor.units, dimension.unit)

  return (
    <div className="flex items-center justify-between gap-3 py-3 first:pt-0 last:pb-0">
      <div className="min-w-0">
        <p className="truncate text-sm font-medium">{contributor.label}</p>
        <p className="text-xs text-muted-foreground">{dimension.label}</p>
      </div>
      <div className="text-right">
        <p className="text-sm font-semibold tabular-nums">{amount}</p>
        <p className="text-xs text-muted-foreground">{boundedPercent(contributor.percentage).toFixed(1)}%</p>
      </div>
    </div>
  )
}

function contributorRankValue({
  contributor,
  dimension,
}: {
  readonly contributor: BillingContributor
  readonly dimension: BillingInsightDimension
}): number {
  return dimension.unit === 'bytes' ? contributor.bytes : contributor.units
}

function formatUsageValue(value: number, unit: string): string {
  if (unit === 'bytes') return `${(value / BYTES_PER_GB).toFixed(2)} GB`
  return formatNumber(value)
}

function formatLimit(value: number, unit: string): string {
  if (value <= 0 || value >= 9_007_199_254_740_000) return 'Unlimited'
  return formatUsageValue(value, unit)
}

function formatNumber(value: number): string {
  return Math.round(value).toLocaleString()
}

function formatCurrency(cents: number): string {
  return `$${(cents / MONEY_DIVISOR).toFixed(2)}`
}

function formatBillingDate(value: string, timezone = 'UTC'): string {
  if (DATE_ONLY_PATTERN.test(value)) {
    const [year, month, day] = value.split('-').map(Number)
    return new Intl.DateTimeFormat(undefined, {
      timeZone: 'UTC',
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    }).format(new Date(Date.UTC(year, month - 1, day)))
  }
  return formatDate(value, timezone)
}

function forecastDetail(dimension: BillingInsightDimension, timezone = 'UTC'): string {
  const effectiveDate = dimension.forecast.projectedEffectiveLimitHitDate
  if (effectiveDate) {
    return `Effective capacity ${formatBillingDate(effectiveDate, timezone)}`
  }
  const includedDate = dimension.forecast.projectedBaseLimitHitDate
  if (includedDate) {
    return `Included limit ${formatBillingDate(includedDate, timezone)}`
  }
  if (dimension.forecast.dailyRate > 0) {
    return `${formatRate(dimension.forecast.dailyRate, dimension.unit)} / day`
  }
  return 'Insufficient recent usage'
}

function formatRate(value: number, unit: string): string {
  if (unit === 'bytes') return `${(value / BYTES_PER_GB).toFixed(2)} GB`
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}M ${unit}`
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}K ${unit}`
  return `${value.toFixed(1)} ${unit}`
}

function boundedPercent(value: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.max(0, Math.min(MAX_BAR_PERCENT, value))
}

function barClass(percent: number): string {
  if (percent >= MAX_BAR_PERCENT) return 'bg-danger-solid'
  if (percent >= 80) return 'bg-warning-solid'
  return 'bg-primary'
}

function riskRank(risk: string): number {
  if (risk === 'critical') return 4
  if (risk === 'warning') return 3
  if (risk === 'watch') return 2
  return 1
}

function riskLabel(risk: string): string {
  if (risk === 'critical') return 'Critical'
  if (risk === 'warning') return 'Warning'
  if (risk === 'watch') return 'Watch'
  return 'On track'
}

function riskBadgeVariant(risk: string): BadgeProps['variant'] {
  if (risk === 'critical') return 'dangerSolid'
  if (risk === 'warning') return 'warningSolid'
  if (risk === 'watch') return 'accent'
  return 'neutral'
}

function forecastWindowLabel(window: string): string {
  if (window === '7d') return 'Adaptive 7-day rate'
  if (window === 'period_to_date') return 'Period-to-date rate'
  if (window === '30d') return '30-day fallback rate'
  return 'Needs more data'
}

function limitHitLabel(dimension: BillingInsightDimension, timezone: string): string {
  const effective = dimension.forecast.projectedEffectiveLimitHitDate
  const base = dimension.forecast.projectedBaseLimitHitDate
  if (effective) return `Effective ${formatBillingDate(effective, timezone)}`
  if (base) return `Included ${formatBillingDate(base, timezone)}`
  return 'Not projected'
}

function earliestLimitHit(dimensions: BillingInsightDimension[]): {
  readonly date: string
  readonly dimension: BillingInsightDimension
  readonly label: string
} | null {
  const hits = dimensions.flatMap((dimension) => {
    const effective = dimension.forecast.projectedEffectiveLimitHitDate
    const included = dimension.forecast.projectedBaseLimitHitDate
    return [
      effective ? {date: effective, dimension, label: 'effective capacity'} : null,
      included ? {date: included, dimension, label: 'included limit'} : null,
    ].filter((hit): hit is {date: string; dimension: BillingInsightDimension; label: string} => hit !== null)
  })
  return hits.sort((a, b) => a.date.localeCompare(b.date))[0] ?? null
}

function formatApmSource(source: string): string {
  const normalized = source.trim().toLowerCase()
  if (normalized === 'otlp') return 'OTLP'
  if (normalized === 'sentry') return 'Sentry'
  if (!normalized || normalized === 'datadog') return 'Datadog'
  return source
}

function apmGroupKey(group: {
  source: string
  service: string
  operation: string
  resource: string
  projectId?: string | null
}): string {
  return [group.source, group.service, group.operation, group.resource, group.projectId ?? 'org'].join('|')
}
