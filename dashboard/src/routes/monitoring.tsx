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
import {ArrowLeft} from 'lucide-react'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {MonitoringTabBar} from '@/components/monitoring/MonitoringTabBar'

export const Route = createFileRoute('/monitoring')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      const hasSession = await api.checkAuth()
      if (!hasSession) throw redirect({to: '/login'})
    }
  },
  component: MonitoringLayout,
})

const KNOWN_TAB_PATHS = [
  'hosts', 'map', 'containers', 'processes', 'network', 'events',
  'kubernetes', 'databases', 'debugger', 'network-devices', 'service-map', 'sbom',
]

function MonitoringLayout() {
  const router = useRouterState()
  const currentPath = router.location.pathname

  const pathParts = currentPath.replace(/^\/monitoring\/?/, '').split('/').filter(Boolean)
  const isHostDetailPage =
    pathParts.length >= 2 && pathParts[0] === 'hosts' && !KNOWN_TAB_PATHS.includes(pathParts[1])
  const isSystemDetailPage =
    pathParts.length === 1 && !KNOWN_TAB_PATHS.includes(pathParts[0])

  if (isHostDetailPage) {
    return <Outlet />
  }
  if (isSystemDetailPage) {
    return (
      <div>
        <div className="border-b bg-card/50">
          <div className="container mx-auto px-4 py-4">
            <Button variant="ghost" size="sm" asChild className="gap-2 text-muted-foreground hover:text-foreground">
              <Link to="/resources">
                <ArrowLeft className="h-4 w-4" />
                Back to Resources
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
      <MonitoringTabBar />
      <Outlet />
    </div>
  )
}
