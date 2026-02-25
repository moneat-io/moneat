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

import {useEffect, useState, useMemo} from 'react'
import {useCommandPalette} from '@/hooks/useCommandPalette'
import {useNavigate} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command'
import {Dialog, DialogContent} from '@/components/ui/dialog'
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
  Settings,
  Folder,
  BarChart3,
} from 'lucide-react'
import {hasEnterpriseModule, useEnterpriseFeatures} from '@/hooks/useEnterpriseFeatures'

const PAGE_ITEMS: Array<{
  label: string
  description: string
  href: string
  icon: React.ComponentType<{className?: string}>
  keywords?: string[]
}> = [
  {label: 'Overview', description: 'Project metrics and key stats', href: '/', icon: Home, keywords: ['home']},
  {label: 'Issues', description: 'Errors and exceptions', href: '/issues', icon: AlertCircle, keywords: ['errors', 'bugs']},
  {label: 'Performance', description: 'Traces and transaction timing', href: '/performance', icon: Timer, keywords: ['traces', 'transactions']},
  {label: 'Logs', description: 'Search and explore log events', href: '/logs', icon: ScrollText, keywords: ['logging']},
  {label: 'Dashboards', description: 'Custom metrics and visualizations', href: '/dashboards', icon: LayoutDashboard, keywords: ['widgets']},
  {label: 'Monitoring', description: 'Infrastructure and system health', href: '/monitoring', icon: Server, keywords: ['infrastructure', 'systems', 'servers']},
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
  const [open, setOpen] = useState(false)
  const isOpen = palette?.open ?? open
  const setIsOpen = palette?.setOpen ?? setOpen
  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const navigate = useNavigate()

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

  const {data: searchResult} = useQuery({
    queryKey: ['search', debouncedSearch],
    queryFn: () => api.search(debouncedSearch),
    enabled: open && debouncedSearch.length >= 2,
  })

  const {data: features} = useEnterpriseFeatures()

  const visiblePageItems = useMemo(
    () =>
      PAGE_ITEMS.filter((p) => {
        if (p.label === 'Analytics' && !hasEnterpriseModule(features, 'analytics')) return false
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
    return SETTINGS_ITEMS.filter(
      (s) =>
        s.label.toLowerCase().includes(q) ||
        s.description.toLowerCase().includes(q) ||
        s.keywords?.some((k) => k.toLowerCase().includes(q) || q.includes(k.toLowerCase()))
    )
  }, [search])

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
        if (!o) setSearch('')
      }}
    >
      <DialogContent className="overflow-hidden p-0 shadow-lg">
        <Command
          className="[&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:font-medium [&_[cmdk-group-heading]]:text-muted-foreground [&_[cmdk-group]:not([hidden])_~[cmdk-group]]:pt-0 [&_[cmdk-group]]:px-2 [&_[cmdk-input-wrapper]_svg]:h-5 [&_[cmdk-input-wrapper]_svg]:w-5 [&_[cmdk-input]]:h-12 [&_[cmdk-item]]:px-2 [&_[cmdk-item]]:py-3 [&_[cmdk-item]_svg]:h-5 [&_[cmdk-item]_svg]:w-5"
          shouldFilter={false}
          disablePointerSelection
        >
          <CommandInput
            placeholder="Search dashboards, projects, pages..."
            value={search}
            onValueChange={setSearch}
          />
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
          <CommandGroup heading="Projects">
            {searchResult.projects.map((p) => (
              <CommandItem
                key={p.id}
                value={`project-${p.id}`}
                onSelect={() => {
                  setIsOpen(false)
                  setSearch('')
                  navigate({to: '/projects/$projectId', params: {projectId: String(p.id)}})
                }}
              >
                <Folder className="mr-2 h-4 w-4" />
                {p.name}
              </CommandItem>
            ))}
          </CommandGroup>
        )}
      </CommandList>
        </Command>
      </DialogContent>
    </Dialog>
  )
}
