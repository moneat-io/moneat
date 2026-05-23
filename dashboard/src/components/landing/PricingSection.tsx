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
import {Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle} from '@/components/ui/card'
import {api} from '@/lib/api'
import {buildPricingCardModel, type PricingCardModel} from '@/lib/pricing-display'
import {useToast} from '@/hooks/useToast'
import {useState} from 'react'

function PricingLoadingState() {
  return (
    <div className="mb-12 grid gap-6 md:grid-cols-2 lg:grid-cols-4">
      {Array.from({length: 4}, (_, idx) => `loading-card-${idx}`).map((cardId) => (
        <Card key={cardId} className="rounded-lg border-slate-200 bg-white shadow-sm">
          <CardHeader>
            <div className="h-5 w-20 animate-pulse rounded bg-slate-100" />
            <div className="h-4 w-36 animate-pulse rounded bg-slate-100" />
            <div className="mt-2 h-10 w-24 animate-pulse rounded bg-slate-100" />
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              {Array.from({length: 6}, (_, featureIdx) => `loading-feature-${featureIdx}`).map((featureId) => (
                <div key={featureId} className="h-3 w-full animate-pulse rounded bg-slate-100" />
              ))}
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

function PricingErrorState() {
  return (
    <Card className="mb-12 rounded-lg border-red-200 bg-white shadow-sm">
      <CardHeader>
        <CardTitle>Unable to load pricing right now</CardTitle>
        <CardDescription>Please refresh the page in a moment.</CardDescription>
      </CardHeader>
    </Card>
  )
}

function PricingEmptyState() {
  return (
    <Card className="mb-12 rounded-lg border-slate-200 bg-white shadow-sm">
      <CardHeader>
        <CardTitle>Pricing unavailable</CardTitle>
        <CardDescription>No plans are currently published.</CardDescription>
      </CardHeader>
    </Card>
  )
}

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
  const accentClass = tier.highlight ? 'text-sky-500' : 'text-emerald-500'
  const accentBgClass = tier.highlight ? 'bg-sky-500/10' : 'bg-emerald-500/10'
  const highlightClass = tier.highlight
    ? 'relative border-slate-950 shadow-[0_18px_50px_rgba(15,23,42,0.12)]'
    : 'border-slate-200 shadow-sm'
  const btnClass = tier.highlight
    ? 'w-full bg-slate-950 text-white hover:bg-slate-800'
    : 'w-full'
  const btnVariant: 'default' | 'outline' = tier.highlight ? 'default' : 'outline'

  return (
    <Card className={`flex flex-col rounded-lg bg-white ${highlightClass}`}>
      {tier.highlight && (
        <div className="absolute -top-3 left-1/2 -translate-x-1/2">
          <span className="inline-flex items-center rounded-md bg-slate-950 px-3 py-1 text-xs font-semibold text-white">
            Most popular
          </span>
        </div>
      )}
      <CardHeader>
        <CardTitle className="text-lg text-slate-950">{tier.name}</CardTitle>
        <CardDescription className="text-xs text-slate-500">{tier.description}</CardDescription>
        <div className="mt-4">
          <span className="text-4xl font-semibold text-slate-950">
            ${tier.displayPrice === 0 ? '0' : tier.displayPrice.toFixed(0)}
          </span>
          <span className="text-sm text-slate-500">
            /mo{isYearly && tier.displayPrice > 0 ? (
              <span className="block text-xs mt-1">
                billed ${tier.yearlyTotalPrice.toFixed(0)}/yr
              </span>
            ) : ''}
          </span>
        </div>
      </CardHeader>
      <CardContent className="flex-1 space-y-4">
        <div>
          <p className="mb-2 text-[11px] font-semibold text-slate-500">
            Included
          </p>
          <ul className="space-y-2">
            {tier.includedLimits.map((limit) => (
              <li key={limit} className="flex items-start gap-2">
                <div className={`mt-0.5 rounded-md p-0.5 ${accentBgClass}`}>
                  <Check className={`size-3 ${accentClass}`} />
                </div>
                <span className="text-xs leading-tight text-slate-700">{limit}</span>
              </li>
            ))}
          </ul>
        </div>

        {tier.overages.length > 0 && (
          <div>
            <p className="mb-2 text-[11px] font-semibold text-slate-500">
              Overages
            </p>
            <ul className="space-y-1.5">
              {tier.overages.map((o) => (
                <li key={o.label} className="flex items-center gap-2">
                  <TrendingUp className="size-3 shrink-0 text-slate-400" />
                  <span className="text-xs text-slate-500">
                    {o.label}: <span className="font-medium text-slate-950">{o.rate}</span>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}

        {tier.platformFeatures.length > 0 && (
          <div>
            <p className="mb-2 text-[11px] font-semibold text-slate-500">
              Features
            </p>
            <ul className="space-y-2">
              {tier.platformFeatures.map((feature) => (
                <li key={feature} className="flex items-start gap-2">
                  <div className={`mt-0.5 rounded-md p-0.5 ${accentBgClass}`}>
                    <Check className={`size-3 ${accentClass}`} />
                  </div>
                  <span className="text-xs leading-tight text-slate-700">{feature}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </CardContent>
      <CardFooter>
        {isAuthenticated && tier.tierName !== 'FREE' ? (
          <Button
            className={btnClass}
            variant={btnVariant}
            size="lg"
            disabled={isPending}
            onClick={() => onPaidTierClick(tier.tierName)}
          >
            {tier.cta}
          </Button>
        ) : (
          <Button
            asChild
            className={btnClass}
            variant={btnVariant}
            size="lg"
          >
            <Link to={tier.ctaLink}>{tier.cta}</Link>
          </Button>
        )}
      </CardFooter>
    </Card>
  )
}

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
    <section
      id="pricing"
      className="scroll-mt-24 bg-white px-4 py-24 sm:px-6 lg:px-8"
    >
      <div className="mx-auto max-w-7xl">
        <div className="mb-14 grid gap-8 lg:grid-cols-[1fr_auto] lg:items-end">
          <div>
            <h1 className="max-w-2xl text-4xl font-semibold leading-tight text-slate-950 sm:text-5xl">
              Simple, transparent pricing
            </h1>
            <p className="mt-4 max-w-2xl text-lg leading-8 text-slate-600">
              Per-type limits so you only pay for what you use. Unlimited team members on every plan.
            </p>
          </div>

          <div role="radiogroup" aria-label="Billing interval" className="inline-flex w-fit items-center rounded-lg border border-slate-200 bg-slate-50 p-1">
            <button
              role="radio"
              aria-checked={billingInterval === 'monthly'}
              onClick={() => setBillingInterval('monthly')}
              className={`relative rounded-md px-4 py-2 text-sm font-medium transition-all ${
                billingInterval === 'monthly'
                  ? 'bg-white text-slate-950 shadow-sm'
                  : 'text-slate-500 hover:text-slate-950'
              }`}
            >
              Monthly
            </button>
            <button
              role="radio"
              aria-checked={billingInterval === 'yearly'}
              onClick={() => setBillingInterval('yearly')}
              className={`relative rounded-md px-4 py-2 text-sm font-medium transition-all ${
                billingInterval === 'yearly'
                  ? 'bg-white text-slate-950 shadow-sm'
                  : 'text-slate-500 hover:text-slate-950'
              }`}
            >
              Yearly{' '}
              <span className="ml-1.5 inline-flex items-center rounded-md bg-sky-50 px-2 py-0.5 text-xs font-semibold text-sky-700">
                Save {savingsPercent}%
              </span>
            </button>
          </div>
        </div>

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

        <div className="mt-8 border-t border-slate-200 pt-8 text-center">
          <p className="text-sm text-slate-500">
            Unlimited team members on every plan. <span className="mx-1.5">·</span> <span className="text-xs">30-day money-back guarantee</span>
          </p>
        </div>
      </div>
    </section>
  )
}
