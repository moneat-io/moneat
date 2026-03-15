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

const ACCENT_STYLES = {
  blue: { bar: 'bg-blue-500', icon: 'bg-blue-500/15 text-blue-600 dark:text-blue-400', text: 'text-blue-600 dark:text-blue-400' },
  amber: { bar: 'bg-amber-500', icon: 'bg-amber-500/15 text-amber-600 dark:text-amber-400', text: 'text-amber-600 dark:text-amber-400' },
  emerald: { bar: 'bg-emerald-500', icon: 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400', text: 'text-emerald-600 dark:text-emerald-400' },
  violet: { bar: 'bg-violet-500', icon: 'bg-violet-500/15 text-violet-600 dark:text-violet-400', text: 'text-violet-600 dark:text-violet-400' },
  rose: { bar: 'bg-rose-500', icon: 'bg-rose-500/15 text-rose-600 dark:text-rose-400', text: 'text-rose-600 dark:text-rose-400' },
  cyan: { bar: 'bg-cyan-500', icon: 'bg-cyan-500/15 text-cyan-600 dark:text-cyan-400', text: 'text-cyan-600 dark:text-cyan-400' },
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
            <p className={cn('font-bold leading-tight', compact ? 'text-base' : 'text-lg', valueColor)}>{value}</p>
            {subtitle && (
              <p className={cn('text-muted-foreground truncate', compact ? 'text-[10px]' : 'text-[11px]')}>{subtitle}</p>
            )}
            {trend && (
              <p className={`text-[11px] ${trend.positive ? 'text-emerald-600' : 'text-rose-600'}`}>
                {trend.positive ? '↑' : '↓'} {Math.abs(trend.value)}%
              </p>
            )}
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
