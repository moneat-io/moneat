import {createFileRoute, Link, Outlet, useRouterState} from '@tanstack/react-router'
import {Bell, Calendar, ListChecks, AlertTriangle, Shield} from 'lucide-react'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/on-call')({
  component: OnCallLayout,
})

const tabs = [
  {id: 'overview', label: 'Overview', href: '/on-call', icon: Bell, color: 'text-blue-500'},
  {id: 'schedules', label: 'Schedules', href: '/on-call/schedules', icon: Calendar, color: 'text-violet-500'},
  {id: 'escalation-policies', label: 'Escalation Policies', href: '/on-call/escalation-policies', icon: ListChecks, color: 'text-amber-500'},
  {id: 'alerts', label: 'Alerts', href: '/on-call/incidents', icon: AlertTriangle, color: 'text-red-500'},
  {id: 'incidents', label: 'Incidents', href: '/on-call/declared-incidents', icon: Shield, color: 'text-orange-500'},
]

function OnCallLayout() {
  const router = useRouterState()
  const currentPath = router.location.pathname

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center gap-3">
        <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-gradient-to-br from-blue-500 to-violet-600 shadow-lg shadow-blue-500/20">
          <Shield className="h-5 w-5 text-white" />
        </div>
        <div>
          <h1 className="text-3xl font-bold">On-Call Management</h1>
          <p className="text-muted-foreground text-sm">
            Manage schedules, escalation policies, and incidents
          </p>
        </div>
      </div>

      {/* Tab Navigation */}
      <div className="border-b">
        <nav className="flex gap-1">
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
                  'flex items-center gap-2 px-4 py-3 border-b-2 transition-all font-medium text-sm rounded-t-md',
                  isActive
                    ? `border-current ${tab.color} bg-current/5`
                    : 'border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/50'
                )}
              >
                <Icon className={cn('h-4 w-4', isActive && tab.color)} />
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
