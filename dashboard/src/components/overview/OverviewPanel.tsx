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

import type {ComponentType, ReactNode} from 'react'
import type {LucideIcon} from 'lucide-react'
import {cn} from '@/lib/utils'
import type {Tone} from './overviewMockData'
import {toneBgSolid} from './overviewTone'

interface OverviewPanelProps {
  title: string
  icon?: LucideIcon | ComponentType<{className?: string}>
  count?: ReactNode
  actions?: ReactNode
  testId?: string
  /** Remove body padding (e.g. for full-bleed tables). */
  flush?: boolean
  bodyClassName?: string
  children: ReactNode
}

/** Shared panel chrome for overview widgets — mirrors SectionCard at compact density. */
export function OverviewPanel({
  title,
  icon: Icon,
  count,
  actions,
  testId,
  flush,
  bodyClassName,
  children,
}: OverviewPanelProps) {
  return (
    <div
      data-testid={testId}
      className="flex h-full flex-col overflow-hidden rounded-lg border border-border/60 bg-card"
    >
      <div className="flex items-center gap-2 border-b border-border/40 px-3 py-1.5">
        {Icon && (
          <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-muted">
            <Icon className="h-3.5 w-3.5 text-muted-foreground" />
          </span>
        )}
        <span className="truncate text-xs font-semibold text-foreground">{title}</span>
        {count != null && (
          <span className="rounded-full bg-muted px-1.5 py-0.5 text-[10px] font-medium tabular-nums text-muted-foreground">
            {count}
          </span>
        )}
        {actions && <div className="ml-auto flex items-center gap-1.5">{actions}</div>}
      </div>
      <div className={cn('min-h-0 flex-1 overflow-auto', flush ? '' : 'px-3 py-2', bodyClassName)}>
        {children}
      </div>
    </div>
  )
}

/** A compact "View all" style text link used in panel headers. */
export function PanelLink({children, className}: {children: ReactNode; className?: string}) {
  return (
    <button
      type="button"
      className={cn(
        'inline-flex items-center gap-0.5 text-[11px] font-medium text-primary hover:underline',
        className,
      )}
    >
      {children}
    </button>
  )
}

/** Minimal sparkline. Inherits color via `currentColor` (set text color on parent). */
export function Sparkline({
  data,
  className,
  height = 24,
  strokeWidth = 1.6,
}: {
  data: number[]
  className?: string
  height?: number
  strokeWidth?: number
}) {
  if (data.length === 0) return null
  const w = 100
  const h = 30
  const min = Math.min(...data)
  const max = Math.max(...data)
  const range = max - min || 1
  const n = data.length
  const points = data
    .map((v, i) => {
      const x = n > 1 ? (i / (n - 1)) * w : 0
      const y = h - ((v - min) / range) * (h - 4) - 2
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
  return (
    <svg
      className={className}
      viewBox={`0 0 ${w} ${h}`}
      preserveAspectRatio="none"
      style={{width: '100%', height}}
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <polyline points={points} />
    </svg>
  )
}

/** Horizontal proportion bar (error rate, resource usage). */
export function MiniBar({
  pct,
  tone,
  className,
}: {
  pct: number
  tone: Tone
  className?: string
}) {
  const clamped = Math.min(100, Math.max(0, pct))
  return (
    <span className={cn('relative block h-1.5 overflow-hidden rounded-full bg-muted', className)}>
      <span
        className={cn('absolute inset-y-0 left-0 rounded-full', toneBgSolid[tone])}
        style={{width: `${clamped}%`}}
      />
    </span>
  )
}
