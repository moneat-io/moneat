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
import {GitBranch, Globe, Palette, RefreshCw, Bell, Shield} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {WindowFrame, StatTile} from '@/components/landing/Landing'
import {cn} from '@/lib/utils'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('public-status-pages')

// 90 daily uptime bars for a single component row.
// Each entry: 'up' | 'deg' | 'down'
type BarState = 'up' | 'deg' | 'down'

function UptimeBars({bars}: {readonly bars: readonly BarState[]}) {
  return (
    <div className="flex items-end gap-[2px]">
      {bars.map((s, i) => (
        <div
          key={i}
          className={cn(
            'w-[3px] rounded-[1px]',
            s === 'up' ? 'h-3 bg-emerald-500/70' : s === 'deg' ? 'h-3 bg-amber-400/80' : 'h-2 bg-rose-500/80',
          )}
        />
      ))}
    </div>
  )
}

function mkBars(degradedAt: number[], downAt: number[] = []): BarState[] {
  return Array.from({length: 30}, (_, i) =>
    downAt.includes(i) ? 'down' : degradedAt.includes(i) ? 'deg' : 'up',
  )
}

type ComponentRow = {name: string; status: string; statusColor: string; bars: BarState[]}

function StatusPageHero() {
  const rows: ComponentRow[] = [
    {
      name: 'API',
      status: 'Operational',
      statusColor: 'text-emerald-400',
      bars: mkBars([]),
    },
    {
      name: 'Dashboard',
      status: 'Operational',
      statusColor: 'text-emerald-400',
      bars: mkBars([11]),
    },
    {
      name: 'Ingest pipeline',
      status: 'Degraded performance',
      statusColor: 'text-amber-400',
      bars: mkBars([6, 7, 28, 29]),
    },
    {
      name: 'Webhooks',
      status: 'Operational',
      statusColor: 'text-emerald-400',
      bars: mkBars([19], [20]),
    },
  ]

  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(16,185,129,0.22),rgba(34,211,238,0.05)_60%,transparent)] blur-2xl"
      />
      <WindowFrame title="moneat · status" live className="relative">
        <div className="grid gap-3">
          {/* Banner */}
          <div className="flex items-center justify-between gap-3 rounded-md border border-white/[0.07] bg-white/[0.02] px-3 py-2">
            <div className="flex items-center gap-2">
              <span className="size-2 shrink-0 rounded-full bg-emerald-400" />
              <span className="font-brandmono text-[12px] text-slate-100">All systems operational</span>
            </div>
            <span className="font-brandmono text-[10px] text-slate-500">2026-06-01 12:34:07 UTC</span>
          </div>

          {/* Component rows */}
          <div className="overflow-hidden rounded-md border border-white/[0.07]">
            {rows.map((row, i) => (
              <div
                key={row.name}
                className={cn(
                  'grid grid-cols-[1fr_auto] items-center gap-x-4 gap-y-1 px-3 py-2.5',
                  i > 0 && 'border-t border-white/[0.06]',
                  i === 2 && 'bg-white/[0.02]',
                )}
              >
                <span className="font-brandmono text-[12px] text-slate-200">{row.name}</span>
                <span className={cn('text-right font-brandmono text-[11px] font-medium', row.statusColor)}>
                  {row.status}
                </span>
                <div className="col-span-2">
                  <UptimeBars bars={row.bars} />
                </div>
              </div>
            ))}
          </div>

          {/* Footer stat row */}
          <div className="grid grid-cols-3 gap-2.5">
            <StatTile label="uptime 90d" value="99.98%" accent />
            <StatTile label="incidents 30d" value="2" />
            <StatTile label="subscribers" value="1,204" />
          </div>

          {/* Footer label */}
          <div className="text-right font-brandmono text-[10px] text-slate-500">
            Uptime 99.98% over 90 days
          </div>
        </div>
      </WindowFrame>
    </div>
  )
}

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'Keep your users informed',
  description: 'Beautiful public status pages with custom domains, automated from your monitors. Show your customers real-time service health with zero manual effort. Free on all plans.',
  metaDescription: pageSeo.metaDescription,
  icon: GitBranch,
  iconColor: 'text-cyan-400',
  iconBg: 'bg-cyan-500/10',
  gradient: 'from-cyan-500 to-teal-400',
  accentColor: 'text-cyan-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Public status page showing service health and uptime history',
  subFeatures: [
    {icon: Globe, title: 'Custom Domains', description: 'Host your status page on your own domain like status.yourapp.com with automatic SSL.', iconColor: 'text-cyan-400'},
    {icon: RefreshCw, title: 'Auto-Updated', description: 'Status pages update automatically based on your uptime monitors — no manual toggling.', iconColor: 'text-green-400'},
    {icon: Palette, title: 'Customizable', description: 'Match your brand with custom logos, colors, and component grouping.', iconColor: 'text-violet-400'},
    {icon: Bell, title: 'Subscriber Notifications', description: 'Let customers subscribe to status updates via email for the components they care about.', iconColor: 'text-amber-400'},
    {icon: GitBranch, title: 'Component Groups', description: 'Organize services into logical groups to give customers a clear view of what affects them.', iconColor: 'text-blue-400'},
    {icon: Shield, title: 'Free on All Plans', description: 'Status pages are included free on every plan, including the free tier.', iconColor: 'text-emerald-400'},
  ],
  heroVisual: <StatusPageHero />,
}

export const Route = createFileRoute('/public-status-pages')({
  component: () => <FeaturePageTemplate config={config} />,
})
