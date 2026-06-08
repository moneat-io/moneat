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

import {Card, CardContent} from '@/components/ui/card'
import {LucideIcon} from 'lucide-react'
import {cn} from '@/lib/utils'

// Accent presets map onto the shared status language (style guide); "cyan" uses
// a categorical chart hue for visual distinction without implying a status.
const ACCENT_STYLES = {
  blue: { bar: 'bg-info-solid', icon: 'bg-info-bg text-info-fg', text: 'text-info-fg' },
  amber: { bar: 'bg-warning-solid', icon: 'bg-warning-bg text-warning-fg', text: 'text-warning-fg' },
  emerald: { bar: 'bg-success-solid', icon: 'bg-success-bg text-success-fg', text: 'text-success-fg' },
  violet: { bar: 'bg-primary', icon: 'bg-[hsl(var(--primary)/0.12)] text-primary', text: 'text-primary' },
  rose: { bar: 'bg-danger-solid', icon: 'bg-danger-bg text-danger-fg', text: 'text-danger-fg' },
  cyan: { bar: 'bg-chart-3', icon: 'bg-chart-3/15 text-chart-3', text: 'text-chart-3' },
} satisfies Record<string, { bar: string; icon: string; text: string }>

export type StatsCardAccent = keyof typeof ACCENT_STYLES

export function StatsCardSkeleton({ accent, compact, className }: { readonly accent?: StatsCardAccent; readonly compact?: boolean; readonly className?: string }) {
  const styles = accent ? ACCENT_STYLES[accent] : null
  return (
    <Card className={cn('overflow-hidden', className)}>
      {styles && <div className={cn('w-full shrink-0', compact ? 'h-0.5' : 'h-1', styles.bar)} aria-hidden />}
      <CardContent className={compact ? 'px-3 py-2' : 'px-4 py-3'}>
        <div className={cn('flex items-center', compact ? 'gap-2' : 'gap-3')}>
          <div className={cn(
            'shrink-0 rounded-lg animate-pulse',
            compact ? 'h-7 w-7' : 'h-9 w-9',
            styles ? styles.icon.replace(/text-\S+/, 'bg-muted') : 'bg-muted'
          )} />
          <div className="min-w-0 flex-1 space-y-2">
            <div className={cn('bg-muted rounded animate-pulse', compact ? 'h-2.5 w-14' : 'h-3 w-16')} />
            <div className={cn('bg-muted rounded animate-pulse', compact ? 'h-4 w-10' : 'h-5 w-12')} />
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

interface StatsCardProps {
  readonly title: string
  readonly value: string | number
  readonly icon: LucideIcon
  readonly trend?: {
    value: number
    positive: boolean
  }
  /** Optional secondary label beneath the value */
  readonly subtitle?: string
  /** Preset accent: blue, amber, emerald, violet, rose, cyan. Adds a top bar and colored icon. */
  readonly accent?: StatsCardAccent
  /** Optional color class override for the value text */
  readonly valueColor?: string
  /** Use smaller padding and typography for compact layouts */
  readonly compact?: boolean
  readonly className?: string
}

export function StatsCard({ title, value, icon: Icon, trend, subtitle, accent, valueColor, compact, className }: StatsCardProps) {
  const styles = accent ? ACCENT_STYLES[accent] : null
  return (
    <Card className={cn('overflow-hidden', className)}>
      {styles && <div className={cn('w-full shrink-0', compact ? 'h-0.5' : 'h-1', styles.bar)} aria-hidden />}
      <CardContent className={compact ? 'px-3 py-2' : 'px-3 py-2 sm:px-4 sm:py-3'}>
        <div className={cn('flex items-center', compact ? 'gap-2' : 'gap-2 sm:gap-3')}>
          <div
            className={cn(
              'shrink-0 rounded-lg flex items-center justify-center',
              compact ? 'h-7 w-7' : 'h-8 w-8 sm:h-9 sm:w-9',
              styles ? styles.icon : 'bg-primary/10 text-primary'
            )}
          >
            <Icon className={compact ? 'h-3.5 w-3.5' : 'h-4 w-4'} />
          </div>
          <div className="min-w-0 flex-1">
            <p className={cn('font-medium text-muted-foreground truncate', compact ? 'text-[11px]' : 'text-xs')}>{title}</p>
            <p className={cn('font-semibold leading-tight tabular-nums', compact ? 'text-base' : 'text-lg', valueColor)}>{value}</p>
            {subtitle && (
              <p className={cn('text-muted-foreground truncate', compact ? 'text-[10px]' : 'text-[11px]')}>{subtitle}</p>
            )}
            {trend && (
              <p className={`text-[11px] tabular-nums ${trend.positive ? 'text-success-fg' : 'text-danger-fg'}`}>
                {trend.positive ? '↑' : '↓'} {Math.abs(trend.value)}%
              </p>
            )}
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
