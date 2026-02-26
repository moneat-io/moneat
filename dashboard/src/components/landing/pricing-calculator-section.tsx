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
import {ArrowRight, Check, TrendingUp, Sparkles, AlertTriangle} from 'lucide-react'
import {api} from '@/lib/api'
import {type PricingCardTierInput} from '@/lib/pricing-display'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle, CardDescription} from '@/components/ui/card'

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
  errors: number
  logGb: number
  aiEvents: number
  pageViews: number
  apmSpans: number
  customMetrics: number
  hosts: number
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

  if (isFree) {
    const errorLimit = tier.monthlyErrorLimit ?? 0
    const logLimitGb = tier.monthlyGbLimit / BYTES_PER_GB
    const aiLimit = tier.monthlyLlmEventLimit ?? 0
    const pvLimit = tier.monthlyAnalyticsPageviewLimit ?? 0
    const apmLimit = tier.monthlyApmSpanLimit ?? 0
    const metricLimit = tier.monthlyCustomMetricLimit ?? 0
    const hostLimit = tier.maxHosts ?? 0
    const exceedsFreeLimits =
      (errorLimit > 0 && usage.errors > errorLimit) ||
      (logLimitGb > 0 && usage.logGb > logLimitGb) ||
      (aiLimit > 0 && usage.aiEvents > aiLimit) ||
      (pvLimit > 0 && usage.pageViews > pvLimit) ||
      (apmLimit > 0 && usage.apmSpans > apmLimit) ||
      (metricLimit > 0 && usage.customMetrics > metricLimit) ||
      (hostLimit > 0 && usage.hosts > hostLimit)
    return {base: 0, overageLines: [], total: 0, exceedsFreeLimits}
  }

  const overageLines: OverageLine[] = []

  // Errors
  const errorLimit = tier.monthlyErrorLimit ?? 0
  const errRate = tier.errorOverageRateCentsPer1k ?? 0
  if (errRate > 0 && usage.errors > errorLimit) {
    const extraK = Math.ceil((usage.errors - errorLimit) / 1000)
    overageLines.push({label: 'Extra errors', cost: (extraK * errRate) / 100})
  }

  // Log data
  const logLimitGb = tier.monthlyGbLimit / BYTES_PER_GB
  const logRate = tier.overageRateCentsPerGb ?? 0
  if (logRate > 0 && usage.logGb > logLimitGb) {
    const extraGb = usage.logGb - logLimitGb
    overageLines.push({label: 'Extra log data', cost: (extraGb * logRate) / 100})
  }

  // AI events
  const aiLimit = tier.monthlyLlmEventLimit ?? 0
  const aiRate = tier.llmOverageRateCentsPer1k ?? 0
  if (aiRate > 0 && usage.aiEvents > aiLimit) {
    const extraK = Math.ceil((usage.aiEvents - aiLimit) / 1000)
    overageLines.push({label: 'Extra AI events', cost: (extraK * aiRate) / 100})
  }

  // Page views
  const pvLimit = tier.monthlyAnalyticsPageviewLimit ?? 0
  const pvRate = tier.analyticsPageviewOverageRateCentsPer100k ?? 0
  if (pvRate > 0 && usage.pageViews > pvLimit) {
    const extra100k = Math.ceil((usage.pageViews - pvLimit) / 100_000)
    overageLines.push({label: 'Extra page views', cost: (extra100k * pvRate) / 100})
  }

  // APM spans
  const apmLimit = tier.monthlyApmSpanLimit ?? 0
  const apmRate = tier.apmSpanOverageRateCentsPer1m ?? 0
  if (apmRate > 0 && usage.apmSpans > apmLimit) {
    const extraM = Math.ceil((usage.apmSpans - apmLimit) / 1_000_000)
    overageLines.push({label: 'Extra APM spans', cost: (extraM * apmRate) / 100})
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
      errors: 10_000,
      logGb: 2,
      aiEvents: 5_000,
      pageViews: 100_000,
      apmSpans: 1_000_000,
      customMetrics: 200_000,
      hosts: 5,
    },
  },
  {
    label: 'Growing',
    usage: {
      errors: 150_000,
      logGb: 10,
      aiEvents: 50_000,
      pageViews: 1_000_000,
      apmSpans: 10_000_000,
      customMetrics: 1_000_000,
      hosts: 20,
    },
  },
  {
    label: 'Scale',
    usage: {
      errors: 1_000_000,
      logGb: 50,
      aiEvents: 250_000,
      pageViews: 5_000_000,
      apmSpans: 50_000_000,
      customMetrics: 5_000_000,
      hosts: 50,
    },
  },
]

// ─── SliderInput ──────────────────────────────────────────────────────────────────

interface SliderInputProps {
  label: string
  sublabel: string
  value: number
  max: number
  logMin?: number
  unit?: string
  step?: number
  onChange: (v: number) => void
}

function SliderInput({label, sublabel, value, max, logMin, unit, step = 1, onChange}: SliderInputProps) {
  const isLog = logMin != null
  const sliderPos = isLog ? toLogPos(value, logMin!, max) : Math.round((value / max) * 100)

  const handleSlider = (pos: number) => {
    const v = isLog ? toLogValue(pos, logMin!, max) : Math.round((pos / 100) * max)
    onChange(Math.max(0, Math.min(max, v)))
  }

  const maxLabel = unit ? `${max} ${unit}` : fmtCount(max)

  return (
    <div className="space-y-2.5">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-medium leading-tight">{label}</p>
          <p className="text-xs text-muted-foreground mt-0.5">{sublabel}</p>
        </div>
        <div className="flex items-center gap-1.5 shrink-0">
          <input
            type="number"
            min={0}
            max={max}
            step={step}
            value={value}
            onChange={(e) => onChange(Math.max(0, Math.min(max, Number(e.target.value) || 0)))}
            className="w-28 rounded-md border border-border/60 bg-background px-3 py-1.5 text-right text-sm font-mono tabular-nums focus:outline-none focus:ring-2 focus:ring-sky-500/30 focus:border-sky-500/50"
          />
          {unit && <span className="text-xs text-muted-foreground w-6">{unit}</span>}
        </div>
      </div>
      <input
        type="range"
        min={0}
        max={100}
        value={sliderPos}
        onChange={(e) => handleSlider(Number(e.target.value))}
        className="w-full h-1.5 accent-sky-500 cursor-pointer rounded-full"
      />
      <div className="flex justify-between text-[10px] text-muted-foreground/50 tabular-nums">
        <span>0</span>
        <span>{maxLabel}</span>
      </div>
    </div>
  )
}

// ─── PlanCard ─────────────────────────────────────────────────────────────────────

interface PlanCardProps {
  tier: PricingCardTierInput
  cost: PlanCost
  isBest: boolean
}

function PlanCard({tier, cost, isBest}: PlanCardProps) {
  const name = tier.tierName.charAt(0) + tier.tierName.slice(1).toLowerCase()
  const isFree = tier.monthlyPriceCents === 0
  const accent = isBest ? 'text-sky-500' : 'text-emerald-500'
  const accentBg = isBest ? 'bg-sky-500/10' : 'bg-emerald-500/10'

  return (
    <Card
      className={`flex flex-col ${
        isBest
          ? 'relative border-sky-500/50 shadow-lg shadow-sky-500/10'
          : 'border-border/60'
      }`}
    >
      {isBest && (
        <div className="absolute -top-3 left-1/2 -translate-x-1/2 z-10">
          <span className="inline-flex items-center gap-1 rounded-full bg-gradient-to-r from-sky-500 to-cyan-400 px-3 py-0.5 text-xs font-semibold text-white shadow-md shadow-sky-500/20 whitespace-nowrap">
            <Sparkles className="h-3 w-3" />
            Best match
          </span>
        </div>
      )}

      <CardHeader className="pb-2 pt-5">
        <CardTitle className="text-base">{name}</CardTitle>
        {isFree ? (
          <div className="mt-2">
            <span className="text-3xl font-bold">$0</span>
            <span className="text-muted-foreground text-sm">/mo</span>
            <div className="mt-1.5 flex items-center gap-1">
              {cost.exceedsFreeLimits ? (
                <>
                  <AlertTriangle className="h-3 w-3 text-amber-500" />
                  <span className="text-xs text-amber-500">Upgrade needed</span>
                </>
              ) : (
                <>
                  <Check className="h-3 w-3 text-emerald-500" />
                  <span className="text-xs text-emerald-500">Fits your usage</span>
                </>
              )}
            </div>
          </div>
        ) : (
          <div className="mt-2">
            <span className={`text-3xl font-bold ${isBest ? 'text-sky-500' : ''}`}>
              {fmtMoney(cost.total)}
            </span>
            <span className="text-muted-foreground text-sm">/mo</span>
          </div>
        )}
      </CardHeader>

      <CardContent className="flex-1 space-y-2 text-xs pb-4">
        {!isFree && (
          <>
            <div className="flex justify-between text-muted-foreground">
              <span>Base plan</span>
              <span className="tabular-nums">{fmtMoney(cost.base)}</span>
            </div>
            {cost.overageLines.map((line) => (
              <div key={line.label} className="flex justify-between text-muted-foreground">
                <span className="flex items-center gap-1">
                  <TrendingUp className="h-3 w-3 shrink-0" />
                  {line.label}
                </span>
                <span className="tabular-nums">+{fmtMoney(line.cost)}</span>
              </div>
            ))}
            {cost.overageLines.length > 0 && (
              <div className={`flex justify-between font-semibold border-t border-border/50 pt-2 ${accent}`}>
                <span>Total</span>
                <span className="tabular-nums">{fmtMoney(cost.total)}</span>
              </div>
            )}
            {cost.overageLines.length === 0 && (
              <div className="flex items-center gap-1.5 mt-1">
                <div className={`rounded-full p-0.5 ${accentBg}`}>
                  <Check className={`h-3 w-3 ${accent}`} />
                </div>
                <span className="text-muted-foreground">No overages</span>
              </div>
            )}
          </>
        )}

        <Button
          asChild
          size="sm"
          className={`w-full mt-3 ${
            isBest
              ? 'bg-sky-500 hover:bg-sky-400 text-white shadow-md shadow-sky-500/25'
              : ''
          }`}
          variant={isBest ? 'default' : 'outline'}
        >
          <Link to="/signup">
            {isFree ? 'Start Free' : `Start Trial`}
          </Link>
        </Button>
      </CardContent>
    </Card>
  )
}

// ─── Main section ─────────────────────────────────────────────────────────────────

// Datadog pricing estimate (annual rates): infra $15, APM $31, profiler $19, network $5 per host
const DATADOG_COST_PER_HOST = 15 + 31 + 19 + 5 // $70/host/mo
const DATADOG_LOG_COST_PER_GB = 1.8 // ingest + index
const DATADOG_ERROR_COST_FIRST_50K = 25
const DATADOG_ERROR_RATE_PER_1K = 0.25

function estimateDatadogCost(usage: Usage): number {
  const infraCost = usage.hosts * DATADOG_COST_PER_HOST
  const logCost = usage.logGb * DATADOG_LOG_COST_PER_GB
  let errorCost = 0
  if (usage.errors <= 50_000) {
    errorCost = usage.errors > 0 ? DATADOG_ERROR_COST_FIRST_50K : 0
  } else {
    errorCost =
      DATADOG_ERROR_COST_FIRST_50K + Math.ceil((usage.errors - 50_000) / 1000) * DATADOG_ERROR_RATE_PER_1K
  }
  return infraCost + logCost + errorCost
}

export function PricingCalculatorSection({standalone = false}: {standalone?: boolean}) {
  const [usage, setUsage] = useState<Usage>({
    errors: 0,
    logGb: 0,
    aiEvents: 0,
    pageViews: 0,
    apmSpans: 0,
    customMetrics: 0,
    hosts: 0,
  })

  const {data: billingPlans, isPending} = useQuery({
    queryKey: ['billing-plans'],
    queryFn: () => api.getBillingPlans(),
  })

  const tiers: PricingCardTierInput[] = useMemo(
    () =>
      billingPlans?.plans.map((p) => ({
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
    return paidIndexes.reduce((best, curr) =>
      costs[curr.i].total < costs[best.i].total ? curr : best,
    ).i
  }, [costs, tiers])

  const set = (key: keyof Usage) => (v: number) => setUsage((u) => ({...u, [key]: v}))

  const heading = standalone ? (
    <h1 className="text-3xl font-bold tracking-tight sm:text-4xl lg:text-5xl mb-4">
      Pricing Calculator
    </h1>
  ) : (
    <h2 className="text-3xl font-bold tracking-tight sm:text-4xl lg:text-5xl mb-4">
      <Link
        to="/pricing-calculator"
        className="hover:text-sky-500 transition-colors group inline-flex items-center gap-3"
      >
        Pricing Calculator
        <ArrowRight className="h-7 w-7 opacity-30 transition-transform group-hover:translate-x-1 group-hover:opacity-60" />
      </Link>
    </h2>
  )

  return (
    <section
      id="pricing-calculator"
      className={`py-28 px-4 sm:px-6 lg:px-8 scroll-mt-24 ${
        standalone ? 'bg-background' : 'border-t border-border/40 bg-muted/20'
      }`}
    >
      <div className="max-w-7xl mx-auto">
        {/* Header */}
        <div className="text-center mb-14">
          <p className="text-sm font-semibold text-sky-500 tracking-wide uppercase mb-3">
            Estimate your cost
          </p>
          {heading}
          <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
            Dial in your monthly usage below and see exactly what you'd pay on each plan — including overages.
          </p>
        </div>

        {/* Presets — above both columns so sliders and cards align */}
        <div className="mb-6">
          <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-3">
            Quick presets
          </p>
          <div className="flex gap-2 flex-wrap">
            {PRESETS.map((preset) => (
              <button
                key={preset.label}
                onClick={() => setUsage(preset.usage)}
                className="rounded-md border border-border/60 px-4 py-1.5 text-sm hover:border-sky-500/50 hover:text-sky-600 hover:bg-sky-500/5 transition-all"
              >
                {preset.label}
              </button>
            ))}
          </div>
        </div>

        <div className="grid lg:grid-cols-5 gap-10 xl:gap-16 items-start">
          {/* ── Left: Inputs ── */}
          <div className="lg:col-span-2 space-y-8">
            {/* Sliders */}
            <div className="space-y-8 rounded-xl border border-border/50 bg-background p-6">
              <SliderInput
                label="Errors per month"
                sublabel="JS exceptions, backend crashes, and unhandled errors"
                value={usage.errors}
                max={5_000_000}
                logMin={500}
                onChange={set('errors')}
              />
              <SliderInput
                label="Log data"
                sublabel="Ingested log volume per month"
                value={usage.logGb}
                max={500}
                unit="GB"
                step={5}
                onChange={set('logGb')}
              />
              <SliderInput
                label="AI observability events"
                sublabel="LLM calls, traces, and generations tracked"
                value={usage.aiEvents}
                max={1_000_000}
                logMin={100}
                onChange={set('aiEvents')}
              />
              <SliderInput
                label="Page views"
                sublabel="Monthly analytics page views across all sites"
                value={usage.pageViews}
                max={10_000_000}
                logMin={10_000}
                onChange={set('pageViews')}
              />
            </div>
          </div>

          {/* ── Right: Plan comparison ── */}
          <div className="lg:col-span-3">
            {isPending ? (
              <div className="grid grid-cols-2 gap-4">
                {Array.from({length: 4}).map((_, i) => (
                  <Card key={i} className="border-border/60">
                    <CardHeader>
                      <div className="h-4 w-16 bg-muted rounded animate-pulse" />
                      <div className="h-8 w-20 bg-muted rounded animate-pulse mt-2" />
                    </CardHeader>
                    <CardContent className="space-y-2">
                      {Array.from({length: 3}).map((__, j) => (
                        <div key={j} className="h-3 w-full bg-muted rounded animate-pulse" />
                      ))}
                    </CardContent>
                  </Card>
                ))}
              </div>
            ) : tiers.length === 0 ? (
              <Card className="border-border/60">
                <CardHeader>
                  <CardTitle>Pricing unavailable</CardTitle>
                  <CardDescription>No plans are currently published.</CardDescription>
                </CardHeader>
              </Card>
            ) : (
              <div className="space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  {tiers.map((tier, idx) => (
                    <div key={tier.tierName} className="relative">
                      <PlanCard tier={tier} cost={costs[idx]} isBest={idx === bestPlanIdx} />
                    </div>
                  ))}
                </div>
                {(() => {
                  const datadogEst = estimateDatadogCost(usage)
                  const freeIdx = tiers.findIndex((t) => t.tierName === 'FREE')
                  const freeFits = freeIdx >= 0 && costs[freeIdx] && !costs[freeIdx].exceedsFreeLimits
                  const displayMoneatCost = freeFits
                    ? 0
                    : bestPlanIdx >= 0
                      ? costs[bestPlanIdx].total
                      : 0
                  const hasUsage =
                    usage.errors > 0 ||
                    usage.logGb > 0 ||
                    usage.hosts > 0 ||
                    usage.apmSpans > 0 ||
                    usage.customMetrics > 0
                  if (!hasUsage || datadogEst < 10) return null
                  return (
                    <Card className="border-amber-500/30 bg-amber-500/5">
                      <CardContent className="pt-4 pb-4">
                        <div className="flex items-center justify-between gap-4">
                          <div>
                            <p className="text-sm font-semibold">vs Datadog (estimated)</p>
                            <p className="text-xs text-muted-foreground mt-0.5">
                              Same usage would cost ~{fmtMoney(datadogEst)}/mo on Datadog (infra + APM + profiler +
                              network + logs)
                            </p>
                          </div>
                          <div className="text-right">
                            <p className="text-2xl font-bold text-emerald-600">
                              Save ~
                              {datadogEst > 0
                                ? Math.round((1 - displayMoneatCost / datadogEst) * 100)
                                : 0}
                              %
                            </p>
                            <p className="text-xs text-muted-foreground">with Moneat</p>
                          </div>
                        </div>
                      </CardContent>
                    </Card>
                  )
                })()}
              </div>
            )}

            {!isPending && tiers.length > 0 && (
              <p className="text-xs text-muted-foreground mt-6 text-center">
                Estimates are based on monthly billing. Unlimited team members on every plan.
              </p>
            )}
          </div>
        </div>
      </div>
    </section>
  )
}
