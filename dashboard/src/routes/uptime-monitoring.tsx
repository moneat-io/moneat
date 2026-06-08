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
import {Globe, Clock, Bell, BarChart3, GitBranch, Phone} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {WindowFrame, StatTile} from '@/components/landing/Landing'
import {cn} from '@/lib/utils'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('uptime-monitoring')

// 24 bars per monitor: mostly emerald, occasional amber or rose blips.
// Encoded as 'g' (green), 'a' (amber), 'r' (rose).
type BarState = 'g' | 'a' | 'r'

function UptimeBars({pattern}: {readonly pattern: BarState[]}) {
  const color: Record<BarState, string> = {
    g: 'bg-emerald-500',
    a: 'bg-amber-400',
    r: 'bg-rose-500',
  }
  return (
    <div className="flex items-end gap-px">
      {pattern.map((state, i) => (
        <div
          key={i}
          className={cn('w-[4px] rounded-[1px]', color[state])}
          style={{height: state === 'g' ? '14px' : state === 'a' ? '10px' : '8px'}}
        />
      ))}
    </div>
  )
}

const G = 'g' as BarState
const A = 'a' as BarState
const R = 'r' as BarState

function UptimeMonitoringHero() {
  const monitors: Array<{
    name: string
    url: string
    uptime: string
    latency: string
    dot: string
    bars: BarState[]
  }> = [
    {
      name: 'API',
      url: 'api.acme.com',
      uptime: '99.98%',
      latency: '184ms',
      dot: 'bg-emerald-400',
      bars: [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,A,G,G,G,G,G],
    },
    {
      name: 'Web',
      url: 'acme.com',
      uptime: '100.00%',
      latency: '91ms',
      dot: 'bg-emerald-400',
      bars: [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
    },
    {
      name: 'Auth',
      url: 'auth.acme.com',
      uptime: '99.91%',
      latency: '312ms',
      dot: 'bg-emerald-400',
      bars: [G,G,G,G,G,G,G,G,G,G,G,A,G,R,G,G,G,G,G,G,G,G,G,G],
    },
    {
      name: 'Checkout',
      url: 'pay.acme.com',
      uptime: '99.99%',
      latency: '228ms',
      dot: 'bg-emerald-400',
      bars: [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,A,G],
    },
    {
      name: 'Webhooks',
      url: 'hooks.acme.com',
      uptime: '99.85%',
      latency: '447ms',
      dot: 'bg-amber-400',
      bars: [G,G,G,G,G,G,G,G,G,G,G,G,G,A,G,A,G,G,A,G,G,A,G,G],
    },
  ]

  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(16,185,129,0.22),rgba(34,211,238,0.05)_60%,transparent)] blur-2xl"
      />
      <WindowFrame title="moneat · uptime" live className="relative">
        <div className="grid gap-3">
          {/* column header */}
          <div className="flex items-center gap-2 px-0.5">
            <div className="flex-1" />
            <span className="w-14 font-brandmono text-[9px] uppercase tracking-[0.12em] text-slate-600">uptime</span>
            <span className="w-14 font-brandmono text-[9px] uppercase tracking-[0.12em] text-slate-600">p50 rtt</span>
            <span className="w-[106px] font-brandmono text-[9px] uppercase tracking-[0.12em] text-slate-600">
              last 24 checks
            </span>
          </div>

          {/* monitor rows */}
          <div className="overflow-hidden rounded-md border border-white/[0.07]">
            {monitors.map((m, i) => (
              <div
                key={m.url}
                className={cn(
                  'flex items-center gap-3 px-3 py-2',
                  i > 0 && 'border-t border-white/[0.06]',
                  i === 0 && 'bg-white/[0.03]',
                )}
              >
                <span className={cn('size-2 shrink-0 rounded-full', m.dot)} />
                <div className="min-w-0 flex-1">
                  <span className="font-brandmono text-[12px] text-slate-100">{m.name}</span>
                  <span className="font-brandmono text-[11px] text-slate-500"> · {m.url}</span>
                </div>
                <span className="w-14 font-brandmono text-[12px] text-slate-200">{m.uptime}</span>
                <span className="w-14 font-brandmono text-[12px] text-slate-400">{m.latency}</span>
                <UptimeBars pattern={m.bars} />
              </div>
            ))}
          </div>

          {/* stat strip */}
          <div className="grid grid-cols-3 gap-2.5">
            <StatTile label="monitors up" value="42/42" accent />
            <StatTile label="avg response" value="252ms" />
            <StatTile label="incidents" value="0" />
          </div>
        </div>
      </WindowFrame>
    </div>
  )
}

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'Never miss downtime',
  description: 'Monitor your services 24/7 with customizable check intervals. Get alerted instantly via phone, SMS, Slack, or Discord when something goes down. Automatic public status pages included.',
  metaDescription: pageSeo.metaDescription,
  icon: Globe,
  iconColor: 'text-green-400',
  iconBg: 'bg-green-500/10',
  gradient: 'from-green-500 to-emerald-400',
  accentColor: 'text-green-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Uptime monitoring dashboard with status checks and availability metrics',
  subFeatures: [
    {icon: Clock, title: 'Configurable Intervals', description: 'Check every 30 seconds to every 30 minutes. Choose the cadence that matches your SLA.', iconColor: 'text-green-400'},
    {icon: Bell, title: 'Multi-Channel Alerts', description: 'Get notified via phone calls, SMS, Slack, Discord, or email when monitors go down.', iconColor: 'text-rose-400'},
    {icon: BarChart3, title: 'Availability Reports', description: 'Track uptime percentages, response times, and SLA compliance over any time range.', iconColor: 'text-cyan-400'},
    {icon: GitBranch, title: 'Status Pages', description: 'Public status pages with custom domains, automated from your monitors, free on all tiers.', iconColor: 'text-violet-400'},
    {icon: Phone, title: 'On-Call Integration', description: 'Route alerts through on-call schedules and escalation policies to reach the right person.', iconColor: 'text-orange-400'},
    {icon: Globe, title: 'Global Checks', description: 'Monitor from multiple regions to detect localized outages and latency issues.', iconColor: 'text-sky-400'},
  ],
  heroVisual: <UptimeMonitoringHero />,
}

export const Route = createFileRoute('/uptime-monitoring')({
  component: () => <FeaturePageTemplate config={config} />,
})
