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

import {Activity} from 'lucide-react'

import {SectionCard} from '@/components/ui/section-card'
import {cn} from '@/lib/utils'
import type {ProductEventTrend} from '@/lib/api'

import {formatSignedRatio} from './format'

const EVENT_TREND_SKELETON_KEYS = [
  'event-trend-1',
  'event-trend-2',
  'event-trend-3',
  'event-trend-4',
  'event-trend-5',
  'event-trend-6',
] as const

function deltaClassName(ratio: number): string {
  if (ratio > 0) return 'text-success-fg'
  if (ratio < 0) return 'text-danger-fg'
  return 'text-muted-foreground'
}

function DeltaCell({ratio}: Readonly<{ratio?: number}>) {
  if (ratio == null) return <span className="text-muted-foreground">—</span>
  return (
    <span className={cn('font-mono font-semibold', deltaClassName(ratio))}>
      {formatSignedRatio(ratio)}
    </span>
  )
}

function ProductEventTrendsBody({data, isLoading}: Readonly<{data?: ProductEventTrend[]; isLoading?: boolean}>) {
  if (isLoading) {
    return (
      <div className="space-y-1.5 p-3">
        {EVENT_TREND_SKELETON_KEYS.map((key) => (
          <div key={key} className="h-7 w-full animate-pulse rounded bg-muted" />
        ))}
      </div>
    )
  }
  if (data == null || data.length === 0) {
    return <p className="px-3 py-8 text-center text-xs text-muted-foreground">No events for the selected period</p>
  }
  return (
    <table className="w-full text-sm">
      <thead>
        <tr className="border-b bg-muted/40 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
          <th className="px-3 py-1.5 text-left font-semibold">Event</th>
          <th className="px-3 py-1.5 text-right font-semibold">Count</th>
          <th className="px-3 py-1.5 text-right font-semibold">Users</th>
          <th className="px-3 py-1.5 text-right font-semibold">Δ</th>
        </tr>
      </thead>
      <tbody>
        {data.map((event) => (
          <tr key={event.name} className="border-b last:border-b-0 hover:bg-muted/40">
            <td className="px-3 py-2 font-mono text-xs">{event.name}</td>
            <td className="px-3 py-2 text-right font-mono tabular-nums">{event.count.toLocaleString()}</td>
            <td className="px-3 py-2 text-right font-mono tabular-nums">{event.users.toLocaleString()}</td>
            <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">
              <DeltaCell ratio={event.changeRatio} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export function ProductEventTrends({
  data,
  isLoading,
}: Readonly<{data?: ProductEventTrend[]; isLoading?: boolean}>) {
  return (
    <SectionCard title="Key event trends" icon={Activity} flushBody>
      <ProductEventTrendsBody data={data} isLoading={isLoading} />
    </SectionCard>
  )
}
