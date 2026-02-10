import {Link} from '@tanstack/react-router'
import {
  Activity,
  ArrowRight,
  Bell,
  FileText,
  GitBranch,
  Globe,
  Play,
  Shield,
  Zap,
  type LucideIcon,
} from 'lucide-react'
import {Button} from '@/components/ui/button'

// ────────────────────────────────────────────────────────────────
// Screenshot frame — browser-chrome wrapper for 16:9 screenshots
// Replace children with <img src="..." className="w-full h-full object-cover" />
// when real screenshots are available.
// ────────────────────────────────────────────────────────────────

function ScreenshotFrame({
  gradient,
  className,
  children,
}: {
  gradient: string
  className?: string
  children?: React.ReactNode
}) {
  return (
    <div className={`relative ${className ?? ''}`}>
      {/* Ambient glow */}
      <div
        className={`absolute -inset-4 bg-gradient-to-r ${gradient} opacity-20 blur-2xl rounded-3xl pointer-events-none`}
      />
      {/* Window frame */}
      <div className="relative rounded-xl border border-white/[0.08] bg-[#0c0e14] shadow-2xl shadow-black/40 overflow-hidden ring-1 ring-white/[0.05]">
        {/* macOS title bar */}
        <div className="flex items-center gap-2 px-4 py-2.5 border-b border-white/[0.06] bg-white/[0.02]">
          <div className="flex gap-1.5">
            <div className="w-2.5 h-2.5 rounded-full bg-[#ff5f57]/80" />
            <div className="w-2.5 h-2.5 rounded-full bg-[#febc2e]/80" />
            <div className="w-2.5 h-2.5 rounded-full bg-[#28c840]/80" />
          </div>
          <div className="flex-1 mx-8">
            <div className="h-4 rounded-md bg-white/[0.04] max-w-[200px] mx-auto" />
          </div>
        </div>
        {/* 16:9 screenshot area */}
        <div className="aspect-video relative overflow-hidden bg-[#0c0e14]">
          {children}
        </div>
      </div>
    </div>
  )
}

// ────────────────────────────────────────────────────────────────
// Mock UI compositions for screenshot stubs
// ────────────────────────────────────────────────────────────────

function HeroDashboardMock() {
  const statCardColors = [
    'bg-sky-400/20',
    'bg-emerald-400/20',
    'bg-amber-400/20',
    'bg-violet-400/20',
  ]
  return (
    <div className="absolute inset-0 flex">
      {/* Sidebar */}
      <div className="w-[14%] border-r border-white/[0.04] p-2.5 space-y-2">
        <div className="h-2.5 w-7 rounded bg-sky-400/20 mb-3" />
        {Array.from({length: 5}).map((_, i) => (
          <div
            key={i}
            className={`h-1.5 rounded ${i === 1 ? 'w-full bg-sky-400/15' : 'w-3/4 bg-white/[0.04]'}`}
          />
        ))}
      </div>
      {/* Main content */}
      <div className="flex-1 p-3 flex flex-col gap-2.5">
        <div className="h-2.5 w-28 rounded bg-white/[0.08]" />
        {/* Stat cards */}
        <div className="grid grid-cols-4 gap-1.5">
          {statCardColors.map((color, i) => (
            <div key={i} className="rounded bg-white/[0.02] border border-white/[0.04] p-1.5">
              <div className={`h-1 w-5 rounded ${color} mb-1`} />
              <div className="h-2.5 w-8 rounded bg-white/[0.06]" />
            </div>
          ))}
        </div>
        {/* Chart */}
        <div className="rounded bg-white/[0.02] border border-white/[0.04] p-2 flex-1 min-h-0">
          <div className="flex items-end gap-[3px] h-full">
            {[40, 65, 45, 80, 55, 70, 50, 85, 60, 75, 45, 90, 55, 70, 80, 65, 50, 75, 85, 60].map(
              (h, i) => (
                <div
                  key={i}
                  className="flex-1 rounded-t bg-sky-400/15"
                  style={{height: `${h}%`}}
                />
              ),
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

function ErrorTrackingMock() {
  const errors = [
    {color: 'bg-red-400/60', w: 'w-48'},
    {color: 'bg-red-400/60', w: 'w-56'},
    {color: 'bg-amber-400/60', w: 'w-40'},
    {color: 'bg-amber-400/60', w: 'w-52'},
    {color: 'bg-sky-400/40', w: 'w-44'},
  ]
  return (
    <div className="absolute inset-0 p-3 sm:p-4 space-y-1.5">
      <div className="flex items-center gap-2 mb-2">
        <div className="h-2.5 w-20 rounded bg-white/[0.08]" />
        <div className="flex-1" />
        <div className="h-5 w-28 rounded bg-white/[0.04] border border-white/[0.06]" />
      </div>
      {errors.map((e, i) => (
        <div
          key={i}
          className="flex items-center gap-2 rounded bg-white/[0.02] border border-white/[0.04] px-2.5 py-2"
        >
          <div className={`w-1.5 h-1.5 rounded-full shrink-0 ${e.color}`} />
          <div className={`h-1.5 ${e.w} rounded bg-white/[0.07]`} />
          <div className="h-1 w-16 rounded bg-white/[0.03] ml-1 hidden sm:block" />
          <div className="flex-1" />
          <div className="h-1.5 w-10 rounded bg-sky-400/10" />
        </div>
      ))}
    </div>
  )
}

function LogManagementMock() {
  const logs: Array<{level: string; color: string}> = [
    {level: 'INFO', color: 'text-blue-400/50 bg-blue-400/10'},
    {level: 'INFO', color: 'text-blue-400/50 bg-blue-400/10'},
    {level: 'WARN', color: 'text-amber-400/50 bg-amber-400/10'},
    {level: 'ERR', color: 'text-red-400/50 bg-red-400/10'},
    {level: 'INFO', color: 'text-blue-400/50 bg-blue-400/10'},
    {level: 'INFO', color: 'text-blue-400/50 bg-blue-400/10'},
  ]
  return (
    <div className="absolute inset-0 p-3 sm:p-4 space-y-1.5">
      {/* Search bar */}
      <div className="h-6 rounded bg-white/[0.03] border border-white/[0.06] flex items-center px-2">
        <div className="h-1.5 w-2.5 rounded bg-white/[0.1]" />
        <div className="h-1.5 w-20 rounded bg-white/[0.04] ml-1.5" />
      </div>
      {/* Filter chips */}
      <div className="flex gap-1.5">
        {(['INFO', 'WARN', 'ERR'] as const).map((l) => (
          <div
            key={l}
            className={`h-4 px-1.5 rounded-full text-[6px] flex items-center font-medium ${
              l === 'INFO'
                ? 'bg-blue-400/10 text-blue-400/50'
                : l === 'WARN'
                  ? 'bg-amber-400/10 text-amber-400/50'
                  : 'bg-red-400/10 text-red-400/50'
            }`}
          >
            {l}
          </div>
        ))}
      </div>
      {/* Log lines */}
      {logs.map((log, i) => (
        <div
          key={i}
          className="flex items-center gap-1.5 text-[6px] py-0.5 border-b border-white/[0.03]"
        >
          <span className="text-white/15 w-10 shrink-0 font-mono">12:34:5{i}</span>
          <span className={`px-1 py-0.5 rounded text-[5px] font-semibold shrink-0 ${log.color}`}>
            {log.level}
          </span>
          <div
            className="h-1 rounded bg-white/[0.04]"
            style={{width: `${40 + i * 8}%`}}
          />
        </div>
      ))}
    </div>
  )
}

function SessionReplayMock() {
  return (
    <div className="absolute inset-0 flex flex-col">
      {/* Video area */}
      <div className="flex-1 relative bg-gradient-to-br from-violet-500/[0.03] to-transparent">
        {/* Mock browser viewport */}
        <div className="absolute inset-3 rounded border border-white/[0.06] bg-white/[0.01] overflow-hidden">
          <div className="h-5 border-b border-white/[0.04] bg-white/[0.02] px-2 flex items-center">
            <div className="h-1.5 w-16 rounded bg-white/[0.04]" />
          </div>
          <div className="p-2 space-y-1.5">
            <div className="h-1.5 w-12 rounded bg-white/[0.06]" />
            <div className="h-1 w-24 rounded bg-white/[0.03]" />
            <div className="h-1 w-20 rounded bg-white/[0.03]" />
            <div className="h-6 w-14 rounded bg-violet-400/10 mt-1.5" />
          </div>
        </div>
        {/* Play button */}
        <div className="absolute inset-0 flex items-center justify-center">
          <div className="w-10 h-10 rounded-full bg-violet-500/20 flex items-center justify-center border border-violet-400/20">
            <Play className="w-4 h-4 text-violet-400/60 ml-0.5" />
          </div>
        </div>
        {/* Cursor indicator */}
        <div className="absolute top-1/3 right-1/3">
          <div className="w-3 h-3 border-2 border-violet-400/40 rounded-full animate-pulse" />
        </div>
      </div>
      {/* Timeline */}
      <div className="h-8 border-t border-white/[0.06] bg-white/[0.02] px-3 flex items-center gap-1.5">
        <span className="text-[6px] text-white/20 w-6">0:00</span>
        <div className="flex-1 h-1 rounded-full bg-white/[0.04] relative">
          <div className="absolute left-0 top-0 h-full w-[35%] rounded-full bg-violet-400/30" />
          {[20, 55, 78].map((p) => (
            <div
              key={p}
              className="absolute top-1/2 -translate-y-1/2 w-0.5 h-2 rounded-full bg-red-400/40"
              style={{left: `${p}%`}}
            />
          ))}
        </div>
        <span className="text-[6px] text-white/20 w-6 text-right">2:34</span>
      </div>
    </div>
  )
}

function PerformanceMock() {
  const metrics = [
    {label: 'p50', color: 'bg-emerald-400/15'},
    {label: 'p95', color: 'bg-amber-400/15'},
    {label: 'p99', color: 'bg-red-400/15'},
  ]
  const spans = [
    {off: 0, w: 100, c: 'bg-amber-400/20'},
    {off: 5, w: 60, c: 'bg-sky-400/20'},
    {off: 10, w: 35, c: 'bg-emerald-400/20'},
    {off: 15, w: 25, c: 'bg-sky-400/20'},
    {off: 8, w: 80, c: 'bg-violet-400/20'},
  ]
  return (
    <div className="absolute inset-0 p-3 space-y-2">
      {/* Metric cards */}
      <div className="grid grid-cols-3 gap-1.5">
        {metrics.map((m) => (
          <div key={m.label} className="rounded bg-white/[0.02] border border-white/[0.04] p-1.5">
            <div className="text-[5px] text-white/20 mb-0.5">{m.label}</div>
            <div className={`h-2 w-10 rounded ${m.color}`} />
          </div>
        ))}
      </div>
      {/* Waterfall */}
      <div className="rounded bg-white/[0.02] border border-white/[0.04] p-2 space-y-1">
        <div className="h-1.5 w-14 rounded bg-white/[0.06] mb-1.5" />
        {spans.map((s, i) => (
          <div key={i} className="flex items-center gap-1.5">
            <div className="h-1 w-12 rounded bg-white/[0.04] shrink-0" />
            <div className="flex-1 h-2.5 relative">
              <div
                className={`absolute top-0 h-full rounded ${s.c}`}
                style={{left: `${s.off}%`, width: `${s.w * 0.6}%`}}
              />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function UptimeMock() {
  const monitors = [
    {status: 'green' as const},
    {status: 'green' as const},
    {status: 'yellow' as const},
    {status: 'green' as const},
  ]
  return (
    <div className="absolute inset-0 p-3 space-y-1.5">
      <div className="h-2 w-20 rounded bg-white/[0.08] mb-2" />
      {monitors.map((m, i) => (
        <div
          key={i}
          className="flex items-center gap-1.5 rounded bg-white/[0.02] border border-white/[0.04] px-2 py-1.5"
        >
          <div
            className={`w-1.5 h-1.5 rounded-full shrink-0 ${
              m.status === 'green' ? 'bg-emerald-400/60' : 'bg-amber-400/60'
            }`}
          />
          <div className="h-1.5 w-8 rounded bg-white/[0.06] shrink-0" />
          <div className="flex-1 h-2.5 rounded-sm overflow-hidden flex gap-px">
            {Array.from({length: 20}).map((_, j) => (
              <div
                key={j}
                className={`flex-1 ${
                  j === 14 && m.status === 'yellow'
                    ? 'bg-amber-400/30'
                    : 'bg-emerald-400/20'
                }`}
              />
            ))}
          </div>
          <div className="text-[6px] text-emerald-400/40 w-8 text-right shrink-0">99.9%</div>
        </div>
      ))}
    </div>
  )
}

function StatusPagesMock() {
  return (
    <div className="absolute inset-0 p-3">
      {/* Status banner */}
      <div className="rounded bg-emerald-400/10 border border-emerald-400/20 p-2 mb-2 flex items-center gap-1.5">
        <div className="w-2 h-2 rounded-full bg-emerald-400/60" />
        <div className="h-1.5 w-24 rounded bg-emerald-400/20" />
      </div>
      {/* Component list */}
      <div className="space-y-1">
        {Array.from({length: 4}).map((_, i) => (
          <div
            key={i}
            className="flex items-center justify-between rounded bg-white/[0.02] border border-white/[0.04] px-2 py-1.5"
          >
            <div className="h-1.5 w-14 rounded bg-white/[0.06]" />
            <div className="flex items-center gap-1">
              <div className="h-1 w-10 rounded bg-emerald-400/15" />
              <div className="w-1.5 h-1.5 rounded-full bg-emerald-400/50" />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function AlertingMock() {
  return (
    <div className="absolute inset-0 p-3 space-y-1.5">
      {/* Alert card */}
      <div className="rounded bg-red-400/[0.06] border border-red-400/10 p-2 flex items-start gap-1.5">
        <div className="w-1.5 h-1.5 rounded-full bg-red-400/60 mt-0.5 shrink-0" />
        <div className="space-y-0.5 flex-1">
          <div className="h-1.5 w-28 rounded bg-red-400/15" />
          <div className="h-1 w-16 rounded bg-white/[0.04]" />
        </div>
      </div>
      {/* Slack-style message */}
      <div className="rounded bg-white/[0.02] border border-white/[0.04] p-2">
        <div className="flex items-start gap-1.5">
          <div className="w-5 h-5 rounded bg-rose-400/15 shrink-0 flex items-center justify-center">
            <Bell className="w-2.5 h-2.5 text-rose-400/40" />
          </div>
          <div className="space-y-1 flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <div className="h-1.5 w-14 rounded bg-white/[0.08]" />
              <span className="text-[6px] text-white/15">12:34 PM</span>
            </div>
            <div className="rounded bg-white/[0.03] border border-white/[0.04] p-1.5 space-y-0.5">
              <div className="h-1 w-full rounded bg-white/[0.05]" />
              <div className="h-1 w-3/4 rounded bg-white/[0.04]" />
              <div className="flex gap-1.5 mt-1">
                <div className="h-3 w-12 rounded bg-rose-400/10" />
                <div className="h-3 w-10 rounded bg-white/[0.04]" />
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

// ────────────────────────────────────────────────────────────────
// Feature data
// ────────────────────────────────────────────────────────────────

interface Feature {
  icon: LucideIcon
  title: string
  description: string
  gradient: string
  iconBg: string
  iconColor: string
  mock: React.ReactNode
}

const primaryFeatures: Feature[] = [
  {
    icon: Activity,
    title: 'Error tracking',
    description:
      'Catch, group, and triage errors with smart fingerprinting. See full stack traces, breadcrumbs, and user context for every exception — across all your projects.',
    gradient: 'from-sky-500 to-cyan-400',
    iconBg: 'bg-sky-500/10',
    iconColor: 'text-sky-400',
    mock: <ErrorTrackingMock />,
  },
  {
    icon: FileText,
    title: 'Log management',
    description:
      'Structured JSON logs with live tail, full-text search, and powerful filtering. Unified with your errors and traces for faster root-cause analysis.',
    gradient: 'from-blue-500 to-indigo-400',
    iconBg: 'bg-blue-500/10',
    iconColor: 'text-blue-400',
    mock: <LogManagementMock />,
  },
  {
    icon: Play,
    title: 'Session replay',
    description:
      'Watch exactly what users did before an error. See clicks, navigation, and console output reconstructed in real-time. No more "works on my machine."',
    gradient: 'from-violet-500 to-purple-400',
    iconBg: 'bg-violet-500/10',
    iconColor: 'text-violet-400',
    mock: <SessionReplayMock />,
  },
]

const secondaryFeatures: Feature[] = [
  {
    icon: Zap,
    title: 'Performance monitoring',
    description:
      'Track transactions and spans. Find slow endpoints before your users do.',
    gradient: 'from-amber-500 to-orange-400',
    iconBg: 'bg-amber-500/10',
    iconColor: 'text-amber-400',
    mock: <PerformanceMock />,
  },
  {
    icon: Globe,
    title: 'Uptime monitoring',
    description:
      'Monitor your services 24/7. Get alerted when something goes down, with customizable check intervals.',
    gradient: 'from-green-500 to-emerald-400',
    iconBg: 'bg-green-500/10',
    iconColor: 'text-green-400',
    mock: <UptimeMock />,
  },
  {
    icon: GitBranch,
    title: 'Status pages',
    description:
      'Public status pages with custom domains. Automated from your monitors, free on all tiers.',
    gradient: 'from-cyan-500 to-teal-400',
    iconBg: 'bg-cyan-500/10',
    iconColor: 'text-cyan-400',
    mock: <StatusPagesMock />,
  },
  {
    icon: Bell,
    title: 'Alerting & Slack',
    description:
      'Multi-channel alerts with Slack integration. Acknowledge and resolve incidents directly from chat.',
    gradient: 'from-rose-500 to-pink-400',
    iconBg: 'bg-rose-500/10',
    iconColor: 'text-rose-400',
    mock: <AlertingMock />,
  },
]

const stats = [
  {value: '$0.40/GB', label: 'vs $0.60+ on BetterStack'},
  {value: '1 GB free', label: 'No credit card required'},
  {value: 'Zero', label: 'Per-seat fees'},
  {value: '99.9%', label: 'Uptime SLA'},
]

// ────────────────────────────────────────────────────────────────
// Main component
// ────────────────────────────────────────────────────────────────

export function VariantA() {
  return (
    <>
      {/* ── Hero ─────────────────────────────────────────── */}
      <section className="relative overflow-hidden bg-slate-950">
        {/* Gradient orbs */}
        <div className="absolute inset-0 overflow-hidden pointer-events-none">
          <div className="absolute -top-40 -right-40 w-[600px] h-[600px] rounded-full bg-sky-500/20 blur-[120px] animate-pulse-glow" />
          <div className="absolute -bottom-40 -left-40 w-[500px] h-[500px] rounded-full bg-violet-500/15 blur-[120px] animate-pulse-glow animation-delay-200" />
          <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[400px] h-[400px] rounded-full bg-cyan-500/10 blur-[100px] animate-pulse-glow animation-delay-400" />
        </div>

        {/* Grid pattern overlay */}
        <div
          className="absolute inset-0 opacity-[0.03]"
          style={{
            backgroundImage: `linear-gradient(rgba(255,255,255,0.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.1) 1px, transparent 1px)`,
            backgroundSize: '60px 60px',
          }}
        />

        {/* Animated pulse line */}
        <svg
          viewBox="0 0 1200 120"
          className="absolute bottom-0 left-0 right-0 w-full opacity-20 z-[1]"
          aria-hidden="true"
          preserveAspectRatio="none"
        >
          <polyline
            points="0,80 100,80 160,20 240,100 320,20 400,80 480,40 560,90 640,30 720,70 800,80 1200,80"
            fill="none"
            stroke="url(#hero-line-gradient)"
            strokeWidth="2"
            className="animate-draw-line"
          />
          <defs>
            <linearGradient id="hero-line-gradient" x1="0%" y1="0%" x2="100%" y2="0%">
              <stop offset="0%" stopColor="#38bdf8" />
              <stop offset="50%" stopColor="#a78bfa" />
              <stop offset="100%" stopColor="#38bdf8" />
            </linearGradient>
          </defs>
        </svg>

        {/* Hero text */}
        <div className="min-h-[70vh] flex flex-col justify-center relative z-10 px-4 sm:px-6 lg:px-8 pt-16">
          <div className="max-w-4xl mx-auto text-center">
            <div className="animate-fade-in-up">
              <div className="inline-flex items-center gap-2 rounded-full border border-sky-500/30 bg-sky-500/10 px-4 py-1.5 text-sm text-sky-300 mb-8 backdrop-blur-sm">
                <span className="relative flex h-2 w-2">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-sky-400 opacity-75" />
                  <span className="relative inline-flex rounded-full h-2 w-2 bg-sky-400" />
                </span>
                Sentry-compatible &mdash; switch in minutes
              </div>
            </div>

            <h1 className="text-5xl font-bold tracking-tight text-white sm:text-6xl lg:text-7xl mb-6 animate-fade-in-up animation-delay-100">
              Monitor everything.
              <br />
              <span className="bg-gradient-to-r from-sky-400 via-cyan-300 to-sky-400 bg-clip-text text-transparent">
                Pay for what you use.
              </span>
            </h1>

            <p className="text-lg sm:text-xl text-slate-400 max-w-2xl mx-auto mb-10 leading-relaxed animate-fade-in-up animation-delay-200">
              Errors, logs, uptime, and status pages — one platform, simple GB pricing.
              Switch from Sentry in minutes.
            </p>

            <div className="flex flex-col sm:flex-row gap-4 justify-center animate-fade-in-up animation-delay-300">
              <Button
                asChild
                size="lg"
                className="bg-sky-500 hover:bg-sky-400 text-white shadow-lg shadow-sky-500/30 hover:shadow-sky-400/40 transition-all duration-300 text-base px-8 h-12"
              >
                <Link to="/signup">
                  Start Free
                  <ArrowRight className="ml-2 h-4 w-4" />
                </Link>
              </Button>
              <Button
                asChild
                variant="outline"
                size="lg"
                className="border-slate-700 text-slate-200 hover:bg-slate-800 hover:border-slate-600 transition-all duration-300 text-base px-8 h-12"
              >
                <a href="#pricing">View Pricing</a>
              </Button>
            </div>
          </div>
        </div>

        {/* Hero screenshot — floating dashboard preview */}
        <div className="relative z-10 max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 mt-12 pb-16 animate-fade-in-up animation-delay-400">
          <div style={{perspective: '2000px'}}>
            <div style={{transform: 'rotateX(8deg)', transformOrigin: 'center top'}}>
              <ScreenshotFrame gradient="from-sky-500 to-cyan-400">
                <HeroDashboardMock />
              </ScreenshotFrame>
            </div>
          </div>
          {/* Bottom fade into next section */}
          <div className="absolute -bottom-1 left-0 right-0 h-32 bg-gradient-to-t from-slate-950 to-transparent pointer-events-none" />
        </div>
      </section>

      {/* ── Stats bar ────────────────────────────────────── */}
      <section className="relative bg-slate-900 border-y border-slate-800">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-12">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-8">
            {stats.map((stat, i) => (
              <div
                key={stat.label}
                className={`text-center animate-fade-in-up animation-delay-${(i + 1) * 100}`}
              >
                <div className="text-3xl sm:text-4xl font-bold bg-gradient-to-r from-sky-400 to-cyan-300 bg-clip-text text-transparent mb-1">
                  {stat.value}
                </div>
                <div className="text-sm text-slate-400">{stat.label}</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ── Primary features — alternating showcases ──── */}
      <section id="features" className="py-28 px-4 sm:px-6 lg:px-8 bg-background scroll-mt-24">
        <div className="max-w-6xl mx-auto">
          <div className="text-center mb-20">
            <p className="text-sm font-semibold text-sky-500 tracking-wide uppercase mb-3">
              Features
            </p>
            <h2 className="text-3xl font-bold tracking-tight sm:text-4xl lg:text-5xl mb-4">
              All-in-one observability
            </h2>
            <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
              Errors, logs, uptime, replays, and status pages. Everything you need to monitor your
              apps.
            </p>
          </div>

          <div className="space-y-24 lg:space-y-32">
            {primaryFeatures.map((feature, i) => {
              const reversed = i % 2 === 1
              return (
                <div
                  key={feature.title}
                  className={`flex flex-col ${reversed ? 'lg:flex-row-reverse' : 'lg:flex-row'} items-center gap-10 lg:gap-16`}
                >
                  {/* Text */}
                  <div className="lg:w-[40%] text-center lg:text-left">
                    <div
                      className={`inline-flex rounded-lg ${feature.iconBg} p-3 mb-5 ring-1 ring-inset ring-white/5`}
                    >
                      <feature.icon className={`h-6 w-6 ${feature.iconColor}`} />
                    </div>
                    <h3 className="text-2xl sm:text-3xl font-bold mb-4">{feature.title}</h3>
                    <p className="text-muted-foreground text-base sm:text-lg leading-relaxed">
                      {feature.description}
                    </p>
                  </div>
                  {/* Screenshot */}
                  <div className="lg:w-[60%] w-full">
                    <ScreenshotFrame gradient={feature.gradient}>
                      {feature.mock}
                    </ScreenshotFrame>
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      </section>

      {/* ── Secondary features — grid with screenshots ── */}
      <section className="py-28 px-4 sm:px-6 lg:px-8 bg-slate-950">
        <div className="max-w-6xl mx-auto">
          <div className="text-center mb-16">
            <h2 className="text-2xl font-bold tracking-tight sm:text-3xl text-white mb-3">
              Plus everything else you need
            </h2>
            <p className="text-slate-400 max-w-xl mx-auto">
              A complete observability toolkit — not just error tracking.
            </p>
          </div>

          <div className="grid sm:grid-cols-2 gap-10">
            {secondaryFeatures.map((feature) => (
              <div key={feature.title} className="group">
                <ScreenshotFrame gradient={feature.gradient} className="mb-6">
                  {feature.mock}
                </ScreenshotFrame>
                <div className="flex items-start gap-4 px-1">
                  <div
                    className={`shrink-0 rounded-lg ${feature.iconBg} p-2.5 ring-1 ring-inset ring-white/5`}
                  >
                    <feature.icon className={`h-5 w-5 ${feature.iconColor}`} />
                  </div>
                  <div>
                    <h3 className="font-semibold text-lg text-white mb-1">{feature.title}</h3>
                    <p className="text-slate-400 text-sm leading-relaxed">
                      {feature.description}
                    </p>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ── Sentry-compatible callout ────────────────────── */}
      <section className="py-24 px-4 sm:px-6 lg:px-8 bg-background border-y border-border/30">
        <div className="max-w-6xl mx-auto">
          <div className="flex flex-col lg:flex-row items-center gap-10 lg:gap-16">
            {/* Text */}
            <div className="lg:w-[42%] text-center lg:text-left">
              <div className="inline-flex rounded-lg bg-indigo-500/10 p-3 mb-5 ring-1 ring-inset ring-white/5">
                <Shield className="h-6 w-6 text-indigo-400" />
              </div>
              <h2 className="text-2xl sm:text-3xl font-bold mb-4">Sentry-compatible SDKs</h2>
              <p className="text-muted-foreground text-base leading-relaxed mb-6">
                Drop-in replacement for Sentry SDKs. Change your DSN, redeploy, and you're done.
                No code changes, no migration headaches. Switch in minutes, not days.
              </p>
              <Button
                asChild
                variant="outline"
                className="border-indigo-500/30 text-indigo-400 hover:bg-indigo-500/10 hover:border-indigo-500/50"
              >
                <Link to="/signup">
                  Try it free
                  <ArrowRight className="ml-2 h-4 w-4" />
                </Link>
              </Button>
            </div>
            {/* Code-style screenshot */}
            <div className="lg:w-[58%] w-full">
              <ScreenshotFrame gradient="from-indigo-500 to-sky-400">
                <div className="absolute inset-0 p-4 sm:p-6 font-mono text-[9px] sm:text-xs leading-relaxed">
                  <div className="space-y-0.5">
                    <div>
                      <span className="text-slate-500">{'// Just change your DSN — that\'s it.'}</span>
                    </div>
                    <div className="mt-3">
                      <span className="text-violet-400">import</span>
                      <span className="text-white/60"> * </span>
                      <span className="text-violet-400">as</span>
                      <span className="text-sky-300"> Sentry </span>
                      <span className="text-violet-400">from</span>
                      <span className="text-emerald-400"> &apos;@sentry/node&apos;</span>
                    </div>
                    <div className="mt-4">
                      <span className="text-sky-300">Sentry</span>
                      <span className="text-white/60">.</span>
                      <span className="text-amber-300">init</span>
                      <span className="text-white/40">{'({'}</span>
                    </div>
                    <div className="pl-4">
                      <span className="text-white/60">dsn: </span>
                      <span className="text-emerald-400">
                        &quot;https://
                        <span className="text-sky-300 font-semibold">your-project</span>
                        .moneat.io/1&quot;
                      </span>
                      <span className="text-white/40">,</span>
                    </div>
                    <div className="pl-4">
                      <span className="text-white/60">tracesSampleRate: </span>
                      <span className="text-amber-300">1.0</span>
                      <span className="text-white/40">,</span>
                    </div>
                    <div className="pl-4">
                      <span className="text-white/60">profilesSampleRate: </span>
                      <span className="text-amber-300">1.0</span>
                      <span className="text-white/40">,</span>
                    </div>
                    <div>
                      <span className="text-white/40">{'})'}</span>
                    </div>
                    <div className="mt-4">
                      <span className="text-slate-500">{'// That\'s it. All Sentry SDK features work.'}</span>
                    </div>
                  </div>
                </div>
              </ScreenshotFrame>
            </div>
          </div>
        </div>
      </section>

      {/* ── CTA Banner ───────────────────────────────────── */}
      <section className="relative overflow-hidden bg-slate-950 py-24 px-4 sm:px-6 lg:px-8">
        <div className="absolute inset-0 overflow-hidden pointer-events-none">
          <div className="absolute top-0 left-1/4 w-[400px] h-[400px] rounded-full bg-sky-500/10 blur-[100px]" />
          <div className="absolute bottom-0 right-1/4 w-[300px] h-[300px] rounded-full bg-violet-500/10 blur-[80px]" />
        </div>
        <div className="max-w-3xl mx-auto text-center relative z-10">
          <h2 className="text-3xl sm:text-4xl font-bold text-white mb-4">
            Ready to ditch the{' '}
            <span className="line-through decoration-slate-600 text-slate-500">overpriced</span>{' '}
            alternative?
          </h2>
          <p className="text-lg text-slate-400 mb-8 max-w-xl mx-auto">
            Create a free account in 30 seconds. No credit card required.
          </p>
          <Button
            asChild
            size="lg"
            className="bg-sky-500 hover:bg-sky-400 text-white shadow-lg shadow-sky-500/30 hover:shadow-sky-400/40 transition-all duration-300 text-base px-8 h-12"
          >
            <Link to="/signup">
              Get Started Free
              <ArrowRight className="ml-2 h-4 w-4" />
            </Link>
          </Button>
        </div>
      </section>
    </>
  )
}
