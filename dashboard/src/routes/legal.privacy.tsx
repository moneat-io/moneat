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
import {LEGAL_PRIVACY_VERSION} from '@/lib/legal'
import {SeoHead} from '@/components/SeoHead'
import {privacySeo} from '@/lib/seo/routes'
import {LandingNavbar, LandingFooter} from '@/components/landing/LandingNavbar'
import {useForceDarkTheme} from '@/components/landing/usePublicPageTheme'

export const Route = createFileRoute('/legal/privacy')({
  component: PrivacyPolicyPage,
})

function PrivacyPolicyPage() {
  useForceDarkTheme()

  return (
    <div className="min-h-screen bg-[#08090f] font-display text-slate-300">
      <SeoHead seo={privacySeo} />

      <LandingNavbar tone="dark" />

      <main className="mx-auto max-w-4xl px-6 py-12 md:py-16">
        <div className="mb-10 space-y-3">
          <p className="font-brandmono text-xs uppercase tracking-widest text-slate-500">Legal</p>
          <h1 className="text-3xl font-bold tracking-tight text-white">Privacy Policy</h1>
          <p className="font-brandmono text-xs text-slate-500">
            Last Updated: {LEGAL_PRIVACY_VERSION}
          </p>
          <p className="text-sm text-slate-400">
            This Privacy Policy explains how Moneat collects, uses, and discloses information.
          </p>
        </div>

        <div className="space-y-8 border-t border-white/10 pt-8 text-sm leading-7 text-slate-300">
          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">1. Information We Collect</h2>
            <p>
              We collect account information (such as name and email), service usage data, diagnostics and monitoring payloads you submit, billing and subscription data, and support communications.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">2. Sources of Information</h2>
            <p>
              Information is collected directly from you, from your use of Moneat, from connected integrations, and from service providers that support authentication, billing, hosting, and communications.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">3. How We Use Information</h2>
            <p>
              We use data to provide and secure Moneat, process subscriptions, support users, improve product functionality, detect abuse, and comply with legal obligations.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">4. Legal Bases (Where Applicable)</h2>
            <p>
              Depending on your jurisdiction, legal bases include contract performance, legitimate interests, consent where required, and compliance with legal obligations.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">5. Sharing and Disclosure</h2>
            <p>
              We may share information with subprocessors and service providers operating under contract, and when required by law, regulation, court order, or to protect rights, safety, and platform integrity.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">6. International Transfers</h2>
            <p>
              Your information may be transferred to and processed in countries outside your own. Where required, we use safeguards intended to protect personal data during such transfers.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">7. Data Retention</h2>
            <p>
              We retain personal data for as long as necessary to provide services, meet contractual and legal obligations, resolve disputes, enforce agreements, and maintain security and compliance records.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">8. Security</h2>
            <p>
              We use administrative, technical, and organizational measures designed to protect personal data. No method of transmission or storage can be guaranteed to be completely secure.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">9. Your Rights</h2>
            <p>
              Depending on your location, you may have rights to access, correct, delete, or export your data, object to or restrict certain processing, and exercise privacy rights under laws such as GDPR and CCPA.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">10. Do Not Track and Global Privacy Controls</h2>
            <p>
              Some browsers provide Do Not Track or similar signals. Moneat currently does not guarantee automatic response to all such signals across all contexts.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">11. Children&apos;s Privacy</h2>
            <p>
              Moneat is not intended for children under 13, and we do not knowingly collect personal data from children under 13.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-base font-semibold text-white">12. Changes to This Policy</h2>
            <p>
              We may update this Privacy Policy periodically. Updates are reflected by a new Last Updated date and version number.
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
