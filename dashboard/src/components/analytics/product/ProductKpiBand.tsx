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

import type {LucideIcon} from 'lucide-react'
import {ArrowDown, ArrowUp, Repeat, Star, Target, UserPlus, Users, Zap} from 'lucide-react'

import {Card} from '@/components/ui/card'
import {cn} from '@/lib/utils'
import type {ProductAnalyticsSummary, ProductKpiMetric} from '@/lib/api'

import {formatCompact, formatPercent, pointsDelta, relativeDelta, type MetricDelta} from './format'

function Sparkline({values}: Readonly<{values?: number[]}>) {
  if (!values || values.length < 2) return <div className="h-[26px]" />
  const max = Math.max(...values)
  const min = Math.min(...values)
  const span = max - min || 1
  const points = values
    .map((value, index) => {
      const x = (index / (values.length - 1)) * 100
      const y = 24 - ((value - min) / span) * 22
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
  return (
    <svg
      viewBox="0 0 100 26"
      preserveAspectRatio="none"
      className="block h-[26px] w-full"
      aria-hidden="true"
    >
      <polyline points={points} fill="none" stroke="hsl(var(--chart-1))" strokeWidth={1.5} />
    </svg>
  )
}

const deltaTone: Record<MetricDelta['direction'], string> = {
  up: 'text-success-fg',
  down: 'text-danger-fg',
  flat: 'text-muted-foreground',
}

function DeltaBadge({delta}: Readonly<{delta?: MetricDelta}>) {
  if (!delta) return null
  return (
    <span className={cn('inline-flex items-center gap-0.5 pb-0.5 text-xs font-semibold tabular-nums', deltaTone[delta.direction])}>
      {delta.direction === 'up' && <ArrowUp className="h-3 w-3" />}
      {delta.direction === 'down' && <ArrowDown className="h-3 w-3" />}
      {delta.value}
    </span>
  )
}

interface KpiCardProps {
  readonly label: string
  readonly icon: LucideIcon
  readonly value: string
  readonly unit?: string
  readonly delta?: MetricDelta
  readonly spark?: number[]
  readonly foot: string
  readonly star?: boolean
}

function KpiCard({label, icon: Icon, value, unit, delta, spark, foot, star}: KpiCardProps) {
  return (
    <Card
      className={cn(
        'relative flex flex-col gap-1.5 p-3',
        star && 'border-primary/40 bg-gradient-to-b from-primary/[0.07] to-transparent',
      )}
    >
      {star && <Star className="absolute right-2.5 top-2.5 h-3.5 w-3.5 text-primary" />}
      <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <Icon className="h-3 w-3 text-muted-foreground/70" />
        {label}
      </div>
      <div className="flex items-end justify-between gap-2">
        <div className="text-2xl font-bold leading-none tracking-tight tabular-nums">
          {value}
          {unit && <span className="text-base font-semibold text-muted-foreground">{unit}</span>}
        </div>
        <DeltaBadge delta={delta} />
      </div>
      <Sparkline values={spark} />
      <div className="mt-px border-t pt-1.5 text-[11px] text-muted-foreground">{foot}</div>
    </Card>
  )
}

function CountCard(
  props: Readonly<{label: string; icon: LucideIcon; metric?: ProductKpiMetric; foot: string; star?: boolean}>,
) {
  const {label, icon, metric, foot, star} = props
  return (
    <KpiCard
      label={label}
      icon={icon}
      value={metric ? formatCompact(metric.value) : '—'}
      delta={metric ? relativeDelta(metric.value, metric.previous) : undefined}
      spark={metric?.spark}
      foot={foot}
      star={star}
    />
  )
}

function RateCard(
  props: Readonly<{label: string; icon: LucideIcon; metric?: ProductKpiMetric; foot: string; star?: boolean}>,
) {
  const {label, icon, metric, foot, star} = props
  return (
    <KpiCard
      label={label}
      icon={icon}
      value={metric ? formatPercent(metric.value) : '—'}
      unit={metric ? '%' : undefined}
      delta={metric ? pointsDelta(metric.value, metric.previous) : undefined}
      spark={metric?.spark}
      foot={foot}
      star={star}
    />
  )
}

export function ProductKpiBand({data}: Readonly<{data?: ProductAnalyticsSummary; isLoading?: boolean}>) {
  const dauFoot = data?.dailyActiveUsers == null ? 'weekly actives' : `DAU ${formatCompact(data.dailyActiveUsers)}`
  return (
    <div className="grid gap-2 [grid-template-columns:repeat(auto-fit,minmax(160px,1fr))]">
      <CountCard label="Weekly active users" icon={Users} metric={data?.weeklyActiveUsers} foot={dauFoot} />
      <CountCard label="New users" icon={UserPlus} metric={data?.newUsers} foot="new signups" />
      <RateCard label="Activation rate" icon={Target} metric={data?.activationRate} foot="North-star metric" star />
      <RateCard label="Stickiness" icon={Repeat} metric={data?.stickiness} foot="DAU / MAU" />
      <RateCard label="Week-1 retention" icon={Repeat} metric={data?.week1Retention} foot="return to key action" />
      <RateCard label="Power users" icon={Zap} metric={data?.powerUsers} foot="5+ key actions / wk" />
    </div>
  )
}
