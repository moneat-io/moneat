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

import {useEffect, useState} from 'react'
import {Link} from '@tanstack/react-router'
import {Menu} from 'lucide-react'
import {Logo} from '@/components/logo'
import {Button} from '@/components/ui/button'
import {Sheet, SheetContent, SheetTrigger} from '@/components/ui/sheet'
import {VariantA} from './variant-a'
import {PricingSection} from './pricing-section'
import {PricingCalculatorSection} from './pricing-calculator-section'
import {Helmet} from 'react-helmet-async'

const NAV_LINKS = [
  {label: 'Features', href: '#features'},
  {label: 'Live Demo', to: '/demo'},
  {label: 'Pricing', href: '#pricing'},
  {label: 'Calculator', href: '#pricing-calculator'},
  {label: 'Docs', href: '/docs'},
] as const

function MobileNav() {
  const [open, setOpen] = useState(false)

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetTrigger asChild>
        <Button variant="ghost" size="icon" className="md:hidden" aria-label="Open menu">
          <Menu className="h-5 w-5" />
        </Button>
      </SheetTrigger>
      <SheetContent side="right" className="w-72 bg-background p-0">
        <div className="flex items-center px-4 py-4 border-b border-border/50">
          <Logo className="h-7" />
        </div>
        <nav className="flex flex-col gap-1 px-3 py-4">
          {NAV_LINKS.map((link) =>
            'to' in link ? (
              <Link
                key={link.label}
                to={link.to}
                onClick={() => setOpen(false)}
                className="rounded-md px-3 py-2.5 text-sm font-medium text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
              >
                {link.label}
              </Link>
            ) : (
              <a
                key={link.label}
                href={link.href}
                onClick={() => setOpen(false)}
                className="rounded-md px-3 py-2.5 text-sm font-medium text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
              >
                {link.label}
              </a>
            ),
          )}
        </nav>
        <div className="flex flex-col gap-3 px-4 pt-2 border-t border-border/50 mt-auto">
          <Link to="/login" onClick={() => setOpen(false)}>
            <Button variant="outline" className="w-full">Log in</Button>
          </Link>
          <Link to="/signup" onClick={() => setOpen(false)}>
            <Button className="w-full bg-sky-500 hover:bg-sky-600 text-white shadow-md shadow-sky-500/25">
              Sign up free
            </Button>
          </Link>
        </div>
      </SheetContent>
    </Sheet>
  )
}

export function LandingPage() {
  // The landing page is always dark regardless of the user's saved theme preference.
  useEffect(() => {
    const root = document.documentElement
    const prev = root.className
    root.classList.add('dark')
    return () => { root.className = prev }
  }, [])
  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name: 'Moneat',
    url: 'https://moneat.io',
    applicationCategory: 'DeveloperApplication',
    description: 'Errors, logs, infrastructure, APM, AI observability, on-call, and status pages in one platform. Works with Sentry SDKs and the Datadog Agent.',
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
        <title>Moneat | Errors, Logs, Infrastructure, APM, and On-Call in One Platform</title>
        <meta
          name="description"
          content="Stop juggling monitoring tools. Moneat brings errors, logs, infrastructure, APM, AI observability, and on-call into one platform. Works with Sentry SDKs and the Datadog Agent."
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
            <Link
              to="/demo"
              className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
            >
              Live Demo
            </Link>
            <a
              href="#pricing"
              className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
            >
              Pricing
            </a>
            <a
              href="#pricing-calculator"
              className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
            >
              Calculator
            </a>
            <a
              href="/docs"
              className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
            >
              Docs
            </a>
          </nav>
          <div className="hidden md:flex items-center gap-3">
            <Link to="/login">
              <Button variant="ghost" className="text-sm">Log in</Button>
            </Link>
            <Link to="/signup">
              <Button className="bg-sky-500 hover:bg-sky-600 text-white shadow-md shadow-sky-500/25 text-sm">
                Sign up free
              </Button>
            </Link>
          </div>

          {/* Mobile menu */}
          <MobileNav />
        </div>
      </header>

      <main>
        <VariantA />
        <PricingSection />
        <PricingCalculatorSection />
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
                Errors, logs, infrastructure, APM, and on-call — one platform, simple pricing.
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
              Compatible with Sentry&reg; SDKs &amp; Datadog&reg; Agent. Switch in minutes.
              Sentry is a registered trademark of Functional Software, Inc.
              Datadog is a registered trademark of Datadog, Inc.
              Moneat is not affiliated with or endorsed by either company.
            </p>
          </div>
        </div>
      </footer>
    </article>
  )
}
