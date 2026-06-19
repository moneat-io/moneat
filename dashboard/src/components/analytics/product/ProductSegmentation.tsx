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

import {useState} from 'react'
import {Users} from 'lucide-react'

import {SectionCard} from '@/components/ui/section-card'
import {cn} from '@/lib/utils'
import type {ProductSegmentation as ProductSegmentationData, ProductSegmentDimension, ProductSegmentRow} from '@/lib/api'

const DIMENSIONS: ReadonlyArray<{value: ProductSegmentDimension; label: string}> = [
  {value: 'plan', label: 'Plan'},
  {value: 'platform', label: 'Platform'},
  {value: 'country', label: 'Country'},
]

const SEGMENTATION_SKELETON_KEYS = ['segment-1', 'segment-2', 'segment-3'] as const

function DimensionToggle({
  value,
  onChange,
}: Readonly<{value: ProductSegmentDimension; onChange: (value: ProductSegmentDimension) => void}>) {
  return (
    <div className="inline-flex gap-0.5 rounded-md border bg-muted/40 p-0.5">
      {DIMENSIONS.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={option.value === value}
          onClick={() => onChange(option.value)}
          className={cn(
            'rounded-sm px-2 py-1 text-xs font-medium transition-colors',
            option.value === value ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

function SegmentTable({label, rows}: Readonly<{label: string; rows: ProductSegmentRow[]}>) {
  if (rows.length === 0) {
    return <p className="px-3 py-8 text-center text-xs text-muted-foreground">No data for this dimension</p>
  }
  return (
    <table className="w-full text-sm">
      <thead>
        <tr className="border-b bg-muted/40 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
          <th className="px-3 py-1.5 text-left font-semibold">{label}</th>
          <th className="px-3 py-1.5 text-right font-semibold">Users</th>
          <th className="px-3 py-1.5 text-right font-semibold">Activation</th>
          <th className="px-3 py-1.5 text-right font-semibold">W1 retention</th>
          <th className="px-3 py-1.5 text-right font-semibold">Stickiness</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.name} className="border-b last:border-b-0 hover:bg-muted/40">
            <td className="px-3 py-2 font-medium">{row.name}</td>
            <td className="px-3 py-2 text-right font-mono tabular-nums">{row.users.toLocaleString()}</td>
            <td className="px-3 py-2 text-right font-mono tabular-nums">{row.activationRate.toFixed(0)}%</td>
            <td className="px-3 py-2 text-right font-mono tabular-nums">{row.week1Retention.toFixed(0)}%</td>
            <td className="px-3 py-2 text-right font-mono tabular-nums">{row.stickiness.toFixed(0)}%</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function ProductSegmentationBody({
  data,
  dimension,
  isLoading,
  label,
}: Readonly<{
  data?: ProductSegmentationData
  dimension: ProductSegmentDimension
  isLoading?: boolean
  label: string
}>) {
  if (isLoading) {
    return (
      <div className="space-y-1.5 p-3">
        {SEGMENTATION_SKELETON_KEYS.map((key) => (
          <div key={key} className="h-8 w-full animate-pulse rounded bg-muted" />
        ))}
      </div>
    )
  }
  if (data == null) {
    return <p className="px-3 py-8 text-center text-xs text-muted-foreground">No segmentation data</p>
  }
  return <SegmentTable label={label} rows={data[dimension]} />
}

export function ProductSegmentation({
  data,
  isLoading,
}: Readonly<{data?: ProductSegmentationData; isLoading?: boolean}>) {
  const [dimension, setDimension] = useState<ProductSegmentDimension>('plan')
  const label = DIMENSIONS.find((option) => option.value === dimension)?.label ?? 'Segment'

  return (
    <SectionCard
      title="Segmentation"
      icon={Users}
      flushBody
      actions={<DimensionToggle value={dimension} onChange={setDimension} />}
    >
      <ProductSegmentationBody data={data} dimension={dimension} isLoading={isLoading} label={label} />
    </SectionCard>
  )
}
