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
import {Activity, Fingerprint, Layers, Search, Tag, Workflow} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {WindowFrame, StatTile} from '@/components/landing/Landing'
import {cn} from '@/lib/utils'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('error-tracking')

// Feature-specific hero: a grouped-issues list, the surface error tracking
// actually shows. Built from the shared brand primitives (dark, mono, one
// gradient). This is the reference pattern for the other feature heroes.
function ErrorTrackingHero() {
  const issues: Array<{title: string; culprit: string; events: string; dot: string}> = [
    {title: "TypeError: undefined is not a function", culprit: 'checkout/PaymentForm.tsx', events: '1.2k', dot: 'bg-rose-400'},
    {title: 'TimeoutError: upstream request failed', culprit: 'api/orders.ts in handler', events: '438', dot: 'bg-rose-400'},
    {title: 'DeprecationWarning: legacy client', culprit: 'lib/legacyClient.ts', events: '212', dot: 'bg-amber-400'},
    {title: 'NetworkError: failed to fetch', culprit: 'web/useFeed.ts', events: '96', dot: 'bg-rose-400'},
  ]
  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(124,92,246,0.28),rgba(34,211,238,0.05)_60%,transparent)] blur-2xl"
      />
      <WindowFrame title="moneat · issues" live className="relative">
        <div className="grid gap-3">
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <span className="rounded-md border border-white/10 bg-white/[0.04] px-2 py-1 font-brandmono text-[11px] text-slate-300">
                is:unresolved
              </span>
              <span className="font-brandmono text-[11px] text-slate-500">24 issues</span>
            </div>
            <span className="font-brandmono text-[11px] text-slate-500">last 24h</span>
          </div>
          <div className="overflow-hidden rounded-md border border-white/[0.07]">
            {issues.map((it, i) => (
              <div
                key={it.title}
                className={cn(
                  'flex items-center gap-3 px-3 py-2.5',
                  i > 0 && 'border-t border-white/[0.06]',
                  i === 0 && 'bg-white/[0.03]',
                )}
              >
                <span className={cn('size-2 shrink-0 rounded-full', it.dot)} />
                <div className="min-w-0 flex-1">
                  <div className="truncate font-brandmono text-[12px] text-slate-100">{it.title}</div>
                  <div className="truncate font-brandmono text-[10px] text-slate-500">{it.culprit}</div>
                </div>
                <div className="text-right">
                  <div className="font-brandmono text-[12px] text-slate-200">{it.events}</div>
                  <div className="font-brandmono text-[9px] uppercase tracking-[0.12em] text-slate-500">events</div>
                </div>
              </div>
            ))}
          </div>
          <div className="grid grid-cols-3 gap-2.5">
            <StatTile label="open" value="24" accent />
            <StatTile label="resolved 24h" value="61" />
            <StatTile label="users hit" value="312" />
          </div>
        </div>
      </WindowFrame>
    </div>
  )
}

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'Triage production exceptions',
  description:
    'Catch, group, and triage errors with smart fingerprinting. See full stack traces, breadcrumbs, and user ' +
    'context for every exception across all your projects. Works with Sentry-compatible SDKs.',
  metaDescription: pageSeo.metaDescription,
  icon: Activity,
  iconColor: 'text-sky-400',
  iconBg: 'bg-sky-500/10',
  gradient: 'from-sky-500 to-cyan-400',
  accentColor: 'text-sky-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Error tracking dashboard showing issues list with stack traces and context',
  subFeatures: [
    {icon: Fingerprint, title: 'Smart Fingerprinting', description: 'Automatically group similar errors together with intelligent fingerprinting that understands your stack.', iconColor: 'text-sky-400'},
    {icon: Layers, title: 'Stack Traces & Breadcrumbs', description: 'Full stack traces with source context, plus breadcrumbs showing what led up to each error.', iconColor: 'text-blue-400'},
    {icon: Search, title: 'Full-Text Search', description: 'Search across all your errors by message, user, tag, or any custom attribute you send.', iconColor: 'text-cyan-400'},
    {icon: Tag, title: 'Tags & Context', description: 'Attach user info, device details, custom tags, and structured context to every event.', iconColor: 'text-violet-400'},
    {icon: Workflow, title: 'Issue Workflow', description: 'Mark issues as resolved, ignored, or regressed. Get notified when resolved issues recur.', iconColor: 'text-amber-400'},
    {icon: Activity, title: 'Release Tracking', description: 'Track which releases introduced new errors and which resolved them.', iconColor: 'text-green-400'},
  ],
  compatNote:
    'Compatible with Sentry SDKs for 100+ platforms. Just change your DSN and redeploy — no code changes required.',
  heroVisual: <ErrorTrackingHero />,
}

export const Route = createFileRoute('/error-tracking')({
  component: () => <FeaturePageTemplate config={config} />,
})
