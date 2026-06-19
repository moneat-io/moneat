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
import {useQuery} from '@tanstack/react-query'
import {Repeat} from 'lucide-react'

import {SectionCard} from '@/components/ui/section-card'
import {Input} from '@/components/ui/input'
import {cn} from '@/lib/utils'
import {api} from '@/lib/api'
import type {AnalyticsParams, AnalyticsScopeId, ProductRetentionMode} from '@/lib/api'

const MODE_OPTIONS: ReadonlyArray<{value: ProductRetentionMode; label: string}> = [
  {value: 'key_action', label: 'Key action'},
  {value: 'any_session', label: 'Any session'},
  {value: 'custom', label: 'Custom event'},
]

const RETENTION_COLUMNS = 7

function HeatCell({value}: Readonly<{value: number | null}>) {
  if (value == null) {
    return (
      <td className="h-[30px] rounded bg-muted text-center font-mono text-xs text-muted-foreground">—</td>
    )
  }
  const alpha = (0.1 + (value / 100) * 0.72).toFixed(2)
  return (
    <td
      className={cn('h-[30px] rounded text-center font-mono text-xs font-medium tabular-nums', value >= 60 ? 'text-[#04212f]' : 'text-foreground')}
      style={{backgroundColor: `hsl(var(--chart-1) / ${alpha})`}}
    >
      {value}
    </td>
  )
}

function ModeToggle({
  value,
  onChange,
}: Readonly<{value: ProductRetentionMode; onChange: (value: ProductRetentionMode) => void}>) {
  return (
    <div className="inline-flex gap-0.5 rounded-md border bg-muted/40 p-0.5">
      {MODE_OPTIONS.map((option) => (
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

type ProductRetentionData = Awaited<ReturnType<typeof api.getProductRetention>>

function ProductRetentionContent({
  awaitingCustomEvent,
  data,
  isLoading,
  periods,
}: Readonly<{
  awaitingCustomEvent: boolean
  data?: ProductRetentionData
  isLoading: boolean
  periods: number[]
}>) {
  if (awaitingCustomEvent) {
    return (
      <p className="py-10 text-center text-xs text-muted-foreground">
        Enter a custom event name to see retention for it.
      </p>
    )
  }
  if (isLoading) {
    return <div className="h-[200px] w-full animate-pulse rounded bg-muted" />
  }
  if (data == null || data.cohorts.length === 0) {
    return <p className="py-10 text-center text-xs text-muted-foreground">No cohort data for the selected period</p>
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-separate border-spacing-[3px] text-xs">
        <thead>
          <tr className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
            <th className="px-1 py-1 text-left">Weekly cohort</th>
            <th className="px-1 py-1 text-right">Users</th>
            {periods.map((period) => (
              <th key={period} className="px-1 py-1 text-center">W{period}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.cohorts.map((cohort) => (
            <tr key={cohort.cohort}>
              <td className="whitespace-nowrap px-2 font-medium text-foreground">{cohort.cohort}</td>
              <td className="px-2 text-right font-mono tabular-nums text-muted-foreground">
                {cohort.users.toLocaleString()}
              </td>
              {periods.map((period, index) => (
                <HeatCell key={period} value={cohort.values[index] ?? null} />
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function ProductRetentionCohort({
  scopeId,
  params,
}: Readonly<{scopeId: AnalyticsScopeId; params: AnalyticsParams}>) {
  const [mode, setMode] = useState<ProductRetentionMode>('key_action')
  // Draft mirrors the input; the committed value drives the query so we don't
  // refetch on every keystroke.
  const [customDraft, setCustomDraft] = useState('')
  const [customEvent, setCustomEvent] = useState('')
  const trimmedEvent = customEvent.trim()
  const awaitingCustomEvent = mode === 'custom' && trimmedEvent.length === 0
  const commitCustomEvent = () => setCustomEvent(customDraft.trim())

  const {data, isLoading} = useQuery({
    queryKey: ['product-retention', scopeId, params, mode, mode === 'custom' ? trimmedEvent : ''],
    queryFn: () =>
      api.getProductRetention(scopeId, {
        ...params,
        mode,
        periods: RETENTION_COLUMNS,
        ...(mode === 'custom' ? {customEvent: trimmedEvent} : {}),
      }),
    enabled: !awaitingCustomEvent,
    retry: false,
  })

  const periods = data?.periods ?? Array.from({length: RETENTION_COLUMNS}, (_, index) => index)

  return (
    <SectionCard
      title="Retention cohorts"
      icon={Repeat}
      actions={
        <div className="flex items-center gap-2">
          <span className="hidden text-xs text-muted-foreground sm:inline">return event</span>
          <ModeToggle value={mode} onChange={setMode} />
          {mode === 'custom' && (
            <form
              onSubmit={(event) => {
                event.preventDefault()
                commitCustomEvent()
              }}
            >
              <Input
                value={customDraft}
                onChange={(event) => setCustomDraft(event.target.value)}
                onBlur={commitCustomEvent}
                placeholder="event.name"
                aria-label="Custom retention event"
                className="h-7 w-44 px-2 font-mono text-xs"
              />
            </form>
          )}
        </div>
      }
    >
      <ProductRetentionContent
        awaitingCustomEvent={awaitingCustomEvent}
        data={data}
        isLoading={isLoading}
        periods={periods}
      />
    </SectionCard>
  )
}
