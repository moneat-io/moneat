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

import {createFileRoute, Link} from '@tanstack/react-router'
import {Logo} from '@/components/logo'
import {Button} from '@/components/ui/button'
import {PricingCalculatorSection} from '@/components/landing/pricing-calculator-section'
import {Helmet} from 'react-helmet-async'

export const Route = createFileRoute('/pricing-calculator')({
  component: PricingCalculatorPage,
})

function PricingCalculatorPage() {
  return (
    <article className="min-h-screen bg-background">
      <Helmet>
        <title>Pricing Calculator | Moneat</title>
        <meta
          name="description"
          content="Estimate your exact monthly Moneat cost. Dial in your errors, logs, AI events, and page views to see what you'd pay on each plan."
        />
        <link rel="canonical" href="https://moneat.io/pricing-calculator" />
      </Helmet>

      <header className="sticky top-0 z-50 w-full border-b border-border/50 bg-background/80 backdrop-blur-lg">
        <div className="flex h-16 items-center px-4 sm:px-6 lg:px-8 max-w-7xl mx-auto justify-between">
          <Link to="/" className="flex items-center" aria-label="Moneat Home">
            <Logo className="h-8" />
          </Link>
          <nav className="hidden md:flex items-center gap-8" aria-label="Main navigation">
            <a
              href="/#features"
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
              href="/#pricing"
              className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
            >
              Pricing
            </a>
            <Link
              to="/pricing-calculator"
              className="text-sm font-medium text-sky-500"
              aria-current="page"
            >
              Calculator
            </Link>
            <a
              href="/docs"
              className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
            >
              Docs
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
        <PricingCalculatorSection standalone />
      </main>

      <footer className="border-t border-border/50 bg-slate-950 py-10 px-4 sm:px-6 lg:px-8">
        <div className="max-w-6xl mx-auto flex flex-col sm:flex-row items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <Logo className="h-6" markOnly />
            <span className="text-sm font-medium text-white">moneat</span>
          </div>
          <nav className="flex items-center gap-6" aria-label="Footer navigation">
            <a href="/#pricing" className="text-sm text-slate-400 hover:text-sky-400 transition-colors">
              Pricing
            </a>
            <Link to="/signup" className="text-sm text-slate-400 hover:text-sky-400 transition-colors">
              Sign up
            </Link>
            <Link to="/legal/terms" className="text-sm text-slate-400 hover:text-sky-400 transition-colors">
              Terms
            </Link>
            <Link to="/legal/privacy" className="text-sm text-slate-400 hover:text-sky-400 transition-colors">
              Privacy
            </Link>
          </nav>
          <p className="text-xs text-slate-500">
            &copy; {new Date().getFullYear()} Moneat. All rights reserved.
          </p>
        </div>
      </footer>
    </article>
  )
}
