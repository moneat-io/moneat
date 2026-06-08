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

import {Link} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {useState, useMemo} from 'react'
import {ArrowRight, Check, TrendingUp, AlertTriangle} from 'lucide-react'
import {api} from '@/lib/api'
import {type PricingCardTierInput} from '@/lib/pricing-display'
import {Button} from '@/components/ui/button'
import {cn} from '@/lib/utils'
import {GRADIENT_BG, GRADIENT_BAR, GRADIENT_TEXT, SectionHeading} from './Landing'

// ─── Constants ─────────────────────────────────────────────────────────────────

const BYTES_PER_GB = 1024 * 1024 * 1024

// ─── Log-scale slider helpers ───────────────────────────────────────────────────

function toLogValue(pos: number, logMin: number, max: number): number {
  if (pos <= 0) return 0
  if (pos >= 100) return max
  const minLog = Math.log(logMin)
  const maxLog = Math.log(max)
  return Math.round(Math.exp(minLog + (maxLog - minLog) * (pos / 100)))
}

function toLogPos(val: number, logMin: number, max: number): number {
  if (val <= 0) return 0
  if (val >= max) return 100
  const v = Math.max(val, logMin)
  const minLog = Math.log(logMin)
  const maxLog = Math.log(max)
  return Math.round(((Math.log(v) - minLog) / (maxLog - minLog)) * 100)
}

// ─── Formatting ─────────────────────────────────────────────────────────────────

function fmtCount(n: number): string {
  if (n >= 1_000_000) return `${+(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${+(n / 1_000).toFixed(1)}K`
  return n.toLocaleString()
}

function fmtMoney(n: number): string {
  if (n === 0) return '$0'
  const rounded = Math.round(n * 100) / 100
  if (Number.isInteger(rounded)) return `$${rounded}`
  return `$${rounded.toFixed(2)}`
}

// ─── Cost calculation ────────────────────────────────────────────────────────────

interface Usage {
  ingestGb: number
  pageViews: number
  customMetrics: number
}

interface OverageLine {
  label: string
  cost: number
}

interface PlanCost {
  base: number
  overageLines: OverageLine[]
  total: number
  exceedsFreeLimits: boolean
}

function calcCost(tier: PricingCardTierInput, usage: Usage): PlanCost {
  const isFree = tier.monthlyPriceCents === 0
  const base = tier.monthlyPriceCents / 100
  const ingestLimitGb = tier.monthlyGbLimit / BYTES_PER_GB

  if (isFree) {
    const pvLimit = tier.monthlyAnalyticsPageviewLimit ?? 0
    const metricLimit = tier.monthlyCustomMetricLimit ?? 0
    const exceedsFreeLimits =
      (ingestLimitGb > 0 && usage.ingestGb > ingestLimitGb) ||
      (pvLimit > 0 && usage.pageViews > pvLimit) ||
      (metricLimit > 0 && usage.customMetrics > metricLimit)
    return {base: 0, overageLines: [], total: 0, exceedsFreeLimits}
  }

  const overageLines: OverageLine[] = []

  // Unified ingestion overage (errors, logs, replays, AI events, APM spans all count toward GB)
  const ingestRate = tier.overageRateCentsPerGb ?? 0
  if (ingestRate > 0 && usage.ingestGb > ingestLimitGb) {
    const extraGb = usage.ingestGb - ingestLimitGb
    overageLines.push({label: 'Extra ingestion', cost: (extraGb * ingestRate) / 100})
  }

  // Page views
  const pvLimit = tier.monthlyAnalyticsPageviewLimit ?? 0
  const pvRate = tier.analyticsPageviewOverageRateCentsPer100k ?? 0
  if (pvRate > 0 && usage.pageViews > pvLimit) {
    const extra100k = Math.ceil((usage.pageViews - pvLimit) / 100_000)
    overageLines.push({label: 'Extra page views', cost: (extra100k * pvRate) / 100})
  }

  // Custom metrics
  const metricLimit = tier.monthlyCustomMetricLimit ?? 0
  const metricRate = tier.customMetricOverageRateCentsPer100k ?? 0
  if (metricRate > 0 && usage.customMetrics > metricLimit) {
    const extra100k = Math.ceil((usage.customMetrics - metricLimit) / 100_000)
    overageLines.push({label: 'Extra custom metrics', cost: (extra100k * metricRate) / 100})
  }

  const total = base + overageLines.reduce((s, l) => s + l.cost, 0)
  return {base, overageLines, total, exceedsFreeLimits: false}
}

// ─── Presets ─────────────────────────────────────────────────────────────────────

const PRESETS: {label: string; usage: Usage}[] = [
  {
    label: 'Startup',
    usage: {
      ingestGb: 5,
      pageViews: 100_000,
      customMetrics: 200_000,
    },
  },
  {
    label: 'Growing',
    usage: {
      ingestGb: 75,
      pageViews: 1_000_000,
      customMetrics: 1_000_000,
    },
  },
  {
    label: 'Scale',
    usage: {
      ingestGb: 500,
      pageViews: 5_000_000,
      customMetrics: 5_000_000,
    },
  },
]

// ─── SliderInput ──────────────────────────────────────────────────────────────────

interface SliderInputProps {
  readonly label: string
  readonly sublabel: string
  readonly value: number
  readonly max: number
  readonly logMin?: number
  readonly unit?: string
  readonly step?: number
  readonly onChange: (v: number) => void
}

function SliderInput({label, sublabel, value, max, logMin, unit, step = 1, onChange}: SliderInputProps) {
  const sliderPos = logMin != null ? toLogPos(value, logMin, max) : Math.round((value / max) * 100)

  const handleSlider = (pos: number) => {
    const v = logMin != null ? toLogValue(pos, logMin, max) : Math.round((pos / 100) * max)
    onChange(Math.max(0, Math.min(max, v)))
  }

  const maxLabel = unit ? `${max} ${unit}` : fmtCount(max)

  return (
    <div className="space-y-2.5">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-medium leading-tight text-slate-200">{label}</p>
          <p className="mt-0.5 text-xs text-slate-500">{sublabel}</p>
        </div>
        <div className="flex shrink-0 items-center gap-1.5">
          <input
            type="number"
            min={0}
            max={max}
            step={step}
            value={value}
            onChange={(e) => onChange(Math.max(0, Math.min(max, Number(e.target.value) || 0)))}
            className="w-28 rounded-md border border-white/10 bg-white/[0.03] px-3 py-1.5 text-right font-brandmono text-sm tabular-nums text-slate-100 focus:border-indigo-400 focus:outline-none focus:ring-2 focus:ring-indigo-400/20"
          />
          {unit && <span className="w-6 text-xs text-slate-500">{unit}</span>}
        </div>
      </div>
      <input
        type="range"
        min={0}
        max={100}
        value={sliderPos}
        onChange={(e) => handleSlider(Number(e.target.value))}
        className="h-1.5 w-full cursor-pointer rounded-full accent-indigo-400"
      />
      <div className="flex justify-between font-brandmono text-[10px] tabular-nums text-slate-600">
        <span>0</span>
        <span>{maxLabel}</span>
      </div>
    </div>
  )
}

// ─── PlanCard ─────────────────────────────────────────────────────────────────────

interface PlanCardProps {
  readonly tier: PricingCardTierInput
  readonly cost: PlanCost
  readonly isBest: boolean
}

function PlanCard({tier, cost, isBest}: PlanCardProps) {
  const name = tier.tierName.charAt(0) + tier.tierName.slice(1).toLowerCase()
  const isFree = tier.monthlyPriceCents === 0

  return (
    <div
      className={cn(
        'relative flex flex-col rounded-lg border bg-[#0c0e16] p-5',
        isBest ? 'border-indigo-400/40' : 'border-white/[0.08]',
      )}
    >
      {/* Best-match keyline */}
      {isBest ? <div className={cn('absolute inset-x-0 top-0 h-px rounded-t-lg', GRADIENT_BAR)} /> : null}

      {/* Violet glow */}
      {isBest ? (
        <div
          aria-hidden
          className="pointer-events-none absolute inset-0 rounded-lg"
          style={{
            background: 'radial-gradient(50% 60% at 50% 0%, rgba(99,102,241,0.13) 0%, transparent 100%)',
          }}
        />
      ) : null}

      {/* Best-match badge */}
      {isBest ? (
        <div className="absolute -top-3 left-1/2 z-10 -translate-x-1/2">
          <span
            className={cn(
              'inline-flex items-center whitespace-nowrap rounded-md px-3 py-0.5 font-brandmono text-[11px] font-semibold uppercase tracking-[0.12em] text-white',
              GRADIENT_BG,
            )}
          >
            Best match
          </span>
        </div>
      ) : null}

      {/* Plan name */}
      <p className="text-sm font-semibold text-white">{name}</p>

      {/* Price */}
      {isFree ? (
        <div className="mt-2">
          <span className="font-brandmono text-3xl font-bold text-white">$0</span>
          <span className="font-brandmono text-sm text-slate-500">/mo</span>
          <div className="mt-1.5 flex items-center gap-1">
            {cost.exceedsFreeLimits ? (
              <>
                <AlertTriangle className="size-3 text-amber-400" />
                <span className="font-brandmono text-xs text-amber-400">Upgrade needed</span>
              </>
            ) : (
              <>
                <Check className="size-3 text-emerald-400" />
                <span className="font-brandmono text-xs text-emerald-400">Fits your usage</span>
              </>
            )}
          </div>
        </div>
      ) : (
        <div className="mt-2">
          <span className={cn('font-brandmono text-3xl font-bold', isBest ? GRADIENT_TEXT : 'text-white')}>
            {fmtMoney(cost.total)}
          </span>
          <span className="font-brandmono text-sm text-slate-500">/mo</span>
        </div>
      )}

      {/* Cost breakdown */}
      {!isFree && (
        <div className="mt-3 flex-1 space-y-1.5 text-xs">
          <div className="flex justify-between text-slate-500">
            <span>Base plan</span>
            <span className="tabular-nums font-brandmono">{fmtMoney(cost.base)}</span>
          </div>
          {cost.overageLines.map((line) => (
            <div key={line.label} className="flex justify-between text-slate-500">
              <span className="flex items-center gap-1">
                <TrendingUp className="size-3 shrink-0" />
                {line.label}
              </span>
              <span className="tabular-nums font-brandmono">+{fmtMoney(line.cost)}</span>
            </div>
          ))}
          {cost.overageLines.length > 0 && (
            <div className="flex justify-between border-t border-white/[0.06] pt-2 font-semibold text-indigo-300 font-brandmono">
              <span>Total</span>
              <span className="tabular-nums">{fmtMoney(cost.total)}</span>
            </div>
          )}
          {cost.overageLines.length === 0 && (
            <div className="flex items-center gap-1.5 mt-1">
              <Check className="size-3 text-emerald-400" />
              <span className="text-slate-500">No overages</span>
            </div>
          )}
        </div>
      )}

      {/* CTA */}
      <div className="mt-4">
        {isBest ? (
          <Button
            asChild
            size="sm"
            className={cn('w-full border-0 text-white shadow-[0_8px_20px_-8px_#6366F1] hover:brightness-110', GRADIENT_BG)}
          >
            <Link to="/signup">{isFree ? 'Start Free' : 'Start Trial'}</Link>
          </Button>
        ) : (
          <Button
            asChild
            size="sm"
            variant="outline"
            className="w-full border-white/15 bg-white/[0.03] text-slate-100 hover:bg-white/[0.07] hover:text-white"
          >
            <Link to="/signup">{isFree ? 'Start Free' : 'Start Trial'}</Link>
          </Button>
        )}
      </div>
    </div>
  )
}

// ─── Main section ─────────────────────────────────────────────────────────────────

// Datadog pricing estimate — verified against datadoghq.com/pricing on Feb 27, 2026 (annual commitment rates)
const DATADOG_LOG_COST_PER_GB = 0.1 // log ingestion only; standard indexing is separate ($1.70/million events)

interface DatadogExtras {
  hosts: number
  apm: boolean
  profiling: boolean
  networkMonitoring: boolean
  dbm: boolean
}

// Per-host/mo costs sourced from Datadog public pricing (annual commitment), verified Feb 27, 2026
const DD_INFRA_PER_HOST = 15      // Infrastructure Pro
const DD_APM_PER_HOST = 31        // APM (with infra attached)
const DD_PROFILING_PER_HOST = 19  // Continuous Profiler (standalone)
const DD_NPM_PER_HOST = 5         // Cloud Network Monitoring
const DD_DBM_PER_DB_HOST = 70     // Database Monitoring — note: per database host, not infra host

function estimateDatadogCost(usage: Usage, extras: DatadogExtras): number {
  const logCost = usage.ingestGb * DATADOG_LOG_COST_PER_GB
  const hostCost =
    extras.hosts *
    (DD_INFRA_PER_HOST +
      (extras.apm ? DD_APM_PER_HOST : 0) +
      (extras.profiling ? DD_PROFILING_PER_HOST : 0) +
      (extras.networkMonitoring ? DD_NPM_PER_HOST : 0) +
      (extras.dbm ? DD_DBM_PER_DB_HOST : 0))
  return logCost + hostCost
}

export function PricingCalculatorSection({standalone = false}: {readonly standalone?: boolean}) {
  const [usage, setUsage] = useState<Usage>({
    ingestGb: 0,
    pageViews: 0,
    customMetrics: 0,
  })

  const [ddExtras, setDdExtras] = useState<DatadogExtras>({
    hosts: 0,
    apm: false,
    profiling: false,
    networkMonitoring: false,
    dbm: false,
  })

  const {data: billingPlans, isPending, isError} = useQuery({
    queryKey: ['billing-plans'],
    queryFn: () => api.getBillingPlans(),
  })

  const tiers: PricingCardTierInput[] = useMemo(
    () =>
      billingPlans?.plans
        .filter((p) => p.tier.tierName !== 'BUSINESS')
        .map((p) => ({
          ...p.tier,
          trialDays: p.trialDays ?? p.tier.trialDays,
        })) ?? [],
    [billingPlans],
  )

  const costs = useMemo(() => tiers.map((t) => calcCost(t, usage)), [tiers, usage])

  const bestPlanIdx = useMemo(() => {
    const paidIndexes = tiers
      .map((t, i) => ({t, i}))
      .filter(({t}) => t.monthlyPriceCents > 0)
    if (paidIndexes.length === 0) return -1
    return paidIndexes.reduce(
      (best, curr) => costs[curr.i].total < costs[best.i].total ? curr : best,
      paidIndexes[0],
    ).i
  }, [costs, tiers])

  const set = (key: keyof Usage) => (v: number) => setUsage((u) => ({...u, [key]: v}))

  const heading = standalone ? (
    <SectionHeading
      kicker="Pricing Calculator"
      title="See exactly what you'd pay."
      lead="Dial in your monthly usage and see your cost on each plan — including overages."
      align="left"
    />
  ) : (
    <div>
      <h2 className="mb-4 text-3xl font-bold tracking-[-0.035em] text-white sm:text-4xl">
        <Link
          to="/pricing-calculator"
          className="group inline-flex items-center gap-3 transition-colors hover:opacity-80"
        >
          Pricing Calculator
          <ArrowRight className="size-7 opacity-30 transition-transform group-hover:translate-x-1 group-hover:opacity-60" />
        </Link>
      </h2>
      <p className="text-base leading-7 text-slate-400">
        Dial in your monthly usage below and see exactly what you'd pay on each plan — including overages.
      </p>
    </div>
  )

  return (
    <section
      id="pricing-calculator"
      className={cn(
        'scroll-mt-24 px-4 py-24 sm:px-6 lg:px-8',
        standalone ? '' : 'border-t border-white/[0.06] bg-[#0a0b12]',
      )}
    >
      <div className="mx-auto max-w-7xl">
        <div className="mb-14 max-w-2xl">{heading}</div>

        {/* Quick presets */}
        <div className="mb-8">
          <p className="mb-3 font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">
            Quick presets
          </p>
          <div className="flex flex-wrap gap-2">
            {PRESETS.map((preset) => (
              <button
                key={preset.label}
                onClick={() => setUsage(preset.usage)}
                className="rounded-md border border-white/10 bg-white/[0.03] px-4 py-1.5 font-brandmono text-sm text-slate-400 transition-all hover:border-indigo-400/40 hover:bg-indigo-500/10 hover:text-indigo-300"
              >
                {preset.label}
              </button>
            ))}
          </div>
        </div>

        <div className="grid items-start gap-10 lg:grid-cols-5 xl:gap-16">
          {/* Left column — inputs */}
          <div className="space-y-6 lg:col-span-2">
            {/* Usage sliders */}
            <div className="space-y-8 rounded-lg border border-white/[0.08] bg-[#0c0e16] p-6">
              <SliderInput
                label="Ingestion per month"
                sublabel="Total data ingested: errors, logs, replays, AI events, APM spans"
                value={usage.ingestGb}
                max={2_000}
                logMin={0.5}
                unit="GB"
                step={1}
                onChange={set('ingestGb')}
              />
              <SliderInput
                label="Page views"
                sublabel="Monthly analytics page views across all sites"
                value={usage.pageViews}
                max={10_000_000}
                logMin={10_000}
                onChange={set('pageViews')}
              />
              <SliderInput
                label="Custom metrics"
                sublabel="Custom time-series metrics tracked per month"
                value={usage.customMetrics}
                max={50_000_000}
                logMin={10_000}
                onChange={set('customMetrics')}
              />
            </div>

            {/* Datadog surcharges panel */}
            <div className="space-y-5 rounded-lg border border-amber-400/20 bg-amber-400/[0.04] p-6">
              <div>
                <div className="flex items-center gap-2 mb-1">
                  <span className={cn('h-px w-4 rounded', GRADIENT_BAR)} />
                  <p className="font-brandmono text-[10px] uppercase tracking-[0.12em] text-amber-400/80">
                    Datadog surcharges
                  </p>
                </div>
                <p className="mt-1 text-xs leading-5 text-slate-500">
                  These don't add separate line items to your Moneat bill — no per-host fees. APM, profiling, and log data count toward your unified GB ingestion above.
                </p>
              </div>
              <SliderInput
                label="Hosts"
                sublabel={`$${DD_INFRA_PER_HOST}/host/mo on Datadog`}
                value={ddExtras.hosts}
                max={500}
                logMin={1}
                onChange={(v) => setDdExtras((e) => ({...e, hosts: v}))}
              />
              {ddExtras.hosts > 0 && (
                <div className="space-y-2 pt-1">
                  <p className="font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">
                    Datadog add-ons (per host)
                  </p>
                  {(
                    [
                      {key: 'apm', label: 'APM & distributed tracing', cost: DD_APM_PER_HOST},
                      {key: 'profiling', label: 'Continuous profiling', cost: DD_PROFILING_PER_HOST},
                      {key: 'networkMonitoring', label: 'Network performance monitoring', cost: DD_NPM_PER_HOST},
                      {key: 'dbm', label: 'Database monitoring ($70/db host)', cost: DD_DBM_PER_DB_HOST},
                    ] as const
                  ).map(({key, label, cost}) => (
                    <label key={key} className="group flex cursor-pointer items-center justify-between gap-3">
                      <span className="flex items-center gap-2 text-xs">
                        <input
                          type="checkbox"
                          checked={ddExtras[key]}
                          onChange={(e) => setDdExtras((ex) => ({...ex, [key]: e.target.checked}))}
                          className="cursor-pointer accent-amber-400"
                        />
                        <span className="text-slate-400 transition-colors group-hover:text-slate-200">{label}</span>
                      </span>
                      <span className="shrink-0 font-brandmono text-xs font-medium tabular-nums text-amber-400/80">
                        +${cost}/host/mo
                      </span>
                    </label>
                  ))}
                </div>
              )}
            </div>
          </div>

          {/* Right column — plan cards */}
          <div className="lg:col-span-3">
            {isPending && (
              <div className="grid grid-cols-2 gap-4">
                {Array.from({length: 4}, (_, i) => `loading-plan-${i}`).map((planId) => (
                  <div key={planId} className="rounded-lg border border-white/[0.08] bg-[#0c0e16] p-5">
                    <div className="h-4 w-16 animate-pulse rounded bg-white/[0.06]" />
                    <div className="mt-3 h-8 w-20 animate-pulse rounded bg-white/[0.06]" />
                    <div className="mt-4 space-y-2">
                      {Array.from({length: 3}, (_, j) => `loading-feature-${j}`).map((featureId) => (
                        <div key={featureId} className="h-3 w-full animate-pulse rounded bg-white/[0.06]" />
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}

            {!isPending && isError && (
              <div className="rounded-lg border border-rose-500/20 bg-rose-500/5 p-6">
                <p className="font-semibold text-rose-300">Unable to load pricing right now</p>
                <p className="mt-1 text-sm text-slate-500">Please refresh the page in a moment.</p>
              </div>
            )}

            {!isPending && !isError && tiers.length === 0 && (
              <div className="rounded-lg border border-white/[0.08] bg-[#0c0e16] p-6">
                <p className="font-semibold text-slate-300">Pricing unavailable</p>
                <p className="mt-1 text-sm text-slate-500">No plans are currently published.</p>
              </div>
            )}

            {!isPending && !isError && tiers.length > 0 && (
              <div className="space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  {tiers.map((tier, idx) => (
                    <div key={tier.tierName} className="relative">
                      <PlanCard tier={tier} cost={costs[idx]} isBest={idx === bestPlanIdx} />
                    </div>
                  ))}
                </div>

                {/* Datadog comparison banner */}
                {(() => {
                  const datadogEst = estimateDatadogCost(usage, ddExtras)
                  const freeIdx = tiers.findIndex((t) => t.tierName === 'FREE')
                  const freeFits = freeIdx >= 0 && costs[freeIdx] && !costs[freeIdx].exceedsFreeLimits
                  let displayMoneatCost: number | null
                  if (freeFits) {
                    displayMoneatCost = 0
                  } else if (bestPlanIdx >= 0) {
                    displayMoneatCost = costs[bestPlanIdx].total
                  } else {
                    displayMoneatCost = null
                  }
                  const hasUsage =
                    usage.ingestGb > 0 ||
                    usage.pageViews > 0 ||
                    usage.customMetrics > 0 ||
                    ddExtras.hosts > 0
                  if (!hasUsage || datadogEst < 10) return null
                  return (
                    <div className="rounded-lg border border-amber-400/20 bg-amber-400/[0.04] p-5">
                      <div className="flex items-center justify-between gap-4">
                        <div>
                          <p className="text-sm font-semibold text-slate-200">vs Datadog (estimated)</p>
                          <p className="mt-0.5 text-xs leading-5 text-slate-500">
                            Same usage would cost ~{fmtMoney(datadogEst)}/mo on Datadog (log ingest at $0.10/GB; indexing &amp; retention billed separately)
                          </p>
                        </div>
                        {displayMoneatCost !== null && datadogEst > 0 && (
                          <div className="shrink-0 text-right">
                            <p className={cn('font-brandmono text-2xl font-bold', GRADIENT_TEXT)}>
                              Save ~{Math.round((1 - displayMoneatCost / datadogEst) * 100)}%
                            </p>
                            <p className="font-brandmono text-xs text-slate-500">with Moneat</p>
                          </div>
                        )}
                      </div>
                    </div>
                  )
                })()}
              </div>
            )}

            {!isPending && tiers.length > 0 && (
              <p className="mt-6 text-center font-brandmono text-[11px] leading-5 text-slate-600">
                Estimates are based on monthly billing. Unlimited team members on every plan.<br />
                Datadog prices verified Feb 27, 2026 from datadoghq.com/pricing (annual commitment rates). Estimates only — actual costs vary by configuration.
              </p>
            )}
          </div>
        </div>
      </div>
    </section>
  )
}
