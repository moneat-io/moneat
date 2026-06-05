// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY IMPLIED WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {useEffect, useMemo, useState, type KeyboardEvent as ReactKeyboardEvent} from 'react'
import {useCommandPalette} from '@/hooks/useCommandPalette'
import {useNavigate} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {cn} from '@/lib/utils'
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command'
import {Dialog, DialogContent} from '@/components/ui/dialog'
import {Button} from '@/components/ui/button'
import {AiChatContent} from '@/components/command-palette/AiChatContent'
import {
  Home,
  AlertCircle,
  Timer,
  ScrollText,
  LayoutDashboard,
  Server,
  Activity,
  Globe,
  Play,
  MessageSquare,
  Package,
  Brain,
  Bell,
  Shield,
  ShieldCheck,
  Settings,
  Folder,
  BarChart3,
  Cpu,
  Flame,
  Flag,
  Box,
  Terminal,
  Network,
  CalendarClock,
  Ship,
  Database,
  Bug,
  Router,
  Map as MapIcon,
  Sparkles,
} from 'lucide-react'
import {hasEnterpriseModule, useEnterpriseFeatures} from '@/hooks/useEnterpriseFeatures'
import {APP_OVERVIEW_HREF} from '@/lib/overview-route'

const PAGE_ITEMS: Array<{
  label: string
  description: string
  href: string
  icon: React.ComponentType<{className?: string}>
  keywords?: string[]
}> = [
  {
    label: 'Overview',
    description: 'Service metrics and key stats',
    href: APP_OVERVIEW_HREF,
    icon: Home,
    keywords: ['home'],
  },
  {label: 'Issues', description: 'Errors and exceptions', href: '/issues', icon: AlertCircle, keywords: ['errors', 'bugs']},
  {label: 'Traces', description: 'Distributed traces and service latency', href: '/performance/traces', icon: Timer, keywords: ['performance', 'traces', 'services', 'latency']},
  {label: 'APM Traces', description: 'Application performance traces and spans', href: '/apm-traces', icon: Cpu, keywords: ['apm', 'traces', 'spans', 'distributed tracing']},
  {label: 'Profiles', description: 'Continuous profiling and flamegraphs', href: '/profiles', icon: Flame, keywords: ['profiling', 'flamegraph', 'cpu', 'memory']},
  {label: 'Logs', description: 'Search and explore log events', href: '/logs', icon: ScrollText, keywords: ['logging']},
  {label: 'Dashboards', description: 'Custom metrics and visualizations', href: '/dashboards', icon: LayoutDashboard, keywords: ['widgets']},
  {label: 'Feature Flags', description: 'OpenFeature flags and experiments', href: '/feature-flags', icon: Flag, keywords: ['flags', 'openfeature', 'ofrep', 'experiments']},
  {label: 'Monitoring', description: 'Infrastructure and system health', href: '/monitoring', icon: Server, keywords: ['infrastructure', 'systems', 'servers']},
  {label: 'Monitoring – Map', description: 'Visual map for services, hosts, and containers', href: '/monitoring/map', icon: MapIcon, keywords: ['infrastructure map', 'host map', 'containers', 'tags', 'topology']},
  {label: 'Service Map', description: 'Service dependency topology', href: '/monitoring/service-map', icon: Network, keywords: ['service map', 'dependencies', 'traces', 'topology']},
  {label: 'Monitoring – Hosts', description: 'Host metrics and system resources', href: '/monitoring/hosts', icon: Server, keywords: ['infrastructure', 'servers', 'cpu', 'memory', 'disk']},
  {label: 'Monitoring – Containers', description: 'Docker and Kubernetes container metrics', href: '/monitoring/containers', icon: Box, keywords: ['docker', 'kubernetes', 'k8s', 'pods']},
  {label: 'Monitoring – Processes', description: 'Running process explorer', href: '/monitoring/processes', icon: Terminal, keywords: ['processes', 'pid', 'cpu', 'top']},
  {label: 'Monitoring – Network', description: 'Network connections and traffic', href: '/monitoring/network', icon: Network, keywords: ['network', 'connections', 'traffic', 'tcp', 'udp']},
  {label: 'Monitoring – Events', description: 'Infrastructure events and service checks', href: '/monitoring/events', icon: CalendarClock, keywords: ['events', 'service checks', 'alerts', 'datadog']},
  {label: 'Monitoring – Kubernetes', description: 'Kubernetes clusters, pods and workloads', href: '/monitoring/kubernetes', icon: Ship, keywords: ['kubernetes', 'k8s', 'pods', 'clusters', 'workloads']},
  {label: 'Monitoring – Databases', description: 'Database performance and query monitoring', href: '/monitoring/databases', icon: Database, keywords: ['databases', 'sql', 'queries', 'db', 'postgres', 'mysql']},
  {label: 'Monitoring – Debugger', description: 'Live code debugging and profiling', href: '/monitoring/debugger', icon: Bug, keywords: ['debugger', 'debug', 'live debugging', 'breakpoints']},
  {label: 'Monitoring – Network Devices', description: 'Network device monitoring (SNMP)', href: '/monitoring/network-devices', icon: Router, keywords: ['network devices', 'snmp', 'routers', 'switches']},
  {label: 'Monitoring – SBOM', description: 'Software bill of materials and vulnerability scanning', href: '/monitoring/sbom', icon: Package, keywords: ['sbom', 'vulnerabilities', 'dependencies', 'security', 'cve']},
  {label: 'Uptime', description: 'Uptime monitors and checks', href: '/uptime', icon: Activity, keywords: ['monitors', 'uptime monitors', 'checks']},
  {label: 'Status Pages', description: 'Public status pages', href: '/status-pages', icon: Globe, keywords: ['statuspage']},
  {label: 'Replays', description: 'Session replay recordings', href: '/replays', icon: Play, keywords: ['session replay']},
  {label: 'Feedback', description: 'User feedback and surveys', href: '/feedback', icon: MessageSquare, keywords: ['user feedback', 'userfeedback']},
  {label: 'Releases', description: 'Deployments and releases', href: '/releases', icon: Package, keywords: ['deployments']},
  {label: 'Analytics', description: 'Usage analytics and funnels', href: '/analytics', icon: BarChart3, keywords: ['metrics']},
  {label: 'AI', description: 'AI-powered insights', href: '/ai', icon: Brain, keywords: []},
  {label: 'On-Call', description: 'Incidents and on-call scheduling', href: '/on-call', icon: Bell, keywords: ['incidents', 'pager']},
  {label: 'Admin', description: 'Organization settings and billing', href: '/admin', icon: Shield, keywords: []},
  {label: 'Settings', description: 'Account and preferences', href: '/settings', icon: Settings, keywords: ['billing', 'account']},
]

const SETTINGS_ITEMS: Array<{
  label: string
  description: string
  href: string
  tab: string
  icon: React.ComponentType<{className?: string}>
  keywords?: string[]
}> = [
  {
    label: 'API Keys',
    description: 'Auth tokens and log API keys',
    href: '/settings?tab=api-keys',
    tab: 'api-keys',
    icon: Shield,
    keywords: ['api', 'tokens', 'keys', 'authentication', 'logs', 'otlp', 'ingestion'],
  },
  {
    label: 'General Settings',
    description: 'Display and sidebar preferences',
    href: '/settings?tab=general',
    tab: 'general',
    icon: Settings,
    keywords: ['preferences', 'display', 'sidebar', 'navigation'],
  },
  {
    label: 'Integrations',
    description: 'Connect Slack, Discord, and more',
    href: '/settings?tab=integrations',
    tab: 'integrations',
    icon: Globe,
    keywords: ['slack', 'discord', 'webhooks', 'connect'],
  },
  {
    label: 'Notifications',
    description: 'Configure alert notifications',
    href: '/settings?tab=notifications',
    tab: 'notifications',
    icon: Bell,
    keywords: ['alerts', 'email', 'channels'],
  },
  {
    label: 'Silence Periods',
    description: 'Manage alert silence schedules',
    href: '/settings?tab=silence',
    tab: 'silence',
    icon: Bell,
    keywords: ['mute', 'quiet', 'schedule'],
  },
  {
    label: 'Team',
    description: 'Manage team members and roles',
    href: '/settings?tab=team',
    tab: 'team',
    icon: Settings,
    keywords: ['users', 'members', 'permissions', 'roles'],
  },
  {
    label: 'RBAC',
    description: 'Configure custom access roles',
    href: '/settings?tab=rbac',
    tab: 'rbac',
    icon: ShieldCheck,
    keywords: ['permissions', 'roles', 'access control', 'rbac'],
  },
  {
    label: 'Billing',
    description: 'Plans, payments, and invoices',
    href: '/settings?tab=billing',
    tab: 'billing',
    icon: Settings,
    keywords: ['subscription', 'payment', 'invoice', 'plan', 'pricing'],
  },
  {
    label: 'Usage',
    description: 'View usage metrics and limits',
    href: '/settings?tab=usage',
    tab: 'usage',
    icon: BarChart3,
    keywords: ['quota', 'limits', 'metrics'],
  },
  {
    label: 'SSO',
    description: 'Single sign-on configuration',
    href: '/settings?tab=sso',
    tab: 'sso',
    icon: Shield,
    keywords: ['saml', 'oauth', 'single sign-on'],
  },
  {
    label: 'Account',
    description: 'Delete account',
    href: '/settings?tab=account',
    tab: 'account',
    icon: Settings,
    keywords: ['delete', 'remove'],
  },
]

export function CommandPalette() {
  const palette = useCommandPalette()
  const [localOpen, setLocalOpen] = useState(false)
  const isOpen = palette?.open ?? localOpen
  const setIsOpen = palette?.setOpen ?? setLocalOpen
  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const [localAiMode, setLocalAiMode] = useState(false)
  const navigate = useNavigate()

  const aiPanelMode = palette?.aiPanelMode ?? 'dialog'
  const setAiInput = palette?.setAiInput ?? (() => undefined)
  const handleAiSubmit = palette?.handleAiSubmit ?? (() => Promise.resolve())
  const aiMessages = palette?.aiMessages ?? []

  // Show AI mode in the dialog only when explicitly in dialog mode
  const showAiMode = aiPanelMode === 'dialog' && (localAiMode || (isOpen && aiMessages.length > 0))

  // Cmd+K always opens the palette (search in split/float, dialog in dialog mode)
  useEffect(() => {
    const down = (e: KeyboardEvent) => {
      if (e.key === 'k' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault()
        setIsOpen((prev: boolean) => !prev)
      }
    }
    document.addEventListener('keydown', down)
    return () => document.removeEventListener('keydown', down)
  }, [setIsOpen])

  useEffect(() => {
    const t = setTimeout(() => setDebouncedSearch(search.trim()), 200)
    return () => clearTimeout(t)
  }, [search])

  const handleSearchChange = (value: string) => {
    if (!localAiMode && value.startsWith('/')) {
      setLocalAiMode(true)
      setAiInput(value.slice(1))
      setSearch('')
      return
    }
    setSearch(value)
  }

  const {data: searchResult} = useQuery({
    queryKey: ['search', debouncedSearch],
    queryFn: () => api.search(debouncedSearch),
    enabled: isOpen && !showAiMode && debouncedSearch.length >= 2,
  })

  const {data: features} = useEnterpriseFeatures()

  const visiblePageItems = useMemo(
    () =>
      PAGE_ITEMS.filter((p) => {
        if (p.label === 'On-Call' && !hasEnterpriseModule(features, 'oncall')) return false
        return true
      }),
    [features]
  )

  const filteredPages = useMemo(() => {
    if (!search.trim()) return visiblePageItems
    const q = search.trim().toLowerCase()
    return visiblePageItems.filter(
      (p) =>
        p.label.toLowerCase().includes(q) ||
        p.description.toLowerCase().includes(q) ||
        p.keywords?.some((k) => k.toLowerCase().includes(q) || q.includes(k.toLowerCase()))
    )
  }, [search, visiblePageItems])

  const filteredSettings = useMemo(() => {
    if (!search.trim()) return []
    const q = search.trim().toLowerCase()
    return SETTINGS_ITEMS.filter((s) => {
      if (s.label === 'RBAC' && !hasEnterpriseModule(features, 'advanced_rbac')) return false
      return (
        s.label.toLowerCase().includes(q) ||
        s.description.toLowerCase().includes(q) ||
        s.keywords?.some((k) => k.toLowerCase().includes(q) || q.includes(k.toLowerCase()))
      )
    })
  }, [features, search])

  const handleInputKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter' && search.startsWith('/')) {
      event.preventDefault()
      const prompt = search.slice(1).trim()
      if (prompt) {
        setLocalAiMode(true)
        setSearch('')
        void handleAiSubmit(prompt)
      }
    }
  }

  const handleSelect = (href: string) => {
    setIsOpen(false)
    setSearch('')
    navigate({to: href as '/'})
  }

  return (
    <Dialog
      open={isOpen}
      onOpenChange={(o) => {
        setIsOpen(o)
        if (!o) {
          setSearch('')
          setAiInput('')
          if (!aiMessages.length) setLocalAiMode(false)
        }
      }}
    >
      <DialogContent className={cn('overflow-hidden p-0', showAiMode && 'max-w-3xl')}>
        <Command
          className="[&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:font-medium [&_[cmdk-group-heading]]:text-muted-foreground [&_[cmdk-group]:not([hidden])_~[cmdk-group]]:pt-0 [&_[cmdk-group]]:px-2 [&_[cmdk-input-wrapper]_svg]:h-5 [&_[cmdk-input-wrapper]_svg]:w-5 [&_[cmdk-input]]:h-12 [&_[cmdk-item]]:px-2 [&_[cmdk-item]]:py-3 [&_[cmdk-item]_svg]:h-5 [&_[cmdk-item]_svg]:w-5"
          shouldFilter={false}
          disablePointerSelection
        >
          {!showAiMode && (
            <div className="relative">
              <CommandInput
                className="pr-24"
                placeholder="Search dashboards, services, pages..."
                value={search}
                onValueChange={handleSearchChange}
                onKeyDown={handleInputKeyDown}
              />
              <Button
                type="button"
                variant="ghost"
                onClick={() => {
                  if (aiPanelMode !== 'dialog') {
                    // Switch back to dialog mode so AI appears here
                    palette?.setAiPanelMode('dialog')
                  }
                  setLocalAiMode(true)
                }}
                className="absolute right-3 top-1/2 -translate-y-1/2 flex items-center gap-1.5 rounded-full border border-border/60 bg-muted/50 px-2 py-1 text-xs text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
              >
                <Sparkles className="h-3 w-3" />
                <span>Ask AI</span>
                <kbd className="rounded bg-background/80 px-1 py-0.5 font-mono text-[10px] leading-none border border-border/40">/</kbd>
              </Button>
            </div>
          )}
          {showAiMode ? (
            <AiChatContent
              variant="dialog"
              onClose={() => setIsOpen(false)}
            />
          ) : (
            <CommandList>
              {search.trim() &&
              filteredPages.length === 0 &&
              filteredSettings.length === 0 &&
              !searchResult?.dashboards?.length &&
              !searchResult?.projects?.length && (
                <CommandEmpty>No results found.</CommandEmpty>
              )}
              {filteredPages.length > 0 && (
                <CommandGroup heading="Pages" forceMount>
                  {filteredPages.map((item) => {
                    const Icon = item.icon
                    return (
                      <CommandItem
                        key={`page-${item.label}`}
                        value={item.label}
                        onSelect={() => handleSelect(item.href)}
                      >
                        <Icon className="mr-2 h-4 w-4 shrink-0" />
                        <div className="flex flex-col gap-0.5 min-w-0">
                          <span>{item.label}</span>
                          <span className="text-xs text-muted-foreground">{item.description}</span>
                        </div>
                      </CommandItem>
                    )
                  })}
                </CommandGroup>
              )}
              {filteredSettings.length > 0 && (
                <CommandGroup heading="Settings" forceMount>
                  {filteredSettings.map((item) => {
                    const Icon = item.icon
                    return (
                      <CommandItem
                        key={`settings-${item.tab}`}
                        value={item.label}
                        onSelect={() => handleSelect(item.href)}
                      >
                        <Icon className="mr-2 h-4 w-4 shrink-0" />
                        <div className="flex flex-col gap-0.5 min-w-0">
                          <span>{item.label}</span>
                          <span className="text-xs text-muted-foreground">{item.description}</span>
                        </div>
                      </CommandItem>
                    )
                  })}
                </CommandGroup>
              )}
              {searchResult?.dashboards && searchResult.dashboards.length > 0 && (
                <CommandGroup heading="Dashboards">
                  {searchResult.dashboards.map((d) => (
                    <CommandItem
                      key={d.id}
                      value={`dashboard-${d.id}`}
                      onSelect={() => {
                        setIsOpen(false)
                        setSearch('')
                        navigate({to: '/dashboards/$dashboardId', params: {dashboardId: String(d.id)}})
                      }}
                    >
                      <LayoutDashboard className="mr-2 h-4 w-4" />
                      {d.title}
                    </CommandItem>
                  ))}
                </CommandGroup>
              )}
              {searchResult?.projects && searchResult.projects.length > 0 && (
                <CommandGroup heading="Services">
                  {searchResult.projects.map((p) => (
                    <CommandItem
                      key={p.id}
                      value={`project-${p.id}`}
                      onSelect={() => {
                        setIsOpen(false)
                        setSearch('')
                        navigate({to: '/projects/$projectId', params: {projectId: p.resourceId}})
                      }}
                    >
                      <Folder className="mr-2 h-4 w-4" />
                      {p.name}
                    </CommandItem>
                  ))}
                </CommandGroup>
              )}
            </CommandList>
          )}
        </Command>
      </DialogContent>
    </Dialog>
  )
}
