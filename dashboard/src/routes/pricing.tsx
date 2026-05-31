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

import {createFileRoute} from '@tanstack/react-router'
import {PricingSection} from '@/components/landing/PricingSection'
import {PricingCalculatorSection} from '@/components/landing/PricingCalculatorSection'
import {LandingNavbar, LandingFooter} from '@/components/landing/LandingNavbar'
import {SeoHead} from '@/components/SeoHead'
import {pricingSeo} from '@/lib/seo/routes'

export const Route = createFileRoute('/pricing')({
  component: PricingPage,
})

function PricingPage() {
  return (
    <article className="min-h-screen bg-white text-slate-950">
      <SeoHead seo={pricingSeo} />

      <LandingNavbar tone="light" />

      <main>
        <PricingSection />
        <PricingCalculatorSection />
      </main>

      <LandingFooter tone="light" />
    </article>
  )
}
