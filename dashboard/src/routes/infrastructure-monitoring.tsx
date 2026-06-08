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
import {Server, Cpu, HardDrive, Box, Network, Database} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {WindowFrame, StatTile} from '@/components/landing/Landing'
import {cn} from '@/lib/utils'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('infrastructure-monitoring')

// Feature-specific hero: a live host-fleet list, the surface infrastructure
// monitoring actually shows. Status dot + hostname + region + CPU/MEM inline
// meters; stat strip for fleet-wide KPIs. Built from shared brand primitives.
function InfrastructureMonitoringHero() {
  const hosts: Array<{
    name: string
    role: string
    region: string
    cpu: number
    mem: number
  }> = [
    {name: 'ip-10-0-3-14', role: 'api', region: 'us-east-1', cpu: 71, mem: 58},
    {name: 'ip-10-0-3-22', role: 'worker', region: 'us-east-1', cpu: 88, mem: 82},
    {name: 'ip-10-0-1-9', role: 'db-replica', region: 'us-west-2', cpu: 34, mem: 67},
    {name: 'ip-10-0-5-3', role: 'cache', region: 'eu-west-1', cpu: 21, mem: 44},
    {name: 'ip-10-0-2-17', role: 'api', region: 'ap-southeast-1', cpu: 56, mem: 73},
    {name: 'ip-10-0-4-11', role: 'batch', region: 'us-east-1', cpu: 91, mem: 55},
  ]

  function MeterBar({pct}: {readonly pct: number}) {
    const high = pct >= 80
    return (
      <div className="flex items-center gap-1.5">
        <div className="h-1.5 w-16 overflow-hidden rounded-full bg-white/10">
          <div
            className={cn('h-full rounded-full', high ? 'bg-amber-400' : 'bg-indigo-400')}
            style={{width: `${pct}%`}}
          />
        </div>
        <span className={cn('w-7 font-brandmono text-[10px]', high ? 'text-amber-400' : 'text-slate-400')}>
          {pct}%
        </span>
      </div>
    )
  }

  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(124,92,246,0.28),rgba(34,211,238,0.05)_60%,transparent)] blur-2xl"
      />
      <WindowFrame title="moneat · hosts" live className="relative">
        <div className="grid gap-3">
          <div className="flex items-center justify-between gap-3">
            <span className="font-brandmono text-[11px] text-slate-500">128 hosts · 6 shown</span>
            <span className="font-brandmono text-[11px] text-slate-500">live</span>
          </div>
          <div className="overflow-hidden rounded-md border border-white/[0.07]">
            {/* header */}
            <div className="grid grid-cols-[1fr_auto_auto] items-center gap-x-3 border-b border-white/[0.06] bg-white/[0.02] px-3 py-1.5">
              <span className="font-brandmono text-[9px] uppercase tracking-[0.12em] text-slate-500">host / role</span>
              <span className="w-24 font-brandmono text-[9px] uppercase tracking-[0.12em] text-slate-500">cpu</span>
              <span className="w-24 font-brandmono text-[9px] uppercase tracking-[0.12em] text-slate-500">mem</span>
            </div>
            {hosts.map((h, i) => (
              <div
                key={h.name}
                className={cn(
                  'grid grid-cols-[1fr_auto_auto] items-center gap-x-3 px-3 py-2',
                  i > 0 && 'border-t border-white/[0.06]',
                  i === 0 && 'bg-white/[0.03]',
                )}
              >
                <div className="flex min-w-0 items-center gap-2">
                  <span
                    className={cn(
                      'size-1.5 shrink-0 rounded-full',
                      h.cpu >= 80 ? 'bg-amber-400' : 'bg-emerald-400',
                    )}
                  />
                  <div className="min-w-0">
                    <div className="truncate font-brandmono text-[12px] text-slate-100">
                      {h.name} · {h.role}
                    </div>
                    <div className="font-brandmono text-[10px] text-slate-500">{h.region}</div>
                  </div>
                </div>
                <div className="w-24">
                  <MeterBar pct={h.cpu} />
                </div>
                <div className="w-24">
                  <MeterBar pct={h.mem} />
                </div>
              </div>
            ))}
          </div>
          <div className="grid grid-cols-3 gap-2.5">
            <StatTile label="hosts" value="128" accent />
            <StatTile label="avg cpu" value="54%" />
            <StatTile label="avg mem" value="63%" />
          </div>
        </div>
      </WindowFrame>
    </div>
  )
}

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'Infrastructure telemetry',
  description:
    'Monitor hosts, containers, Kubernetes clusters, and databases with real-time infrastructure telemetry. ' +
    'Point compatible agents at Moneat and get full visibility without changing your applications.',
  metaDescription: pageSeo.metaDescription,
  icon: Server,
  iconColor: 'text-orange-400',
  iconBg: 'bg-orange-500/10',
  gradient: 'from-orange-500 to-amber-400',
  accentColor: 'text-orange-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Infrastructure monitoring showing host and container metrics',
  subFeatures: [
    {icon: Cpu, title: 'Host Metrics', description: 'CPU, memory, disk, network, and load metrics from every host in your fleet.', iconColor: 'text-orange-400'},
    {icon: Box, title: 'Container Monitoring', description: 'Real-time Docker container metrics including CPU, memory, I/O, and network per container.', iconColor: 'text-blue-400'},
    {icon: Network, title: 'Kubernetes', description: 'Cluster, node, pod, and deployment metrics with automatic discovery and labeling.', iconColor: 'text-cyan-400'},
    {icon: Database, title: 'Database Monitoring', description: 'Track query performance, connections, and resource usage for PostgreSQL, MySQL, and more.', iconColor: 'text-violet-400'},
    {icon: HardDrive, title: 'Process Monitoring', description: 'Track individual processes, resource consumption, and detect runaway processes.', iconColor: 'text-amber-400'},
    {
      icon: Server,
      title: 'Custom Metrics',
      description:
        'Send custom metrics via OpenTelemetry, DogStatsD, or compatible agents and visualize them ' +
        'on dashboards.',
      iconColor: 'text-green-400',
    },
  ],
  compatNote:
    'Works with compatible agents, including the Datadog Agent. Point the intake URL at your Moneat instance ' +
    'and infrastructure data flows through.',
  heroVisual: <InfrastructureMonitoringHero />,
}

export const Route = createFileRoute('/infrastructure-monitoring')({
  component: () => <FeaturePageTemplate config={config} />,
})
