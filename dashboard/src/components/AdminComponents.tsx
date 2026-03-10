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

import {type ReactNode} from 'react'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {cn} from '@/lib/utils'
import type {LucideIcon} from 'lucide-react'

// ─── Plan Badge ─────────────────────────────────────────────────────────────

const planConfig: Record<string, { label: string; className: string }> = {
  free: {
    label: 'Free',
    className: 'bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300 border-zinc-200 dark:border-zinc-700',
  },
  pro: {
    label: 'Pro',
    className: 'bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-300 border-blue-200 dark:border-blue-800',
  },
  team: {
    label: 'Team',
    className: 'bg-violet-50 text-violet-700 dark:bg-violet-950 dark:text-violet-300 border-violet-200 dark:border-violet-800',
  },
  business: {
    label: 'Business',
    className: 'bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300 border-amber-200 dark:border-amber-800',
  },
}

export function PlanBadge({ plan }: { readonly plan: string }) {
  const config = planConfig[plan.toLowerCase()] ?? {
    label: plan,
    className: 'bg-secondary text-secondary-foreground',
  }
  return (
    <Badge variant="outline" className={cn('font-medium capitalize', config.className)}>
      {config.label}
    </Badge>
  )
}

// ─── Metric Card ────────────────────────────────────────────────────────────

interface MetricCardProps {
  readonly title: string
  readonly value: ReactNode
  readonly subtitle?: ReactNode
  readonly icon: LucideIcon
  readonly iconColor?: string
  readonly iconBg?: string
  readonly className?: string
  readonly children?: ReactNode
}

export function MetricCard({
  title,
  value,
  subtitle,
  icon: Icon,
  iconColor = 'text-muted-foreground',
  iconBg = 'bg-muted',
  className,
  children,
}: MetricCardProps) {
  return (
    <Card className={cn('relative overflow-hidden', className)}>
      <CardHeader className="flex flex-row items-center justify-between pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">{title}</CardTitle>
        <div className={cn('rounded-lg p-2', iconBg)}>
          <Icon className={cn('h-4 w-4', iconColor)} />
        </div>
      </CardHeader>
      <CardContent>
        <div className="text-2xl font-bold tracking-tight">{value}</div>
        {subtitle && <p className="text-xs text-muted-foreground mt-1">{subtitle}</p>}
        {children}
      </CardContent>
    </Card>
  )
}

// ─── Quota / Progress Bar ──────────────────────────────────────────────────

interface QuotaBarProps {
  readonly percent: number | null | undefined
  readonly size?: 'sm' | 'md'
  readonly showLabel?: boolean
  readonly className?: string
}

export function QuotaBar({ percent, size = 'sm', showLabel = true, className }: QuotaBarProps) {
  if (percent == null) {
    return <span className="text-xs text-muted-foreground">Unlimited</span>
  }

  const clamped = Math.min(percent, 100)
  let color: string
  if (percent >= 90) {
    color = 'bg-red-500'
  } else if (percent >= 70) {
    color = 'bg-amber-500'
  } else {
    color = 'bg-emerald-500'
  }

  let labelColor: string
  if (percent >= 90) {
    labelColor = 'text-red-600 dark:text-red-400'
  } else if (percent >= 70) {
    labelColor = 'text-amber-600 dark:text-amber-400'
  } else {
    labelColor = 'text-muted-foreground'
  }

  return (
    <div className={cn('flex items-center gap-2', className)}>
      <div className={cn('flex-1 rounded-full bg-muted overflow-hidden', size === 'sm' ? 'h-1.5' : 'h-2.5')}>
        <div
          className={cn('h-full rounded-full transition-all duration-500', color)}
          style={{ width: `${clamped}%` }}
        />
      </div>
      {showLabel && (
        <span className={cn(
          'text-xs font-medium tabular-nums min-w-[3rem] text-right',
          labelColor
        )}>
          {percent.toFixed(1)}%
        </span>
      )}
    </div>
  )
}

// ─── Storage Ring (for infrastructure) ──────────────────────────────────────

interface StorageRingProps {
  readonly percent: number
  readonly size?: number
}

export function StorageRing({ percent, size = 64 }: StorageRingProps) {
  const clamped = Math.min(percent, 100)
  const strokeWidth = 6
  const radius = (size - strokeWidth) / 2
  const circumference = 2 * Math.PI * radius
  const strokeDashoffset = circumference - (clamped / 100) * circumference

  let color: string
  if (percent >= 90) {
    color = 'stroke-red-500'
  } else if (percent >= 70) {
    color = 'stroke-amber-500'
  } else {
    color = 'stroke-emerald-500'
  }

  return (
    <svg width={size} height={size} className="-rotate-90">
      <circle
        cx={size / 2}
        cy={size / 2}
        r={radius}
        strokeWidth={strokeWidth}
        fill="none"
        className="stroke-muted"
      />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={radius}
        strokeWidth={strokeWidth}
        fill="none"
        strokeLinecap="round"
        className={cn('transition-all duration-700', color)}
        style={{ strokeDasharray: circumference, strokeDashoffset }}
      />
    </svg>
  )
}

// ─── Custom Chart Tooltip ──────────────────────────────────────────────────

interface ChartTooltipPayload {
  readonly name?: string
  readonly value?: number
  readonly color?: string
  readonly dataKey?: string
}

interface ChartTooltipProps {
  readonly active?: boolean
  readonly payload?: ChartTooltipPayload[]
  readonly label?: string
  readonly formatter?: (value: number, name: string) => string
}

export function ChartTooltipContent({ active, payload, label, formatter }: ChartTooltipProps) {
  if (!active || !payload?.length) return null

  return (
    <div className="rounded-lg border bg-card px-3 py-2 shadow-lg">
      <p className="text-xs font-medium text-muted-foreground mb-1.5">{label}</p>
      <div className="space-y-1">
        {payload.map((entry, i) => (
          <div key={entry.name ?? entry.dataKey ?? `entry-${i}`} className="flex items-center gap-2 text-sm">
            <div
              className="h-2.5 w-2.5 rounded-full shrink-0"
              style={{ backgroundColor: entry.color }}
            />
            <span className="text-muted-foreground">{entry.name}:</span>
            <span className="font-semibold ml-auto tabular-nums">
              {formatter
                ? formatter(entry.value ?? 0, entry.name ?? '')
                : (entry.value ?? 0).toLocaleString()}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

// ─── Section Header ─────────────────────────────────────────────────────────

interface SectionHeaderProps {
  readonly title: string
  readonly description?: string
  readonly children?: ReactNode
}

export function SectionHeader({ title, description, children }: SectionHeaderProps) {
  return (
    <div className="flex items-center justify-between">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">{title}</h2>
        {description && <p className="text-muted-foreground mt-1">{description}</p>}
      </div>
      {children}
    </div>
  )
}

// ─── Stat Inline ────────────────────────────────────────────────────────────

export function StatInline({ label, value }: { readonly label: string; readonly value: ReactNode }) {
  return (
    <div className="flex items-baseline gap-1.5">
      <span className="text-sm text-muted-foreground">{label}</span>
      <span className="text-sm font-semibold">{value}</span>
    </div>
  )
}

// ─── Loading Skeleton ───────────────────────────────────────────────────────

export function AdminSkeleton() {
  return (
    <div className="space-y-6 animate-pulse">
      <div className="h-8 w-40 bg-muted rounded" />
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {['a', 'b', 'c', 'd'].map((id) => (
          <div key={`stat-${id}`} className="h-32 bg-muted rounded-xl" />
        ))}
      </div>
      <div className="h-80 bg-muted rounded-xl" />
    </div>
  )
}

// ─── Formatters ─────────────────────────────────────────────────────────────

// eslint-disable-next-line react-refresh/only-export-components
export function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const k = 1024
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${(bytes / Math.pow(k, i)).toFixed(i > 1 ? 2 : 0)} ${units[i]}`
}

// eslint-disable-next-line react-refresh/only-export-components
export function formatNumber(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`
  return n.toLocaleString()
}

// ─── Event Type Colors ──────────────────────────────────────────────────────

// eslint-disable-next-line react-refresh/only-export-components
export const eventTypeColors = {
  error: { stroke: '#ef4444', fill: '#ef444433', label: 'Errors' },
  transaction: { stroke: '#3b82f6', fill: '#3b82f633', label: 'Transactions' },
  replay: { stroke: '#8b5cf6', fill: '#8b5cf633', label: 'Replays' },
  feedback: { stroke: '#f59e0b', fill: '#f59e0b33', label: 'Feedback' },
  log: { stroke: '#06b6d4', fill: '#06b6d433', label: 'Logs' },
} as const

// ─── Empty State ────────────────────────────────────────────────────────────

export function EmptyState({ message, icon: Icon }: { readonly message: string; readonly icon?: LucideIcon }) {
  return (
    <div className="flex flex-col items-center justify-center py-12 text-muted-foreground">
      {Icon && <Icon className="h-10 w-10 mb-3 opacity-40" />}
      <p className="text-sm">{message}</p>
    </div>
  )
}
