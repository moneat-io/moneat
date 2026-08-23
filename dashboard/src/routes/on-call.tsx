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
import {Bell, Calendar, ListChecks, AlertTriangle, Settings2, Shield, Users2} from 'lucide-react'
import {cn} from '@/lib/utils'
import {PageHeader} from '@/components/ui/page-header'
import {useNativeIncidentRollout} from '@/hooks/useNativeIncidentRollout'

export const Route = createFileRoute('/on-call')({
  component: OnCallLayout,
})

const tabs = [
  {id: 'overview', label: 'Overview', href: '/on-call', icon: Bell},
  {id: 'teams', label: 'Teams', href: '/on-call/teams', icon: Users2},
  {id: 'schedules', label: 'Schedules', href: '/on-call/schedules', icon: Calendar},
  {id: 'escalation-policies', label: 'Escalation Policies', href: '/on-call/escalation-policies', icon: ListChecks},
  {id: 'alerts', label: 'Alerts', href: '/on-call/alerts', icon: AlertTriangle},
  {id: 'incidents', label: 'Incidents', href: '/on-call/incidents', icon: Shield},
  {
    id: 'incident-configuration',
    label: 'Configuration',
    href: '/on-call/incident-configuration',
    icon: Settings2,
  },
]

// Tabs that expose native incident response and stay hidden unless the rollout
// is enabled for the organization.
const NATIVE_INCIDENT_TAB_IDS = new Set(['incidents', 'incident-configuration'])

function OnCallLayout() {
  const router = useRouterState()
  const currentPath = router.location.pathname
  const nativeIncidentRollout = useNativeIncidentRollout()
  const visibleTabs = tabs.filter((tab) =>
    NATIVE_INCIDENT_TAB_IDS.has(tab.id) ? nativeIncidentRollout.enabled : true
  )

  return (
    <div className="px-6 py-4 space-y-4">
      <PageHeader
        icon={Shield}
        title="On-call management"
        description="Manage schedules, escalation policies, alerts, and incidents"
      />

      {/* Tab Navigation */}
      <div className="border-b">
        <nav className="flex gap-1 overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
          {visibleTabs.map((tab) => {
            const isActive = tab.href === '/on-call'
              ? currentPath === '/on-call'
              : currentPath.startsWith(tab.href)
            const Icon = tab.icon

            return (
              <Link
                key={tab.id}
                to={tab.href}
                className={cn(
                  'flex items-center gap-1.5 px-3 py-2 border-b-2 transition-colors font-medium text-sm -mb-px whitespace-nowrap',
                  isActive
                    ? 'border-primary text-foreground'
                    : 'border-transparent text-muted-foreground hover:text-foreground'
                )}
              >
                <Icon className="h-3.5 w-3.5" />
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
