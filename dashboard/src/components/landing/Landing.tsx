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

import {useEffect, useId, useRef} from 'react'
import {Link} from '@tanstack/react-router'
import {
  Activity,
  ArrowRight,
  Bell,
  Check,
  Github,
  Minus,
  Play,
  Server,
  ShieldCheck,
  Zap,
  type LucideIcon,
} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {cn} from '@/lib/utils'
import {compareColumns, compareRows, SOURCE_REVIEW_DATE, type CellValue} from './competitorComparisonData'

// ── Brand signal: one violet→indigo→cyan gradient (style-guide) ──────────────
const GRADIENT_BG = 'bg-[linear-gradient(115deg,#8B5CF6_0%,#6366F1_48%,#22D3EE_100%)]'
const GRADIENT_BAR = 'bg-[linear-gradient(90deg,#8B5CF6,#6366F1_50%,#22D3EE)]'
const GRADIENT_TEXT =
  'bg-[linear-gradient(115deg,#8B5CF6_0%,#6366F1_48%,#22D3EE_100%)] bg-clip-text text-transparent'

// ─────────────────────────────────────────────────────────────────────────────
// Shared product-shot frame. Kept for FeaturePageTemplate; the home page below
// builds its product UI directly instead of embedding screenshots.
// ─────────────────────────────────────────────────────────────────────────────
export function ScreenshotFrame({
  gradient,
  className,
  children,
  fade,
}: {
  readonly gradient: string
  readonly className?: string
  readonly children?: React.ReactNode
  readonly fade?: 'bottom' | 'all-edges'
}) {
  let maskStyle: React.CSSProperties | undefined
  if (fade === 'all-edges') {
    maskStyle = {
      maskImage:
        'linear-gradient(to bottom, black 42%, transparent 100%), ' +
        'linear-gradient(to right, transparent 0%, black 8%, black 92%, transparent 100%)',
      WebkitMaskImage:
        'linear-gradient(to bottom, black 42%, transparent 100%), ' +
        'linear-gradient(to right, transparent 0%, black 8%, black 92%, transparent 100%)',
      maskComposite: 'intersect',
      WebkitMaskComposite: 'source-in',
    }
  } else if (fade === 'bottom') {
    maskStyle = {
      maskImage: 'linear-gradient(to bottom, black 0%, black 72%, transparent 100%)',
      WebkitMaskImage: 'linear-gradient(to bottom, black 0%, black 72%, transparent 100%)',
    }
  }

  return (
    <div className={cn('relative', className)} style={maskStyle}>
      <div
        className={cn(
          'relative overflow-hidden rounded-lg border border-slate-900/10 bg-[#0b1220]',
          'shadow-[0_24px_80px_rgba(15,23,42,0.16)] ring-1 ring-white/10',
        )}
      >
        <div className={cn('absolute inset-x-0 top-0 h-px bg-gradient-to-r opacity-80', gradient)} />
        <div className="relative z-10 flex items-center gap-2 border-b border-white/10 bg-slate-950 px-4 py-2.5">
          <div className="flex gap-1.5">
            <div className="size-2.5 rounded-full bg-[#ef4444]" />
            <div className="size-2.5 rounded-full bg-[#f59e0b]" />
            <div className="size-2.5 rounded-full bg-[#22c55e]" />
          </div>
          <div className="mx-6 h-3.5 flex-1 rounded bg-white/5" />
          <div className="h-3.5 w-16 rounded bg-cyan-400/15" />
        </div>
        <div className="relative z-10 aspect-video overflow-hidden bg-[#070b14] p-1.5">
          <div className="relative size-full overflow-hidden rounded bg-[#080d18]">{children}</div>
        </div>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Faux product-UI primitives — built components, not screenshots. They render
// representative product surfaces in the brand system (dark, mono, gradient).
// ─────────────────────────────────────────────────────────────────────────────
function WindowFrame({
  title,
  live,
  children,
  className,
}: {
  readonly title: string
  readonly live?: boolean
  readonly children: React.ReactNode
  readonly className?: string
}) {
  return (
    <div
      className={cn(
        'overflow-hidden rounded-lg border border-white/10 bg-[#0c0e16]',
        'shadow-[0_30px_80px_-40px_rgba(2,6,23,0.9)]',
        className,
      )}
    >
      <div className={cn('h-px w-full', GRADIENT_BAR)} />
      <div className="flex items-center gap-2 border-b border-white/[0.06] bg-[#0a0b12] px-3.5 py-2.5">
        <span className={cn('size-2.5 shrink-0 rounded-[3px]', GRADIENT_BG)} />
        <span className="truncate font-brandmono text-[11px] text-slate-400">{title}</span>
        {live ? (
          <span className="ml-auto inline-flex items-center gap-1.5 rounded-full bg-emerald-500/10 px-2 py-0.5 font-brandmono text-[10px] tracking-wide text-emerald-300">
            <span className="size-1.5 rounded-full bg-emerald-400" />
            live
          </span>
        ) : null}
      </div>
      <div className="p-3.5">{children}</div>
    </div>
  )
}

function SignalChart({className, heightClass = 'h-28'}: {readonly className?: string; readonly heightClass?: string}) {
  const id = useId()
  const area =
    'M0,82 L29,74 L58,80 L87,46 L116,60 L145,34 L174,52 L203,24 L232,42 L261,18 L290,38 L320,28 L320,110 L0,110 Z'
  const line = 'M0,82 L29,74 L58,80 L87,46 L116,60 L145,34 L174,52 L203,24 L232,42 L261,18 L290,38 L320,28'
  return (
    <div
      className={cn(
        'relative w-full overflow-hidden rounded-md border border-white/[0.06] bg-[#0a0c14]',
        heightClass,
        className,
      )}
    >
      <svg viewBox="0 0 320 110" preserveAspectRatio="none" className="size-full" aria-hidden>
        <defs>
          <linearGradient id={`${id}-fill`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#6366F1" stopOpacity="0.5" />
            <stop offset="60%" stopColor="#22D3EE" stopOpacity="0.12" />
            <stop offset="100%" stopColor="#22D3EE" stopOpacity="0" />
          </linearGradient>
          <linearGradient id={`${id}-line`} x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#8B5CF6" />
            <stop offset="50%" stopColor="#6366F1" />
            <stop offset="100%" stopColor="#22D3EE" />
          </linearGradient>
        </defs>
        <path d={area} fill={`url(#${id}-fill)`} />
        <path
          d={line}
          fill="none"
          stroke={`url(#${id}-line)`}
          strokeWidth="2"
          strokeLinejoin="round"
          strokeLinecap="round"
          vectorEffect="non-scaling-stroke"
        />
      </svg>
    </div>
  )
}

function StatTile({label, value, accent}: {readonly label: string; readonly value: string; readonly accent?: boolean}) {
  return (
    <div className="rounded-md border border-white/[0.07] bg-white/[0.02] px-3 py-2.5">
      <div className="font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">{label}</div>
      <div className={cn('mt-1 font-brandmono text-sm font-semibold', accent ? GRADIENT_TEXT : 'text-slate-100')}>
        {value}
      </div>
    </div>
  )
}

function TerminalBlock({
  title,
  className,
  children,
}: {
  readonly title: string
  readonly className?: string
  readonly children: React.ReactNode
}) {
  return (
    <div
      className={cn(
        'overflow-hidden rounded-lg border border-white/10 bg-[#07080e]',
        'shadow-[0_24px_70px_-28px_rgba(2,6,23,0.7)]',
        className,
      )}
    >
      <div className={cn('h-px w-full', GRADIENT_BAR)} />
      <div className="flex items-center gap-2 border-b border-white/[0.07] px-4 py-2.5">
        <span className="flex gap-1.5">
          <span className="size-2.5 rounded-full bg-[#ef4444]" />
          <span className="size-2.5 rounded-full bg-[#f59e0b]" />
          <span className="size-2.5 rounded-full bg-[#22c55e]" />
        </span>
        <span className="ml-1 font-brandmono text-[11px] text-slate-500">{title}</span>
      </div>
      <div className="overflow-x-auto px-4 py-4 font-brandmono text-[13px] leading-7 text-slate-300">{children}</div>
    </div>
  )
}

const Prompt = () => <span className="text-indigo-300">$</span>
const Comment = ({children}: {readonly children: React.ReactNode}) => (
  <span className="text-slate-500">{children}</span>
)
const Ok = () => <span className="text-emerald-400">✓</span>
const Accent = ({children}: {readonly children: React.ReactNode}) => (
  <span className={GRADIENT_TEXT}>{children}</span>
)

// ─────────────────────────────────────────────────────────────────────────────
// Hero — centered, outcome-first, with one large readable product surface.
// The hero visual is a live service map: telemetry sources (Datadog Agent,
// Sentry SDK, OTLP) feed a service topology with packets flowing along the
// edges. Geometry and packet timing are deterministic so the prerendered HTML
// matches client hydration, and SMIL pauses under prefers-reduced-motion.
// ─────────────────────────────────────────────────────────────────────────────
type ServiceNode = {
  readonly key: string
  readonly x: number
  readonly y: number
  readonly label: string
  readonly sub: string
  readonly warn?: boolean
}
type TelemetrySource = {
  readonly key: string
  readonly x: number
  readonly y: number
  readonly label: string
  readonly color: string
}
type Packet = {
  readonly r: number
  readonly color: string
  readonly dur: number
  readonly begin: number
}

// Map canvas + card geometry, in SVG user units.
const MAP_W = 1120
const MAP_H = 560
const NODE_W = 128
const NODE_H = 46
const SRC_W = 152
const SRC_H = 36

const TELEMETRY_SOURCES: readonly TelemetrySource[] = [
  {key: 'ddog', x: 96, y: 140, label: 'Datadog Agent', color: '#818cf8'},
  {key: 'sentry', x: 96, y: 255, label: 'Sentry SDK', color: '#a78bfa'},
  {key: 'otlp', x: 96, y: 370, label: 'OTLP', color: '#22d3ee'},
]

const SERVICE_NODES: readonly ServiceNode[] = [
  {key: 'lb', x: 322, y: 255, label: 'edge-lb', sub: '12.4k rps'},
  {key: 'web', x: 506, y: 150, label: 'web', sub: '38ms'},
  {key: 'api', x: 506, y: 400, label: 'api', sub: '184ms'},
  {key: 'auth', x: 716, y: 96, label: 'auth', sub: '22ms'},
  {key: 'ord', x: 716, y: 236, label: 'orders', sub: '96ms'},
  {key: 'pay', x: 716, y: 376, label: 'payments', sub: '512ms p95', warn: true},
  {key: 'cache', x: 716, y: 498, label: 'redis', sub: '0.4ms'},
  {key: 'pg', x: 952, y: 170, label: 'postgres', sub: '14ms'},
  {key: 'kafka', x: 952, y: 322, label: 'kafka', sub: '1.1k/s'},
  {key: 'wrk', x: 952, y: 474, label: 'workers ×12', sub: 'healthy'},
]

// [from, to, hot?] — hot edges flag the degraded payments path.
const SERVICE_EDGES: readonly (readonly [string, string, boolean?])[] = [
  ['lb', 'web'],
  ['lb', 'api'],
  ['web', 'auth'],
  ['web', 'ord'],
  ['api', 'ord'],
  ['api', 'pay', true],
  ['api', 'cache'],
  ['auth', 'pg'],
  ['ord', 'pg'],
  ['ord', 'kafka'],
  ['pay', 'pg', true],
  ['kafka', 'wrk'],
]

const nodeByKey = new Map(SERVICE_NODES.map((n) => [n.key, n] as const))
function requireNode(key: string): ServiceNode {
  const node = nodeByKey.get(key)
  if (!node) throw new Error(`HeroServiceMap: unknown node "${key}"`)
  return node
}

// Deterministic [0,1) hash so prerender (Node) and hydration (browser) agree;
// rounding the derived timings to 2 decimals keeps them stable across JS engines.
function hash01(seed: number): number {
  const x = Math.sin(seed * 127.1 + 311.7) * 43758.5453
  return x - Math.floor(x)
}
const lerp = (a: number, b: number, t: number) => a + (b - a) * t
const round2 = (n: number) => Math.round(n * 100) / 100

function buildPackets(seed: number, count: number, r: number, color: string, durMin: number, durMax: number): Packet[] {
  const packets: Packet[] = []
  for (let p = 0; p < count; p++) {
    const dur = round2(lerp(durMin, durMax, hash01(seed * 7 + p * 13 + 1)))
    // Negative begin offsets stagger packets along the wire from first paint.
    const begin = round2(-dur * hash01(seed * 7 + p * 13 + 101))
    packets.push({r, color, dur, begin})
  }
  return packets
}

type Pt = readonly [number, number]
const rightAnchor = (n: ServiceNode): Pt => [n.x + NODE_W / 2, n.y]
const leftAnchor = (n: ServiceNode): Pt => [n.x - NODE_W / 2, n.y]
const topAnchor = (n: ServiceNode): Pt => [n.x, n.y - NODE_H / 2]
const bottomAnchor = (n: ServiceNode): Pt => [n.x, n.y + NODE_H / 2]

function horizCurve(x1: number, y1: number, x2: number, y2: number): string {
  const k = Math.max(46, (x2 - x1) * 0.5)
  return `M${x1} ${y1} C ${x1 + k} ${y1}, ${x2 - k} ${y2}, ${x2} ${y2}`
}
function edgePath(a: ServiceNode, b: ServiceNode): string {
  // Near-vertical pairs route top→bottom; everything else flows left→right.
  if (Math.abs(b.x - a.x) < 40) {
    const [x1, y1] = bottomAnchor(a)
    const [x2, y2] = topAnchor(b)
    const k = Math.abs(y2 - y1) * 0.4
    return `M${x1} ${y1} C ${x1} ${y1 + k}, ${x2} ${y2 - k}, ${x2} ${y2}`
  }
  const [x1, y1] = rightAnchor(a)
  const [x2, y2] = leftAnchor(b)
  return horizCurve(x1, y1, x2, y2)
}

const SOURCE_WIRES = TELEMETRY_SOURCES.map((s, i) => {
  const lb = requireNode('lb')
  return {
    key: s.key,
    color: s.color,
    d: horizCurve(s.x + SRC_W / 2, s.y, lb.x - NODE_W / 2, lb.y),
    packets: buildPackets(i, 2, 3.1, s.color, 2.3, 3.4),
  }
})

const EDGE_WIRES = SERVICE_EDGES.map(([from, to, hot], i) => ({
  key: `${from}-${to}`,
  hot: Boolean(hot),
  d: edgePath(requireNode(from), requireNode(to)),
  packets: hot
    ? buildPackets(100 + i, 3, 3.4, '#fbbf24', 2.6, 3.9)
    : buildPackets(100 + i, 2, 3, '#34e3ff', 2.6, 3.9),
}))

// A tight radial mask bleeds the map into the hero background instead of a hard card edge.
const MAP_MASK: React.CSSProperties = {
  maskImage: 'radial-gradient(125% 96% at 50% 42%, #000 58%, transparent 100%)',
  WebkitMaskImage: 'radial-gradient(125% 96% at 50% 42%, #000 58%, transparent 100%)',
}

const HERO_MAP_FLOOR_GLOW =
  'pointer-events-none absolute inset-x-[14%] bottom-[-22px] z-0 h-14 ' +
  'bg-[radial-gradient(closest-side,rgba(99,102,241,0.34),transparent)] blur-[15px]'
const HERO_MAP_AMBIENT_GLOW =
  'pointer-events-none absolute [inset:-8%_-4%] ' +
  'bg-[radial-gradient(closest-side,rgba(124,92,246,0.2),rgba(34,211,238,0.05)_62%,transparent)] blur-[10px]'
const HERO_MAP_HUD =
  'absolute bottom-[18px] left-[18px] z-[8] hidden min-w-[190px] rounded-[10px] border border-white/10 ' +
  'bg-[#0a0b12]/85 px-3.5 py-3 shadow-[0_18px_40px_-22px_rgba(2,6,23,0.9)] backdrop-blur md:block'

const HUD_ROWS: readonly {readonly label: string; readonly value: string; readonly dot: string}[] = [
  {label: 'Throughput', value: '1,284 rps', dot: '#22d3ee'},
  {label: 'p95 latency', value: '184 ms', dot: '#6366f1'},
  {label: 'Degraded', value: '1 service', dot: '#f59e0b'},
]

function PacketDot({pathId, packet}: {readonly pathId: string; readonly packet: Packet}) {
  return (
    <circle r={packet.r} fill={packet.color}>
      <animateMotion dur={`${packet.dur}s`} begin={`${packet.begin}s`} repeatCount="indefinite">
        <mpath xlinkHref={`#${pathId}`} href={`#${pathId}`} />
      </animateMotion>
    </circle>
  )
}

function HeroServiceMap() {
  const uid = useId()
  const svgRef = useRef<SVGSVGElement>(null)
  // useId returns colon-wrapped ids; strip colons so they're clean fragment refs.
  const wireId = (kind: string, key: string) => `${uid}-${kind}-${key}`.replace(/:/g, '')

  // SMIL has no prefers-reduced-motion hook, so pause it on the client after mount.
  // This leaves the prerendered markup untouched, so hydration still matches.
  useEffect(() => {
    const svg = svgRef.current
    if (svg && globalThis.window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      svg.pauseAnimations()
    }
  }, [])

  return (
    <div className="relative mx-auto mt-16 max-w-[1100px]">
      {/* floor glow — bleeds below the map's lower edge */}
      <div aria-hidden className={HERO_MAP_FLOOR_GLOW} />
      <div className="relative overflow-visible" style={MAP_MASK}>
        {/* ambient glow behind the topology */}
        <div aria-hidden className={HERO_MAP_AMBIENT_GLOW} />
        <svg
          ref={svgRef}
          viewBox={`0 0 ${MAP_W} ${MAP_H}`}
          className="relative block h-auto w-full font-brandmono"
          role="img"
          aria-label="Live service map: Datadog, Sentry, and OTLP telemetry flowing into one platform"
        >
          <defs>
            {SOURCE_WIRES.map((w) => (
              <path key={w.key} id={wireId('s', w.key)} d={w.d} />
            ))}
            {EDGE_WIRES.map((w) => (
              <path key={w.key} id={wireId('e', w.key)} d={w.d} />
            ))}
          </defs>

          {/* source → load-balancer wires */}
          <g>
            {SOURCE_WIRES.map((w) => (
              <path key={w.key} d={w.d} fill="none" stroke={w.color} strokeOpacity={0.24} strokeWidth={1.5} />
            ))}
          </g>
          {/* service-to-service wires */}
          <g>
            {EDGE_WIRES.map((w) => (
              <path
                key={w.key}
                d={w.d}
                fill="none"
                stroke={w.hot ? 'rgba(245,158,11,0.32)' : 'rgba(148,163,184,0.18)'}
                strokeWidth={1.5}
              />
            ))}
          </g>
          {/* flowing packets — painted under the node cards */}
          <g>
            {SOURCE_WIRES.map((w) =>
              w.packets.map((packet, pi) => (
                <PacketDot key={`${w.key}-${pi}`} pathId={wireId('s', w.key)} packet={packet} />
              )),
            )}
            {EDGE_WIRES.map((w) =>
              w.packets.map((packet, pi) => (
                <PacketDot key={`${w.key}-${pi}`} pathId={wireId('e', w.key)} packet={packet} />
              )),
            )}
          </g>

          {/* telemetry source pills */}
          {TELEMETRY_SOURCES.map((s) => {
            const x = s.x - SRC_W / 2
            const y = s.y - SRC_H / 2
            return (
              <g key={s.key}>
                <rect
                  x={x}
                  y={y}
                  width={SRC_W}
                  height={SRC_H}
                  rx={SRC_H / 2}
                  fill="#0e111b"
                  stroke="rgba(255,255,255,0.1)"
                  strokeWidth={1}
                />
                <circle cx={x + 17} cy={s.y} r={4} fill={s.color}>
                  <animate attributeName="opacity" values="1;.45;1" dur="2.6s" repeatCount="indefinite" />
                </circle>
                <text x={x + 30} y={s.y + 4} fontSize={11.5} fill="#c7d2e0">
                  {s.label}
                </text>
                <path
                  d={`M${x + SRC_W - 14} ${s.y - 4} l5 4 l-5 4`}
                  fill="none"
                  stroke={s.color}
                  strokeWidth={1.5}
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </g>
            )
          })}

          {/* service nodes */}
          {SERVICE_NODES.map((n, i) => {
            const x = n.x - NODE_W / 2
            const y = n.y - NODE_H / 2
            const dotColor = n.warn ? '#f59e0b' : '#34d399'
            const pulse = round2(lerp(2.4, 3.6, hash01(i * 9 + 3)))
            return (
              <g key={n.key}>
                {n.warn ? (
                  <circle
                    cx={n.x}
                    cy={n.y}
                    r={NODE_W / 2 - 2}
                    fill="none"
                    stroke="rgba(245,158,11,0.5)"
                    strokeWidth={1.2}
                  >
                    <animate
                      attributeName="r"
                      values={`${NODE_W / 2 - 2};${NODE_W / 2 + 14}`}
                      dur="2.4s"
                      repeatCount="indefinite"
                    />
                    <animate attributeName="opacity" values=".55;0" dur="2.4s" repeatCount="indefinite" />
                  </circle>
                ) : null}
                <rect
                  x={x}
                  y={y}
                  width={NODE_W}
                  height={NODE_H}
                  rx={10}
                  fill="#0c0e16"
                  stroke={n.warn ? 'rgba(245,158,11,0.55)' : 'rgba(255,255,255,0.1)'}
                  strokeWidth={1}
                />
                <circle cx={x + 15} cy={n.y} r={3.6} fill={dotColor}>
                  {n.warn ? null : (
                    <animate
                      attributeName="opacity"
                      values="1;.4;1"
                      dur={`${pulse}s`}
                      repeatCount="indefinite"
                    />
                  )}
                </circle>
                <text x={x + 28} y={n.y - 2} fontSize={13} fontWeight={500} fill="#e2e8f0">
                  {n.label}
                </text>
                <text x={x + 28} y={n.y + 13} fontSize={10} fill={n.warn ? '#f59e0b' : '#64748b'}>
                  {n.sub}
                </text>
              </g>
            )
          })}
        </svg>
      </div>

      {/* HUD — cluster summary, hidden on narrow viewports (as in the demo) */}
      <div className={HERO_MAP_HUD}>
        <div className="mb-2 font-brandmono text-[9.5px] uppercase tracking-[0.14em] text-slate-500">
          cluster · last 60s
        </div>
        {HUD_ROWS.map((row) => (
          <div key={row.label} className="flex items-center justify-between gap-4 py-[3px]">
            <span className="inline-flex items-center gap-2 text-xs text-slate-400">
              <span className="size-[7px] rounded-full" style={{background: row.dot}} />
              {row.label}
            </span>
            <span className="font-brandmono text-xs text-slate-100">{row.value}</span>
          </div>
        ))}
      </div>
    </div>
  )
}

function Hero() {
  return (
    <section className="relative overflow-hidden px-4 pb-24 pt-20 sm:px-6 lg:px-8">
      <div aria-hidden className="pointer-events-none absolute inset-0">
        <div className="absolute inset-0 bg-[radial-gradient(58rem_30rem_at_72%_-10%,rgba(124,92,246,0.2),transparent_70%)]" />
        <div className="absolute inset-0 bg-[linear-gradient(rgba(148,163,184,0.05)_1px,transparent_1px),linear-gradient(90deg,rgba(148,163,184,0.05)_1px,transparent_1px)] bg-[size:56px_56px] [mask-image:radial-gradient(60rem_40rem_at_50%_0%,#000,transparent_75%)]" />
      </div>

      <div className="relative mx-auto max-w-5xl text-center">
        <div className="mb-6 inline-flex items-center gap-2.5 font-brandmono text-[11px] font-medium uppercase tracking-[0.18em] text-indigo-300">
          <span className={cn('h-px w-6 rounded', GRADIENT_BAR)} />
          Open source · Drop-in · Self-hostable
        </div>
        <h1 className="mx-auto max-w-4xl text-balance text-4xl font-bold leading-[0.95] tracking-[-0.04em] text-white sm:text-6xl">
          Switch from Sentry and Datadog.{' '}
          <span className={GRADIENT_TEXT}>Keep your SDK and agent.</span>
        </h1>
        <p className="mx-auto mt-6 max-w-2xl text-pretty text-lg leading-8 text-slate-400">
          Moneat ingests your existing Sentry SDK events and Datadog Agent traffic, so errors, logs,
          traces, infrastructure, uptime, and on-call move into one platform.
        </p>
        <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
          <Button
            asChild
            size="lg"
            className={cn('border-0 text-white shadow-[0_10px_28px_-10px_#6366F1] hover:brightness-110', GRADIENT_BG)}
          >
            <Link to="/signup">
              Start free
              <ArrowRight data-icon="inline-end" />
            </Link>
          </Button>
          <Button
            asChild
            variant="outline"
            size="lg"
            className="border-white/15 bg-white/[0.03] text-slate-100 hover:bg-white/[0.07] hover:text-white"
          >
            <Link to="/demo">
              <Play data-icon="inline-start" />
              Live demo
            </Link>
          </Button>
        </div>
        <p className="mt-4 font-brandmono text-xs text-slate-500">No credit card · No signup for the demo</p>
      </div>

      <HeroServiceMap />
    </section>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Proof strip — outcome first, the feature underneath as the label.
// ─────────────────────────────────────────────────────────────────────────────
const proofPoints: Array<{icon: LucideIcon; label: string; outcome: string}> = [
  {icon: Activity, label: 'Sentry SDK', outcome: 'Switch the DSN, keep your instrumentation'},
  {icon: Server, label: 'Datadog Agent', outcome: 'Redirect your agent, import your dashboard and alerts'},
  {icon: ShieldCheck, label: 'AGPL v3', outcome: 'Self-host and own your telemetry'},
  {icon: Zap, label: 'Free tier', outcome: '1 GB a month to start'},
]

function ProofStrip() {
  return (
    <section className="border-y border-white/[0.06] bg-[#0a0b12]">
      <div className="mx-auto grid max-w-6xl gap-px px-4 sm:grid-cols-2 lg:grid-cols-4 lg:px-8">
        {proofPoints.map((point) => (
          <div key={point.label} className="px-5 py-6">
            <div className="flex items-start gap-3">
              <div className="flex size-9 shrink-0 items-center justify-center rounded-md border border-white/[0.08] bg-white/[0.03]">
                <point.icon className="size-4 text-indigo-300" />
              </div>
              <div>
                <p className="text-sm font-semibold leading-snug text-slate-100">{point.outcome}</p>
                <p className="mt-1 font-brandmono text-[10px] uppercase tracking-[0.14em] text-slate-500">
                  {point.label}
                </p>
              </div>
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Compatibility — the lead value: keep your agents/SDKs. Real terminal blocks.
// ─────────────────────────────────────────────────────────────────────────────
function CompatibilitySection() {
  return (
    <section className="px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <SectionHeading
          kicker="Compatible by default"
          title="Drop in where your data already flows."
          lead="Point your existing SDKs and agents at Moneat. No re-instrumentation, no rewrite."
        />
        <div className="mt-12 grid gap-6 lg:grid-cols-2">
          <CompatibilityCard
            icon={ShieldCheck}
            title="Keep your Sentry SDK — change one line."
            description="Errors, session replay, performance, and profiling flow in on your current instrumentation."
            terminalTitle="sentry.config.js"
          >
            <span className="text-slate-200">Sentry.init({'{'}</span>
            <br />
            {'  '}dsn: <Accent>&quot;https://your-project.moneat.io/1&quot;</Accent>,
            <br />
            {'  '}tracesSampleRate: <span className="text-slate-200">1.0</span>,
            <br />
            <span className="text-slate-200">{'}'})</span>
          </CompatibilityCard>
          <CompatibilityCard
            icon={Server}
            title="Point the Datadog Agent at one endpoint."
            description="Hosts, containers, logs, and traces keep moving — same agent, new destination."
            terminalTitle="datadog.yaml"
          >
            <Comment># /etc/datadog-agent/datadog.yaml</Comment>
            <br />
            dd_url: <Accent>&quot;https://your-moneat-instance/dd&quot;</Accent>
            <br />
            apm_config:
            <br />
            {'  '}enabled: <span className="text-slate-200">true</span>
            <br />
            logs_enabled: <span className="text-slate-200">true</span>
          </CompatibilityCard>
        </div>
      </div>
    </section>
  )
}

function CompatibilityCard({
  icon: Icon,
  title,
  description,
  terminalTitle,
  children,
}: {
  readonly icon: LucideIcon
  readonly title: string
  readonly description: string
  readonly terminalTitle: string
  readonly children: React.ReactNode
}) {
  return (
    <div className="rounded-lg border border-white/[0.08] bg-[#0c0e16] p-6">
      <div className="flex size-11 items-center justify-center rounded-md border border-white/[0.08] bg-white/[0.03]">
        <Icon className="size-5 text-indigo-300" />
      </div>
      <h3 className="mt-6 text-xl font-semibold tracking-[-0.02em] text-white">{title}</h3>
      <p className="mt-2 text-sm leading-6 text-slate-400">{description}</p>
      <TerminalBlock title={terminalTitle} className="mt-6">
        {children}
      </TerminalBlock>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// How it works — a numbered journey, each step with a focused built surface.
// ─────────────────────────────────────────────────────────────────────────────
function HowItWorks() {
  return (
    <section className="border-y border-white/[0.06] bg-[#0a0b12] px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <SectionHeading
          kicker="How it works"
          title="From any source to the right responder."
          lead="One path: send telemetry, see it together, find the cause, and route the fix — without leaving the workspace."
        />
        <div className="mt-14 grid gap-6 lg:grid-cols-2">
          <Step
            n="01"
            title="Send your telemetry"
            body="Point a Sentry SDK, the Datadog Agent, or any OTLP exporter at Moneat. No new client to learn."
          >
            <TerminalBlock title="bash — ingest">
              <Comment># point any source at Moneat</Comment>
              <br />
              <Prompt /> sentry → <Accent>…moneat.io/1</Accent>
              <br />
              <Prompt /> datadog → <Accent>…/dd</Accent>
              <br />
              <Ok /> otlp, agent &amp; sdk accepted
            </TerminalBlock>
          </Step>
          <Step
            n="02"
            title="It lands in one workspace"
            body="Errors, logs, traces, and infrastructure share one timeline, one search, and one set of tags."
          >
            <WindowFrame title="moneat · workspace">
              <SignalChart heightClass="h-24" />
              <div className="mt-3 grid grid-cols-3 gap-2.5">
                <StatTile label="services" value="14" />
                <StatTile label="logs/s" value="3.4k" accent />
                <StatTile label="spans" value="912k" />
              </div>
            </WindowFrame>
          </Step>
          <Step
            n="03"
            title="Triage with context"
            body="Jump from a latency spike to the issue, the slow span, and the logs that explain it."
          >
            <WindowFrame title="trace · GET /checkout">
              <TraceWaterfall />
            </WindowFrame>
          </Step>
          <Step
            n="04"
            title="Route to on-call"
            body="Alert the right person, open an incident, and update a status page. Build custom workflows."
          >
            <WindowFrame title="incident · #142">
              <EventLog
                rows={[
                  ['alert', 'p95 > 250ms on api-prod', 'text-rose-300'],
                  ['paged', 'on-call: @alex (api)', 'text-indigo-300'],
                  ['escalate', 'secondary in 5m', 'text-amber-300'],
                  ['status', 'page updated · investigating', 'text-emerald-300'],
                ]}
              />
            </WindowFrame>
          </Step>
        </div>
      </div>
    </section>
  )
}

function Step({
  n,
  title,
  body,
  children,
}: {
  readonly n: string
  readonly title: string
  readonly body: string
  readonly children: React.ReactNode
}) {
  return (
    <div className="rounded-lg border border-white/[0.08] bg-[#0c0e16] p-6">
      <div className="flex items-center gap-3">
        <span className={cn('font-brandmono text-lg font-semibold', GRADIENT_TEXT)}>{n}</span>
        <h3 className="text-lg font-semibold tracking-[-0.02em] text-white">{title}</h3>
      </div>
      <p className="mt-2 text-sm leading-6 text-slate-400">{body}</p>
      <div className="mt-5">{children}</div>
    </div>
  )
}

function TraceWaterfall() {
  const rows: Array<{label: string; left: string; width: string; ms: string}> = [
    {label: 'GET /checkout', left: 'left-0', width: 'w-[94%]', ms: '184ms'},
    {label: 'auth.verify', left: 'left-[4%]', width: 'w-[22%]', ms: '38ms'},
    {label: 'db.query users', left: 'left-[20%]', width: 'w-[30%]', ms: '52ms'},
    {label: 'cache.get', left: 'left-[26%]', width: 'w-[8%]', ms: '9ms'},
    {label: 'stripe.charge', left: 'left-[58%]', width: 'w-[30%]', ms: '61ms'},
  ]
  return (
    <div className="grid gap-2">
      {rows.map((row) => (
        <div key={row.label} className="grid grid-cols-[7.5rem_1fr_3rem] items-center gap-3">
          <span className="truncate font-brandmono text-[11px] text-slate-400">{row.label}</span>
          <span className="relative h-3 rounded bg-white/[0.04]">
            <span className={cn('absolute inset-y-0 rounded', GRADIENT_BG, row.left, row.width)} />
          </span>
          <span className="text-right font-brandmono text-[11px] text-slate-500">{row.ms}</span>
        </div>
      ))}
    </div>
  )
}

function EventLog({rows}: {readonly rows: Array<[string, string, string]>}) {
  return (
    <div className="grid gap-px overflow-hidden rounded-md border border-white/[0.07]">
      {rows.map(([tag, text, tone]) => (
        <div key={text} className="flex items-center gap-3 bg-white/[0.02] px-3 py-2.5">
          <span className={cn('w-16 shrink-0 font-brandmono text-[10px] uppercase tracking-[0.1em]', tone)}>{tag}</span>
          <span className="truncate text-sm text-slate-300">{text}</span>
        </div>
      ))}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Comparison — Moneat vs. the four alternatives. Vendor headers deep-link out.
// ─────────────────────────────────────────────────────────────────────────────
function CompareValue({value, highlight}: {readonly value: CellValue; readonly highlight: boolean}) {
  if (value === 'yes') {
    return <Check className={cn('mx-auto size-4', highlight ? 'text-cyan-300' : 'text-emerald-400')} aria-label="Yes" />
  }
  if (value === 'no') {
    return <Minus className="mx-auto size-4 text-slate-600" aria-label="No" />
  }
  if (value === 'partial') {
    return (
      <span className="inline-flex items-center gap-1.5">
        <span className="size-1.5 rounded-full bg-amber-400" />
        <span className="font-brandmono text-[11px] text-amber-300/90">Partial</span>
      </span>
    )
  }
  return <span className={cn('text-xs', highlight ? 'text-slate-200' : 'text-slate-400')}>{value}</span>
}

function ComparisonSection() {
  return (
    <section className="px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <SectionHeading
          kicker="Where Moneat fits"
          title="One platform against four point tools."
          lead="Each alternative is strong somewhere. Moneat covers the whole operational path in one product you can self-host."
        />
        <div className="mt-12 overflow-x-auto rounded-lg border border-white/[0.08] bg-[#0c0e16]">
          <table className="w-full min-w-[700px] border-collapse text-sm">
            <thead>
              <tr className="border-b border-white/[0.08]">
                <th className="px-4 py-4 text-left font-brandmono text-[11px] uppercase tracking-[0.12em] text-slate-500">
                  Capability
                </th>
                <th className="border-x border-white/[0.08] bg-white/[0.03] px-4 py-4 text-center">
                  <span className={cn('text-base font-bold', GRADIENT_TEXT)}>Moneat</span>
                </th>
                {compareColumns.map((col) => (
                  <th key={col.slug} className="px-4 py-4 text-center">
                    <a
                      href={col.route}
                      className="text-sm font-semibold text-slate-300 underline-offset-4 transition-colors hover:text-white hover:underline"
                    >
                      {col.name}
                    </a>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {compareRows.map((row) => (
                <tr key={row.label} className="border-t border-white/[0.06]">
                  <td className="px-4 py-3.5 text-left font-medium text-slate-300">{row.label}</td>
                  <td className="border-x border-white/[0.08] bg-white/[0.03] px-4 py-3.5 text-center">
                    <CompareValue value={row.moneat} highlight />
                  </td>
                  {compareColumns.map((col) => (
                    <td key={col.slug} className="px-4 py-3.5 text-center">
                      <CompareValue value={row.values[col.slug]} highlight={false} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="mt-4 font-brandmono text-[11px] leading-5 text-slate-500">
          Drawn from each vendor&apos;s public docs and pricing, reviewed {SOURCE_REVIEW_DATE}.{' '}
          <a href="/compare" className="text-indigo-300 underline-offset-4 hover:underline">
            See the full per-vendor breakdowns →
          </a>
        </p>
      </div>
    </section>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Capabilities breadth — what's in the box, grouped by the incident path.
// ─────────────────────────────────────────────────────────────────────────────
const platformGroups: Array<{icon: LucideIcon; title: string; description: string; items: string[]}> = [
  {
    icon: Activity,
    title: 'Observe',
    description: 'Production telemetry without sending each signal to a separate product.',
    items: ['Errors', 'Logs', 'APM traces', 'Session replay', 'Profiling'],
  },
  {
    icon: Server,
    title: 'Operate',
    description: 'Infrastructure and service health views that match how incidents unfold.',
    items: ['Hosts', 'Containers', 'Kubernetes', 'Uptime checks', 'Status pages'],
  },
  {
    icon: Bell,
    title: 'Respond',
    description: 'Close the loop from signal to owner with fewer handoffs.',
    items: ['Alerting', 'On-call schedules', 'Escalations', 'Incident timelines', 'AI triage'],
  },
]

function CapabilitiesSection() {
  return (
    <section className="border-y border-white/[0.06] bg-[#0a0b12] px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <SectionHeading
          kicker="One operational surface"
          title="Every signal an engineer already uses."
          lead="Bring the context engineers need into one workspace: what failed, where it happened, which services are affected, and who responds next."
        />
        <div className="mt-12 grid gap-5 lg:grid-cols-3">
          {platformGroups.map((group) => (
            <div key={group.title} className="rounded-lg border border-white/[0.08] bg-[#0c0e16] p-6">
              <div className="flex size-10 items-center justify-center rounded-md border border-white/[0.08] bg-white/[0.03]">
                <group.icon className="size-5 text-indigo-300" />
              </div>
              <h3 className="mt-5 text-xl font-semibold tracking-[-0.02em] text-white">{group.title}</h3>
              <p className="mt-2 min-h-12 text-sm leading-6 text-slate-400">{group.description}</p>
              <div className="mt-5 grid gap-2">
                {group.items.map((item) => (
                  <div
                    key={item}
                    className="flex items-center justify-between border-t border-white/[0.06] py-2.5"
                  >
                    <span className="text-sm font-medium text-slate-300">{item}</span>
                    <Check className="size-4 text-slate-600" />
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Open source / self-host — show the command.
// ─────────────────────────────────────────────────────────────────────────────
function OpenSourceSection() {
  return (
    <section className="px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl items-center gap-12 lg:grid-cols-[0.95fr_1.05fr]">
        <div>
          <SectionHeading
            kicker="Open source"
            title="Your telemetry, your network, your servers."
            lead="Start hosted, self-host under AGPL v3 when your requirements demand it, and keep the same ingestion model either way."
            align="left"
          />
          <div className="mt-8 grid gap-3">
            {[
              'Source available under AGPL v3',
              'Self-hostable with your own storage',
              'The same SDK and agent paths, hosted or not',
            ].map((item) => (
              <div key={item} className="flex items-center gap-3 text-sm font-medium text-slate-300">
                <Check className="size-4 text-emerald-400" />
                {item}
              </div>
            ))}
          </div>
          <div className="mt-8 flex flex-col gap-3 sm:flex-row">
            <Button
              asChild
              variant="outline"
              className="border-white/15 bg-white/[0.03] text-slate-100 hover:bg-white/[0.07] hover:text-white"
            >
              <a href="https://github.com/moneat-io/moneat" target="_blank" rel="noopener noreferrer">
                <Github data-icon="inline-start" />
                View on GitHub
              </a>
            </Button>
            <Button asChild variant="ghost" className="text-slate-300 hover:bg-white/[0.05] hover:text-white">
              <a href="/docs/self-hosting">
                Self-host docs
                <ArrowRight data-icon="inline-end" />
              </a>
            </Button>
          </div>
        </div>

        <TerminalBlock title="bash — moneat">
          <Comment># self-host the whole platform</Comment>
          <br />
          <Prompt /> curl -fsSL moneat.sh | sh
          <br />
          <Prompt /> moneat up --port <Accent>8080</Accent>
          <br />
          <Ok /> dashboard ready at <Accent>localhost:8080</Accent>
          <br />
          <Ok /> ingesting <Accent>traces · logs · metrics</Accent>
        </TerminalBlock>
      </div>
    </section>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Condensed pricing — the free tier up front, paid tiers summarized.
// ─────────────────────────────────────────────────────────────────────────────
const pricingTiers: Array<{
  name: string
  price: string
  cadence: string
  blurb: string
  points: string[]
  highlight: boolean
}> = [
  {
    name: 'Free',
    price: '$0',
    cadence: '',
    blurb: 'Side projects and getting started',
    points: ['1 GB ingest / month', 'Every signal type included', 'Community support'],
    highlight: false,
  },
  {
    name: 'Pro',
    price: '$29',
    cadence: '/mo',
    blurb: 'Growing teams shipping to production',
    points: ['More ingest & retention', '$0.40 / GB overage', 'Slack & Discord alerts'],
    highlight: true,
  },
  {
    name: 'Team',
    price: '$79',
    cadence: '/mo',
    blurb: 'Teams that need scale and compliance',
    points: ['Higher limits', 'SSO (SAML / OIDC)', 'Priority support'],
    highlight: false,
  },
  {
    name: 'Business',
    price: '$199',
    cadence: '/mo',
    blurb: 'Enterprises with custom requirements',
    points: ['Custom retention', 'SLA guarantee', 'Everything in Team'],
    highlight: false,
  },
]

function PricingBand() {
  return (
    <section className="border-y border-white/[0.06] bg-[#0a0b12] px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <SectionHeading
          kicker="Pricing"
          title="Pay only for what you ingest."
          lead="Pricing is based on ingestion volume, with no per-host fees. Send the telemetry you need and scale usage as your systems grow."
        />
        <div className="mt-12 grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
          {pricingTiers.map((tier) => (
            <div
              key={tier.name}
              className={cn(
                'relative rounded-lg border bg-[#0c0e16] p-6',
                tier.highlight ? 'border-indigo-400/40' : 'border-white/[0.08]',
              )}
            >
              {tier.highlight ? <div className={cn('absolute inset-x-0 top-0 h-px', GRADIENT_BAR)} /> : null}
              <div className="flex items-baseline justify-between">
                <h3 className="text-lg font-semibold text-white">{tier.name}</h3>
                <div className="text-right">
                  <span className={cn('font-brandmono text-2xl font-bold', tier.highlight ? GRADIENT_TEXT : 'text-white')}>
                    {tier.price}
                  </span>
                  <span className="font-brandmono text-xs text-slate-500">{tier.cadence}</span>
                </div>
              </div>
              <p className="mt-2 min-h-10 text-sm leading-6 text-slate-400">{tier.blurb}</p>
              <div className="mt-5 grid gap-2.5">
                {tier.points.map((point) => (
                  <div key={point} className="flex items-center gap-2.5 text-sm text-slate-300">
                    <Check className="size-4 shrink-0 text-emerald-400" />
                    {point}
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
        <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
          <Button asChild variant="ghost" className="text-indigo-300 hover:bg-white/[0.05] hover:text-white">
            <Link to="/pricing">
              See full pricing
              <ArrowRight data-icon="inline-end" />
            </Link>
          </Button>
        </div>
      </div>
    </section>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Final CTA
// ─────────────────────────────────────────────────────────────────────────────
function FinalCta() {
  return (
    <section className="relative overflow-hidden px-4 py-24 sm:px-6 lg:px-8">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 bg-[radial-gradient(48rem_24rem_at_50%_120%,rgba(124,92,246,0.18),transparent_70%)]"
      />
      <div className="relative mx-auto max-w-3xl text-center">
        <h2 className="text-3xl font-bold tracking-[-0.03em] text-white sm:text-4xl">
          Run your whole observability stack as one product.
        </h2>
        <p className="mx-auto mt-4 max-w-2xl text-base leading-7 text-slate-400">
          Start hosted, move to self-hosting when you need to, and keep your existing SDK and agent
          paths intact the whole way.
        </p>
        <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
          <Button
            asChild
            size="lg"
            className={cn('border-0 text-white shadow-[0_10px_28px_-10px_#6366F1] hover:brightness-110', GRADIENT_BG)}
          >
            <Link to="/signup">
              Start free
              <ArrowRight data-icon="inline-end" />
            </Link>
          </Button>
          <Button
            asChild
            variant="outline"
            size="lg"
            className="border-white/15 bg-white/[0.03] text-slate-100 hover:bg-white/[0.07] hover:text-white"
          >
            <Link to="/demo">
              <Play data-icon="inline-start" />
              Live demo
            </Link>
          </Button>
        </div>
      </div>
    </section>
  )
}

// ── Shared section heading ───────────────────────────────────────────────────
function SectionHeading({
  kicker,
  title,
  lead,
  align = 'center',
}: {
  readonly kicker: string
  readonly title: string
  readonly lead?: string
  readonly align?: 'center' | 'left'
}) {
  const centered = align === 'center'
  return (
    <div className={cn(centered ? 'mx-auto max-w-2xl text-center' : 'max-w-2xl text-left')}>
      <div
        className={cn(
          'inline-flex items-center gap-2.5 font-brandmono text-[11px] font-medium uppercase tracking-[0.18em] text-indigo-300',
          centered && 'justify-center',
        )}
      >
        <span className={cn('h-px w-6 rounded', GRADIENT_BAR)} />
        {kicker}
      </div>
      <h2 className="mt-4 text-3xl font-bold tracking-[-0.035em] text-white sm:text-4xl">{title}</h2>
      {lead ? <p className="mt-4 text-base leading-7 text-slate-400">{lead}</p> : null}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
export function Landing() {
  return (
    <>
      <Hero />
      <ProofStrip />
      <CompatibilitySection />
      <HowItWorks />
      <ComparisonSection />
      <CapabilitiesSection />
      <OpenSourceSection />
      <PricingBand />
      <FinalCta />
    </>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared brand kit. These primitives are the canonical dark/style-guide look;
// the other public pages (feature, comparison, pricing, blog, docs, legal)
// import them from here so the whole marketing surface stays one system.
// ─────────────────────────────────────────────────────────────────────────────
export {
  GRADIENT_BG,
  GRADIENT_BAR,
  GRADIENT_TEXT,
  WindowFrame,
  SignalChart,
  StatTile,
  TerminalBlock,
  Prompt,
  Comment,
  Ok,
  Accent,
  SectionHeading,
}
