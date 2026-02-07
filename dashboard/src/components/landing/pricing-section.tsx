import {Link} from '@tanstack/react-router'
import {Check} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle,} from '@/components/ui/card'

const tiers = [
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
              </CardFooter>
            </Card>
          ))}
        </div>
      </div>
    </section>
  )
}
