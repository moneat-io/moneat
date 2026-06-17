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

import {createFileRoute, Link, Outlet, useRouterState} from '@tanstack/react-router'
import {AlertTriangle, ListFilter, PackageSearch, ShieldAlert, ShieldCheck} from 'lucide-react'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/security')({
  component: SecurityLayout,
})

const tabs = [
  {id: 'signals', label: 'Signals', href: '/security/signals', icon: AlertTriangle},
  {id: 'vulnerabilities', label: 'Vulnerabilities', href: '/security/vulnerabilities', icon: PackageSearch},
  {id: 'detections', label: 'Detections', href: '/security/detections', icon: ListFilter},
  {id: 'events', label: 'Security Events', href: '/security/events', icon: ShieldAlert},
  {id: 'compliance', label: 'Compliance', href: '/security/compliance', icon: ShieldCheck},
]

function SecurityLayout() {
  const router = useRouterState()
  const currentPath = router.location.pathname

  return (
    <div className="space-y-2">
      <div className="space-y-3 px-6 py-4">
      <div className="flex items-center gap-3">
        <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-danger-bg">
          <ShieldAlert className="h-4 w-4 text-danger-fg" />
        </span>
        <div className="min-w-0">
          <h1 className="text-xl font-semibold tracking-tight">Security</h1>
          <p className="text-sm text-muted-foreground">Runtime security events and compliance</p>
        </div>
      </div>
      <div className="border-b">
        <nav className="flex gap-1">
          {tabs.map((tab) => {
            const isActive = currentPath.startsWith(tab.href)
            const Icon = tab.icon
            return (
              <Link key={tab.id} to={tab.href}
                className={cn(
                  'flex items-center gap-1.5 px-3 py-2 border-b-2 transition-colors font-medium text-sm -mb-px whitespace-nowrap',
                  isActive
                    ? 'border-primary text-foreground'
                    : 'border-transparent text-muted-foreground hover:text-foreground'
                )}>
                <Icon className="h-3.5 w-3.5" />
                {tab.label}
              </Link>
            )
          })}
        </nav>
      </div>
      <Outlet />
    </div>
    </div>
  )
}
