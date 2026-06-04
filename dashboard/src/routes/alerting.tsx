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
import {Bell, MessageSquare, Mail, Filter, Workflow, Phone} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {WindowFrame, StatTile, SignalChart} from '@/components/landing/Landing'
import {cn} from '@/lib/utils'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('alerting')

// Feature-specific hero: a live alert firing on a CPU threshold, with a
// chart overlay, firing + recent-alert rows, and a stat strip.
function AlertingHero() {
  const recentAlerts: Array<{label: string; monitor: string; ts: string; state: 'firing' | 'resolved'}> = [
    {label: 'Memory > 90% for 3m', monitor: 'worker-pool-2', ts: '14:02:11', state: 'resolved'},
    {label: 'p99 latency > 500ms', monitor: 'api-prod', ts: '13:48:37', state: 'firing'},
  ]
  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(244,63,94,0.22),rgba(34,211,238,0.04)_60%,transparent)] blur-2xl"
      />
      <WindowFrame title="moneat · alerts" live className="relative">
        <div className="grid gap-3">
          {/* Chart panel with threshold overlay */}
          <div className="overflow-hidden rounded-md border border-white/[0.07] bg-white/[0.02] px-3 pb-2 pt-2">
            <div className="mb-1.5 flex items-center justify-between">
              <span className="font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">cpu utilization · api-prod</span>
              <span className="font-brandmono text-[10px] text-slate-500">last 30m</span>
            </div>
            <div className="relative">
              <SignalChart heightClass="h-24" />
              {/* Threshold line */}
              <div className="pointer-events-none absolute inset-x-0 top-[18%] flex items-center gap-1.5">
                <div className="h-0 flex-1 border-t border-dashed border-rose-400/50" />
                <span className="font-brandmono text-[9px] text-rose-400/80">threshold 80%</span>
              </div>
            </div>
          </div>

          {/* Active firing alert row */}
          <div className="overflow-hidden rounded-md border border-white/[0.07]">
            <div className="flex items-center gap-3 bg-white/[0.03] px-3 py-2.5">
              <span className="size-2 shrink-0 rounded-full bg-rose-400" />
              <div className="min-w-0 flex-1">
                <div className="font-brandmono text-[12px] text-slate-100">CPU &gt; 80% for 5m</div>
                <div className="font-brandmono text-[10px] text-slate-500">api-prod</div>
              </div>
              <span className="shrink-0 rounded border border-rose-400/30 bg-rose-500/10 px-2 py-0.5 font-brandmono text-[10px] uppercase tracking-[0.1em] text-rose-400">
                FIRING
              </span>
            </div>

            {/* Recent alert rows */}
            {recentAlerts.map((alert) => (
              <div
                key={alert.ts}
                className="flex items-center gap-3 border-t border-white/[0.06] px-3 py-2"
              >
                <span
                  className={cn(
                    'size-2 shrink-0 rounded-full',
                    alert.state === 'firing' ? 'bg-rose-400' : 'bg-emerald-400',
                  )}
                />
                <div className="min-w-0 flex-1">
                  <div className="truncate font-brandmono text-[11px] text-slate-200">{alert.label}</div>
                  <div className="font-brandmono text-[10px] text-slate-500">{alert.monitor}</div>
                </div>
                <div className="flex flex-col items-end gap-0.5">
                  <span
                    className={cn(
                      'font-brandmono text-[10px] uppercase tracking-[0.08em]',
                      alert.state === 'firing' ? 'text-rose-400' : 'text-emerald-400',
                    )}
                  >
                    {alert.state === 'firing' ? 'Firing' : 'Resolved'}
                  </span>
                  <span className="font-brandmono text-[9px] text-slate-500">{alert.ts}</span>
                </div>
              </div>
            ))}
          </div>

          {/* Stat strip */}
          <div className="grid grid-cols-3 gap-2.5">
            <StatTile label="firing" value="2" accent />
            <StatTile label="resolved 24h" value="17" />
            <StatTile label="MTTR" value="8m" />
          </div>
        </div>
      </WindowFrame>
    </div>
  )
}

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'Never miss what matters',
  description: 'Multi-channel alerts with Slack, Discord, email, phone, and SMS integrations. Route alerts to the right teams instantly with flexible rules and escalation policies.',
  metaDescription: pageSeo.metaDescription,
  icon: Bell,
  iconColor: 'text-rose-400',
  iconBg: 'bg-rose-500/10',
  gradient: 'from-rose-500 to-pink-400',
  accentColor: 'text-rose-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Alerting configuration with multi-channel notification settings',
  subFeatures: [
    {icon: MessageSquare, title: 'Slack & Discord', description: 'Rich alert notifications in Slack and Discord with actionable buttons to acknowledge or resolve.', iconColor: 'text-rose-400'},
    {icon: Mail, title: 'Email Alerts', description: 'Detailed email notifications with error context, stack traces, and direct links to investigate.', iconColor: 'text-blue-400'},
    {icon: Phone, title: 'Phone & SMS', description: 'Critical alerts that call or text the on-call responder when immediate attention is needed.', iconColor: 'text-amber-400'},
    {icon: Filter, title: 'Alert Rules', description: 'Configure alert thresholds on error rates, log patterns, uptime, or any metric you track.', iconColor: 'text-violet-400'},
    {icon: Workflow, title: 'Routing Rules', description: 'Route different alert types to different channels and teams based on service, severity, or tags.', iconColor: 'text-cyan-400'},
    {icon: Bell, title: 'Digest & Dedup', description: 'Group related alerts together to avoid notification fatigue during incident storms.', iconColor: 'text-green-400'},
  ],
  heroVisual: <AlertingHero />,
}

export const Route = createFileRoute('/alerting')({
  component: () => <FeaturePageTemplate config={config} />,
})
