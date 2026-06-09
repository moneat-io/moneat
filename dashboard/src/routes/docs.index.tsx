import {useEffect, useState, type ComponentType, type ReactNode} from 'react'
import {createFileRoute, Link} from '@tanstack/react-router'
import {
  Activity,
  ArrowRight,
  ArrowUpRight,
  Bell,
  BookOpen,
  Bug,
  Check,
  Copy,
  Filter,
  Flag,
  Github,
  GitBranch,
  Globe,
  HardDrive,
  LayoutDashboard,
  Lightbulb,
  MessageCircle,
  PlayCircle,
  Rocket,
  ScrollText,
  Search,
  Server,
  Shuffle,
  Sparkles,
  Tag,
} from 'lucide-react'
import {SeoHead} from '@/components/SeoHead'
import {docsIndexSeo} from '@/lib/seo/routes'
import {DocsFeedback} from '@/docs/components/DocsFeedback'
import {useDocsSearch} from '@/docs/components/DocsSearch'

export const Route = createFileRoute('/docs/')({
  component: DocsIndex,
})

const GITHUB_URL = 'https://github.com/moneat-io/moneat'
const DISCORD_URL = 'https://discord.com/invite/dTsahnJeyH'

interface StartCard {
  icon: ComponentType<{className?: string}>
  title: string
  badge: string
  badgeClass: string
  desc: string
  slug: string
  tile: string
  arrow: string
}

const startHere: StartCard[] = [
  {
    icon: Rocket,
    title: 'Get started',
    badge: '~5 min',
    badgeClass: 'border-emerald-400/20 bg-emerald-500/10 text-emerald-300',
    desc: 'Create a project, send your first event, and watch data land in real time.',
    slug: 'getting-started',
    tile: 'border-indigo-400/25 bg-indigo-500/10 text-indigo-300',
    arrow: 'group-hover:text-indigo-300',
  },
  {
    icon: Shuffle,
    title: 'Migrate your stack',
    badge: 'Datadog · Sentry',
    badgeClass: 'border-white/10 bg-white/[0.03] text-slate-400',
    desc: 'Repoint the Datadog Agent and Sentry SDKs at Moneat — no re-instrumentation.',
    slug: 'datadog-agent',
    tile: 'border-cyan-400/25 bg-cyan-500/10 text-cyan-300',
    arrow: 'group-hover:text-cyan-300',
  },
  {
    icon: Server,
    title: 'Self-host',
    badge: 'AGPL-3.0',
    badgeClass: 'border-white/10 bg-white/[0.03] text-slate-400',
    desc: 'Run the entire platform on your own infrastructure with one Docker command.',
    slug: 'self-hosting',
    tile: 'border-violet-400/25 bg-violet-500/10 text-violet-300',
    arrow: 'group-hover:text-violet-300',
  },
]

interface Capability {
  icon: ComponentType<{className?: string}>
  title: string
  desc: string
  slug: string
  color: string
}

const capabilities: Capability[] = [
  {icon: Bug, title: 'Error monitoring', desc: 'Capture and group errors with Sentry-compatible SDKs.', slug: 'error-monitoring', color: 'text-rose-300'},
  {icon: Activity, title: 'Performance monitoring', desc: 'Distributed tracing, spans, and live service maps.', slug: 'performance-monitoring', color: 'text-cyan-300'},
  {icon: ScrollText, title: 'Logging & OTLP', desc: 'Ingest, search, and tail logs from any OTLP source.', slug: 'logging', color: 'text-indigo-300'},
  {icon: PlayCircle, title: 'Session replay', desc: 'Replay user sessions linked directly to errors.', slug: 'session-replay', color: 'text-emerald-300'},
  {icon: HardDrive, title: 'Infrastructure', desc: 'Hosts, containers, processes, and network telemetry.', slug: 'infrastructure-monitoring', color: 'text-sky-300'},
  {icon: Sparkles, title: 'AI observability', desc: 'Trace LLM apps and agent executions end to end.', slug: 'ai-observability', color: 'text-violet-300'},
  {icon: LayoutDashboard, title: 'Custom dashboards', desc: 'Drag-and-drop widgets over any of your data.', slug: 'custom-dashboards', color: 'text-indigo-300'},
  {icon: Globe, title: 'Uptime & status', desc: 'HTTP checks, heartbeats, and public status pages.', slug: 'uptime-monitoring', color: 'text-emerald-300'},
  {icon: Bell, title: 'On-call & incidents', desc: 'Schedules, escalation policies, and incident response.', slug: 'on-call', color: 'text-amber-300'},
  {icon: Tag, title: 'Releases', desc: 'Track deploys and upload source maps.', slug: 'releases', color: 'text-cyan-300'},
  {icon: Flag, title: 'Feature flags', desc: 'Roll out, target, and measure with flags.', slug: 'feature-flags', color: 'text-sky-300'},
  {icon: Filter, title: 'Product analytics', desc: 'Funnels, retention, and event tracking.', slug: 'product-analytics', color: 'text-rose-300'},
]

const popular = [
  {label: 'Quickstart', slug: 'getting-started'},
  {label: 'Send OTLP data', slug: 'logging'},
  {label: 'Datadog Agent', slug: 'datadog-agent'},
  {label: 'Self-host', slug: 'self-hosting'},
]

const configLinks = [
  {label: 'SDK setup', slug: 'sdk-setup'},
  {label: 'Integrations', slug: 'integrations'},
  {label: 'SSO & authentication', slug: 'sso-authentication'},
  {label: 'API tokens', slug: 'api-tokens'},
  {label: 'MCP server', slug: 'mcp-server/overview'},
  {label: 'Billing & usage', slug: 'billing'},
]

const gradText = 'bg-gradient-to-r from-indigo-300 via-indigo-400 to-cyan-400 bg-clip-text text-transparent'

function DocLink({slug, className, children}: {slug: string; className?: string; children: ReactNode}) {
  return (
    <Link to="/docs/$" params={{_splat: slug}} className={className}>
      {children}
    </Link>
  )
}

const connectTabs = [
  {
    id: 'otel',
    label: 'OpenTelemetry',
    filename: '.env',
    code: `# point any OTLP exporter at Moneat
OTEL_EXPORTER_OTLP_ENDPOINT=https://api.moneat.io/otlp
OTEL_EXPORTER_OTLP_HEADERS="x-moneat-key=mnt_live_••••••"
OTEL_SERVICE_NAME=checkout-api`,
  },
  {
    id: 'datadog',
    label: 'Datadog',
    filename: 'datadog.yaml',
    code: `# keep the Datadog Agent — just repoint it
dd_url=https://api.moneat.io/dd
api_key=mnt_live_••••••
logs_config.logs_dd_url=api.moneat.io:443`,
  },
  {
    id: 'sentry',
    label: 'Sentry',
    filename: '.env',
    code: `# reuse your Sentry SDK — swap the DSN
SENTRY_DSN=https://mnt_live_••••••@api.moneat.io/1
# no re-instrumentation required`,
  },
] as const

function CodeLine({line}: {line: string}) {
  if (line.trim().startsWith('#')) return <span className="text-slate-600">{line}</span>
  const eq = line.indexOf('=')
  if (eq > -1) {
    return (
      <>
        <span className="text-indigo-300">{line.slice(0, eq)}</span>
        <span className="text-slate-500">=</span>
        <span className="text-emerald-300">{line.slice(eq + 1)}</span>
      </>
    )
  }
  return <span className="text-slate-300">{line}</span>
}

function ConnectPanel() {
  const [tab, setTab] = useState<(typeof connectTabs)[number]['id']>('otel')
  const [copied, setCopied] = useState(false)
  const active = connectTabs.find((t) => t.id === tab) ?? connectTabs[0]

  const copy = () => {
    void navigator.clipboard?.writeText(active.code)
    setCopied(true)
    setTimeout(() => setCopied(false), 1600)
  }

  return (
    <div className="border-t border-white/[0.06] bg-[#07080e] lg:border-l lg:border-t-0">
      <div className="h-px w-full bg-gradient-to-r from-indigo-500 to-cyan-400 opacity-60" />
      <div className="flex items-center gap-2 border-b border-white/[0.06] px-4 py-2.5">
        <span className="size-2.5 rounded-full bg-[#ff5f57]" />
        <span className="size-2.5 rounded-full bg-[#febc2e]" />
        <span className="size-2.5 rounded-full bg-[#28c840]" />
        <span className="ml-2 font-brandmono text-[11px] text-slate-500">{active.filename}</span>
        <div className="ml-auto flex gap-1 font-brandmono text-[11px]">
          {connectTabs.map((t) => (
            <button
              key={t.id}
              type="button"
              onClick={() => setTab(t.id)}
              className={`rounded-md px-2 py-1 transition-colors ${
                t.id === tab ? 'bg-white/[0.06] text-slate-200' : 'text-slate-500 hover:text-slate-300'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>
      <div className="group relative p-5">
        <button
          type="button"
          onClick={copy}
          aria-label={copied ? 'Snippet copied' : 'Copy snippet'}
          className="absolute right-3 top-3 grid size-7 place-items-center rounded-md border border-white/10 bg-white/[0.03] text-slate-400 opacity-0 transition hover:text-white group-hover:opacity-100"
        >
          {copied ? <Check className="size-3.5 text-emerald-300" /> : <Copy className="size-3.5" />}
        </button>
        <pre className="overflow-x-auto font-brandmono text-[12.5px] leading-7">
          {active.code.split('\n').map((line, i) => (
            <span key={i}>
              {i > 0 ? '\n' : ''}
              <CodeLine line={line} />
            </span>
          ))}
        </pre>
      </div>
      <div className="mx-5 mb-5 rounded-md border border-emerald-400/30 bg-emerald-950/30 p-4">
        <div className="mb-1 flex items-center gap-2 text-[13px] font-semibold text-emerald-100">
          <Lightbulb className="size-4 text-emerald-300" /> Tip
        </div>
        <p className="text-[12.5px] leading-6 text-slate-300">
          Already running the Datadog Agent? Set{' '}
          <span className="rounded bg-white/[0.06] px-1 py-0.5 font-brandmono text-[11.5px] text-slate-200">dd_url</span> to
          your Moneat endpoint and keep the rest of your config untouched.
        </p>
      </div>
    </div>
  )
}

const sections = [
  {id: 'start', label: 'Start here'},
  {id: 'capabilities', label: 'Browse by capability'},
  {id: 'connect', label: 'Connect telemetry'},
  {id: 'config', label: 'Configuration'},
]

function OnThisPage() {
  const [activeId, setActiveId] = useState('start')

  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) setActiveId(entry.target.id)
        }
      },
      {rootMargin: '-20% 0px -70% 0px', threshold: 0},
    )
    for (const s of sections) {
      const el = document.getElementById(s.id)
      if (el) observer.observe(el)
    }
    return () => observer.disconnect()
  }, [])

  return (
    <>
      <p className="font-brandmono text-[10px] font-medium uppercase tracking-[0.16em] text-slate-500">On this page</p>
      <ul className="mt-3 space-y-1 border-l border-white/[0.08] text-[13px]">
        {sections.map((s) => (
          <li key={s.id}>
            <a
              href={`#${s.id}`}
              className={`-ml-px block border-l-2 py-1 pl-3 transition-colors ${
                activeId === s.id
                  ? 'border-indigo-400 text-slate-100'
                  : 'border-transparent text-slate-400 hover:border-white/20 hover:text-slate-200'
              }`}
            >
              {s.label}
            </a>
          </li>
        ))}
      </ul>
    </>
  )
}

function DocsIndex() {
  const {open} = useDocsSearch()

  return (
    <>
      <SeoHead seo={docsIndexSeo} />
      <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_15.5rem]">
        <div className="min-w-0 px-6 pb-24 pt-8 sm:px-10 lg:px-14">
          {/* Hero */}
          <header className="relative">
            <div className="rise d1 inline-flex items-center gap-2 rounded-full border border-indigo-400/25 bg-indigo-500/[0.07] px-3 py-1 font-brandmono text-[11px] uppercase tracking-[0.14em] text-indigo-200">
              <svg viewBox="0 0 24 10" className="h-2.5 w-6 overflow-visible">
                <path
                  d="M0 5h6l2-4 3 8 2-5 2 3h7"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.6"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
              Documentation
            </div>

            <h1 className="rise d2 mt-5 max-w-3xl text-4xl font-bold leading-[1.05] tracking-tight text-white sm:text-[3.25rem]">
              Instrument once.
              <br />
              <span className={gradText}>See everything.</span>
            </h1>
            <p className="rise d3 mt-5 max-w-2xl text-[17px] leading-8 text-slate-400">
              Moneat is an OpenTelemetry-native platform for errors, traces, session replay, logs, uptime, and
              incidents — compatible with <span className="text-slate-200">Sentry SDKs</span>, the{' '}
              <span className="text-slate-200">Datadog&nbsp;Agent</span>, and any{' '}
              <span className="font-brandmono text-[15px] text-slate-200">OTLP</span> exporter.
            </p>

            {/* Search-forward command bar */}
            <div className="rise d4 mt-8 max-w-2xl">
              <button
                type="button"
                onClick={open}
                className="group flex w-full items-center gap-3 rounded-xl border border-white/[0.09] bg-white/[0.03] px-4 py-3.5 text-left transition-colors hover:border-white/[0.14]"
              >
                <Search className="size-5 text-slate-400" />
                <span className="flex-1 text-[15px] text-slate-500">Search the docs — try “send OTLP traces”…</span>
                <kbd>⌘K</kbd>
              </button>
              <div className="mt-3 flex flex-wrap items-center gap-2">
                <span className="font-brandmono text-[11px] uppercase tracking-wider text-slate-600">Popular</span>
                {popular.map((p) => (
                  <DocLink
                    key={p.slug + p.label}
                    slug={p.slug}
                    className="rounded-full border border-white/[0.08] bg-white/[0.02] px-3 py-1 text-[12.5px] text-slate-300 transition-colors hover:border-indigo-400/40 hover:text-white"
                  >
                    {p.label}
                  </DocLink>
                ))}
              </div>
            </div>

            {/* Signature pulse divider */}
            <div className="rise d5 mt-12" aria-hidden>
              <svg viewBox="0 0 1000 40" preserveAspectRatio="none" className="h-8 w-full">
                <path
                  className="pulse-line"
                  d="M0 20h360l14-14 18 28 16-34 12 24 10 -8h94l12 10 14-18 16 22 12 -6h372"
                  fill="none"
                  stroke="url(#docsDiv)"
                  strokeWidth="1.6"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
                <defs>
                  <linearGradient id="docsDiv" x1="0" x2="1" y1="0" y2="0">
                    <stop offset="0" stopColor="#6366F1" stopOpacity="0" />
                    <stop offset=".25" stopColor="#6366F1" />
                    <stop offset=".75" stopColor="#22D3EE" />
                    <stop offset="1" stopColor="#22D3EE" stopOpacity="0" />
                  </linearGradient>
                </defs>
              </svg>
            </div>
          </header>

          {/* Start here */}
          <section id="start" className="mt-6 scroll-mt-24">
            <h2 className="text-sm font-semibold uppercase tracking-[0.14em] text-slate-400">Start here</h2>
            <div className="mt-4 grid gap-4 sm:grid-cols-3">
              {startHere.map((card) => (
                <DocLink
                  key={card.slug}
                  slug={card.slug}
                  className="card-hover group relative overflow-hidden rounded-xl border border-white/[0.08] bg-[#0c0e16] p-5 hover:border-indigo-400/40"
                >
                  <div className={`mb-4 grid size-10 place-items-center rounded-lg border ${card.tile}`}>
                    <card.icon className="size-5" />
                  </div>
                  <div className="flex items-center gap-2">
                    <h3 className="text-[15px] font-semibold text-white">{card.title}</h3>
                    <span className={`rounded-full border px-1.5 py-0.5 font-brandmono text-[10px] ${card.badgeClass}`}>
                      {card.badge}
                    </span>
                  </div>
                  <p className="mt-1.5 text-[13px] leading-6 text-slate-400">{card.desc}</p>
                  <ArrowRight className={`absolute right-4 top-5 size-4 text-slate-600 transition group-hover:translate-x-0.5 ${card.arrow}`} />
                </DocLink>
              ))}
            </div>
          </section>

          {/* Browse by capability */}
          <section id="capabilities" className="mt-16 scroll-mt-24">
            <h2 className="text-2xl font-semibold tracking-tight text-white">Browse by capability</h2>
            <p className="mt-1.5 text-[14px] text-slate-400">
              One platform across the whole signal stack. Pick where you want to go.
            </p>
            <div className="mt-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {capabilities.map((cap) => (
                <DocLink
                  key={cap.slug}
                  slug={cap.slug}
                  className="card-hover group flex gap-3.5 rounded-xl border border-white/[0.07] bg-white/[0.015] p-4 hover:border-white/15 hover:bg-white/[0.03]"
                >
                  <span className={`mt-0.5 grid size-9 shrink-0 place-items-center rounded-md border border-white/10 bg-white/[0.03] ${cap.color}`}>
                    <cap.icon className="size-[18px]" />
                  </span>
                  <span>
                    <span className="block text-[14px] font-semibold text-white">{cap.title}</span>
                    <span className="mt-0.5 block text-[12.5px] leading-5 text-slate-400">{cap.desc}</span>
                  </span>
                </DocLink>
              ))}
            </div>
          </section>

          {/* Connect telemetry */}
          <section id="connect" className="mt-16 scroll-mt-24 overflow-hidden rounded-2xl border border-white/[0.08] bg-gradient-to-b from-white/[0.025] to-transparent">
            <div className="grid gap-0 lg:grid-cols-[1fr_1.05fr]">
              <div className="p-7 lg:p-8">
                <div className="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/[0.03] px-2.5 py-1 font-brandmono text-[10px] uppercase tracking-[0.14em] text-slate-400">
                  <span className="size-1.5 rounded-full bg-emerald-400 beat" /> OTLP-native
                </div>
                <h2 className="mt-4 text-2xl font-semibold tracking-tight text-white">Connect your telemetry</h2>
                <p className="mt-2 max-w-md text-[14px] leading-7 text-slate-400">
                  Keep the agents and SDKs you already run. Moneat speaks the{' '}
                  <span className="text-slate-200">OpenTelemetry protocol</span>, the{' '}
                  <span className="text-slate-200">Datadog Agent</span> wire format, and{' '}
                  <span className="text-slate-200">Sentry SDKs</span> — point them at one endpoint and go.
                </p>
                <ul className="mt-5 space-y-2.5">
                  {['No re-instrumentation or vendor lock-in', 'Drop-in for existing Datadog Agent fleets', '1 GB free — no per-host fees'].map(
                    (item) => (
                      <li key={item} className="flex items-center gap-2.5 text-[13.5px] text-slate-300">
                        <Check className="size-4 shrink-0 text-emerald-400" /> {item}
                      </li>
                    ),
                  )}
                </ul>
                <div className="mt-6 flex flex-wrap gap-2.5">
                  <DocLink
                    slug="getting-started"
                    className="inline-flex items-center gap-2 rounded-lg bg-white px-4 py-2 text-[13.5px] font-semibold text-slate-950 transition-colors hover:bg-slate-200"
                  >
                    Open quickstart <ArrowRight className="size-4" />
                  </DocLink>
                  <DocLink
                    slug="datadog-agent"
                    className="inline-flex items-center gap-2 rounded-lg border border-white/15 px-4 py-2 text-[13.5px] font-medium text-slate-200 transition-colors hover:border-white/30 hover:text-white"
                  >
                    Datadog Agent setup
                  </DocLink>
                </div>
              </div>
              <ConnectPanel />
            </div>
          </section>

          {/* Configuration & account */}
          <section id="config" className="mt-16 scroll-mt-24">
            <h2 className="text-sm font-semibold uppercase tracking-[0.14em] text-slate-400">Configuration &amp; account</h2>
            <div className="mt-4 grid gap-x-8 gap-y-1 sm:grid-cols-2 lg:grid-cols-3">
              {configLinks.map((link) => (
                <DocLink
                  key={link.slug}
                  slug={link.slug}
                  className="group flex items-center justify-between border-b border-white/[0.06] py-2.5 text-[13.5px] text-slate-300 transition-colors hover:text-white"
                >
                  <span>{link.label}</span>
                  <ArrowRight className="size-3.5 text-slate-600 transition group-hover:translate-x-0.5 group-hover:text-indigo-300" />
                </DocLink>
              ))}
            </div>
          </section>

          <DocsFeedback slug="intro" />
        </div>

        {/* Right utility rail */}
        <aside className="docs-scroll sticky top-16 hidden h-[calc(100vh-4rem)] overflow-y-auto border-l border-white/[0.06] px-5 py-8 xl:block">
          <OnThisPage />

          <p className="mt-8 font-brandmono text-[10px] font-medium uppercase tracking-[0.16em] text-slate-500">Resources</p>
          <ul className="mt-2.5 space-y-0.5 text-[13px]">
            <li>
              <Link
                to="/docs/$"
                params={{_splat: 'getting-started'}}
                className="flex items-center gap-2.5 rounded-md px-2 py-1.5 text-slate-300 transition-colors hover:bg-white/[0.04]"
              >
                <BookOpen className="size-4 text-slate-500" /> Getting started
              </Link>
            </li>
            <li>
              <a
                href={`${GITHUB_URL}/releases`}
                target="_blank"
                rel="noopener noreferrer"
                className="flex items-center gap-2.5 rounded-md px-2 py-1.5 text-slate-300 transition-colors hover:bg-white/[0.04]"
              >
                <GitBranch className="size-4 text-slate-500" /> Changelog
                <ArrowUpRight className="ml-auto size-3 text-slate-600" />
              </a>
            </li>
            <li>
              <a
                href={DISCORD_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="flex items-center gap-2.5 rounded-md px-2 py-1.5 text-slate-300 transition-colors hover:bg-white/[0.04]"
              >
                <MessageCircle className="size-4 text-slate-500" /> Community
                <ArrowUpRight className="ml-auto size-3 text-slate-600" />
              </a>
            </li>
            <li>
              <a
                href={GITHUB_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="flex items-center gap-2.5 rounded-md px-2 py-1.5 text-slate-300 transition-colors hover:bg-white/[0.04]"
              >
                <Github className="size-4 text-slate-500" /> GitHub
                <ArrowUpRight className="ml-auto size-3 text-slate-600" />
              </a>
            </li>
          </ul>
        </aside>
      </div>
    </>
  )
}
