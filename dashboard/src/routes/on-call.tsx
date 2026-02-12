import {createFileRoute, Link, Outlet, useRouterState} from '@tanstack/react-router'
import {Bell, Calendar, ListChecks, AlertTriangle} from 'lucide-react'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/on-call')({
  component: OnCallLayout,
})

const tabs = [
  {id: 'overview', label: 'Overview', href: '/on-call', icon: Bell},
  {id: 'schedules', label: 'Schedules', href: '/on-call/schedules', icon: Calendar},
  {id: 'escalation-policies', label: 'Escalation Policies', href: '/on-call/escalation-policies', icon: ListChecks},
  {id: 'incidents', label: 'Incidents', href: '/on-call/incidents', icon: AlertTriangle},
]

function OnCallLayout() {
  const router = useRouterState()
  const currentPath = router.location.pathname

  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-3xl font-bold mb-2">On-Call Management</h1>
        <p className="text-muted-foreground">
          Manage on-call schedules, escalation policies, and incidents
        </p>
      </div>

      {/* Tab Navigation */}
      <div className="border-b">
        <nav className="flex gap-4">
          {tabs.map((tab) => {
            const isActive = tab.href === '/on-call'
              ? currentPath === '/on-call'
              : currentPath.startsWith(tab.href)
            const Icon = tab.icon
            
            return (
              <Link
                key={tab.id}
                to={tab.href}
                className={cn(
                  'flex items-center gap-2 px-4 py-3 border-b-2 transition-colors font-medium text-sm',
                  isActive
                    ? 'border-primary text-primary'
                    : 'border-transparent text-muted-foreground hover:text-foreground hover:border-muted'
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
