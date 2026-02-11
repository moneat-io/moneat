import {Link} from '@tanstack/react-router'
import {useMutation, useQuery} from '@tanstack/react-query'
import {Check} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle,} from '@/components/ui/card'
import {api} from '@/lib/api'
import {useToast} from '@/hooks/use-toast'
import {useState} from 'react'

// Fallback tiers when API fails; should match pricing_tier_configs
const fallbackTiers = [
  {
    name: 'Free',
    monthlyPrice: 0,
    yearlyPrice: 0,
    gbLimit: 1,
    retentionDays: 7,
    description: 'Perfect for side projects and getting started',
    features: [
      '1 GB/mo data',
      '3 projects',
      '3 monitors (60s interval)',
      'Unlimited team members',
      'Email alerts',
      'Sentry SDK compatibility',
    ],
    cta: 'Start Free',
    ctaLink: '/signup',
    highlight: false,
  },
  {
    name: 'Pro',
    monthlyPrice: 29,
    yearlyPrice: 24,
    gbLimit: 50,
    retentionDays: 30,
    description: 'For growing teams shipping production apps',
    features: [
      '50 GB/mo data',
      'Unlimited projects',
      '10 monitors (30s interval)',
      'Performance monitoring',
      'Session replay',
      'Slack integration',
      'Priority email support',
    ],
    cta: 'Start 14-Day Trial',
    ctaLink: '/signup',
    highlight: true,
  },
  {
    name: 'Team',
    monthlyPrice: 79,
    yearlyPrice: 66,
    gbLimit: 200,
    retentionDays: 90,
    description: 'For teams that need scale and compliance',
    features: [
      '200 GB/mo data',
      'Unlimited projects',
      '25 monitors (10s interval)',
      'Advanced dashboards',
      'SAML SSO',
      'Priority support',
    ],
    cta: 'Start 14-Day Trial',
    ctaLink: '/signup',
    highlight: false,
  },
  {
    name: 'Business',
    monthlyPrice: 199,
    yearlyPrice: 166,
    gbLimit: 1024,
    retentionDays: 180,
    description: 'For enterprises with custom requirements',
    features: [
      '1 TB/mo data',
      'Unlimited everything',
      'Custom monitor intervals',
      'Dedicated support',
      'SLA guarantee',
      'Custom data retention',
    ],
    cta: 'Start 14-Day Trial',
    ctaLink: '/signup',
    highlight: false,
  },
]

export function PricingSection() {
  const {toast} = useToast()
  const [billingInterval, setBillingInterval] = useState<'monthly' | 'yearly'>('monthly')
  const isAuthenticated = api.isAuthenticated()
  // Public endpoint available, but only fetch when user visits page while authenticated
  const {data: billingPlans} = useQuery({
    queryKey: ['billing-plans'],
    queryFn: () => api.getBillingPlans(),
    enabled: isAuthenticated,
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

  const tiers = billingPlans?.plans?.length
    ? billingPlans.plans.map((plan) => {
      const tier = plan.tier
      const monthlyPrice = tier.monthlyPriceCents / 100
      const yearlyPrice = tier.yearlyPriceCents / (100 * 12)  // Display as monthly equivalent
      const gbLimit = tier.monthlyGbLimit / (1024 * 1024 * 1024)  // Convert bytes to GB
      
      return {
        name: tier.tierName.charAt(0) + tier.tierName.slice(1).toLowerCase(),
        monthlyPrice,
        yearlyPrice,
        gbLimit,
        retentionDays: tier.retentionDays,
        description: tier.tierName === 'FREE'
          ? 'Perfect for side projects and getting started'
          : tier.tierName === 'PRO'
            ? 'For growing teams shipping production apps'
            : tier.tierName === 'TEAM'
              ? 'For teams that need scale and compliance'
              : 'For enterprises with custom requirements',
        features: [
          `${gbLimit >= 1024 ? `${gbLimit / 1024} TB` : `${gbLimit} GB`}/mo data`,
          `${tier.retentionDays}-day retention`,
          tier.maxProjects == null ? 'Unlimited projects' : `${tier.maxProjects} project${tier.maxProjects === 1 ? '' : 's'}`,
          `${tier.maxSystems} monitors (${tier.monitorIntervalSeconds}s interval)`,
          ...(tier.tierName !== 'FREE' ? ['Performance monitoring', 'Session replay'] : []),
          ...(tier.tierName === 'TEAM' || tier.tierName === 'BUSINESS' ? ['SAML SSO', 'Priority support'] : []),
          ...(tier.tierName === 'BUSINESS' ? ['SLA guarantee', 'Custom retention'] : []),
        ],
        cta: tier.monthlyPriceCents === 0 ? 'Start Free' : `Start ${plan.trialDays}-Day Trial`,
        tierName: tier.tierName,
        ctaLink: '/signup',
        highlight: tier.tierName === 'PRO',
      }
    })
    : fallbackTiers.map((tier) => ({...tier, tierName: tier.name.toUpperCase()}))

  const handlePaidTierClick = (tierName: string) => {
    if (!isAuthenticated) return
    checkoutMutation.mutate({tierName, interval: billingInterval})
  }

  const savingsPercent = 17  // 17% discount for yearly billing

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
            Pay for what you use. No per-seat fees — your whole team included.
          </p>
          
          {/* Monthly/Yearly Toggle */}
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
        
        <div className="grid md:grid-cols-2 lg:grid-cols-4 gap-6 mb-12">
          {tiers.map((tier) => {
            const displayPrice = billingInterval === 'yearly' ? tier.yearlyPrice : tier.monthlyPrice
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
                          billed ${(tier.yearlyPrice * 12).toFixed(0)}/yr
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
        
        {/* Overage Pricing */}
        <div className="text-center mt-8 pt-8 border-t border-border/40">
          <p className="text-sm text-muted-foreground">
            Need more data? <span className="font-semibold text-foreground">$0.40/GB</span> for overage
            <span className="mx-2">•</span>
            <span className="text-xs">30-day money-back guarantee</span>
          </p>
        </div>
      </div>
    </section>
  )
}

