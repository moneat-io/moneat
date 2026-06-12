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

import {useMemo} from 'react'
import {Tag} from 'lucide-react'

import {cn} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {StatusDot, type StatusTone} from '@/components/ui/status-dot'
import {
  HEALTH_BADGE,
  HEALTH_LABEL,
  HEALTH_TONE,
  KIND_META,
  TONE_BAR,
  TONE_TEXT,
  VULN_BAR,
  VULN_SEVERITIES,
  sparkline,
  totalVulns,
  type Health,
  type ResourceKind,
  type VulnCounts,
} from './resourceCatalogData'

/** A compact inline trend sparkline (deterministic from its seed). */
export function Sparkline({seed, tone = 'accent', className}: {readonly seed: string; readonly tone?: StatusTone; readonly className?: string}) {
  const data = useMemo(() => sparkline(seed), [seed])
  const width = 100
  const height = 28
  const max = Math.max(...data)
  const min = Math.min(...data)
  const span = Math.max(1, max - min)
  const step = width / (data.length - 1)
  const line = data
    .map((v, i) => `${(i * step).toFixed(1)},${(height - 2 - ((v - min) / span) * (height - 4)).toFixed(1)}`)
    .join(' ')
  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      className={cn('block', TONE_TEXT[tone], className)}
      aria-hidden="true"
    >
      <polyline points={`0,${height} ${line} ${width},${height}`} fill="currentColor" fillOpacity={0.12} stroke="none" />
      <polyline
        points={line}
        fill="none"
        stroke="currentColor"
        strokeWidth={1.5}
        strokeLinejoin="round"
        strokeLinecap="round"
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  )
}

/** A thin utilization meter. */
export function Meter({value, tone}: {readonly value: number; readonly tone: StatusTone}) {
  return (
    <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
      <div className={cn('h-full rounded-full', TONE_BAR[tone])} style={{width: `${Math.min(100, Math.max(0, value))}%`}} />
    </div>
  )
}

/** Stacked severity bar for a resource's vulnerability counts. */
export function VulnBar({vulns}: {readonly vulns: VulnCounts}) {
  const total = totalVulns(vulns)
  if (total === 0) return <div className="h-1.5 w-full rounded-full bg-success-solid/25" />
  return (
    <div className="flex h-1.5 w-full overflow-hidden rounded-full bg-muted">
      {VULN_SEVERITIES.map((s) =>
        vulns[s] > 0 ? <div key={s} className={cn('h-full', VULN_BAR[s])} style={{width: `${(vulns[s] / total) * 100}%`}} /> : null,
      )}
    </div>
  )
}

/** The lucide glyph for a resource kind. */
export function KindIcon({kind, className}: {readonly kind: ResourceKind; readonly className?: string}) {
  const Icon = KIND_META[kind].icon
  return <Icon className={cn('h-4 w-4 text-muted-foreground', className)} />
}

/**
 * Health pill in the shared status language. Soft variants get a leading status
 * dot; solid variants encode the status in their fill, so the dot is dropped to
 * keep the label centered (a same-tone dot would just vanish into the fill).
 */
export function HealthBadge({health}: {readonly health: Health}) {
  const variant = HEALTH_BADGE[health]
  const solid = variant.endsWith('Solid')
  return (
    <Badge variant={variant} className="gap-1 px-1.5 py-0 text-[10px] leading-4">
      {!solid && <StatusDot tone={HEALTH_TONE[health]} size="sm" />}
      {HEALTH_LABEL[health]}
    </Badge>
  )
}

/** A small tag chip. */
export function TagChip({label}: {readonly label: string}) {
  return (
    <span className="inline-flex items-center gap-1 rounded border border-border bg-muted/60 px-1.5 py-0 text-[10px] leading-4 text-muted-foreground">
      <Tag className="h-2.5 w-2.5" />
      {label}
    </span>
  )
}
