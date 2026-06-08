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
import {LayoutDashboard, Database, LineChart, Grid3x3, Share2, Palette} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {WindowFrame, SignalChart, GRADIENT_TEXT} from '@/components/landing/Landing'
import {cn} from '@/lib/utils'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('custom-dashboards')

// Feature-specific hero: a mini multi-widget dashboard — the surface custom
// dashboards actually show. Built from shared brand primitives (dark, mono,
// one gradient). Mirrors the ErrorTrackingHero reference pattern.
function CustomDashboardsHero() {
  const endpoints: Array<{path: string; fill: string; count: string}> = [
    {path: '/api/orders', fill: 'w-[82%]', count: '14.2k'},
    {path: '/api/products', fill: 'w-[61%]', count: '10.5k'},
    {path: '/api/auth/token', fill: 'w-[38%]', count: '6.6k'},
    {path: '/api/users', fill: 'w-[24%]', count: '4.1k'},
  ]
  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(124,92,246,0.28),rgba(34,211,238,0.05)_60%,transparent)] blur-2xl"
      />
      <WindowFrame title="moneat · dashboard" live className="relative">
        <div className="grid gap-3">
          {/* Toolbar row */}
          <div className="flex items-center justify-between gap-3">
            <span className="rounded-md border border-white/10 bg-white/[0.04] px-2 py-1 font-brandmono text-[11px] text-slate-300">
              Production
            </span>
            <span className="font-brandmono text-[11px] text-slate-500">last 24h</span>
          </div>

          {/* 2-col widget grid */}
          <div className="grid grid-cols-2 gap-2.5">
            {/* (a) Latency p95 */}
            <div className="rounded-md border border-white/[0.08] bg-white/[0.03] px-3 py-2.5">
              <div className="mb-2 font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">
                Latency p95
              </div>
              <SignalChart heightClass="h-20" />
              <div className="mt-1.5 flex items-center justify-between">
                <span className="font-brandmono text-[11px] text-slate-400">avg</span>
                <span className="font-brandmono text-[12px] text-slate-200">142 ms</span>
              </div>
            </div>

            {/* (b) Requests */}
            <div className="rounded-md border border-white/[0.08] bg-white/[0.03] px-3 py-2.5">
              <div className="mb-2 font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">
                Requests
              </div>
              <div className={cn('font-brandmono text-[28px] font-semibold leading-none', GRADIENT_TEXT)}>
                4.1k/s
              </div>
              <div className="mt-2 font-brandmono text-[11px] text-emerald-400">+8% vs 1h ago</div>
              <div className="mt-1 font-brandmono text-[10px] text-slate-500">total: 354M today</div>
            </div>

            {/* (c) Top endpoints */}
            <div className="rounded-md border border-white/[0.08] bg-white/[0.03] px-3 py-2.5">
              <div className="mb-2 font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">
                Top endpoints
              </div>
              <div className="grid gap-1.5">
                {endpoints.map((ep) => (
                  <div key={ep.path} className="flex items-center gap-2">
                    <div className="min-w-0 flex-1">
                      <div className="mb-0.5 truncate font-brandmono text-[10px] text-slate-400">{ep.path}</div>
                      <div className="h-1.5 w-full overflow-hidden rounded-full bg-white/[0.06]">
                        <div className={cn('h-full rounded-full bg-indigo-500/70', ep.fill)} />
                      </div>
                    </div>
                    <span className="shrink-0 font-brandmono text-[10px] text-slate-300">{ep.count}</span>
                  </div>
                ))}
              </div>
            </div>

            {/* (d) Error rate — 2×2 stat tiles */}
            <div className="rounded-md border border-white/[0.08] bg-white/[0.03] px-3 py-2.5">
              <div className="mb-2 font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">
                Error rate
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div className="rounded border border-white/[0.06] bg-white/[0.02] px-2 py-1.5">
                  <div className="font-brandmono text-[9px] uppercase tracking-[0.1em] text-slate-500">rate</div>
                  <div className="font-brandmono text-[15px] text-rose-400">0.18%</div>
                </div>
                <div className="rounded border border-white/[0.06] bg-white/[0.02] px-2 py-1.5">
                  <div className="font-brandmono text-[9px] uppercase tracking-[0.1em] text-slate-500">5xx</div>
                  <div className="font-brandmono text-[15px] text-slate-200">638</div>
                </div>
                <div className="rounded border border-white/[0.06] bg-white/[0.02] px-2 py-1.5">
                  <div className="font-brandmono text-[9px] uppercase tracking-[0.1em] text-slate-500">4xx</div>
                  <div className="font-brandmono text-[15px] text-slate-200">2.4k</div>
                </div>
                <div className="rounded border border-white/[0.06] bg-white/[0.02] px-2 py-1.5">
                  <div className="font-brandmono text-[9px] uppercase tracking-[0.1em] text-slate-500">p99</div>
                  <div className="font-brandmono text-[15px] text-amber-400">318 ms</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </WindowFrame>
    </div>
  )
}

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'Visualize anything',
  description: 'Build custom dashboards with drag-and-drop widgets powered by any data source. Connect PostgreSQL, ClickHouse, BigQuery, or use built-in Moneat metrics to create the views your team needs.',
  metaDescription: pageSeo.metaDescription,
  icon: LayoutDashboard,
  iconColor: 'text-sky-400',
  iconBg: 'bg-sky-500/10',
  gradient: 'from-sky-500 to-blue-400',
  accentColor: 'text-sky-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Custom dashboard with multiple widgets showing metrics and charts',
  heroVisual: <CustomDashboardsHero />,
  subFeatures: [
    {icon: Grid3x3, title: 'Drag & Drop', description: 'Arrange widgets on a flexible grid. Resize, reorder, and organize your dashboard layout visually.', iconColor: 'text-sky-400'},
    {icon: Database, title: 'Custom Data Sources', description: 'Connect PostgreSQL, ClickHouse, BigQuery, or any SQL database to power your dashboard widgets.', iconColor: 'text-blue-400'},
    {icon: LineChart, title: 'Rich Visualizations', description: 'Time series, bar charts, tables, stat cards, and more — all with auto-refreshing data.', iconColor: 'text-violet-400'},
    {icon: LayoutDashboard, title: 'Built-in Metrics', description: 'Use Moneat\'s built-in error, log, uptime, and infrastructure metrics without any extra config.', iconColor: 'text-amber-400'},
    {icon: Share2, title: 'Shareable', description: 'Share dashboards across your team or embed them in internal tools and documentation.', iconColor: 'text-green-400'},
    {icon: Palette, title: 'Theming', description: 'Dashboards respect your workspace theme and look great in both light and dark mode.', iconColor: 'text-rose-400'},
  ],
}

export const Route = createFileRoute('/custom-dashboards')({
  component: () => <FeaturePageTemplate config={config} />,
})
