import {Link} from '@tanstack/react-router'
import {Logo} from '@/components/logo'
import {Button} from '@/components/ui/button'
import {VariantA} from './variant-a'
import {PricingSection} from './pricing-section'
import {Helmet} from 'react-helmet-async'

export function LandingPage() {
  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name: 'Moneat',
    url: 'https://moneat.io',
    applicationCategory: 'DeveloperApplication',
    description: 'Sentry-compatible error, performance, and session replay monitoring.',
    operatingSystem: 'Web',
    offers: {
      '@type': 'Offer',
      price: '0',
      priceCurrency: 'USD',
    },
  }

  return (
    <article className="min-h-screen bg-background">
      <Helmet>
        <title>Moneat | Error, Performance, and Replay Monitoring</title>
        <meta
          name="description"
          content="Sentry-compatible error, performance, and session replay monitoring. Built for developers who care about reliability."
        />
        <link rel="canonical" href="https://moneat.io" />
        <script type="application/ld+json">{JSON.stringify(jsonLd)}</script>
      </Helmet>

      <header className="sticky top-0 z-50 w-full border-b border-border/50 bg-background/80 backdrop-blur-lg">
        <div className="flex h-16 items-center px-4 sm:px-6 lg:px-8 max-w-7xl mx-auto justify-between">
          <Link to="/" className="flex items-center" aria-label="Moneat Home">
            <Logo className="h-8" />
          </Link>
          <nav className="hidden md:flex items-center gap-8" aria-label="Main navigation">
            <a
              href="#features"
              className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
            >
              Features
            </a>
            <a
              href="#pricing"
              className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
            >
              Pricing
            </a>
          </nav>
          <div className="flex items-center gap-3">
            <Link to="/login">
              <Button variant="ghost" className="text-sm">Log in</Button>
            </Link>
            <Link to="/signup">
              <Button className="bg-sky-500 hover:bg-sky-600 text-white shadow-md shadow-sky-500/25 text-sm">
                Sign up free
              </Button>
            </Link>
          </div>
        </div>
      </header>

      <main>
        <VariantA />
        <PricingSection />
      </main>

      <footer className="border-t border-border/50 bg-slate-950 py-16 px-4 sm:px-6 lg:px-8">
        <div className="max-w-6xl mx-auto">
          <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-8">
            <div className="flex flex-col gap-3">
              <div className="flex items-center gap-3">
                <Logo className="h-7" markOnly />
                <span className="text-lg font-semibold text-white">moneat</span>
              </div>
              <p className="text-sm text-slate-400 max-w-xs">
                Monitoring built for developers who care about reliability.
              </p>
            </div>
            <nav className="flex items-center gap-8" aria-label="Footer navigation">
              <a
                href="#features"
                className="text-sm text-slate-400 hover:text-sky-400 transition-colors"
              >
                Features
              </a>
              <a
                href="#pricing"
                className="text-sm text-slate-400 hover:text-sky-400 transition-colors"
              >
                Pricing
              </a>
              <Link
                to="/login"
                className="text-sm text-slate-400 hover:text-sky-400 transition-colors"
              >
                Log in
              </Link>
              <Link
                to="/signup"
                className="text-sm text-slate-400 hover:text-sky-400 transition-colors"
              >
                Sign up
              </Link>
              <Link
                to="/legal/terms"
                className="text-sm text-slate-400 hover:text-sky-400 transition-colors"
              >
                Terms
              </Link>
              <Link
                to="/legal/privacy"
                className="text-sm text-slate-400 hover:text-sky-400 transition-colors"
              >
                Privacy
              </Link>
            </nav>
          </div>
          <div className="mt-10 pt-8 border-t border-slate-800 flex flex-col sm:flex-row items-center justify-between gap-4">
            <p className="text-xs text-slate-500">
              &copy; {new Date().getFullYear()} Moneat. All rights reserved.
            </p>
            <p className="text-xs text-slate-500">
              Sentry-compatible. Switch in minutes.
            </p>
          </div>
        </div>
      </footer>
    </article>
  )
}
