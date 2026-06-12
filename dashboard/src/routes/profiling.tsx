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
import {Flame, Cpu, HardDrive, Clock, Layers, Search} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {WindowFrame, StatTile} from '@/components/landing/Landing'
import {cn} from '@/lib/utils'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('profiling')

// ── Brand constant (mirrors Landing export) ──────────────────────────────────
const GRADIENT_BG = 'bg-[linear-gradient(115deg,#8B5CF6_0%,#6366F1_48%,#22D3EE_100%)]'

// Flamegraph rows: each row is the call-stack level. Each frame has a label,
// an inline width (%), and a flag marking the hot-path segment on that level.
type FlameFrame = {label: string; width: number; hot?: boolean; self?: string}
type FlameRow = {frames: FlameFrame[]}

const FLAME_ROWS: FlameRow[] = [
  // row 0 — root
  {frames: [{label: 'main', width: 100}]},
  // row 1
  {frames: [
    {label: 'http.serve', width: 72, hot: true},
    {label: 'net.listen', width: 28},
  ]},
  // row 2
  {frames: [
    {label: 'router.dispatch', width: 18},
    {label: 'handler.ServeHTTP', width: 54, hot: true, self: '3%'},
    {label: 'tls.handshake', width: 28},
  ]},
  // row 3
  {frames: [
    {label: 'middleware.auth', width: 14},
    {label: 'db.Query', width: 42, hot: true, self: '31%'},
    {label: 'json.Marshal', width: 22},
    {label: 'log.write', width: 22},
  ]},
  // row 4
  {frames: [
    {label: 'sql.prepare', width: 12},
    {label: 'pgx.exec', width: 30, hot: true},
    {label: 'encoding.utf8', width: 20},
    {label: 'runtime.gc', width: 38},
  ]},
  // row 5 — leaf
  {frames: [
    {label: 'net.dial', width: 12},
    {label: 'pgx.readRow', width: 30, hot: true, self: '28%'},
    {label: 'sync.RWMutex', width: 20},
    {label: 'gcBgMarkWorker', width: 28},
    {label: 'mallocgc', width: 10},
  ]},
]

function ProfilingHero() {
  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(124,92,246,0.28),rgba(34,211,238,0.05)_60%,transparent)] blur-2xl"
      />
      <WindowFrame title="moneat · profile" className="relative">
        <div className="grid gap-3">
          {/* flamegraph label row */}
          <div className="flex items-center justify-between gap-2">
            <span className="font-brandmono text-[11px] text-slate-400">cpu · wall-time</span>
            <span className="font-brandmono text-[11px] text-slate-500">48 000 samples</span>
          </div>

          {/* flamegraph body */}
          <div className="overflow-hidden rounded-md border border-white/[0.07] bg-white/[0.02] p-2 pb-1">
            <div className="flex flex-col gap-[3px]">
              {FLAME_ROWS.map((row, rowIdx) => (
                <div key={rowIdx} className="flex gap-[2px]">
                  {row.frames.map((frame, frameIdx) => (
                    <div
                      key={frameIdx}
                      className={cn(
                        'relative flex min-w-0 items-center overflow-hidden rounded-[3px] px-1.5 py-[5px]',
                        frame.hot
                          ? cn(GRADIENT_BG, 'text-white')
                          : 'border border-white/[0.06] bg-white/[0.05] text-slate-400',
                      )}
                      style={{width: `${frame.width}%`}}
                    >
                      <span className="truncate font-brandmono text-[9.5px] leading-none">{frame.label}</span>
                      {frame.self ? (
                        <span
                          className={cn(
                            'ml-auto shrink-0 pl-1 font-brandmono text-[9px] leading-none',
                            frame.hot ? 'text-white/70' : 'text-slate-500',
                          )}
                        >
                          {frame.self}
                        </span>
                      ) : null}
                    </div>
                  ))}
                </div>
              ))}
            </div>
            {/* x-axis time ruler */}
            <div className="mt-1.5 flex justify-between px-0.5">
              {['0ms', '4ms', '8ms', '12ms', '16ms'].map((t) => (
                <span key={t} className="font-brandmono text-[9px] text-slate-600">{t}</span>
              ))}
            </div>
          </div>

          {/* stat strip */}
          <div className="grid grid-cols-3 gap-2.5">
            <StatTile label="samples" value="48k" accent />
            <StatTile label="on-CPU" value="72%" />
            <StatTile label="p99" value="12ms" />
          </div>
        </div>
      </WindowFrame>
    </div>
  )
}

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'Pinpoint hot paths in production',
  description:
    'CPU, heap, and wall-time profiles from production telemetry. Identify hot functions, memory leaks, ' +
    'and resource bottlenecks without adding application overhead.',
  metaDescription: pageSeo.metaDescription,
  icon: Flame,
  iconColor: 'text-red-400',
  iconBg: 'bg-red-500/10',
  gradient: 'from-red-500 to-orange-400',
  accentColor: 'text-red-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Continuous profiling flamegraph showing CPU and memory hotspots',
  subFeatures: [
    {icon: Flame, title: 'Flamegraphs', description: 'Interactive flamegraphs that visualize exactly where your application spends its time.', iconColor: 'text-red-400'},
    {icon: Cpu, title: 'CPU Profiling', description: 'Identify hot functions and optimize the code paths that consume the most CPU cycles.', iconColor: 'text-orange-400'},
    {icon: HardDrive, title: 'Heap Profiling', description: 'Track memory allocations and find memory leaks before they cause OOM kills.', iconColor: 'text-amber-400'},
    {icon: Clock, title: 'Wall-Time Profiles', description: 'Understand where time is spent including I/O waits, locks, and external calls.', iconColor: 'text-blue-400'},
    {
      icon: Layers,
      title: 'Multi-Language',
      description: 'Support for Go, Java, Python, Ruby, Node.js, .NET, and PHP via compatible profiling agents.',
      iconColor: 'text-violet-400',
    },
    {icon: Search, title: 'Compare Profiles', description: 'Diff profiles across deployments to see exactly what changed in your performance.', iconColor: 'text-cyan-400'},
  ],
  compatNote:
    'Profiles are collected through compatible profiling agents with near-zero overhead. Enable profiling with a ' +
    'single config flag.',
  heroVisual: <ProfilingHero />,
}

export const Route = createFileRoute('/profiling')({
  component: () => <FeaturePageTemplate config={config} />,
})
