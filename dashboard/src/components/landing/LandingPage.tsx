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
import {VariantA} from './VariantA'
import {LandingNavbar, LandingFooter} from './LandingNavbar'
import {Helmet} from 'react-helmet-async'

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

      <LandingNavbar />

      <main>
        <VariantA />
      </main>

      <LandingFooter />
    </article>
  )
}
