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
import {ShieldAlert, ShieldCheck} from 'lucide-react'
import {cn} from '@/lib/utils'
import {BetaBanner} from '@/components/BetaBanner'

export const Route = createFileRoute('/security')({
  component: SecurityLayout,
})

const tabs = [
  {id: 'events', label: 'Security Events', href: '/security', icon: ShieldAlert},
  {id: 'compliance', label: 'Compliance', href: '/security/compliance', icon: ShieldCheck},
]

function SecurityLayout() {
  const router = useRouterState()
  const currentPath = router.location.pathname

  return (
    <div className="space-y-3">
      <BetaBanner pageKey="security" />
      <div className="p-4 space-y-3">
      <div className="flex items-center gap-2.5">
        <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-gradient-to-br from-red-500 to-orange-600 shrink-0">
          <ShieldAlert className="h-4 w-4 text-white" />
        </div>
        <div className="min-w-0">
          <h2 className="text-xl font-bold">Security</h2>
          <p className="text-muted-foreground text-xs">Runtime security events and compliance</p>
        </div>
      </div>
      <div className="border-b">
        <nav className="flex gap-1">
          {tabs.map((tab) => {
            const isActive = tab.href === '/security'
              ? currentPath === '/security' || currentPath === '/security/'
              : currentPath.startsWith(tab.href)
            const Icon = tab.icon
            return (
              <Link key={tab.id} to={tab.href}
                className={cn(
                  'flex items-center gap-1.5 px-2.5 py-2 border-b-2 transition-all font-medium text-xs rounded-t-md',
                  isActive
                    ? 'border-primary text-primary bg-primary/5'
                    : 'border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/50'
                )}>
                <Icon className="h-3 w-3" />
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
