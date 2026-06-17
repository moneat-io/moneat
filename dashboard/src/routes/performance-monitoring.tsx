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
import {Zap, Activity, Timer, GitBranch, BarChart3, Search} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {WindowFrame, StatTile, GRADIENT_BG} from '@/components/landing/Landing'
import {cn} from '@/lib/utils'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('performance-monitoring')

// Distributed-trace waterfall — the actual surface performance monitoring shows.
// Header: transaction name + total duration. Six span rows with indented nesting,
// gradient-tinted bars at varied offsets. Stat strip at the bottom.
function PerformanceMonitoringHero() {
  // Each span: label (service.op), depth (indent level), bar offset + width (0–100%),
  // bar style ('gradient' | 'indigo' | 'cyan'), duration label.
  const spans: Array<{
    op: string
    depth: number
    offsetPct: number
    widthPct: number
    style: 'gradient' | 'indigo' | 'cyan'
    duration: string
  }> = [
    {op: 'api.handler',    depth: 0, offsetPct:  0, widthPct: 100, style: 'gradient', duration: '312ms'},
    {op: 'auth.verify',    depth: 1, offsetPct:  1, widthPct:  14, style: 'cyan',     duration:  '44ms'},
    {op: 'cache.get',      depth: 1, offsetPct: 16, widthPct:   8, style: 'cyan',     duration:  '25ms'},
    {op: 'db.query',       depth: 1, offsetPct: 25, widthPct:  38, style: 'indigo',   duration: '118ms'},
    {op: 'db.query',       depth: 2, offsetPct: 26, widthPct:  17, style: 'indigo',   duration:  '53ms'},
    {op: 'http.client',    depth: 1, offsetPct: 64, widthPct:  27, style: 'gradient', duration:  '84ms'},
  ]

  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(99,102,241,0.28),rgba(34,211,238,0.05)_60%,transparent)] blur-2xl"
      />
      <WindowFrame title="moneat · transactions" className="relative">
        <div className="grid gap-3">
          {/* Transaction header */}
          <div className="flex items-baseline justify-between gap-3">
            <span className="font-brandmono text-[13px] font-semibold text-slate-100">
              GET /api/checkout
            </span>
            <span className="font-brandmono text-[12px] text-slate-400">312ms</span>
          </div>

          {/* Span waterfall */}
          <div className="overflow-hidden rounded-md border border-white/[0.07]">
            {spans.map((span, i) => (
              <div
                key={`${span.op}-${i}`}
                className={cn(
                  'flex items-center gap-2 px-3 py-2',
                  i > 0 && 'border-t border-white/[0.06]',
                  i === 0 && 'bg-white/[0.03]',
                )}
              >
                {/* Service.op label — indented by depth */}
                <span
                  className="w-[96px] shrink-0 font-brandmono text-[11px] text-slate-400"
                  style={{paddingLeft: span.depth * 10}}
                >
                  {span.op}
                </span>

                {/* Bar track */}
                <div className="relative h-5 flex-1 rounded bg-white/[0.03]">
                  <div
                    className={cn(
                      'absolute inset-y-[3px] rounded',
                      span.style === 'gradient' && GRADIENT_BG,
                      span.style === 'indigo'   && 'bg-indigo-500/70',
                      span.style === 'cyan'     && 'bg-cyan-500/70',
                    )}
                    style={{
                      left:  `${span.offsetPct}%`,
                      width: `${span.widthPct}%`,
                    }}
                  />
                  {/* Duration label just past bar end */}
                  <span
                    className="absolute inset-y-0 flex items-center font-brandmono text-[10px] text-slate-500"
                    style={{left: `calc(${span.offsetPct + span.widthPct}% + 4px)`}}
                  >
                    {span.duration}
                  </span>
                </div>
              </div>
            ))}
          </div>

          {/* Stat strip */}
          <div className="grid grid-cols-3 gap-2.5">
            <StatTile label="p95" value="184ms" accent />
            <StatTile label="throughput" value="1.2k/s" />
            <StatTile label="apdex" value="0.97" />
          </div>
        </div>
      </WindowFrame>
    </div>
  )
}

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'Find slow endpoints fast',
  description: 'Track transactions and spans across your services. Find slow endpoints, database queries, and external calls before your users notice. Distributed tracing with full context.',
  metaDescription: pageSeo.metaDescription,
  icon: Zap,
  iconColor: 'text-amber-400',
  iconBg: 'bg-amber-500/10',
  gradient: 'from-amber-500 to-orange-400',
  accentColor: 'text-amber-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Performance monitoring dashboard with transaction timings and span waterfall',
  subFeatures: [
    {icon: Activity, title: 'Transaction Tracking', description: 'Automatically capture every HTTP request, background job, and queue consumer as a transaction.', iconColor: 'text-amber-400'},
    {icon: Timer, title: 'Span Waterfall', description: 'See the full breakdown of every transaction with database queries, HTTP calls, and custom spans.', iconColor: 'text-orange-400'},
    {icon: GitBranch, title: 'Distributed Tracing', description: 'Follow requests across services with trace context propagation and correlated spans.', iconColor: 'text-blue-400'},
    {icon: BarChart3, title: 'P50/P95/P99 Latency', description: 'Track percentile latencies over time. Spot regressions before they become incidents.', iconColor: 'text-cyan-400'},
    {icon: Search, title: 'Trace Search', description: 'Search traces by duration, status, tags, or any custom attribute attached to spans.', iconColor: 'text-violet-400'},
    {icon: Zap, title: 'Apdex Scoring', description: 'Measure user satisfaction with Apdex scores based on configurable thresholds.', iconColor: 'text-green-400'},
  ],
  compatNote: 'Ingest traces via OpenTelemetry SDKs, Sentry-compatible SDKs, or compatible agents simultaneously.',
  heroVisual: <PerformanceMonitoringHero />,
}

export const Route = createFileRoute('/performance-monitoring')({
  component: () => <FeaturePageTemplate config={config} />,
})
