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
    icon: Bell,
    title: 'Alerting & Integrations',
    description:
      'Multi-channel alerts with Slack and incident.io integrations. Route alerts to the right teams and create incidents automatically.',
    gradient: 'from-rose-500 to-pink-400',
    iconBg: 'bg-rose-500/10',
    iconColor: 'text-rose-400',
    mock: (
      <div className="flex gap-4 p-6 h-full items-center justify-center">
        <div className="flex-1 rounded-lg overflow-hidden shadow-xl ring-1 ring-white/10 hover:ring-white/20 transition-all hover:scale-[1.02]">
          <img 
            src="/screenshots/slack-integration.png" 
            alt="Slack integration tile showing connection setup" 
            className="w-full h-full object-cover"
          />
        </div>
        <div className="flex-1 rounded-lg overflow-hidden shadow-xl ring-1 ring-white/10 hover:ring-white/20 transition-all hover:scale-[1.02]">
          <img 
            src="/screenshots/incident-io-integration.png" 
            alt="Incident.io integration tile showing incident creation setup" 
            className="w-full h-full object-cover"
          />
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
