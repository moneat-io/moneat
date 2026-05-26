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

import {useEffect, useRef, useState} from 'react'
import {Link} from '@tanstack/react-router'
import {
  Activity,
  Bell,
  Bot,
  Box,
  Brain,
  ChevronDown,
  ChevronRight,
  FileText,
  Flame,
  GitBranch,
  Globe,
  LayoutDashboard,
  Menu,
  Phone,
  Play,
  Server,
  ShieldCheck,
  Zap,
  type LucideIcon,
} from 'lucide-react'
import {Logo} from '@/components/Logo'
import {Button} from '@/components/ui/button'
import {Sheet, SheetContent, SheetDescription, SheetTitle, SheetTrigger} from '@/components/ui/sheet'
import {cn} from '@/lib/utils'

type LandingTone = 'dark' | 'light'

interface LandingToneProps {
  readonly tone?: LandingTone
}

export const GithubIcon = ({className}: {className?: string}) => (
  <svg viewBox="0 0 24 24" fill="currentColor" className={className} xmlns="http://www.w3.org/2000/svg">
    <path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12" />
  </svg>
)

export const DiscordIcon = ({className}: {className?: string}) => (
  <svg viewBox="0 0 24 24" fill="currentColor" className={className} xmlns="http://www.w3.org/2000/svg">
    <path d="M20.317 4.3698a19.7913 19.7913 0 00-4.8851-1.5152.0741.0741 0 00-.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 00-.0785-.037 19.7363 19.7363 0 00-4.8852 1.515.0699.0699 0 00-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 00.0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 00.0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 00-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 01-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 01.0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 01.0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 01-.0066.1276 12.2986 12.2986 0 01-1.873.8914.0766.0766 0 00-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 00.0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 00.0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 00-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189Z"/>
  </svg>
)

export const XIcon = ({className}: {className?: string}) => (
  <svg viewBox="0 0 24 24" fill="currentColor" className={className} xmlns="http://www.w3.org/2000/svg">
    <path d="M18.901 1.153h3.68l-8.04 9.19L24 22.846h-7.406l-5.8-7.584-6.638 7.584H.474l8.6-9.83L0 1.154h7.594l5.243 6.932ZM17.61 20.644h2.039L6.486 3.24H4.298Z" />
  </svg>
)

interface PlatformItem {
  icon: LucideIcon
  title: string
  description: string
  href: string
  iconColor: string
}

interface PlatformCategory {
  label: string
  items: PlatformItem[]
}

const PLATFORM_CATEGORIES: PlatformCategory[] = [
  {
    label: 'Observe',
    items: [
      {icon: Activity, title: 'Error Tracking', description: 'Catch and triage errors with smart fingerprinting', href: '/error-tracking', iconColor: 'text-slate-400'},
      {icon: FileText, title: 'Log Management', description: 'Structured JSON logs with full-text search', href: '/log-management', iconColor: 'text-slate-400'},
      {icon: Play, title: 'Session Replay', description: 'Watch what users did before an error', href: '/session-replay', iconColor: 'text-slate-400'},
      {icon: Zap, title: 'APM & Traces', description: 'Track transactions and find slow endpoints', href: '/performance-monitoring', iconColor: 'text-slate-400'},
    ],
  },
  {
    label: 'Infrastructure',
    items: [
      {icon: Server, title: 'Host Monitoring', description: 'CPU, memory, disk, and network metrics', href: '/infrastructure-monitoring', iconColor: 'text-slate-400'},
      {icon: Box, title: 'Container Monitoring', description: 'Real-time Docker container metrics', href: '/infrastructure-monitoring', iconColor: 'text-slate-400'},
      {icon: LayoutDashboard, title: 'Kubernetes', description: 'Cluster, node, and pod observability', href: '/infrastructure-monitoring', iconColor: 'text-slate-400'},
      {icon: Flame, title: 'Profiling', description: 'CPU and heap profiles from production', href: '/profiling', iconColor: 'text-slate-400'},
    ],
  },
  {
    label: 'Respond',
    items: [
      {icon: Globe, title: 'Uptime Monitoring', description: 'Monitor services 24/7 with instant alerts', href: '/uptime-monitoring', iconColor: 'text-slate-400'},
      {icon: Phone, title: 'On-Call & Incidents', description: 'Rotations, escalation, phone & SMS alerts', href: '/on-call-management', iconColor: 'text-slate-400'},
      {icon: GitBranch, title: 'Status Pages', description: 'Public status pages with custom domains', href: '/public-status-pages', iconColor: 'text-slate-400'},
      {icon: Bell, title: 'Alerting', description: 'Multi-channel alerts to Slack and Discord', href: '/alerting', iconColor: 'text-slate-400'},
    ],
  },
  {
    label: 'Intelligence',
    items: [
      {icon: Brain, title: 'AI & LLM Observability', description: 'Monitor LLM calls, tokens, and costs', href: '/ai-observability', iconColor: 'text-slate-400'},
      {icon: Bot, title: 'MCP Server', description: 'Query Moneat from Cursor, Copilot, or agents', href: '/mcp-server', iconColor: 'text-slate-400'},
      {icon: LayoutDashboard, title: 'Dashboards', description: 'Custom dashboards with any data source', href: '/custom-dashboards', iconColor: 'text-slate-400'},
      {icon: ShieldCheck, title: 'Security & SBOM', description: 'Package inventory with CVE tracking', href: '/security-sbom', iconColor: 'text-slate-400'},
    ],
  },
]

function PlatformMegaMenu({tone = 'dark'}: LandingToneProps) {
  const [open, setOpen] = useState(false)
  const closeTimeout = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const containerRef = useRef<HTMLDivElement>(null)
  const isLight = tone === 'light'

  const handleEnter = () => {
    clearTimeout(closeTimeout.current)
    setOpen(true)
  }

  const handleLeave = () => {
    closeTimeout.current = setTimeout(() => setOpen(false), 150)
  }

  useEffect(() => () => clearTimeout(closeTimeout.current), [])

  return (
    <div
      ref={containerRef}
      role="none"
      className="relative"
      onMouseEnter={handleEnter}
      onMouseLeave={handleLeave}
    >
      <button
        onClick={() => setOpen(v => !v)}
        className={cn(
          'flex items-center gap-1 text-sm font-medium transition-colors',
          isLight ? 'text-slate-600 hover:text-slate-950' : 'text-muted-foreground hover:text-foreground',
        )}
      >
        Platform
        <ChevronDown className={cn('size-3.5 transition-transform duration-200', open && 'rotate-180')} />
      </button>

      {open && (
        <div className="absolute top-full left-1/2 -translate-x-1/2 pt-3 z-50">
          <div
            className={cn(
              'w-[720px] rounded-lg border p-5 shadow-2xl backdrop-blur-xl',
              isLight
                ? 'border-slate-200 bg-white text-slate-950 shadow-slate-950/10'
                : 'border-white/[0.08] bg-[#0c0e14] shadow-black/50',
            )}
          >
            <div className="grid grid-cols-2 gap-x-6 gap-y-5">
              {PLATFORM_CATEGORIES.map(cat => (
                <div key={cat.label}>
                  <div
                    className={cn(
                      'mb-2.5 px-2 text-[11px] font-semibold uppercase',
                      isLight ? 'text-slate-500' : 'text-slate-500',
                    )}
                  >
                    {cat.label}
                  </div>
                  <div className="flex flex-col gap-0.5">
                    {cat.items.map(item => (
                      <a
                        key={item.title}
                        href={item.href}
                        onClick={() => setOpen(false)}
                        className={cn(
                          'group flex items-start gap-3 rounded-md px-2 py-2 transition-colors',
                          isLight ? 'hover:bg-slate-50' : 'hover:bg-white/[0.05]',
                        )}
                      >
                        <div className="shrink-0 mt-0.5">
                          <item.icon className={cn('size-4', isLight ? 'text-slate-500' : item.iconColor)} />
                        </div>
                        <div className="min-w-0">
                          <div
                            className={cn(
                              'flex items-center gap-1 text-sm font-medium',
                              isLight ? 'text-slate-900' : 'text-slate-200 group-hover:text-white',
                            )}
                          >
                            {item.title}
                            <ChevronRight
                              className="size-3 -translate-x-1 opacity-0 transition-all group-hover:translate-x-0 group-hover:opacity-50"
                            />
                          </div>
                          <div
                            className={cn(
                              'text-xs leading-relaxed',
                              isLight ? 'text-slate-500' : 'text-slate-500 group-hover:text-slate-400',
                            )}
                          >
                            {item.description}
                          </div>
                        </div>
                      </a>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function MobileNav({tone = 'dark'}: LandingToneProps) {
  const [open, setOpen] = useState(false)
  const [platformExpanded, setPlatformExpanded] = useState(false)
  const isLight = tone === 'light'
  const navItemClass = cn(
    'rounded-md px-3 py-2.5 text-sm font-medium transition-colors',
    isLight
      ? 'text-slate-700 hover:bg-slate-100 hover:text-slate-950'
      : 'text-slate-300 hover:bg-white/[0.05] hover:text-white',
  )
  const nestedItemClass = cn(
    'flex items-center gap-2.5 rounded-md px-3 py-2 text-sm transition-colors',
    isLight
      ? 'text-slate-600 hover:bg-slate-100 hover:text-slate-950'
      : 'text-slate-400 hover:bg-white/[0.05] hover:text-white',
  )

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          className={cn('lg:hidden', isLight && 'text-slate-700 hover:bg-slate-100')}
          aria-label="Open menu"
        >
          <Menu />
        </Button>
      </SheetTrigger>
      <SheetContent
        side="right"
        className={cn(
          'w-80 p-0',
          isLight ? 'border-slate-200 bg-white' : 'border-white/[0.08] bg-[#0c0e14]',
        )}
      >
        <SheetTitle className="sr-only">Navigation menu</SheetTitle>
        <SheetDescription className="sr-only">
          Primary navigation links for Moneat pages and account actions.
        </SheetDescription>
        <div className={cn('flex items-center border-b px-4 py-4', isLight ? 'border-slate-200' : 'border-white/[0.06]')}>
          <Logo className="h-7" />
        </div>
        <nav className="flex flex-col gap-1 px-3 py-4 overflow-y-auto max-h-[calc(100vh-180px)]">
          <button
            onClick={() => setPlatformExpanded(v => !v)}
            className={cn('flex items-center justify-between', navItemClass)}
          >
            Platform
            <ChevronDown className={cn('size-4 transition-transform duration-200', platformExpanded && 'rotate-180')} />
          </button>
          {platformExpanded && (
            <div className={cn('mb-2 ml-2 flex flex-col gap-1 border-l pl-2', isLight ? 'border-slate-200' : 'border-white/[0.06]')}>
              {PLATFORM_CATEGORIES.map(cat => (
                <div key={cat.label}>
                  <div className="px-3 py-1.5 text-[10px] font-semibold uppercase text-slate-500">
                    {cat.label}
                  </div>
                  {cat.items.map(item => (
                    <a
                      key={item.title}
                      href={item.href}
                      onClick={() => setOpen(false)}
                      className={nestedItemClass}
                    >
                      <item.icon className={cn('size-3.5', isLight ? 'text-slate-500' : item.iconColor)} />
                      {item.title}
                    </a>
                  ))}
                </div>
              ))}
            </div>
          )}
          <Link
            to="/pricing"
            onClick={() => setOpen(false)}
            className={navItemClass}
          >
            Pricing
          </Link>
          <Link
            to="/compare"
            onClick={() => setOpen(false)}
            className={navItemClass}
          >
            Compare
          </Link>
          <Link
            to="/docs"
            onClick={() => setOpen(false)}
            className={navItemClass}
          >
            Docs
          </Link>
          <Link
            to="/demo"
            onClick={() => setOpen(false)}
            className={navItemClass}
          >
            Live Demo
          </Link>
          <a
            href="/blog"
            onClick={() => setOpen(false)}
            className={navItemClass}
          >
            Blog
          </a>
          <div className="flex items-center gap-4 px-3 py-2.5">
            <a
              href="https://github.com/moneat-io/moneat"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="GitHub"
              onClick={() => setOpen(false)}
              className={cn('transition-colors', isLight ? 'text-slate-500 hover:text-slate-950' : 'text-slate-400 hover:text-white')}
            >
              <GithubIcon className="size-5" />
            </a>
            <a
              href="https://discord.com/invite/dTsahnJeyH"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="Discord"
              onClick={() => setOpen(false)}
              className={cn('transition-colors', isLight ? 'text-slate-500 hover:text-slate-950' : 'text-slate-400 hover:text-white')}
            >
              <DiscordIcon className="size-5" />
            </a>
            <a
              href="https://x.com/moneat_io"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="X (Twitter)"
              onClick={() => setOpen(false)}
              className={cn('transition-colors', isLight ? 'text-slate-500 hover:text-slate-950' : 'text-slate-400 hover:text-white')}
            >
              <XIcon className="size-5" />
            </a>
          </div>
        </nav>
        <div className={cn('flex flex-col gap-3 border-t px-4 pb-6 pt-3', isLight ? 'border-slate-200' : 'border-white/[0.06]')}>
          <Link to="/login" onClick={() => setOpen(false)}>
            <Button
              variant="outline"
              className={cn(
                'w-full',
                isLight ? 'border-slate-300 bg-white text-slate-950' : 'border-white/[0.1] text-slate-200 hover:bg-white/[0.05]',
              )}
            >
              Log in
            </Button>
          </Link>
          <Link to="/signup" onClick={() => setOpen(false)}>
            <Button className={cn('w-full text-white', isLight ? 'bg-slate-950 hover:bg-slate-800' : 'bg-sky-500 hover:bg-sky-600 shadow-md shadow-sky-500/25')}>
              Sign up free
            </Button>
          </Link>
        </div>
      </SheetContent>
    </Sheet>
  )
}

export function LandingNavbar({tone = 'dark'}: LandingToneProps) {
  const isLight = tone === 'light'
  useEffect(() => {
    if (!isLight) return undefined
    const root = document.documentElement
    const prevColorScheme = root.style.colorScheme
    root.classList.remove('dark')
    root.style.colorScheme = 'light'
    return () => {
      root.style.colorScheme = prevColorScheme
    }
  }, [isLight])

  const navLinkClass = cn(
    'text-sm font-medium transition-colors',
    isLight ? 'text-slate-600 hover:text-slate-950' : 'text-muted-foreground hover:text-foreground',
  )
  const socialLinkClass = cn(
    'transition-colors',
    isLight ? 'text-slate-500 hover:text-slate-950' : 'text-muted-foreground hover:text-foreground',
  )

  return (
    <header
      className={cn(
        'sticky top-0 z-50 w-full border-b backdrop-blur-xl',
        isLight ? 'border-slate-200 bg-white/90 text-slate-950' : 'border-white/[0.06] bg-[#0a0b14]/80',
      )}
    >
      <div className="flex h-16 items-center px-4 sm:px-6 lg:px-8 max-w-7xl mx-auto justify-between">
        <Link to="/" className="flex items-center" aria-label="Moneat Home">
          <Logo className="h-8" />
        </Link>
        <nav className="hidden items-center gap-8 lg:flex" aria-label="Main navigation">
          <PlatformMegaMenu tone={tone} />
          <Link
            to="/pricing"
            className={navLinkClass}
          >
            Pricing
          </Link>
          <Link
            to="/compare"
            className={navLinkClass}
          >
            Compare
          </Link>
          <Link
            to="/docs"
            className={navLinkClass}
          >
            Docs
          </Link>
          <Link
            to="/demo"
            className={navLinkClass}
          >
            Live Demo
          </Link>
          <a
            href="/blog"
            className={navLinkClass}
          >
            Blog
          </a>
          <div className="flex items-center gap-3 ml-1">
            <a
              href="https://github.com/moneat-io/moneat"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="GitHub"
              className={socialLinkClass}
            >
              <GithubIcon className="size-5" />
            </a>
            <a
              href="https://discord.com/invite/dTsahnJeyH"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="Discord"
              className={socialLinkClass}
            >
              <DiscordIcon className="size-5" />
            </a>
            <a
              href="https://x.com/moneat_io"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="X (Twitter)"
              className={socialLinkClass}
            >
              <XIcon className="size-5" />
            </a>
          </div>
        </nav>
        <div className="hidden items-center gap-3 lg:flex">
          <Link to="/login">
            <Button variant="ghost" className={cn('text-sm', isLight && 'text-slate-700 hover:bg-slate-100')}>
              Log in
            </Button>
          </Link>
          <Link to="/signup">
            <Button
              className={cn(
                'text-sm text-white',
                isLight ? 'bg-slate-950 hover:bg-slate-800' : 'bg-sky-500 hover:bg-sky-600 shadow-md shadow-sky-500/25',
              )}
            >
              Sign up free
            </Button>
          </Link>
        </div>

        <MobileNav tone={tone} />
      </div>
    </header>
  )
}

export function LandingFooter({tone = 'dark'}: LandingToneProps) {
  if (tone === 'light') {
    return (
      <footer className="border-t border-slate-200 bg-white px-4 py-14 text-slate-950 sm:px-6 lg:px-8">
        <div className="mx-auto max-w-6xl">
          <div className="grid gap-10 md:grid-cols-[1.2fr_0.8fr_0.8fr_0.9fr_0.8fr]">
            <div>
              <div className="mb-4 flex items-center gap-3">
                <Logo className="h-7" markOnly />
                <span className="text-lg font-semibold">moneat</span>
              </div>
              <p className="max-w-sm text-sm leading-6 text-slate-600">
                Open-source observability for errors, logs, infrastructure, APM, uptime,
                on-call, and status pages.
              </p>
              <div className="mt-6 flex items-center gap-4">
                <a
                  href="https://github.com/moneat-io/moneat"
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label="GitHub"
                  className="text-slate-500 transition-colors hover:text-slate-950"
                >
                  <GithubIcon className="size-5" />
                </a>
                <a
                  href="https://discord.com/invite/dTsahnJeyH"
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label="Discord"
                  className="text-slate-500 transition-colors hover:text-slate-950"
                >
                  <DiscordIcon className="size-5" />
                </a>
                <a
                  href="https://x.com/moneat_io"
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label="X (Twitter)"
                  className="text-slate-500 transition-colors hover:text-slate-950"
                >
                  <XIcon className="size-5" />
                </a>
              </div>
            </div>

            {[
              {
                title: 'Product',
                links: [
                  ['Error Tracking', '/error-tracking'],
                  ['Log Management', '/log-management'],
                  ['APM & Traces', '/performance-monitoring'],
                  ['Infrastructure', '/infrastructure-monitoring'],
                  ['On-Call', '/on-call-management'],
                ],
              },
              {
                title: 'Resources',
                links: [
                  ['Documentation', '/docs'],
                  ['Pricing', '/pricing'],
                  ['Compare', '/compare'],
                  ['Live Demo', '/demo'],
                  ['Blog', '/blog'],
                ],
              },
              {
                title: 'Alternatives',
                links: [
                  ['Datadog Alternative', '/datadog-alternative'],
                  ['Sentry Alternative', '/sentry-alternative'],
                  ['Better Stack Alternative', '/better-stack-alternative'],
                  ['SigNoz Alternative', '/signoz-alternative'],
                ],
              },
              {
                title: 'Company',
                links: [
                  ['Terms', '/legal/terms'],
                  ['Privacy', '/legal/privacy'],
                  ['Contact', 'mailto:support@moneat.io'],
                  ['Log in', '/login'],
                  ['Sign up free', '/signup'],
                ],
              },
            ].map((group) => (
              <div key={group.title}>
                <h4 className="mb-4 text-xs font-semibold uppercase text-slate-500">{group.title}</h4>
                <ul className="flex flex-col gap-2.5">
                  {group.links.map(([label, href]) => (
                    <li key={label}>
                      <a href={href} className="text-sm text-slate-600 transition-colors hover:text-slate-950">
                        {label}
                      </a>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>

          <div className="mt-10 border-t border-slate-200 pt-6 text-xs leading-6 text-slate-500">
            <p>
              Operated by Adrian Elder &middot; 1235 East Blvd, Ste E PMB 2045,
              Charlotte, NC 28203, USA &middot;{' '}
              <a href="mailto:support@moneat.io" className="hover:text-slate-950">
                support@moneat.io
              </a>
            </p>
            <p className="mt-3">
              &copy; {new Date().getFullYear()} Moneat. Compatible with Sentry&reg; SDKs and
              Datadog&reg; Agent. Moneat is not affiliated with or endorsed by either company.
            </p>
          </div>
        </div>
      </footer>
    )
  }

  return (
    <footer className="border-t border-white/[0.06] bg-[#070810] py-16 px-4 sm:px-6 lg:px-8">
      <div className="max-w-6xl mx-auto">
        <div className="grid grid-cols-2 md:grid-cols-6 gap-10 mb-14">
          <div className="col-span-2 md:col-span-1">
            <div className="flex items-center gap-3 mb-3">
              <Logo className="h-7" markOnly />
              <span className="text-lg font-semibold text-white">moneat</span>
            </div>
            <p className="text-sm text-slate-500 max-w-xs leading-relaxed">
              Errors, logs, infrastructure, APM, and on-call — one platform, simple pricing.
            </p>
          </div>

          <div>
            <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-4">Product</h4>
            <ul className="space-y-2.5">
              <li><a href="/error-tracking" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">Error Tracking</a></li>
              <li><a href="/log-management" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">Log Management</a></li>
              <li><a href="/session-replay" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">Session Replay</a></li>
              <li><a href="/performance-monitoring" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">APM & Traces</a></li>
              <li><a href="/infrastructure-monitoring" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">Infrastructure</a></li>
              <li><a href="/uptime-monitoring" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">Uptime Monitoring</a></li>
              <li><a href="/on-call-management" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">On-Call</a></li>
              <li><a href="/ai-observability" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">AI Observability</a></li>
            </ul>
          </div>

          <div>
            <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-4">Resources</h4>
            <ul className="space-y-2.5">
              <li><Link to="/docs" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">Documentation</Link></li>
              <li><a href="/blog" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">Blog</a></li>
              <li><Link to="/pricing" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">Pricing</Link></li>
              <li>
                <Link to="/compare" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">
                  Compare
                </Link>
              </li>
              <li><Link to="/demo" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">Live Demo</Link></li>
            </ul>
          </div>

          <div>
            <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-4">Alternatives</h4>
            <ul className="space-y-2.5">
              <li>
                <Link
                  to="/datadog-alternative"
                  className="text-sm text-slate-500 hover:text-sky-400 transition-colors"
                >
                  Datadog Alternative
                </Link>
              </li>
              <li>
                <Link
                  to="/sentry-alternative"
                  className="text-sm text-slate-500 hover:text-sky-400 transition-colors"
                >
                  Sentry Alternative
                </Link>
              </li>
              <li>
                <Link
                  to="/better-stack-alternative"
                  className="text-sm text-slate-500 hover:text-sky-400 transition-colors"
                >
                  Better Stack Alternative
                </Link>
              </li>
              <li>
                <Link
                  to="/signoz-alternative"
                  className="text-sm text-slate-500 hover:text-sky-400 transition-colors"
                >
                  SigNoz Alternative
                </Link>
              </li>
            </ul>
          </div>

          <div>
            <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-4">Company</h4>
            <ul className="space-y-2.5">
              <li>
                <Link to="/legal/terms" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">
                  Terms
                </Link>
              </li>
              <li>
                <Link to="/legal/privacy" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">
                  Privacy
                </Link>
              </li>
              <li>
                <a
                  href="mailto:support@moneat.io"
                  className="text-sm text-slate-500 hover:text-sky-400 transition-colors"
                >
                  Contact
                </a>
              </li>
            </ul>
          </div>

          <div>
            <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-4">Account</h4>
            <ul className="space-y-2.5">
              <li><Link to="/login" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">Log in</Link></li>
              <li><Link to="/signup" className="text-sm text-slate-500 hover:text-sky-400 transition-colors">Sign up free</Link></li>
            </ul>
            <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-4 mt-8">Community</h4>
            <div className="flex items-center gap-4">
              <a href="https://github.com/moneat-io/moneat" target="_blank" rel="noopener noreferrer" aria-label="GitHub" className="text-slate-500 hover:text-sky-400 transition-colors">
                <GithubIcon className="h-5 w-5" />
              </a>
              <a href="https://discord.com/invite/dTsahnJeyH" target="_blank" rel="noopener noreferrer" aria-label="Discord" className="text-slate-500 hover:text-sky-400 transition-colors">
                <DiscordIcon className="h-5 w-5" />
              </a>
              <a href="https://x.com/moneat_io" target="_blank" rel="noopener noreferrer" aria-label="X (Twitter)" className="text-slate-500 hover:text-sky-400 transition-colors">
                <XIcon className="h-5 w-5" />
              </a>
            </div>
          </div>
        </div>

        <div className="pt-8 border-t border-white/[0.06] flex flex-col items-center gap-4 text-center">
          <p className="text-xs text-slate-600">
            Operated by Adrian Elder &middot; 1235 East Blvd, Ste E PMB 2045, Charlotte, NC 28203, USA &middot;{' '}
            <a href="mailto:support@moneat.io" className="hover:text-sky-400 transition-colors">support@moneat.io</a>
          </p>
          <p className="text-xs text-slate-600">
            &copy; {new Date().getFullYear()} Moneat. All rights reserved.
          </p>
          <p className="text-xs text-slate-600">
            Compatible with Sentry&reg; SDKs &amp; Datadog&reg; Agent. Switch in minutes.
            Sentry is a registered trademark of Functional Software, Inc.
            Datadog is a registered trademark of Datadog, Inc.
            Moneat is not affiliated with or endorsed by either company.
          </p>
        </div>
      </div>
    </footer>
  )
}
