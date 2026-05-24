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

import {Link} from '@tanstack/react-router'
import {
  Activity,
  ArrowRight,
  Bell,
  Box,
  Brain,
  Check,
  Code2,
  FileText,
  Flame,
  GitBranch,
  Github,
  Globe,
  LayoutDashboard,
  Phone,
  Play,
  Server,
  ShieldCheck,
  Terminal,
  Zap,
  type LucideIcon,
} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {cn} from '@/lib/utils'

type FeatureGroup = {
  title: string
  description: string
  items: string[]
}

type Capability = {
  icon: LucideIcon
  title: string
  description: string
  href: string
  image: string
  imageAlt: string
}

type ProofPoint = {
  icon: LucideIcon
  label: string
  value: string
}

const proofPoints: ProofPoint[] = [
  {
    icon: ShieldCheck,
    label: 'License',
    value: 'Open source AGPL v3',
  },
  {
    icon: Activity,
    label: 'Sentry',
    value: 'SDK-compatible ingestion',
  },
  {
    icon: Server,
    label: 'Datadog',
    value: 'Agent-compatible endpoint',
  },
  {
    icon: Globe,
    label: 'Pricing',
    value: '1 GB free to start',
  },
]

const featureGroups: FeatureGroup[] = [
  {
    title: 'Observe',
    description: 'Production telemetry without sending every workflow to a separate product.',
    items: ['Errors', 'Logs', 'APM traces', 'Session replay', 'Profiling'],
  },
  {
    title: 'Operate',
    description: 'Infrastructure and service health views that match how incidents unfold.',
    items: ['Hosts', 'Containers', 'Kubernetes', 'Uptime checks', 'Status pages'],
  },
  {
    title: 'Respond',
    description: 'Close the loop from signal to owner with fewer handoffs.',
    items: ['Alerting', 'On-call schedules', 'Escalations', 'Incident timelines', 'AI triage'],
  },
]

const capabilities: Capability[] = [
  {
    icon: Activity,
    title: 'Error tracking',
    description: 'Group exceptions, inspect stack traces, and connect each issue to logs and release context.',
    href: '/error-tracking',
    image: '/screenshots/error-tracking.png',
    imageAlt: 'Error tracking dashboard showing issue groups and stack traces',
  },
  {
    icon: FileText,
    title: 'Log management',
    description: 'Search structured logs and move from a noisy event stream to the exact correlated signal.',
    href: '/log-management',
    image: '/screenshots/log-management.png',
    imageAlt: 'Log management dashboard showing searchable production logs',
  },
  {
    icon: Zap,
    title: 'APM and traces',
    description: 'Trace slow transactions, inspect spans, and understand service behavior before users report it.',
    href: '/performance-monitoring',
    image: '/screenshots/performance.png',
    imageAlt: 'Performance monitoring dashboard showing traces and latency data',
  },
]

const secondaryCapabilities = [
  {icon: Play, title: 'Session replay'},
  {icon: Box, title: 'Container monitoring'},
  {icon: GitBranch, title: 'Status pages'},
  {icon: Phone, title: 'On-call scheduling'},
  {icon: Brain, title: 'LLM observability'},
  {icon: Flame, title: 'Continuous profiling'},
  {icon: Bell, title: 'Alerting'},
  {icon: LayoutDashboard, title: 'Custom dashboards'},
] satisfies Array<{icon: LucideIcon; title: string}>

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
          <div className="relative size-full overflow-hidden rounded bg-[#080d18]">
            {children}
          </div>
        </div>
      </div>
    </div>
  )
}

function HeroProductPanel() {
  return (
    <div className="relative">
      <ScreenshotFrame gradient="from-cyan-400 via-sky-400 to-slate-300" className="lg:translate-y-4">
        <img
          src="/screenshots/dashboard.png"
          alt="Moneat observability dashboard with issues, logs, traces, and infrastructure signals"
          className="size-full object-cover"
        />
      </ScreenshotFrame>

      <div
        className={cn(
          'mt-4 grid gap-3 rounded-lg border border-slate-200 bg-white p-3 shadow-sm',
          'sm:absolute sm:-bottom-8 sm:-left-8 sm:mt-0 sm:w-72',
        )}
      >
        <div className="flex items-center justify-between border-b border-slate-100 pb-2">
          <span className="text-xs font-medium uppercase text-slate-500">Active signals</span>
          <span className="text-xs font-medium text-emerald-700">healthy</span>
        </div>
        <div className="grid gap-2">
          {[
            ['Errors', '12 open', 'bg-red-500'],
            ['Traces', '184ms p95', 'bg-cyan-500'],
            ['Uptime', '99.98%', 'bg-emerald-500'],
          ].map(([label, value, color]) => (
            <div key={label} className="flex items-center justify-between text-sm">
              <span className="flex items-center gap-2 text-slate-600">
                <span className={cn('size-2 rounded-full', color)} />
                {label}
              </span>
              <span className="font-medium text-slate-950">{value}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

function ProofStrip() {
  return (
    <section className="border-y border-slate-200 bg-slate-50">
      <div className="mx-auto grid max-w-7xl gap-px px-4 py-px sm:grid-cols-2 lg:grid-cols-4 lg:px-8">
        {proofPoints.map((point) => (
          <div key={point.label} className="bg-white px-5 py-5">
            <div className="flex items-center gap-3">
              <div className="flex size-9 items-center justify-center rounded-md border border-slate-200 bg-slate-50">
                <point.icon className="size-4 text-slate-700" />
              </div>
              <div>
                <p className="text-xs font-medium uppercase text-slate-500">{point.label}</p>
                <p className="text-sm font-semibold text-slate-950">{point.value}</p>
              </div>
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}

function OpenSourceSection() {
  return (
    <section className="bg-white px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl gap-12 lg:grid-cols-[0.9fr_1.1fr] lg:items-center">
        <div>
          <div className="mb-5 flex size-11 items-center justify-center rounded-md border border-slate-200 bg-slate-50">
            <Code2 className="size-5 text-slate-800" />
          </div>
          <h2 className="text-3xl font-semibold text-slate-950 sm:text-4xl">
            Open source where it matters: your telemetry, your network, your controls.
          </h2>
          <p className="mt-5 max-w-xl text-base leading-7 text-slate-600">
            Moneat is built for teams that want the hosted-product experience without giving up
            deployment control. Start hosted, self-host under AGPL v3, and keep the same ingestion
            model either way.
          </p>
          <div className="mt-8 grid gap-3">
            {['Source available under AGPL v3', 'Self-hostable with your own storage', 'Compatible migration path from existing agents'].map(
              item => (
                <div key={item} className="flex items-center gap-3 text-sm font-medium text-slate-800">
                  <Check className="size-4 text-emerald-600" />
                  {item}
                </div>
              ),
            )}
          </div>
          <div className="mt-8 flex flex-col gap-3 sm:flex-row">
            <Button asChild variant="outline" className="border-slate-300 bg-white text-slate-950">
              <a href="https://github.com/moneat-io/moneat" target="_blank" rel="noopener noreferrer">
                <Github data-icon="inline-start" />
                View on GitHub
              </a>
            </Button>
            <Button asChild variant="ghost" className="text-slate-700">
              <a href="/docs/self-hosting">
                Self-host docs
                <ArrowRight data-icon="inline-end" />
              </a>
            </Button>
          </div>
        </div>

        <div className="rounded-lg border border-slate-200 bg-slate-950 p-4 shadow-[0_20px_70px_rgba(15,23,42,0.16)]">
          <div className="mb-4 flex items-center justify-between border-b border-white/10 pb-3">
            <div className="flex items-center gap-2 text-sm font-medium text-white">
              <Terminal className="size-4 text-cyan-300" />
              deployment posture
            </div>
            <span className="text-xs text-slate-400">production-ready</span>
          </div>
          <div className="grid gap-3">
            {[
              ['Ingestion', 'Sentry SDKs and Datadog Agent endpoints'],
              ['Storage', 'PostgreSQL for app data, ClickHouse for analytics'],
              ['Operations', 'Errors, logs, traces, uptime, incidents in one UI'],
              ['Control', 'Hosted or self-managed infrastructure'],
            ].map(([label, value]) => (
              <div key={label} className="grid gap-1 rounded-md border border-white/10 bg-white/[0.03] p-4">
                <p className="text-xs font-medium uppercase text-cyan-200/80">{label}</p>
                <p className="text-sm leading-6 text-slate-200">{value}</p>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  )
}

function CapabilitySection() {
  return (
    <section id="features" className="border-y border-slate-200 bg-slate-50 px-4 py-24 text-slate-950 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="grid gap-8 lg:grid-cols-[0.82fr_1fr] lg:items-end">
          <div>
            <h2 className="text-3xl font-semibold sm:text-4xl">
              One operational surface for the signals engineers already use.
            </h2>
          </div>
          <p className="max-w-2xl text-base leading-7 text-slate-600 lg:justify-self-end">
            Bring the context engineers need into one workspace: what failed, where it happened,
            which services are affected, and who should respond next.
          </p>
        </div>

        <div className="mt-12 grid gap-5 lg:grid-cols-3">
          {capabilities.map((capability) => (
            <Link
              key={capability.title}
              to={capability.href}
              className="group overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm transition-colors hover:border-slate-300"
            >
              <div className="aspect-[16/10] overflow-hidden border-b border-slate-200 bg-slate-950">
                <img
                  src={capability.image}
                  alt={capability.imageAlt}
                  className="size-full object-cover opacity-90 transition-transform duration-500 group-hover:scale-[1.02]"
                />
              </div>
              <div className="p-5">
                <div className="mb-4 flex size-9 items-center justify-center rounded-md bg-slate-100 ring-1 ring-slate-200">
                  <capability.icon className="size-4 text-slate-700" />
                </div>
                <h3 className="text-lg font-semibold">{capability.title}</h3>
                <p className="mt-2 text-sm leading-6 text-slate-600">{capability.description}</p>
              </div>
            </Link>
          ))}
        </div>

        <div className="mt-10 grid gap-4 rounded-lg border border-slate-200 bg-white p-4 shadow-sm md:grid-cols-2 lg:grid-cols-4">
          {secondaryCapabilities.map((capability) => (
            <div key={capability.title} className="flex items-center gap-3 rounded-md bg-slate-50 p-3">
              <capability.icon className="size-4 text-slate-600" />
              <span className="text-sm font-medium text-slate-800">{capability.title}</span>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

function PlatformModelSection() {
  return (
    <section className="bg-white px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="max-w-3xl">
          <h2 className="text-3xl font-semibold text-slate-950 sm:text-4xl">
            Built around the incident path, not around a feature checklist.
          </h2>
          <p className="mt-4 text-base leading-7 text-slate-600">
            Moneat keeps the product story concrete: observe the failure, understand the system,
            then route the response from the same workspace.
          </p>
        </div>

        <div className="mt-12 grid gap-5 lg:grid-cols-3">
          {featureGroups.map((group) => (
            <div key={group.title} className="rounded-lg border border-slate-200 bg-slate-50 p-6">
              <h3 className="text-xl font-semibold text-slate-950">{group.title}</h3>
              <p className="mt-3 min-h-16 text-sm leading-6 text-slate-600">{group.description}</p>
              <div className="mt-6 grid gap-2">
                {group.items.map((item) => (
                  <div key={item} className="flex items-center justify-between border-t border-slate-200 py-2.5">
                    <span className="text-sm font-medium text-slate-800">{item}</span>
                    <Check className="size-4 text-slate-400" />
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

function CompatibilitySection() {
  return (
    <section className="border-y border-slate-200 bg-slate-50 px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto grid min-w-0 max-w-6xl gap-6 lg:grid-cols-2">
        <CompatibilityCard
          icon={ShieldCheck}
          title="Drop-in Sentry compatible."
          description="Update the DSN and keep your existing Sentry SDK instrumentation. Errors, replay,
          performance, and profiling can flow into Moneat without a full client migration."
          href="/signup"
          cta="Start free"
          lines={[
            'Sentry.init({',
            '  dsn: "https://your-project.moneat.io/1",',
            '  tracesSampleRate: 1.0,',
            '})',
          ]}
        />
        <CompatibilityCard
          icon={Server}
          title="Datadog Agent traffic, one endpoint away."
          description="Point your existing agent at Moneat and keep infrastructure, logs, traces,
          containers, and process telemetry moving through a single platform."
          href="/docs/datadog-agent"
          cta="Read the docs"
          lines={[
            'dd_url: "https://your-moneat-instance/dd"',
            'api_key: "your-api-key"',
            'apm_config:',
            '  enabled: true',
            'logs_enabled: true',
          ]}
        />
      </div>
    </section>
  )
}

function CompatibilityCard({
  icon: Icon,
  title,
  description,
  href,
  cta,
  lines,
}: {
  readonly icon: LucideIcon
  readonly title: string
  readonly description: string
  readonly href: string
  readonly cta: string
  readonly lines: string[]
}) {
  return (
    <div className="min-w-0 rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex size-11 items-center justify-center rounded-md border border-slate-200 bg-slate-50">
        <Icon className="size-5 text-slate-800" />
      </div>
      <h2 className="mt-6 text-2xl font-semibold text-slate-950">{title}</h2>
      <p className="mt-3 text-sm leading-6 text-slate-600">{description}</p>
      <pre className="mt-6 overflow-x-auto rounded-md bg-slate-950 p-4 text-xs leading-6 text-slate-200">
        {lines.map((line) => (
          <code key={line} className="block">
            {line}
          </code>
        ))}
      </pre>
      <Button asChild variant="ghost" className="mt-5 px-0 text-slate-800 hover:bg-transparent">
        <a href={href}>
          {cta}
          <ArrowRight data-icon="inline-end" />
        </a>
      </Button>
    </div>
  )
}

function FinalCta() {
  return (
    <section className="border-t border-slate-200 bg-white px-4 py-24 text-slate-950 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-3xl text-center">
        <h2 className="text-3xl font-semibold sm:text-4xl">
          Replace fragmented observability with one product your team can run.
        </h2>
        <p className="mx-auto mt-4 max-w-2xl text-base leading-7 text-slate-600">
          Start with hosted Moneat, move to self-hosting when your requirements demand it,
          and keep your existing SDK and agent paths intact.
        </p>
        <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
          <Button asChild size="lg" className="bg-slate-950 text-white hover:bg-slate-800">
            <Link to="/signup">
              Start free
              <ArrowRight data-icon="inline-end" />
            </Link>
          </Button>
          <Button asChild variant="outline" size="lg" className="border-slate-300 bg-white text-slate-950 hover:bg-slate-50">
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

export function VariantA() {
  return (
    <>
      <section className="relative overflow-hidden bg-white px-4 pb-24 pt-20 text-slate-950 sm:px-6 lg:px-8">
        <div className="absolute inset-x-0 top-0 h-96 bg-[linear-gradient(180deg,#f8fafc_0%,#ffffff_78%)]" />
        <div className="relative mx-auto grid max-w-7xl gap-14 lg:grid-cols-[0.88fr_1.12fr] lg:items-center">
          <div>
            <h1 className="max-w-3xl text-5xl font-semibold leading-[1.02] text-slate-950 sm:text-6xl">
              Replace Sentry and Datadog with one platform.
            </h1>
            <p className="mt-6 max-w-2xl text-lg leading-8 text-slate-600">
              Open-source observability that accepts your existing SDKs and agents. Errors,
              logs, traces, infrastructure, uptime, and incident response in one system.
            </p>
            <div className="mt-8 flex flex-col gap-3 sm:flex-row">
              <Button asChild size="lg" className="bg-slate-950 text-white hover:bg-slate-800">
                <Link to="/signup">
                  Start free
                  <ArrowRight data-icon="inline-end" />
                </Link>
              </Button>
              <Button asChild variant="outline" size="lg" className="border-slate-300 bg-white text-slate-950">
                <Link to="/demo">
                  <Play data-icon="inline-start" />
                  Live demo
                </Link>
              </Button>
            </div>
            <p className="mt-4 text-sm text-slate-500">
              No credit card required. No signup required for the demo.
            </p>
          </div>
          <HeroProductPanel />
        </div>
      </section>

      <ProofStrip />
      <OpenSourceSection />
      <CapabilitySection />
      <PlatformModelSection />
      <CompatibilitySection />
      <FinalCta />
    </>
  )
}
