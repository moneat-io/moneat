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

import {Activity, ArrowDown, ArrowUp} from 'lucide-react'

import {SectionCard} from '@/components/ui/section-card'
import {cn} from '@/lib/utils'
import type {ProductMover} from '@/lib/api'

const MOVER_SKELETON_KEYS = ['mover-1', 'mover-2', 'mover-3', 'mover-4', 'mover-5'] as const

function MoverRow({mover}: Readonly<{mover: ProductMover}>) {
  const good = mover.tone === 'good'
  return (
    <div className="flex items-center gap-2.5 border-t px-3 py-2 first:border-t-0">
      <span className={cn('w-[3px] shrink-0 self-stretch rounded', good ? 'bg-success-solid' : 'bg-danger-solid')} />
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium">{mover.name}</div>
        <div className="mt-0.5 flex items-center gap-1.5 text-[11px] text-muted-foreground">
          <span className="inline-flex h-[17px] items-center rounded border bg-muted px-1.5 font-mono text-[10px]">
            {mover.category}
          </span>
          {mover.detail && <span className="truncate">{mover.detail}</span>}
        </div>
      </div>
      <span
        className={cn(
          'inline-flex shrink-0 items-center gap-1 font-mono text-sm font-semibold tabular-nums',
          good ? 'text-success-fg' : 'text-danger-fg',
        )}
      >
        {good ? <ArrowUp className="h-3 w-3" /> : <ArrowDown className="h-3 w-3" />}
        {mover.change}
      </span>
    </div>
  )
}

function ProductMoversBody({data, isLoading}: Readonly<{data?: ProductMover[]; isLoading?: boolean}>) {
  if (isLoading) {
    return (
      <div className="space-y-2 p-3">
        {MOVER_SKELETON_KEYS.map((key) => (
          <div key={key} className="h-8 w-full animate-pulse rounded bg-muted" />
        ))}
      </div>
    )
  }
  if (data == null || data.length === 0) {
    return <p className="px-3 py-8 text-center text-xs text-muted-foreground">No notable changes this period</p>
  }
  return (
    <div className="flex flex-col">
      {data.map((mover) => (
        <MoverRow key={`${mover.category}:${mover.name}`} mover={mover} />
      ))}
    </div>
  )
}

export function ProductMovers({data, isLoading}: Readonly<{data?: ProductMover[]; isLoading?: boolean}>) {
  return (
    <SectionCard
      title="What changed"
      icon={Activity}
      flushBody
      actions={<span className="text-xs font-normal text-muted-foreground">biggest movers</span>}
    >
      <ProductMoversBody data={data} isLoading={isLoading} />
    </SectionCard>
  )
}
