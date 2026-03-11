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

import {useEffect} from 'react'
import {createFileRoute} from '@tanstack/react-router'
import {PricingSection} from '@/components/landing/PricingSection'
import {PricingCalculatorSection} from '@/components/landing/PricingCalculatorSection'
import {LandingNavbar, LandingFooter} from '@/components/landing/LandingNavbar'
import {Helmet} from 'react-helmet-async'

export const Route = createFileRoute('/pricing')({
  component: PricingPage,
})

function PricingPage() {
  useEffect(() => {
    const root = document.documentElement
    const prev = root.className
    root.classList.add('dark')
    return () => { root.className = prev }
  }, [])

  return (
    <article className="min-h-screen bg-[#0a0b14]">
      <Helmet>
        <title>Pricing | Moneat</title>
        <meta
          name="description"
          content="Simple, transparent pricing for Moneat. Per-type limits so you only pay for what you use. Unlimited team members on every plan. Start free."
        />
        <link rel="canonical" href="https://moneat.io/pricing" />
      </Helmet>

      <LandingNavbar />

      <main>
        <PricingSection />
        <PricingCalculatorSection />
      </main>

      <LandingFooter />
    </article>
  )
}
