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
    description: 'The only open-source observability platform that works as a drop-in replacement for both Sentry and Datadog. Errors, logs, infrastructure, APM, AI observability, on-call, and status pages in one platform — compatible with existing Sentry SDKs and the Datadog Agent.',
    operatingSystem: 'Web',
    alternateName: ['Sentry alternative', 'Datadog alternative', 'open source Sentry alternative', 'self-hosted Datadog replacement'],
    offers: {
      '@type': 'Offer',
      price: '0',
      priceCurrency: 'USD',
    },
  }

  return (
    <article className="min-h-screen bg-background">
      <Helmet>
        <title>Moneat | Open-Source Sentry &amp; Datadog Alternative — Drop-In Compatible with Both</title>
        <meta
          name="description"
          content="The only observability platform that works as a drop-in replacement for both Sentry and Datadog. Use your existing Sentry SDKs and Datadog Agent — zero code changes. Open-source errors, logs, APM, infrastructure, on-call, and AI observability in one platform."
        />
        <meta name="keywords" content="Sentry alternative, Datadog alternative, open source Sentry alternative, self-hosted Datadog replacement, drop-in Datadog replacement, error monitoring, log management, APM, infrastructure monitoring, observability platform" />
        <link rel="canonical" href="https://moneat.io" />

        <meta property="og:type" content="website" />
        <meta property="og:url" content="https://moneat.io" />
        <meta property="og:title" content="Moneat — The Only Platform That Replaces Both Sentry & Datadog" />
        <meta property="og:description" content="Stop paying for Sentry and Datadog separately. The only platform that works as a drop-in replacement for both. Use your existing SDKs and agents — zero code changes." />
        <meta property="og:image" content="https://moneat.io/screenshots/dashboard.png" />

        <meta name="twitter:card" content="summary_large_image" />
        <meta name="twitter:title" content="Moneat — The Only Platform That Replaces Both Sentry & Datadog" />
        <meta name="twitter:description" content="Stop paying for Sentry and Datadog separately. The only platform that works as a drop-in replacement for both. Use your existing SDKs and agents — zero code changes." />
        <meta name="twitter:image" content="https://moneat.io/screenshots/dashboard.png" />

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
