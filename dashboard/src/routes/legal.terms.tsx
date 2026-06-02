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
import {LEGAL_TERMS_VERSION} from '@/lib/legal'
import {SeoHead} from '@/components/SeoHead'
import {termsSeo} from '@/lib/seo/routes'
import {LandingNavbar, LandingFooter} from '@/components/landing/LandingNavbar'
import {useForceDarkTheme} from '@/components/landing/usePublicPageTheme'

export const Route = createFileRoute('/legal/terms')({
  component: TermsOfUsePage,
})

function TermsOfUsePage() {
  useForceDarkTheme()

  return (
    <div className="min-h-screen bg-[#08090f] font-display text-slate-300">
      <SeoHead seo={termsSeo} />

      <LandingNavbar tone="dark" />

      <main className="mx-auto max-w-4xl px-6 py-12 md:py-16">
        <div className="mb-10 space-y-3">
          <p className="font-brandmono text-xs uppercase tracking-widest text-slate-500">Legal</p>
          <h1 className="text-3xl font-bold tracking-tight text-white">Terms of Use</h1>
          <p className="font-brandmono text-xs text-slate-500">
            Last Updated: {LEGAL_TERMS_VERSION}
          </p>
          <p className="text-sm text-slate-400">
            These Terms of Use govern your access to and use of Moneat.
          </p>
        </div>

        <div className="space-y-8 border-t border-white/10 pt-8 text-sm leading-7 text-slate-300">
          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">1. Service and Eligibility</h2>
            <p>
              Moneat is an application monitoring and observability platform. You must be at least 18 years old or the authorized representative of a business to use the service.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">2. Account Responsibilities</h2>
            <p>
              You are responsible for maintaining the confidentiality of your account credentials, for all activity under your account, and for promptly notifying us of unauthorized access.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">3. Acceptable Use</h2>
            <p>
              You may not use Moneat to violate applicable law, interfere with service operation, attempt unauthorized access, transmit malicious code, or submit content you do not have the right to process. Excessive use that degrades service for others or abuses free or trial offerings may result in rate limiting, reduced functionality, or account restrictions.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">4. Fees and Billing</h2>
            <p>
              Paid plan pricing, usage limits, and billing terms are provided in-product or in your subscription order details. You are responsible for applicable taxes and timely payment of fees.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">5. Intellectual Property</h2>
            <p>
              Moneat and related materials are owned by Adrian Lee Elder, d/b/a Moneat. You retain ownership of your data. If you submit feedback, you grant us a worldwide, royalty-free license to use it to improve the service.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">6. Third-Party Services</h2>
            <p>
              Moneat may integrate with third-party infrastructure and providers. Your use of third-party services is subject to those providers&apos; terms and policies.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">7. Disclaimers</h2>
            <p>
              Moneat is provided on an &quot;as is&quot; and &quot;as available&quot; basis without warranties of any kind, whether express or implied, including implied warranties of merchantability, fitness for a particular purpose, and non-infringement.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">8. Limitation of Liability</h2>
            <p>
              To the maximum extent permitted by law, Adrian Lee Elder, d/b/a Moneat, is not liable for indirect, incidental, special, consequential, or punitive damages, or loss of profits, revenues, data, or goodwill.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">9. Indemnification</h2>
            <p>
              You agree to defend, indemnify, and hold harmless Adrian Lee Elder, d/b/a Moneat, from claims, damages, and expenses arising from your use of the service or breach of these terms.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">10. Suspension and Termination</h2>
            <p>
              We may suspend or terminate access for violations of these terms, security risk, or legal compliance reasons. You may stop using Moneat at any time.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">11. Governing Law and Venue</h2>
            <p>
              These terms are governed by the laws of North Carolina, United States, without regard to conflict-of-law principles. Venue for disputes is in courts located in North Carolina, unless otherwise required by law.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">11. Trademarks</h2>
            <p>
              Sentry is a registered trademark of Functional Software, Inc. Datadog is a registered trademark of
              Datadog, Inc. PagerDuty is a registered trademark of PagerDuty, Inc. All other trademarks and service
              marks are the property of their respective owners. Moneat is not affiliated with, endorsed by, or
              sponsored by any of these companies. Use of these names is solely for the purpose of describing
              compatibility with their respective products and protocols.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">12. Changes to These Terms</h2>
            <p>
              We may update these terms over time. Material updates will be reflected by a new Last Updated date and version. Continued use of Moneat after updates constitutes acceptance of revised terms.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">13. Contact</h2>
            <p>
              Adrian Elder, d/b/a Moneat
              <br />
              1235 East Blvd, Ste E PMB 2045, Charlotte, NC 28203, United States
              <br />
              Email:{' '}
              <a className="text-indigo-300 hover:underline" href="mailto:support@moneat.io">
                support@moneat.io
              </a>
            </p>
          </section>
        </div>

        <div className="mt-12 border-t border-white/10 pt-6 text-sm text-slate-500">
          <Link to="/signup" className="text-indigo-300 hover:underline">
            Back to signup
          </Link>
        </div>
      </main>

      <LandingFooter tone="dark" />
    </div>
  )
}
