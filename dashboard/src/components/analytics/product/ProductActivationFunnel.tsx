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
import {Check, ChevronRight, Filter, LogIn, MousePointerClick, Plus, Star, X} from 'lucide-react'
import type {LucideIcon} from 'lucide-react'

import {SectionCard} from '@/components/ui/section-card'
import {cn} from '@/lib/utils'
import {api} from '@/lib/api'
import type {AnalyticsFunnelResponse, AnalyticsParams, AnalyticsScopeId} from '@/lib/api'

interface FunnelStep {
  id: string
  name: string
}

const DEFAULT_STEPS: readonly FunnelStep[] = [
  {id: 'signup-completed', name: 'signup_completed'},
  {id: 'onboarding-completed', name: 'onboarding_completed'},
  {id: 'first-key-action', name: 'first_key_action'},
  {id: 'activated', name: 'activated'},
]

let customStepSequence = 0

function createCustomStep(): FunnelStep {
  customStepSequence += 1
  return {id: `custom-step-${customStepSequence}`, name: ''}
}

function stepIcon(index: number, total: number): LucideIcon {
  if (index === 0) return LogIn
  if (index === total - 1) return Star
  if (index === 1) return Check
  return MousePointerClick
}

interface FunnelRow {
  name: string
  visitors: number
  pct: number
  dropPct: number
  lost: number
}

function toRows(data: AnalyticsFunnelResponse): FunnelRow[] {
  const first = data.steps[0]?.visitors ?? 0
  return data.steps.map((step, index) => {
    const pct = first > 0 ? (step.visitors / first) * 100 : 0
    const prev = data.steps[index - 1]
    const prevPct = prev && first > 0 ? (prev.visitors / first) * 100 : pct
    return {
      name: step.name,
      visitors: step.visitors,
      pct,
      dropPct: index === 0 ? 0 : prevPct - pct,
      lost: index === 0 ? 0 : (prev?.visitors ?? 0) - step.visitors,
    }
  })
}

function biggestDropIndex(rows: FunnelRow[]): number {
  let maxIndex = -1
  let maxDrop = 0
  rows.forEach((row, index) => {
    if (index > 0 && row.dropPct > maxDrop) {
      maxDrop = row.dropPct
      maxIndex = index
    }
  })
  return maxIndex
}

function StepEditor({
  steps,
  onChange,
}: Readonly<{steps: FunnelStep[]; onChange: (steps: FunnelStep[]) => void}>) {
  const update = (id: string, value: string) =>
    onChange(steps.map((step) => (step.id === id ? {...step, name: value} : step)))
  const remove = (id: string) => onChange(steps.filter((step) => step.id !== id))

  return (
    <div className="mb-3 flex flex-wrap items-center gap-1.5">
      {steps.map((step, index) => (
        <div key={step.id} className="flex items-center gap-1.5">
          {index > 0 && <ChevronRight className="h-3 w-3 text-muted-foreground/60" />}
          <span className="inline-flex h-6 items-center gap-1.5 rounded border bg-muted/60 pl-2 pr-1">
            <span className="grid h-3.5 w-3.5 place-items-center rounded-[3px] bg-primary/15 text-[9px] font-bold text-primary">
              {index + 1}
            </span>
            <input
              value={step.name}
              size={Math.max(step.name.length, 6)}
              onChange={(event) => update(step.id, event.target.value)}
              aria-label={`Funnel step ${index + 1}`}
              className="border-0 bg-transparent p-0 font-mono text-xs text-foreground outline-none placeholder:text-muted-foreground"
              placeholder="event.name"
            />
            <button
              type="button"
              onClick={() => remove(step.id)}
              disabled={steps.length <= 2}
              aria-label="Remove step"
              className="grid h-4 w-4 place-items-center rounded text-muted-foreground hover:text-danger-fg disabled:opacity-30"
            >
              <X className="h-3 w-3" />
            </button>
          </span>
        </div>
      ))}
      <button
        type="button"
        onClick={() => onChange([...steps, createCustomStep()])}
        className="inline-flex h-6 items-center gap-1 rounded px-2 text-xs font-medium text-muted-foreground hover:bg-muted hover:text-foreground"
      >
        <Plus className="h-3 w-3" />
        Step
      </button>
    </div>
  )
}

function FunnelBars({data}: Readonly<{data: AnalyticsFunnelResponse}>) {
  const rows = toRows(data)
  const leakIndex = biggestDropIndex(rows)

  return (
    <div className="flex flex-col gap-2.5">
      {rows.map((row, index) => {
        const Icon = stepIcon(index, rows.length)
        const isLast = index === rows.length - 1
        const barPct = Math.min(100, Math.max(0, row.pct, row.visitors > 0 ? 2 : 0))
        const isGain = row.dropPct < 0
        const dropLabel = Math.abs(row.dropPct).toFixed(0)
        const participantDelta = Math.abs(row.lost).toLocaleString()
        const movementLabel = isGain ? 'gain' : 'drop'
        const movementSign = isGain ? '+' : '−'
        const movementClass = isGain ? 'text-success-fg' : 'text-danger-fg'
        const movementVerb = isGain ? 'added after' : 'left after'
        const previousStep = rows[index - 1]?.name ?? 'the previous step'
        const movementDetail =
          index === leakIndex ? ' · biggest drop-off in the path' : ` · ${participantDelta} ${movementVerb} ${previousStep}`
        return (
          <div key={`${row.name}:${row.visitors}:${row.pct}`}>
            <div className="mb-1 flex items-center gap-2 text-sm">
              <span className="flex items-center gap-2 font-medium">
                <span
                  className={cn(
                    'grid h-5 w-5 place-items-center rounded',
                    isLast ? 'bg-primary/15 text-primary' : 'bg-muted text-muted-foreground',
                  )}
                >
                  <Icon className="h-3 w-3" />
                </span>
                <span className="font-mono text-xs">{row.name || '—'}</span>
                {isLast && (
                  <span className="inline-flex h-[17px] items-center rounded border bg-muted px-1.5 font-mono text-[10px] text-muted-foreground">
                    habit
                  </span>
                )}
              </span>
              <span className="ml-auto font-mono text-xs tabular-nums text-muted-foreground">
                <span className="font-semibold text-foreground">{row.visitors.toLocaleString()}</span> · {row.pct.toFixed(0)}%
              </span>
            </div>
            <div className="relative h-[26px] overflow-hidden rounded bg-muted">
              <div
                className="absolute inset-y-0 left-0 rounded bg-gradient-to-r from-primary to-primary/65"
                style={{width: `${barPct}%`}}
              />
              <span className="absolute right-2 top-1/2 -translate-y-1/2 font-mono text-xs font-semibold tabular-nums">
                {row.pct.toFixed(0)}%
              </span>
            </div>
            {index > 0 && (
              <div className="flex items-center gap-1.5 py-1 pl-[30px] text-[11px] text-muted-foreground">
                {movementLabel}{' '}
                <span className={cn('font-mono font-semibold', movementClass)}>
                  {movementSign}{dropLabel}%
                </span>
                {movementDetail}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}

function ProductActivationFunnelContent({
  validStepCount,
  data,
  isLoading,
}: Readonly<{validStepCount: number; data?: AnalyticsFunnelResponse; isLoading: boolean}>) {
  if (validStepCount < 2) {
    return <p className="py-6 text-center text-xs text-muted-foreground">Add at least two steps</p>
  }
  if (isLoading) {
    return <div className="h-[180px] w-full animate-pulse rounded bg-muted" />
  }
  if (data && data.steps.length > 0) {
    return <FunnelBars data={data} />
  }
  return <p className="py-6 text-center text-xs text-muted-foreground">No funnel data for these events</p>
}

export function ProductActivationFunnel({
  scopeId,
  params,
}: Readonly<{scopeId: AnalyticsScopeId; params: AnalyticsParams}>) {
  const [steps, setSteps] = useState<FunnelStep[]>(() => DEFAULT_STEPS.map((step) => ({...step})))
  const validSteps = steps.map((step) => step.name.trim()).filter(Boolean)

  const {data, isLoading} = useQuery({
    queryKey: ['product-funnel', scopeId, params, validSteps],
    queryFn: () => api.getAnalyticsFunnel(scopeId, validSteps, params, {source: 'server', groupBy: 'user_id'}),
    enabled: validSteps.length >= 2,
  })

  const overall = data?.overallConversion == null ? '—' : `${data.overallConversion.toFixed(1)}%`

  return (
    <SectionCard
      title="Activation funnel"
      icon={Filter}
      actions={
        <span className="text-xs font-normal text-muted-foreground">
          overall <span className="font-semibold text-foreground tabular-nums">{overall}</span>
        </span>
      }
    >
      <StepEditor steps={steps} onChange={setSteps} />
      <ProductActivationFunnelContent validStepCount={validSteps.length} data={data} isLoading={isLoading} />
    </SectionCard>
  )
}
