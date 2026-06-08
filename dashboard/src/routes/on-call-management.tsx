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
import {Phone, Calendar, ArrowUpRight, MessageSquare, Clock, Users} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {WindowFrame, StatTile, GRADIENT_BG} from '@/components/landing/Landing'
import {cn} from '@/lib/utils'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('on-call-management')

// Feature-specific hero: on-call management panel showing who is on call now,
// an escalation policy chain, and one active incident row — the surface this
// feature actually shows.
function OnCallHero() {
  const escalationSteps: Array<{step: string; who: string; delay: string}> = [
    {step: 'L1', who: 'Jordan Diaz', delay: 'immediate'},
    {step: 'L2', who: 'Platform SRE', delay: 'after 5m'},
    {step: 'L3', who: 'Eng Leadership', delay: 'after 15m'},
  ]
  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(124,92,246,0.28),rgba(34,211,238,0.05)_60%,transparent)] blur-2xl"
      />
      <WindowFrame title="moneat · on-call" live className="relative">
        <div className="grid gap-3">
          {/* On call now */}
          <div className="flex items-center gap-3 rounded-md border border-white/[0.08] bg-white/[0.02] px-3 py-2.5">
            <div
              className={cn(
                'flex size-8 shrink-0 items-center justify-center rounded-md font-brandmono text-[12px] font-semibold text-white',
                GRADIENT_BG,
              )}
            >
              JD
            </div>
            <div className="min-w-0 flex-1">
              <div className="font-brandmono text-[12px] font-medium text-slate-100">Jordan Diaz</div>
              <div className="font-brandmono text-[10px] text-slate-500">Mon–Fri · 09:00–17:00</div>
            </div>
            <span className="rounded border border-emerald-500/30 bg-emerald-500/10 px-1.5 py-0.5 font-brandmono text-[10px] text-emerald-400">
              ON CALL
            </span>
          </div>

          {/* Escalation policy */}
          <div>
            <div className="mb-1.5 font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">
              Escalation policy
            </div>
            <div className="overflow-hidden rounded-md border border-white/[0.07]">
              {escalationSteps.map((s, i) => (
                <div
                  key={s.step}
                  className={cn(
                    'flex items-center gap-3 px-3 py-2',
                    i > 0 && 'border-t border-white/[0.06]',
                    i === 0 && 'bg-white/[0.03]',
                  )}
                >
                  <span className="w-5 shrink-0 font-brandmono text-[10px] font-semibold text-slate-400">{s.step}</span>
                  <span className="flex-1 font-brandmono text-[11px] text-slate-200">{s.who}</span>
                  <span className="font-brandmono text-[10px] text-slate-500">{s.delay}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Active incident */}
          <div>
            <div className="mb-1.5 font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">
              Active incidents
            </div>
            <div className="flex items-center gap-3 rounded-md border border-white/[0.08] bg-white/[0.02] px-3 py-2.5">
              <span className="size-2 shrink-0 rounded-full bg-rose-400" />
              <div className="min-w-0 flex-1">
                <div className="truncate font-brandmono text-[12px] text-slate-100">API latency SLO breach</div>
                <div className="font-brandmono text-[10px] text-slate-500">production · api-gateway</div>
              </div>
              <span className="rounded border border-rose-500/30 bg-rose-500/10 px-1.5 py-0.5 font-brandmono text-[10px] text-rose-400">
                FIRING
              </span>
            </div>
          </div>

          {/* Stat strip */}
          <div className="grid grid-cols-3 gap-2.5">
            <StatTile label="MTTA" value="3m" accent />
            <StatTile label="on call" value="4" />
            <StatTile label="open" value="1" />
          </div>
        </div>
      </WindowFrame>
    </div>
  )
}

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'The right person, every time',
  description: 'Manage on-call rotations and escalation policies. When things break, Moneat notifies the right person via phone call, SMS, Slack, or email — with automatic escalation if they don\'t respond.',
  metaDescription: pageSeo.metaDescription,
  icon: Phone,
  iconColor: 'text-orange-400',
  iconBg: 'bg-orange-500/10',
  gradient: 'from-orange-500 to-amber-400',
  accentColor: 'text-orange-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'On-call escalation policies with rotation schedules',
  heroVisual: <OnCallHero />,
  subFeatures: [
    {icon: Calendar, title: 'On-Call Schedules', description: 'Create rotating schedules with daily, weekly, or custom rotation patterns for your team.', iconColor: 'text-orange-400'},
    {icon: ArrowUpRight, title: 'Escalation Policies', description: 'Define multi-level escalation chains so incidents never go unacknowledged.', iconColor: 'text-red-400'},
    {icon: Phone, title: 'Phone & SMS Alerts', description: 'Wake the right person with phone calls and SMS when critical incidents fire.', iconColor: 'text-amber-400'},
    {icon: MessageSquare, title: 'Slack Integration', description: 'Create incident channels, acknowledge alerts, and resolve incidents directly from Slack.', iconColor: 'text-blue-400'},
    {icon: Clock, title: 'Incident Timeline', description: 'Track every acknowledgment, escalation, and resolution with a full audit timeline.', iconColor: 'text-violet-400'},
    {icon: Users, title: 'Team Management', description: 'Organize responders into teams and assign escalation policies per team or service.', iconColor: 'text-green-400'},
  ],
}

export const Route = createFileRoute('/on-call-management')({
  component: () => <FeaturePageTemplate config={config} />,
})
