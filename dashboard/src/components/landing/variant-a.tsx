import {Link} from '@tanstack/react-router'
import {Activity, ArrowRight, GitBranch, MessageSquare, Play, Shield, Zap, FileText, Globe, Bell,} from 'lucide-react'
import {Button} from '@/components/ui/button'

const features = [
  {
    icon: Activity,
    title: 'Error tracking',
    description:
      'Catch, group, and triage errors with smart fingerprinting. See exactly what broke and where.',
    color: 'from-sky-500 to-cyan-400',
    iconBg: 'bg-sky-500/10',
    iconColor: 'text-sky-400',
  },
  {
    icon: FileText,
    title: 'Log management',
    description:
      'Structured JSON logs with live tail, search, and filtering. All your observability data in one place.',
    color: 'from-blue-500 to-indigo-400',
    iconBg: 'bg-blue-500/10',
    iconColor: 'text-blue-400',
  },
  {
    icon: Zap,
    title: 'Performance monitoring',
    description:
      'Track transactions and spans. Find slow endpoints before your users do.',
    color: 'from-amber-500 to-orange-400',
    iconBg: 'bg-amber-500/10',
    iconColor: 'text-amber-400',
  },
  {
    icon: Globe,
    title: 'Uptime monitoring',
    description:
      'Monitor your services 24/7. Get alerted when something goes down, with customizable check intervals.',
    color: 'from-green-500 to-emerald-400',
    iconBg: 'bg-green-500/10',
    iconColor: 'text-green-400',
  },
  {
    icon: Play,
    title: 'Session replay',
    description:
      'Watch exactly what users did before an error. No more "works on my machine."',
    color: 'from-violet-500 to-purple-400',
    iconBg: 'bg-violet-500/10',
    iconColor: 'text-violet-400',
  },
  {
    icon: GitBranch,
    title: 'Status pages',
    description:
      'Public status pages with custom domains. Automated from your monitors, free on all tiers.',
    color: 'from-cyan-500 to-teal-400',
    iconBg: 'bg-cyan-500/10',
    iconColor: 'text-cyan-400',
  },
  {
    icon: Bell,
    title: 'Alerting & Slack',
    description:
      'Multi-channel alerts with Slack integration. Error notifications, incident acknowledgment from chat.',
    color: 'from-rose-500 to-pink-400',
    iconBg: 'bg-rose-500/10',
    iconColor: 'text-rose-400',
  },
  {
    icon: Shield,
    title: 'Sentry-compatible',
    description:
      'Drop-in replacement for Sentry SDKs. Switch in minutes, not days. Zero lock-in.',
    color: 'from-sky-500 to-indigo-400',
    iconBg: 'bg-indigo-500/10',
    iconColor: 'text-indigo-400',
  },
]

const stats = [
  { value: '$0.40/GB', label: 'vs $0.60+ on BetterStack' },
  { value: '1 GB free', label: 'No credit card required' },
  { value: 'Zero', label: 'Per-seat fees' },
  { value: '99.9%', label: 'Uptime SLA' },
]

export function VariantA() {
  return (
    <>
      {/* Hero */}
      <section className="relative min-h-[85vh] flex flex-col justify-center overflow-hidden bg-slate-950">
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
          className="absolute bottom-0 left-0 right-0 w-full opacity-20"
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

        <div className="max-w-4xl mx-auto text-center relative z-10 px-4 sm:px-6 lg:px-8">
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
      </section>

      {/* Stats bar */}
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

      {/* Features */}
      <section
        id="features"
        className="py-28 px-4 sm:px-6 lg:px-8 bg-background scroll-mt-24"
      >
        <div className="max-w-6xl mx-auto">
          <div className="text-center mb-20">
            <p className="text-sm font-semibold text-sky-500 tracking-wide uppercase mb-3">
              Features
            </p>
            <h2 className="text-3xl font-bold tracking-tight sm:text-4xl lg:text-5xl mb-4">
              All-in-one observability
            </h2>
            <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
              Errors, logs, uptime, replays, and status pages. Everything you need to monitor your apps.
            </p>
          </div>
          <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-6">
            {features.map((feature) => (
              <div
                key={feature.title}
                className="group relative rounded-xl border border-border/60 bg-card p-6 transition-all duration-300 hover:border-border hover:shadow-lg hover:shadow-sky-500/5 hover:-translate-y-0.5"
              >
                <div className="absolute inset-0 rounded-xl bg-gradient-to-br opacity-0 group-hover:opacity-[0.03] transition-opacity duration-300" />
                <div
                  className={`rounded-lg ${feature.iconBg} w-fit p-3 mb-4 ring-1 ring-inset ring-white/5`}
                >
                  <feature.icon
                    className={`h-5 w-5 ${feature.iconColor}`}
                  />
                </div>
                <h3 className="font-semibold text-lg mb-2">{feature.title}</h3>
                <p className="text-muted-foreground text-sm leading-relaxed">
                  {feature.description}
                </p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* CTA Banner */}
      <section className="relative overflow-hidden bg-slate-950 py-24 px-4 sm:px-6 lg:px-8">
        <div className="absolute inset-0 overflow-hidden pointer-events-none">
          <div className="absolute top-0 left-1/4 w-[400px] h-[400px] rounded-full bg-sky-500/10 blur-[100px]" />
          <div className="absolute bottom-0 right-1/4 w-[300px] h-[300px] rounded-full bg-violet-500/10 blur-[80px]" />
        </div>
        <div className="max-w-3xl mx-auto text-center relative z-10">
          <h2 className="text-3xl sm:text-4xl font-bold text-white mb-4">
            Ready to ditch the{' '}
            <span className="line-through decoration-slate-600 text-slate-500">
              overpriced
            </span>{' '}
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
