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
import {Helmet} from 'react-helmet-async'
import {LandingNavbar, LandingFooter} from '@/components/landing/LandingNavbar'
import {useForceDarkTheme} from '@/components/landing/usePublicPageTheme'

export const Route = createFileRoute('/legal/sms-consent')({
  component: SmsConsentPage,
})

function SmsConsentPage() {
  useForceDarkTheme()

  return (
    <div className="min-h-screen bg-[#08090f] font-display text-slate-300">
      <Helmet>
        <title>SMS &amp; Voice On-Call Consent | Moneat</title>
        <meta name="description" content="How Moneat collects and manages consent for on-call SMS and voice alert notifications." />
      </Helmet>

      <LandingNavbar tone="dark" />

      <main className="mx-auto max-w-3xl px-6 py-12 md:py-16">
        <div className="mb-10 space-y-3">
          <p className="font-brandmono text-xs uppercase tracking-widest text-slate-500">Legal</p>
          <h1 className="text-3xl font-bold text-white">SMS &amp; Voice On-Call Consent</h1>
          <p className="font-brandmono text-xs text-slate-500">Last updated: February 2026</p>
        </div>

        <div className="space-y-8 border-t border-white/10 pt-8 text-sm leading-7">
          <section className="space-y-3">
            <h2 className="text-base font-semibold text-white">What you are consenting to</h2>
            <p>
              When you opt in to on-call SMS and voice alerts, Moneat will send you SMS messages and/or
              automated voice calls to notify you of active incidents that require your attention. These
              messages are sent as a fallback when a push notification or Slack alert has not been
              acknowledged within a configurable delay.
            </p>
            <p className="rounded border-l-4 border-indigo-500/60 bg-white/5 p-4 text-sm text-slate-300">
              <strong className="text-white">Exact opt-in language shown in the product:</strong>
              <br />
              "I agree to receive on-call alert SMS messages and voice calls from Moneat at the number
              provided. Message and data rates may apply. Reply STOP to unsubscribe or HELP for help.
              I understand I can manage this setting anytime in my account."
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-base font-semibold text-white">How consent is collected</h2>
            <ul className="list-disc space-y-2 pl-6 text-slate-300">
              <li>
                Consent is collected in-product via the <strong className="text-white">Notification Settings</strong> page in
                the Moneat dashboard, or via the <strong className="text-white">On-Call Alerts</strong> section in the Moneat
                mobile app.
              </li>
              <li>
                Users must enter a valid phone number in E.164 format (e.g. +15551234567) and
                explicitly check a required consent checkbox before saving.
              </li>
              <li>
                Submitting the form without checking the consent checkbox is not permitted — the Save
                button remains disabled.
              </li>
              <li>
                The date, time, IP address, and consent version are recorded at the time of opt-in and
                stored in an append-only audit log.
              </li>
              <li>
                Consent covers both SMS and voice call on-call fallback notifications from Moneat.
                Consent is user-level (not organization-level).
              </li>
            </ul>
          </section>

          <section className="space-y-3">
            <h2 className="text-base font-semibold text-white">Message frequency</h2>
            <p>
              Message frequency varies. On-call SMS and voice alerts are only sent when an active
              incident has not been acknowledged within the configured escalation delay. You will not
              receive promotional messages.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-base font-semibold text-white">How to stop messages (STOP / opt-out)</h2>
            <ul className="list-disc space-y-2 pl-6 text-slate-300">
              <li>
                Reply <strong className="text-white">STOP</strong>,{' '}
                <strong className="text-white">UNSUBSCRIBE</strong>,{' '}
                <strong className="text-white">CANCEL</strong>,{' '}
                <strong className="text-white">END</strong>, or{' '}
                <strong className="text-white">QUIT</strong> to any Moneat SMS to immediately
                unsubscribe. You will receive a confirmation message and no further on-call SMS or
                voice calls will be sent.
              </li>
              <li>
                You can also opt out at any time by clicking <strong className="text-white">Remove</strong> or{' '}
                <strong className="text-white">Opt out</strong> in your Notification Settings (dashboard or mobile app).
              </li>
              <li>
                Re-enabling SMS alerts after opting out requires you to go through the in-product
                consent flow again. Replying START or YES will not automatically re-enable alerts —
                this is intentional to ensure your consent is always auditable.
              </li>
            </ul>
          </section>

          <section className="space-y-3">
            <h2 className="text-base font-semibold text-white">HELP</h2>
            <p>
              Reply <strong className="text-white">HELP</strong> to any Moneat SMS for help. You will receive a short message
              with opt-out instructions and a support contact.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-base font-semibold text-white">Costs</h2>
            <p>Message and data rates may apply depending on your mobile carrier and plan.</p>
          </section>

          <section className="space-y-3">
            <h2 className="text-base font-semibold text-white">Support</h2>
            <p>
              For questions or assistance, contact us at{' '}
              <a href="mailto:support@moneat.io" className="text-indigo-300 hover:underline">
                support@moneat.io
              </a>
              .
            </p>
          </section>
        </div>
      </main>

      <LandingFooter tone="dark" />
    </div>
  )
}
