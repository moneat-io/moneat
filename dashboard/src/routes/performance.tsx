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
import {cn} from '@/lib/utils'
import {Layers, Server} from 'lucide-react'

export const Route = createFileRoute('/performance')({
  beforeLoad: async ({ location }) => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login', search: { redirect: location.href } })
    }
  },
  component: PerformanceLayout,
})

const tabs = [
  { id: 'traces', label: 'Traces', href: '/performance/traces', icon: Layers },
  { id: 'service-map', label: 'Service Map', href: '/performance/service-map', icon: Server },
] as const

function PerformanceLayout() {
  const router = useRouterState()
  const currentPath = router.location.pathname
  const visibleTabs = tabs

  const activeTab = visibleTabs.find(
    (t) => currentPath.startsWith(t.href),
  ) ?? visibleTabs[0]

  return (
    <div>
      <div className="border-b px-4 sm:px-6 lg:px-8">
        <nav className="flex gap-4 -mb-px" aria-label="Performance tabs">
          {visibleTabs.map((tab) => {
            const Icon = tab.icon
            const isActive = tab.id === activeTab?.id
            return (
              <Link
                key={tab.id}
                to={tab.href}
                className={cn(
                  'flex items-center gap-1.5 border-b-2 px-1 py-3 text-sm font-medium transition-colors',
                  isActive
                    ? 'border-primary text-foreground'
                    : 'border-transparent text-muted-foreground hover:border-muted-foreground/30 hover:text-foreground',
                )}
              >
                <Icon className="h-4 w-4" />
                {tab.label}
              </Link>
            )
          })}
        </nav>
      </div>
      <Outlet />
    </div>
  )
}
