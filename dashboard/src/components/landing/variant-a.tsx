import {Link} from '@tanstack/react-router'
import {
  Activity,
  ArrowRight,
  Bell,
  Box,
  FileText,
  GitBranch,
  Globe,
  Phone,
  Play,
  Shield,
  Zap,
  type LucideIcon,
} from 'lucide-react'
import {Button} from '@/components/ui/button'

// ────────────────────────────────────────────────────────────────
// Logos
// ────────────────────────────────────────────────────────────────

const SlackLogo = ({ className }: { className?: string }) => (
  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" className={className}>
    <path fill="#E01E5A" d="M5.042 15.165a2.528 2.528 0 0 1-2.52 2.523A2.52 2.52 0 0 1 0 15.165a2.527 2.527 0 0 1 2.522-2.52h2.52v2.52zM6.313 15.165a2.527 2.527 0 0 1 2.521-2.52 2.522 2.522 0 0 1 2.521 2.52v6.313A2.52 2.52 0 0 1 8.834 24a2.528 2.528 0 0 1-2.521-2.522v-6.313z"/>
    <path fill="#36C5F0" d="M8.834 5.042a2.528 2.528 0 0 1-2.521-2.52A2.52 2.52 0 0 1 8.834 0a2.528 2.528 0 0 1 2.521 2.522v2.52h-2.521zM8.834 6.313a2.528 2.528 0 0 1 2.521 2.521 2.522 2.522 0 0 1-2.521 2.521H2.522A2.52 2.52 0 0 1 0 8.834a2.528 2.528 0 0 1 2.522-2.521h6.312z"/>
    <path fill="#2EB67D" d="M18.956 8.834a2.528 2.528 0 0 1 2.522-2.521A2.52 2.52 0 0 1 24 8.834a2.528 2.528 0 0 1-2.522 2.521h-2.522V8.834zM17.688 8.834a2.528 2.528 0 0 1-2.523 2.521 2.522 2.522 0 0 1-2.52-2.521V2.522A2.52 2.52 0 0 1 15.165 0a2.528 2.528 0 0 1 2.523 2.522v6.312z"/>
    <path fill="#ECB22E" d="M15.165 18.956a2.528 2.528 0 0 1 2.523 2.522A2.52 2.52 0 0 1 15.165 24a2.527 2.527 0 0 1-2.52-2.522v-2.522h2.52zM15.165 17.688a2.527 2.527 0 0 1-2.52-2.523 2.52 2.52 0 0 1 2.52-2.52h6.313A2.52 2.52 0 0 1 24 15.165a2.528 2.528 0 0 1-2.522 2.523h-6.313z"/>
  </svg>
)

const DiscordLogo = ({className}: {className?: string}) => (
  <svg role="img" viewBox="0 0 24 24" fill="#5865F2" className={className} xmlns="http://www.w3.org/2000/svg">
    <path d="M20.317 4.3698a19.7913 19.7913 0 00-4.8851-1.5152.0741.0741 0 00-.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 00-.0785-.037 19.7363 19.7363 0 00-4.8852 1.515.0699.0699 0 00-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 00.0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 00.0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 00-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 01-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 01.0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 01.0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 01-.0066.1276 12.2986 12.2986 0 01-1.873.8914.0766.0766 0 00-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 00.0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 00.0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 00-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189Z"/>
  </svg>
)

const IncidentIoLogo = ({ className }: { className?: string }) => (
  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 163" fill="none" className={className}>
    <g clipPath="url(#clip0_1361_12561)">
      <path fillRule="evenodd" clipRule="evenodd" d="M48.7336 139.642V163C20.7585 156.323 0 131.711 0 102.372C0 85.4557 7.15792 72.0354 18.1053 58.8703C27.1831 47.9534 49.5985 19.0426 56.6543 3.08954C58.3673 -0.783473 62.7348 -0.633805 64.6182 1.44721C70.6432 8.10421 78.0694 22.6432 80.4983 39.135C80.9932 42.4953 81.1969 45.2388 81.3587 47.4184C81.706 52.0954 81.8604 54.1748 84.2854 54.1748C88.0955 54.1748 90.588 48.3977 91.1358 42.4345C91.4869 38.6136 95.2774 37.3346 97.8914 38.6136C110.463 44.7644 123.292 74.0426 126.393 88.4102C127.366 92.9158 128 97.5719 128 102.372C128 131.646 107.335 156.214 79.4537 162.955V139.642H48.7336ZM64.0002 130.333C73.8316 130.333 81.8016 122.789 81.8016 113.483C81.8016 98.6407 70.8577 88.0345 65.4048 84.8105C65.0364 84.5928 64.8523 84.4839 64.3512 84.4974C63.9843 84.5073 63.4429 84.7369 63.181 84.9935C62.8232 85.3441 62.7283 85.743 62.5387 86.5409C61.5721 90.6065 58.5292 93.5054 55.327 96.556C50.9141 100.76 46.1988 105.252 46.1988 113.483C46.1988 122.789 54.1688 130.333 64.0002 130.333Z" fill="#F25533"/>
    </g>
    <defs>
      <clipPath id="clip0_1361_12561">
        <rect width="128" height="163" fill="white"/>
      </clipPath>
    </defs>
  </svg>
)

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
  noFrame?: boolean
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
    mock: <img src="/screenshots/error-tracking.png" alt="Error tracking dashboard showing issues list with stack traces and context" className="w-full h-full object-cover" />,
  },
  {
    icon: FileText,
    title: 'Log management',
    description:
      'Structured JSON logs with live tail, full-text search, and powerful filtering. Unified with your errors and traces for faster root-cause analysis.',
    gradient: 'from-blue-500 to-indigo-400',
    iconBg: 'bg-blue-500/10',
    iconColor: 'text-blue-400',
    mock: <img src="/screenshots/log-management.png" alt="Log management interface with real-time log viewer and filtering" className="w-full h-full object-cover" />,
  },
  {
    icon: Play,
    title: 'Session replay',
    description:
      'Watch exactly what users did before an error. See clicks, navigation, and console output reconstructed in real-time. No more "works on my machine."',
    gradient: 'from-violet-500 to-purple-400',
    iconBg: 'bg-violet-500/10',
    iconColor: 'text-violet-400',
    mock: <img src="/screenshots/session-replay.png" alt="Session replay showing user interactions before errors occurred" className="w-full h-full object-cover" />,
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
    mock: <img src="/screenshots/performance.png" alt="Performance monitoring dashboard with transaction timings" className="w-full h-full object-cover" />,
  },
  {
    icon: Globe,
    title: 'Uptime monitoring',
    description:
      'Monitor your services 24/7. Get alerted when something goes down, with customizable check intervals.',
    gradient: 'from-green-500 to-emerald-400',
    iconBg: 'bg-green-500/10',
    iconColor: 'text-green-400',
    mock: <img src="/screenshots/uptime.png" alt="Uptime monitoring with status checks and availability metrics" className="w-full h-full object-cover" />,
  },
  {
    icon: Box,
    title: 'Container monitoring',
    description:
      'Real-time Docker container metrics. Track CPU, memory, and network usage across all your containers.',
    gradient: 'from-blue-500 to-cyan-400',
    iconBg: 'bg-blue-500/10',
    iconColor: 'text-blue-400',
    mock: <img src="/screenshots/containers.png" alt="Container monitoring showing Docker metrics and resource usage" className="w-full h-full object-cover" />,
  },
  {
    icon: GitBranch,
    title: 'Status pages',
    description:
      'Public status pages with custom domains. Automated from your monitors, free on all tiers.',
    gradient: 'from-cyan-500 to-teal-400',
    iconBg: 'bg-cyan-500/10',
    iconColor: 'text-cyan-400',
    mock: <img src="/screenshots/status-pages.png" alt="Public status page showing service health and incidents" className="w-full h-full object-cover" />,
  },
  {
    icon: Phone,
    title: 'On-call scheduling',
    description:
      'Manage on-call rotations and escalation policies. Notify the right person via phone, SMS, or Slack when things break.',
    gradient: 'from-orange-500 to-amber-400',
    iconBg: 'bg-orange-500/10',
    iconColor: 'text-orange-400',
    noFrame: true,
    mock: (
      <div className="relative w-full rounded-xl overflow-hidden bg-[#0B1120] border border-white/10">
        <div className="absolute inset-0"
             style={{
               backgroundImage: 'linear-gradient(rgba(255, 255, 255, 0.05) 1px, transparent 1px), linear-gradient(90deg, rgba(255, 255, 255, 0.05) 1px, transparent 1px)',
               backgroundSize: '24px 24px'
             }}
        />
        <div className="absolute inset-0 bg-gradient-to-t from-[#0B1120] via-transparent to-transparent" />

        <div className="flex items-center gap-2 px-4 py-2.5 border-b border-white/[0.06] bg-white/[0.02]">
           <div className="h-4 opacity-0" />
        </div>

        <div className="aspect-video relative flex flex-col items-center justify-center p-6 gap-3">
            <div className="w-full flex items-center gap-3 p-3 rounded-lg bg-white/5 border border-white/10 backdrop-blur-sm">
                <div className="h-8 w-8 rounded-full bg-sky-500/20 flex items-center justify-center text-sky-400 font-bold text-xs">AE</div>
                <div className="flex-1 space-y-1.5">
                    <div className="h-2 w-24 rounded bg-white/20"/>
                    <div className="h-1.5 w-16 rounded bg-white/10"/>
                </div>
                <div className="px-2 py-1 rounded bg-green-500/20 text-green-400 text-[10px] font-medium border border-green-500/20">Active</div>
            </div>
             <div className="h-4 w-0.5 bg-white/10"/>
            <div className="w-full flex items-center gap-3 p-3 rounded-lg bg-white/5 border border-white/10 opacity-60 backdrop-blur-sm">
                <div className="h-8 w-8 rounded-full bg-violet-500/20 flex items-center justify-center text-violet-400 font-bold text-xs">JD</div>
                <div className="flex-1 space-y-1.5">
                    <div className="h-2 w-20 rounded bg-white/20"/>
                    <div className="h-1.5 w-12 rounded bg-white/10"/>
                </div>
                <div className="px-2 py-1 rounded bg-white/10 text-slate-400 text-[10px] font-medium border border-white/10">Backup</div>
            </div>
        </div>
      </div>
    ),
  },
  {
    icon: Bell,
    title: 'Alerting & Integrations',
    description:
      'Multi-channel alerts with Slack and Discord integrations. Route alerts to the right teams instantly.',
    gradient: 'from-rose-500 to-pink-400',
    iconBg: 'bg-rose-500/10',
    iconColor: 'text-rose-400',
    noFrame: true,
    mock: (
      <div className="relative w-full rounded-xl overflow-hidden bg-[#0B1120] border border-white/10">
        <div className="absolute inset-0" 
             style={{
               backgroundImage: 'linear-gradient(rgba(255, 255, 255, 0.05) 1px, transparent 1px), linear-gradient(90deg, rgba(255, 255, 255, 0.05) 1px, transparent 1px)',
               backgroundSize: '24px 24px'
             }} 
        />
        <div className="absolute inset-0 bg-gradient-to-t from-[#0B1120] via-transparent to-transparent" />
        
        {/* Header spacer to match ScreenshotFrame height */}
        <div className="flex items-center gap-2 px-4 py-2.5 border-b border-white/[0.06] bg-white/[0.02]">
          <div className="h-4 opacity-0" />
        </div>

        <div className="aspect-video relative flex items-center justify-center gap-10">
          <div className="p-4 rounded-2xl bg-white/5 border border-white/10 backdrop-blur-sm hover:border-rose-500/50 hover:bg-rose-500/10 transition-colors duration-300">
            <SlackLogo className="h-14 w-14" />
          </div>
          <div className="p-4 rounded-2xl bg-white/5 border border-white/10 backdrop-blur-sm hover:border-indigo-500/50 hover:bg-indigo-500/10 transition-colors duration-300">
            <DiscordLogo className="h-14 w-14" />
          </div>
          <div className="p-4 rounded-2xl bg-white/5 border border-white/10 backdrop-blur-sm hover:border-orange-500/50 hover:bg-orange-500/10 transition-colors duration-300">
            <IncidentIoLogo className="h-14 w-14" />
          </div>
        </div>
      </div>
    ),
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
              Errors, logs, uptime, on-call, and status pages — one platform, simple GB pricing.
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
                <img src="/screenshots/dashboard.png" alt="Main dashboard overview with statistics and error trends" className="w-full h-full object-cover" />
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
                {feature.noFrame ? (
                  <div className="mb-6">{feature.mock}</div>
                ) : (
                  <ScreenshotFrame gradient={feature.gradient} className="mb-6">
                    {feature.mock}
                  </ScreenshotFrame>
                )}
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
