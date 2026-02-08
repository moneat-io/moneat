import {Link} from '@tanstack/react-router'
import {useMutation, useQuery} from '@tanstack/react-query'
import {Check} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle,} from '@/components/ui/card'
import {api} from '@/lib/api'
import {useToast} from '@/hooks/use-toast'

const fallbackTiers = [
  {
    name: 'Free',
    price: '$0',
    period: '/mo',
    description: 'Perfect for side projects and getting started',
    features: [
      '10K errors per month',
      '1 project',
      '7-day retention',
      'Unlimited team members',
      'Email alerts',
      'Sentry-compatible SDKs',
    ],
    cta: 'Start Free',
    ctaLink: '/signup',
    highlight: false,
  },
  {
    name: 'Pro',
    price: '$19',
    period: '/mo',
    description: 'For growing teams shipping production apps',
    features: [
      '500K errors per month',
      'Unlimited projects',
      '30-day retention',
      'Performance monitoring',
      'Session replay (50/month)',
      'Priority email support',
    ],
    cta: 'Start Free Trial',
    ctaLink: '/signup',
    highlight: true,
  },
  {
    name: 'Team',
    price: '$49',
    period: '/mo',
    description: 'For teams that need scale and compliance',
    features: [
      '5M errors per month',
      'Unlimited projects',
      '90-day retention',
      'Advanced dashboards',
      'SAML SSO',
      'Priority support',
    ],
    cta: 'Contact Sales',
    ctaLink: '/signup',
    highlight: false,
  },
]

export function PricingSection() {
  const {toast} = useToast()
  const isAuthenticated = api.isAuthenticated()
  const {data: billingPlans} = useQuery({
    queryKey: ['billing-plans'],
    queryFn: () => api.getBillingPlans(),
    enabled: isAuthenticated,
  })
  const checkoutMutation = useMutation({
    mutationFn: (tierName: string) =>
      api.createBillingCheckoutSession({
        tierName,
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
      const price = tier.monthlyPriceCents === 0 ? '$0' : `$${(tier.monthlyPriceCents / 100).toFixed(0)}`
      const featureLimit = `${Intl.NumberFormat('en-US').format(tier.monthlyUnitLimit)} events per month`
      const paygLine = tier.paygEnabled
        ? `PAYG available at $${((tier.paygRateMicrosPerUnit * 10000) / 1_000_000).toFixed(2)}/10K units`
        : 'No PAYG overage'
      return {
        name: tier.tierName.charAt(0) + tier.tierName.slice(1).toLowerCase(),
        period: '/mo',
        price,
        description: tier.tierName === 'FREE'
          ? 'Perfect for side projects and getting started'
          : tier.tierName === 'PRO'
            ? 'For growing teams shipping production apps'
            : 'For teams that need scale and compliance',
        features: [
          featureLimit,
          `${tier.retentionDays}-day retention`,
          tier.maxProjects == null ? 'Unlimited projects' : `${tier.maxProjects} project${tier.maxProjects === 1 ? '' : 's'}`,
          `${tier.maxSystems} monitored system${tier.maxSystems === 1 ? '' : 's'}`,
          `Monitor interval: ${tier.monitorIntervalSeconds}s`,
          paygLine,
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
    checkoutMutation.mutate(tierName)
  }

  return (
    <section
      id="pricing"
      className="py-28 px-4 sm:px-6 lg:px-8 scroll-mt-24 bg-background"
    >
      <div className="max-w-6xl mx-auto">
        <div className="text-center mb-16">
          <p className="text-sm font-semibold text-sky-500 tracking-wide uppercase mb-3">
            Pricing
          </p>
          <h2 className="text-3xl font-bold tracking-tight sm:text-4xl lg:text-5xl mb-4">
            Simple, transparent pricing
          </h2>
          <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
            10x the error quota at half the price. No per-seat pricing — your
            whole team included.
          </p>
        </div>
        <div className="grid md:grid-cols-3 gap-8">
          {tiers.map((tier) => (
            <Card
              key={tier.name}
              className={
                tier.highlight
                  ? 'relative border-sky-500/50 shadow-xl shadow-sky-500/10 scale-[1.02]'
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
                <CardDescription>{tier.description}</CardDescription>
                <div className="mt-4">
                  <span className="text-4xl font-bold">{tier.price}</span>
                  <span className="text-muted-foreground">{tier.period}</span>
                </div>
              </CardHeader>
              <CardContent>
                <ul className="space-y-3">
                  {tier.features.map((feature) => (
                    <li key={feature} className="flex items-start gap-2.5">
                      <div
                        className={`mt-0.5 rounded-full p-0.5 ${tier.highlight ? 'bg-sky-500/10' : 'bg-emerald-500/10'}`}
                      >
                        <Check
                          className={`h-3.5 w-3.5 ${tier.highlight ? 'text-sky-500' : 'text-emerald-500'}`}
                        />
                      </div>
                      <span className="text-sm">{feature}</span>
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
                    {checkoutMutation.isPending ? 'Opening Checkout...' : tier.cta}
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
          ))}
        </div>
      </div>
    </section>
  )
}
