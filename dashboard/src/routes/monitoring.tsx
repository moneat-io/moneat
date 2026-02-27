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
    Network,
    Package,
    Router,
    Ship,
    Terminal,
} from 'lucide-react'
import {cn} from '@/lib/utils'
import {Button} from '@/components/ui/button'

export const Route = createFileRoute('/monitoring')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: MonitoringLayout,
})

const allTabs = [
  {id: 'hosts', label: 'Hosts', href: '/monitoring', icon: HardDrive},
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
  'hosts', 'containers', 'processes', 'network', 'events',
  'kubernetes', 'databases', 'debugger', 'network-devices', 'sbom',
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

  if (isHostDetailPage || isSystemDetailPage) {
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

  return (
    <div>
      <div className="border-b bg-card/50">
        <div className="container mx-auto px-4 py-4">
          <nav className="flex gap-1" aria-label="Monitoring tabs">
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
                    'flex items-center gap-2 px-4 py-3 border-b-2 transition-all font-medium text-sm rounded-t-md',
                    isActive
                      ? 'border-primary text-primary bg-primary/5'
                      : 'border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/50'
                  )}
                >
                  <Icon className="h-4 w-4" />
                  {tab.label}
                </Link>
              )
            })}
          </nav>
        </div>
      </div>
      <Outlet />
    </div>
  )
}
