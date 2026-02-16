// Moneat - Mobile-First Error Monitoring Platform
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
import {Helmet} from 'react-helmet-async'

export const Route = createFileRoute('/legal/privacy')({
  component: PrivacyPolicyPage,
})

function PrivacyPolicyPage() {
  return (
    <main>
      <Helmet>
        <title>Privacy Policy | Moneat</title>
        <meta name="description" content="Privacy Policy for Moneat monitoring service." />
        <link rel="canonical" href="https://moneat.io/legal/privacy" />
      </Helmet>
      <div className="mx-auto max-w-4xl px-6 py-12 md:py-16">
        <div className="mb-10 space-y-3">
          <h1 className="text-3xl font-bold tracking-tight">Privacy Policy</h1>
          <p className="text-sm text-muted-foreground">
            Last Updated: {LEGAL_PRIVACY_VERSION}
          </p>
          <p className="text-sm text-muted-foreground">
            This Privacy Policy explains how Moneat collects, uses, and discloses information.
          </p>
        </div>

        <div className="space-y-8 text-sm leading-7 text-foreground/90">
          <section className="space-y-2">
            <h2 className="text-lg font-semibold">1. Information We Collect</h2>
            <p>
              We collect account information (such as name and email), service usage data, diagnostics and monitoring payloads you submit, billing and subscription data, and support communications.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-lg font-semibold">2. Sources of Information</h2>
            <p>
              Information is collected directly from you, from your use of Moneat, from connected integrations, and from service providers that support authentication, billing, hosting, and communications.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-lg font-semibold">3. How We Use Information</h2>
            <p>
              We use data to provide and secure Moneat, process subscriptions, support users, improve product functionality, detect abuse, and comply with legal obligations.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-lg font-semibold">4. Legal Bases (Where Applicable)</h2>
            <p>
              Depending on your jurisdiction, legal bases include contract performance, legitimate interests, consent where required, and compliance with legal obligations.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-lg font-semibold">5. Sharing and Disclosure</h2>
            <p>
              We may share information with subprocessors and service providers operating under contract, and when required by law, regulation, court order, or to protect rights, safety, and platform integrity.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-lg font-semibold">6. International Transfers</h2>
            <p>
              Your information may be transferred to and processed in countries outside your own. Where required, we use safeguards intended to protect personal data during such transfers.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-lg font-semibold">7. Data Retention</h2>
            <p>
              We retain personal data for as long as necessary to provide services, meet contractual and legal obligations, resolve disputes, enforce agreements, and maintain security and compliance records.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-lg font-semibold">8. Security</h2>
            <p>
              We use administrative, technical, and organizational measures designed to protect personal data. No method of transmission or storage can be guaranteed to be completely secure.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-lg font-semibold">9. Your Rights</h2>
            <p>
              Depending on your location, you may have rights to access, correct, delete, or export your data, object to or restrict certain processing, and exercise privacy rights under laws such as GDPR and CCPA.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-lg font-semibold">10. Do Not Track and Global Privacy Controls</h2>
            <p>
              Some browsers provide Do Not Track or similar signals. Moneat currently does not guarantee automatic response to all such signals across all contexts.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-lg font-semibold">11. Children&apos;s Privacy</h2>
            <p>
              Moneat is not intended for children under 13, and we do not knowingly collect personal data from children under 13.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-lg font-semibold">12. Changes to This Policy</h2>
            <p>
              We may update this Privacy Policy periodically. Updates are reflected by a new Last Updated date and version number.
            </p>
          </section>

          <section className="space-y-2">
            <h2 className="text-lg font-semibold">13. Contact</h2>
            <p>
              Adrian Lee Elder, d/b/a Moneat
              <br />
              144 Sierra Chase Dr, Statesville, NC 28677, United States
              <br />
              Email: <a className="text-primary hover:underline" href="mailto:support@moneat.io">support@moneat.io</a>
            </p>
          </section>
        </div>

        <div className="mt-12 border-t border-border pt-6 text-sm text-muted-foreground">
          <Link to="/signup" className="text-primary hover:underline">
            Back to signup
          </Link>
        </div>
      </div>
    </main>
  )
}
