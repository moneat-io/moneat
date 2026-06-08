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
import {FileText, Filter, RefreshCw, Search, Braces, Link2} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {WindowFrame, StatTile, SignalChart} from '@/components/landing/Landing'
import {cn} from '@/lib/utils'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('log-management')

// Feature-specific hero: a live log stream — the surface log management
// actually shows. Filter pill + tail indicator + volume sparkline + log rows
// + stat strip. Built from the shared brand primitives (dark, mono, one gradient).
function LogManagementHero() {
  type LogLevel = 'INFO' | 'WARN' | 'ERROR'
  const logs: Array<{ts: string; level: LogLevel; service: string; message: string}> = [
    {ts: '14:22:01.842', level: 'ERROR', service: 'api',       message: 'upstream timeout: POST /v1/checkout → orders-svc [503]'},
    {ts: '14:22:01.719', level: 'ERROR', service: 'api',       message: 'failed to acquire db connection: pool exhausted (max=20)'},
    {ts: '14:22:01.503', level: 'WARN',  service: 'worker',    message: 'job retry #3: payment.charge event_id=evt_9kXm3'},
    {ts: '14:22:00.988', level: 'INFO',  service: 'ingest',    message: 'batch flushed rows=4182 dur=11ms topic=events'},
    {ts: '14:22:00.631', level: 'WARN',  service: 'scheduler', message: 'cron lag 2.1s: daily-rollup behind schedule'},
    {ts: '14:22:00.214', level: 'INFO',  service: 'api',       message: 'GET /health → 200 OK dur=1ms'},
  ]

  const levelStyle: Record<LogLevel, {badge: string; text: string}> = {
    ERROR: {badge: 'bg-rose-500/15 text-rose-400 border-rose-500/25',  text: 'text-rose-400'},
    WARN:  {badge: 'bg-amber-500/15 text-amber-400 border-amber-500/25', text: 'text-amber-400'},
    INFO:  {badge: 'bg-emerald-500/15 text-emerald-400 border-emerald-500/25', text: 'text-emerald-400'},
  }

  return (
    <div className="relative">
      {/* radial glow — mirrors the error-tracking hero */}
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(99,102,241,0.28),rgba(34,211,238,0.05)_60%,transparent)] blur-2xl"
      />
      <WindowFrame title="moneat · logs" live className="relative">
        <div className="grid gap-3">
          {/* search bar + tail indicator */}
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <span className="rounded-md border border-white/10 bg-white/[0.04] px-2 py-1 font-brandmono text-[11px] text-slate-300">
                service:api level:error
              </span>
            </div>
            <div className="flex items-center gap-2">
              <span className="size-1.5 animate-pulse rounded-full bg-emerald-400" />
              <span className="font-brandmono text-[11px] text-slate-500">tail</span>
            </div>
          </div>

          {/* volume sparkline */}
          <SignalChart heightClass="h-10" />

          {/* log rows */}
          <div className="overflow-hidden rounded-md border border-white/[0.07]">
            {logs.map((log, i) => (
              <div
                key={`${log.ts}-${i}`}
                className={cn(
                  'flex items-start gap-2.5 px-3 py-2',
                  i > 0 && 'border-t border-white/[0.06]',
                  i === 0 && 'bg-white/[0.03]',
                )}
              >
                <span className="shrink-0 font-brandmono text-[10px] text-slate-500 pt-px">{log.ts}</span>
                <span
                  className={cn(
                    'shrink-0 rounded border px-1 py-px font-brandmono text-[9px] uppercase tracking-[0.1em]',
                    levelStyle[log.level].badge,
                  )}
                >
                  {log.level}
                </span>
                <span className="shrink-0 rounded bg-white/[0.04] px-1.5 py-px font-brandmono text-[10px] text-slate-400">
                  {log.service}
                </span>
                <span className="min-w-0 flex-1 truncate font-brandmono text-[11px] text-slate-200">
                  {log.message}
                </span>
              </div>
            ))}
          </div>

          {/* stat strip */}
          <div className="grid grid-cols-3 gap-2.5">
            <StatTile label="ingest" value="12k/s" accent />
            <StatTile label="errors/min" value="28" />
            <StatTile label="retention" value="30d" />
          </div>
        </div>
      </WindowFrame>
    </div>
  )
}

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'Structured logging at scale',
  description: 'Structured JSON logs with auto-refresh, full-text search, and powerful filtering. Unified with your errors and traces for faster root-cause analysis.',
  metaDescription: pageSeo.metaDescription,
  icon: FileText,
  iconColor: 'text-blue-400',
  iconBg: 'bg-blue-500/10',
  gradient: 'from-blue-500 to-indigo-400',
  accentColor: 'text-blue-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Log management interface with real-time log viewer and filtering',
  subFeatures: [
    {icon: Search, title: 'Full-Text Search', description: 'Search across billions of log entries with sub-second response times.', iconColor: 'text-blue-400'},
    {icon: RefreshCw, title: 'Real-Time Streaming', description: 'Auto-refreshing log viewer that streams new entries as they arrive.', iconColor: 'text-cyan-400'},
    {icon: Filter, title: 'Powerful Filtering', description: 'Filter by severity, service, host, or any structured field. Save filters as views.', iconColor: 'text-indigo-400'},
    {icon: Braces, title: 'Structured JSON', description: 'First-class support for structured JSON logs with automatic field extraction.', iconColor: 'text-violet-400'},
    {icon: Link2, title: 'Correlated Signals', description: 'Jump from a log line to the related error, trace, or replay in one click.', iconColor: 'text-sky-400'},
    {icon: FileText, title: 'Log-Based Alerts', description: 'Set up alerts based on log patterns, error rates, or missing heartbeat logs.', iconColor: 'text-amber-400'},
  ],
  compatNote: 'Ingest logs via OpenTelemetry, compatible agents, or any source that sends structured JSON over HTTP.',
  heroVisual: <LogManagementHero />,
}

export const Route = createFileRoute('/log-management')({
  component: () => <FeaturePageTemplate config={config} />,
})
