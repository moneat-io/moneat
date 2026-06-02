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
import {useMutation, useQuery} from '@tanstack/react-query'
import {Check, TrendingUp} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {api} from '@/lib/api'
import {buildPricingCardModel, type PricingCardModel} from '@/lib/pricing-display'
import {useToast} from '@/hooks/useToast'
import {useState} from 'react'
import {cn} from '@/lib/utils'
import {GRADIENT_BG, GRADIENT_BAR, GRADIENT_TEXT, SectionHeading} from './Landing'

// ─── Loading / error / empty states ────────────────────────────────────────────

function PricingLoadingState() {
  return (
    <div className="mb-12 grid gap-6 md:grid-cols-2 lg:grid-cols-4">
      {Array.from({length: 4}, (_, idx) => `loading-card-${idx}`).map((cardId) => (
        <div key={cardId} className="rounded-lg border border-white/[0.08] bg-[#0c0e16] p-6">
          <div className="h-5 w-20 animate-pulse rounded bg-white/[0.06]" />
          <div className="mt-3 h-4 w-36 animate-pulse rounded bg-white/[0.06]" />
          <div className="mt-4 h-10 w-24 animate-pulse rounded bg-white/[0.06]" />
          <div className="mt-6 space-y-2">
            {Array.from({length: 4}, (_, featureIdx) => `loading-feature-${featureIdx}`).map((featureId) => (
              <div key={featureId} className="h-3 w-full animate-pulse rounded bg-white/[0.06]" />
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

function PricingErrorState() {
  return (
    <div className="mb-12 rounded-lg border border-rose-500/20 bg-rose-500/5 p-6">
      <p className="font-semibold text-rose-300">Unable to load pricing right now</p>
      <p className="mt-1 text-sm text-slate-500">Please refresh the page in a moment.</p>
    </div>
  )
}

function PricingEmptyState() {
  return (
    <div className="mb-12 rounded-lg border border-white/[0.08] bg-[#0c0e16] p-6">
      <p className="font-semibold text-slate-300">Pricing unavailable</p>
      <p className="mt-1 text-sm text-slate-500">No plans are currently published.</p>
    </div>
  )
}

// ─── Tier card ──────────────────────────────────────────────────────────────────

function TierCard({
  tier,
  billingInterval,
  isAuthenticated,
  isPending,
  onPaidTierClick,
}: {
  readonly tier: PricingCardModel
  readonly billingInterval: 'monthly' | 'yearly'
  readonly isAuthenticated: boolean
  readonly isPending: boolean
  readonly onPaidTierClick: (tierName: string) => void
}) {
  const isYearly = billingInterval === 'yearly'

  return (
    <div
      className={cn(
        'relative flex flex-col rounded-lg border bg-[#0c0e16] p-6',
        tier.highlight ? 'border-indigo-400/40' : 'border-white/[0.08]',
      )}
    >
      {/* Popular tier keyline */}
      {tier.highlight ? <div className={cn('absolute inset-x-0 top-0 h-px rounded-t-lg', GRADIENT_BAR)} /> : null}

      {/* Violet glow behind the popular card */}
      {tier.highlight ? (
        <div
          aria-hidden
          className="pointer-events-none absolute inset-0 rounded-lg"
          style={{
            background: 'radial-gradient(50% 60% at 50% 0%, rgba(99,102,241,0.13) 0%, transparent 100%)',
          }}
        />
      ) : null}

      {/* Popular badge */}
      {tier.highlight ? (
        <div className="absolute -top-3 left-1/2 z-10 -translate-x-1/2">
          <span className={cn('inline-flex items-center rounded-md px-3 py-1 font-brandmono text-[11px] font-semibold uppercase tracking-[0.12em] text-white', GRADIENT_BG)}>
            Most popular
          </span>
        </div>
      ) : null}

      {/* Header */}
      <div className="flex items-baseline justify-between">
        <h3 className="text-lg font-semibold text-white">{tier.name}</h3>
        <div className="text-right">
          <span className={cn('font-brandmono text-2xl font-bold', tier.highlight ? GRADIENT_TEXT : 'text-white')}>
            ${tier.displayPrice === 0 ? '0' : tier.displayPrice.toFixed(0)}
          </span>
          <span className="font-brandmono text-xs text-slate-500">/mo</span>
        </div>
      </div>

      {isYearly && tier.displayPrice > 0 ? (
        <p className="mt-0.5 text-right font-brandmono text-[11px] text-slate-500">
          billed ${tier.yearlyTotalPrice.toFixed(0)}/yr
        </p>
      ) : null}

      <p className="mt-3 min-h-10 text-sm leading-6 text-slate-400">{tier.description}</p>

      {/* Included limits */}
      <div className="mt-5 flex-1 space-y-4">
        {tier.includedLimits.length > 0 && (
          <div>
            <p className="mb-2 font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">Included</p>
            <ul className="space-y-2">
              {tier.includedLimits.map((limit) => (
                <li key={limit} className="flex items-start gap-2">
                  <Check className="mt-0.5 size-3.5 shrink-0 text-emerald-400" />
                  <span className="text-sm leading-snug text-slate-300">{limit}</span>
                </li>
              ))}
            </ul>
          </div>
        )}

        {tier.overages.length > 0 && (
          <div>
            <p className="mb-2 font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">Overages</p>
            <ul className="space-y-1.5">
              {tier.overages.map((o) => (
                <li key={o.label} className="flex items-center gap-2">
                  <TrendingUp className="size-3 shrink-0 text-slate-500" />
                  <span className="text-sm text-slate-500">
                    {o.label}: <span className="font-brandmono font-medium text-slate-300">{o.rate}</span>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}

        {tier.platformFeatures.length > 0 && (
          <div>
            <p className="mb-2 font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">Features</p>
            <ul className="space-y-2">
              {tier.platformFeatures.map((feature) => (
                <li key={feature} className="flex items-start gap-2">
                  <Check className="mt-0.5 size-3.5 shrink-0 text-emerald-400" />
                  <span className="text-sm leading-snug text-slate-300">{feature}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>

      {/* CTA */}
      <div className="mt-6">
        {isAuthenticated && tier.tierName !== 'FREE' ? (
          tier.highlight ? (
            <Button
              className={cn('w-full border-0 text-white shadow-[0_10px_28px_-10px_#6366F1] hover:brightness-110', GRADIENT_BG)}
              size="lg"
              disabled={isPending}
              onClick={() => onPaidTierClick(tier.tierName)}
            >
              {tier.cta}
            </Button>
          ) : (
            <Button
              className="w-full border-white/15 bg-white/[0.03] text-slate-100 hover:bg-white/[0.07] hover:text-white"
              variant="outline"
              size="lg"
              disabled={isPending}
              onClick={() => onPaidTierClick(tier.tierName)}
            >
              {tier.cta}
            </Button>
          )
        ) : tier.highlight ? (
          <Button
            asChild
            size="lg"
            className={cn('w-full border-0 text-white shadow-[0_10px_28px_-10px_#6366F1] hover:brightness-110', GRADIENT_BG)}
          >
            <Link to={tier.ctaLink}>{tier.cta}</Link>
          </Button>
        ) : (
          <Button
            asChild
            variant="outline"
            size="lg"
            className="w-full border-white/15 bg-white/[0.03] text-slate-100 hover:bg-white/[0.07] hover:text-white"
          >
            <Link to={tier.ctaLink}>{tier.cta}</Link>
          </Button>
        )}
      </div>
    </div>
  )
}

// ─── Main section ───────────────────────────────────────────────────────────────

export function PricingSection() {
  const {toast} = useToast()
  const [billingInterval, setBillingInterval] = useState<'monthly' | 'yearly'>('monthly')
  const isAuthenticated = api.isAuthenticated()

  const {
    data: billingPlans,
    isPending: isBillingPlansLoading,
    isError: isBillingPlansError,
  } = useQuery({
    queryKey: ['billing-plans'],
    queryFn: () => api.getBillingPlans(),
    enabled: true,
  })

  const checkoutMutation = useMutation({
    mutationFn: ({tierName, interval}: {tierName: string; interval: string}) =>
      api.createBillingCheckoutSession({
        tierName,
        billingInterval: interval,
        successUrl: `${globalThis.window.location.origin}/settings?checkout=success&tab=billing`,
        cancelUrl: `${globalThis.window.location.origin}/#pricing`,
      }),
    onSuccess: (session) => {
      if (session.url) {
        globalThis.window.location.href = session.url
      }
    },
    onError: (err: Error) => {
      toast({
        title: 'Unable to start checkout',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const tiers: PricingCardModel[] = billingPlans?.plans.map((plan) => {
    return buildPricingCardModel(
      {
        ...plan.tier,
        trialDays: plan.trialDays ?? plan.tier.trialDays,
      },
      billingInterval,
    )
  }) ?? []

  const handlePaidTierClick = (tierName: string) => {
    if (!isAuthenticated) return
    checkoutMutation.mutate({tierName, interval: billingInterval})
  }

  const savingsPercent = 17

  return (
    <section id="pricing" className="scroll-mt-24 px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-7xl">
        {/* Header row */}
        <div className="mb-14 grid gap-8 lg:grid-cols-[1fr_auto] lg:items-end">
          <SectionHeading
            kicker="Pricing"
            title="Every signal type on every plan — including free."
            lead="No feature is locked behind a tier. Plans set how much you ingest and how long you keep it, not what you can monitor."
            align="left"
          />

          {/* Billing toggle */}
          <div
            role="radiogroup"
            aria-label="Billing interval"
            className="inline-flex w-fit items-center rounded-lg border border-white/10 bg-white/[0.03] p-1"
          >
            <button
              role="radio"
              aria-checked={billingInterval === 'monthly'}
              onClick={() => setBillingInterval('monthly')}
              className={cn(
                'relative rounded-md px-4 py-2 font-brandmono text-sm font-medium transition-all',
                billingInterval === 'monthly'
                  ? 'bg-white/[0.08] text-slate-100 shadow-sm'
                  : 'text-slate-500 hover:text-slate-300',
              )}
            >
              Monthly
            </button>
            <button
              role="radio"
              aria-checked={billingInterval === 'yearly'}
              onClick={() => setBillingInterval('yearly')}
              className={cn(
                'relative rounded-md px-4 py-2 font-brandmono text-sm font-medium transition-all',
                billingInterval === 'yearly'
                  ? 'bg-white/[0.08] text-slate-100 shadow-sm'
                  : 'text-slate-500 hover:text-slate-300',
              )}
            >
              Yearly{' '}
              <span className="ml-1.5 inline-flex items-center rounded-md bg-indigo-500/15 px-2 py-0.5 font-brandmono text-[11px] font-semibold text-indigo-300">
                Save {savingsPercent}%
              </span>
            </button>
          </div>
        </div>

        {/* Cards */}
        {(() => {
          if (isBillingPlansLoading) return <PricingLoadingState />
          if (isBillingPlansError) return <PricingErrorState />
          if (tiers.length === 0) return <PricingEmptyState />
          return (
            <div className="mb-12 grid gap-6 md:grid-cols-2 lg:grid-cols-4">
              {tiers.map((tier) => (
                <TierCard
                  key={tier.name}
                  tier={tier}
                  billingInterval={billingInterval}
                  isAuthenticated={isAuthenticated}
                  isPending={checkoutMutation.isPending}
                  onPaidTierClick={handlePaidTierClick}
                />
              ))}
            </div>
          )
        })()}

        {/* Footer note */}
        <div className="mt-8 border-t border-white/[0.06] pt-8 text-center">
          <p className="font-brandmono text-sm text-slate-500">
            Unlimited team members on every plan.{' '}
            <span className="mx-1.5 text-white/20">·</span>
            <span className="text-xs">30-day money-back guarantee</span>
          </p>
        </div>
      </div>
    </section>
  )
}
