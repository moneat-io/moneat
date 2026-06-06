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

import {createFileRoute, Link, Outlet, redirect, useRouterState} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {useEnterpriseFeatures, hasEnterpriseModule} from '@/hooks/useEnterpriseFeatures'
import {
    ArrowLeft,
    Box,
    Bug,
    CalendarClock,
    Database,
    HardDrive,
    HelpCircle,
    Network,
    Package,
    Router,
    Ship,
    Terminal,
    Map as MapIcon,
} from 'lucide-react'
import {cn} from '@/lib/utils'
import {Button} from '@/components/ui/button'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {HostsHeaderActions} from './monitoring.index'
import {DebuggerHeaderActions} from './monitoring.debugger'

export const Route = createFileRoute('/monitoring')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      const hasSession = await api.checkAuth()
      if (!hasSession) throw redirect({to: '/login'})
    }
  },
  component: MonitoringLayout,
})

const TAB_DOCS_URLS: Record<string, string> = {
  hosts: '/docs/datadog-agent/agent-setup',
  map: '/docs/infrastructure-monitoring',
  containers: '/docs/datadog-agent/agent-setup',
  'service-map': '/docs/performance-monitoring#service-map',
  processes: '/docs/datadog-agent/',
  network: '/docs/datadog-agent/',
  events: '/docs/datadog-agent/',
  kubernetes: '/docs/datadog-agent/kubernetes',
  databases: '/docs/datadog-agent/database-monitoring',
  debugger: '/docs/datadog-agent/debugger',
  'network-devices': '/docs/datadog-agent/network-devices',
  sbom: '/docs/datadog-agent/sbom',
}

const allTabs = [
  {id: 'hosts', label: 'Hosts', href: '/monitoring', icon: HardDrive},
  {id: 'map', label: 'Map', href: '/monitoring/map', icon: MapIcon},
  {id: 'containers', label: 'Containers', href: '/monitoring/containers', icon: Box},
  {id: 'processes', label: 'Processes', href: '/monitoring/processes', icon: Terminal, requiresDatadog: true},
  {id: 'network', label: 'Network', href: '/monitoring/network', icon: Network, requiresDatadog: true},
  {id: 'events', label: 'Events', href: '/monitoring/events', icon: CalendarClock, requiresDatadog: true},
  {id: 'kubernetes', label: 'Kubernetes', href: '/monitoring/kubernetes', icon: Ship, requiresDatadog: true},
  {id: 'databases', label: 'Databases', href: '/monitoring/databases', icon: Database, requiresDatadog: true},
  {id: 'debugger', label: 'Debugger', href: '/monitoring/debugger', icon: Bug, requiresDatadog: true},
  {id: 'network-devices', label: 'Network Devices', href: '/monitoring/network-devices', icon: Router, requiresDatadog: true},
  {id: 'sbom', label: 'SBOM', href: '/monitoring/sbom', icon: Package, requiresDatadog: true},
]

const KNOWN_TAB_PATHS = [
  'hosts', 'map', 'containers', 'processes', 'network', 'events',
  'kubernetes', 'databases', 'debugger', 'network-devices', 'service-map', 'sbom',
]

function MonitoringLayout() {
  const router = useRouterState()
  const currentPath = router.location.pathname
  const {data: features} = useEnterpriseFeatures()

  const pathParts = currentPath.replace(/^\/monitoring\/?/, '').split('/').filter(Boolean)
  const isHostDetailPage =
    pathParts.length >= 2 && pathParts[0] === 'hosts' && !KNOWN_TAB_PATHS.includes(pathParts[1])
  const isSystemDetailPage =
    pathParts.length === 1 && !KNOWN_TAB_PATHS.includes(pathParts[0])
  const tabs = allTabs.filter(
    (tab) => !tab.requiresDatadog || hasEnterpriseModule(features, 'datadog')
  )

  if (isHostDetailPage) {
    return <Outlet />
  }
  if (isSystemDetailPage) {
    return (
      <div>
        <div className="border-b bg-card/50">
          <div className="container mx-auto px-4 py-4">
            <Button variant="ghost" size="sm" asChild className="gap-2 text-muted-foreground hover:text-foreground">
              <Link to="/monitoring">
                <ArrowLeft className="h-4 w-4" />
                Back to Monitoring
              </Link>
            </Button>
          </div>
        </div>
        <Outlet />
      </div>
    )
  }

  const isHostsPage = currentPath === '/monitoring' || currentPath === '/monitoring/'
  const activeTabId = tabs.find((t) =>
    t.href === '/monitoring'
      ? currentPath === '/monitoring' || currentPath === '/monitoring/'
      : currentPath.startsWith(t.href)
  )?.id
  const docsUrl = activeTabId ? TAB_DOCS_URLS[activeTabId] : null

  return (
    <div>
      <div className="border-b bg-card/50">
        <div className="container mx-auto px-4 py-2.5">
          <div className="flex flex-col gap-1.5 sm:flex-row sm:items-center sm:gap-2">
            <nav className="flex gap-0.5 min-w-0 overflow-x-auto pb-1 sm:flex-1 sm:pb-0" aria-label="Monitoring tabs">
              {tabs.map((tab) => {
              const isActive =
                tab.href === '/monitoring'
                  ? currentPath === '/monitoring' || currentPath === '/monitoring/'
                  : currentPath.startsWith(tab.href)
              const Icon = tab.icon

              return (
                <Link
                  key={tab.id}
                  to={tab.href}
                  className={cn(
                    'flex items-center gap-1.5 px-2.5 py-2 border-b-2 transition-all font-medium text-xs rounded-t-md whitespace-nowrap',
                    isActive
                      ? 'border-primary text-primary bg-primary/5'
                      : 'border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/50'
                  )}
                >
                  <Icon className="h-3.5 w-3.5 shrink-0" />
                  {tab.label}
                </Link>
              )
            })}
            </nav>
            <div className="flex items-center gap-2 shrink-0">
            {isHostsPage ? (
              <HostsHeaderActions />
            ) : currentPath.startsWith('/monitoring/debugger') ? (
              <DebuggerHeaderActions />
            ) : docsUrl ? (
              <TooltipProvider>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <a href={docsUrl} target="_blank" rel="noreferrer"
                      className="inline-flex items-center justify-center p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-muted/50 transition-colors"
                      aria-label="View docs">
                      <HelpCircle className="h-4 w-4" />
                    </a>
                  </TooltipTrigger>
                  <TooltipContent>
                    <p>View docs</p>
                  </TooltipContent>
                </Tooltip>
              </TooltipProvider>
            ) : null}
            </div>
          </div>
        </div>
      </div>
      <Outlet />
    </div>
  )
}
