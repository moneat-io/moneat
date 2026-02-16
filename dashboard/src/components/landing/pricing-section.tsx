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
import {Check} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle} from '@/components/ui/card'
import {api} from '@/lib/api'
import {buildPricingCardModel} from '@/lib/pricing-display'
import {useToast} from '@/hooks/use-toast'
import {useState} from 'react'

function PricingLoadingState() {
  return (
    <div className="grid md:grid-cols-2 lg:grid-cols-4 gap-6 mb-12">
      {Array.from({length: 4}).map((_, idx) => (
        <Card key={idx} className="border-border/60">
          <CardHeader>
            <div className="h-5 w-20 rounded bg-muted animate-pulse" />
            <div className="h-4 w-36 rounded bg-muted animate-pulse" />
            <div className="h-10 w-24 rounded bg-muted animate-pulse mt-2" />
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              {Array.from({length: 6}).map((__, featureIdx) => (
                <div key={featureIdx} className="h-3 w-full rounded bg-muted animate-pulse" />
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
    <Card className="border-destructive/30 mb-12">
      <CardHeader>
        <CardTitle>Unable to load pricing right now</CardTitle>
        <CardDescription>Please refresh the page in a moment.</CardDescription>
      </CardHeader>
    </Card>
  )
}

function PricingEmptyState() {
  return (
    <Card className="border-border/60 mb-12">
      <CardHeader>
        <CardTitle>Pricing unavailable</CardTitle>
        <CardDescription>No plans are currently published.</CardDescription>
      </CardHeader>
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
        successUrl: `${window.location.origin}/settings`,
        cancelUrl: `${window.location.origin}/#pricing`,
      }),
    onSuccess: (session) => {
      if (session.url) {
        window.location.href = session.url
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

  const tiers = billingPlans?.plans.map((plan) => {
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
      className="py-28 px-4 sm:px-6 lg:px-8 scroll-mt-24 bg-background"
    >
      <div className="max-w-7xl mx-auto">
        <div className="text-center mb-16">
          <p className="text-sm font-semibold text-sky-500 tracking-wide uppercase mb-3">
            Pricing
          </p>
          <h2 className="text-3xl font-bold tracking-tight sm:text-4xl lg:text-5xl mb-4">
            Simple GB-based pricing
          </h2>
          <p className="text-lg text-muted-foreground max-w-2xl mx-auto mb-8">
            Pay for what you use. Unlimited team members on every plan.
          </p>

          <div className="inline-flex items-center rounded-lg border border-border/60 bg-muted/30 p-1">
            <button
              onClick={() => setBillingInterval('monthly')}
              className={`relative rounded-md px-4 py-2 text-sm font-medium transition-all ${
                billingInterval === 'monthly'
                  ? 'bg-background text-foreground shadow-sm'
                  : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              Monthly
            </button>
            <button
              onClick={() => setBillingInterval('yearly')}
              className={`relative rounded-md px-4 py-2 text-sm font-medium transition-all ${
                billingInterval === 'yearly'
                  ? 'bg-background text-foreground shadow-sm'
                  : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              Yearly
              <span className="ml-1.5 inline-flex items-center rounded-full bg-sky-500/10 px-2 py-0.5 text-xs font-semibold text-sky-600 dark:text-sky-400">
                Save {savingsPercent}%
              </span>
            </button>
          </div>
        </div>

        {isBillingPlansLoading ? (
          <PricingLoadingState />
        ) : isBillingPlansError ? (
          <PricingErrorState />
        ) : tiers.length === 0 ? (
          <PricingEmptyState />
        ) : (
          <div className="grid md:grid-cols-2 lg:grid-cols-4 gap-6 mb-12">
            {tiers.map((tier) => {
              const displayPrice = tier.displayPrice
              const isYearly = billingInterval === 'yearly'

              return (
                <Card
                  key={tier.name}
                  className={
                    tier.highlight
                      ? 'relative border-sky-500/50 shadow-xl shadow-sky-500/10'
                      : 'border-border/60'
                  }
                >
                  {tier.highlight && (
                    <div className="absolute -top-3 left-1/2 -translate-x-1/2">
                      <span className="inline-flex items-center rounded-full bg-gradient-to-r from-sky-500 to-cyan-400 px-4 py-1 text-xs font-semibold text-white shadow-md shadow-sky-500/20">
                        Most popular
                      </span>
                    </div>
                  )}
                  <CardHeader>
                    <CardTitle className="text-lg">{tier.name}</CardTitle>
                    <CardDescription className="text-xs">{tier.description}</CardDescription>
                    <div className="mt-4">
                      <span className="text-4xl font-bold">
                        ${displayPrice === 0 ? '0' : displayPrice.toFixed(0)}
                      </span>
                      <span className="text-muted-foreground text-sm">
                        /mo{isYearly && displayPrice > 0 ? (
                          <span className="block text-xs mt-1">
                            billed ${tier.yearlyTotalPrice.toFixed(0)}/yr
                          </span>
                        ) : ''}
                      </span>
                    </div>
                  </CardHeader>
                  <CardContent>
                    <ul className="space-y-2.5">
                      {tier.features.map((feature) => (
                        <li key={feature} className="flex items-start gap-2">
                          <div
                            className={`mt-0.5 rounded-full p-0.5 ${tier.highlight ? 'bg-sky-500/10' : 'bg-emerald-500/10'}`}
                          >
                            <Check
                              className={`h-3 w-3 ${tier.highlight ? 'text-sky-500' : 'text-emerald-500'}`}
                            />
                          </div>
                          <span className="text-xs leading-tight">{feature}</span>
                        </li>
                      ))}
                    </ul>
                  </CardContent>
                  <CardFooter>
                    {isAuthenticated && tier.tierName !== 'FREE' ? (
                      <Button
                        className={`w-full ${
                          tier.highlight
                            ? 'bg-sky-500 hover:bg-sky-400 text-white shadow-md shadow-sky-500/25'
                            : ''
                        }`}
                        variant={tier.highlight ? 'default' : 'outline'}
                        size="lg"
                        disabled={checkoutMutation.isPending}
                        onClick={() => handlePaidTierClick(tier.tierName)}
                      >
                        {tier.cta}
                      </Button>
                    ) : (
                      <Button
                        asChild
                        className={`w-full ${
                          tier.highlight
                            ? 'bg-sky-500 hover:bg-sky-400 text-white shadow-md shadow-sky-500/25'
                            : ''
                        }`}
                        variant={tier.highlight ? 'default' : 'outline'}
                        size="lg"
                      >
                        <Link to={tier.ctaLink}>{tier.cta}</Link>
                      </Button>
                    )}
                  </CardFooter>
                </Card>
              )
            })}
          </div>
        )}

        <div className="text-center mt-8 pt-8 border-t border-border/40">
          <p className="text-sm text-muted-foreground">
            Need more data? <span className="font-semibold text-foreground">$0.40/GB</span> for overage.
            On-call add-on: <span className="font-semibold text-foreground">$5/user/mo</span> for responders.
            <span className="mx-2"> </span>
            <span className="text-xs">30-day money-back guarantee</span>
          </p>
        </div>
      </div>
    </section>
  )
}
