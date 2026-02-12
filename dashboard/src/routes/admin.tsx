import {createFileRoute, Link, Outlet, redirect, useRouterState} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {
    ArrowLeft,
    BarChart3,
    Bell,
    Building2,
    CreditCard,
    DollarSign,
    LayoutDashboard,
    Mail,
    Server,
    Shield,
    AlertTriangle,
} from 'lucide-react'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/admin')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
    try {
      const user = await api.getCurrentUser()
      if (!user.isAdmin) {
        throw redirect({to: '/'})
      }
    } catch (e) {
      if (e instanceof Error && e.message?.includes('redirect')) throw e
      throw redirect({to: '/'})
    }
  },
  component: AdminLayout,
})

const adminNavSections = [
  {
    label: 'Monitor',
    items: [
      {icon: LayoutDashboard, label: 'Overview', href: '/admin'},
      {icon: BarChart3, label: 'Usage', href: '/admin/usage'},
      {icon: Server, label: 'Infrastructure', href: '/admin/infrastructure'},
      {icon: AlertTriangle, label: 'Incidents', href: '/admin/incidents'},
    ],
  },
  {
    label: 'Business',
    items: [
      {icon: Building2, label: 'Organizations', href: '/admin/organizations'},
      {icon: DollarSign, label: 'Revenue', href: '/admin/revenue'},
      {icon: CreditCard, label: 'Billing', href: '/admin/billing'},
    ],
  },
  {
    label: 'Comms',
    items: [
      {icon: Mail, label: 'Emails', href: '/admin/emails'},
      {icon: Bell, label: 'Notifications', href: '/admin/notifications'},
    ],
  },
]

function AdminLayout() {
  const router = useRouterState()
  const currentPath = router.location.pathname

  return (
    <div className="flex min-h-screen">
      {/* Admin Side Navigation */}
      <aside className="w-52 shrink-0 border-r bg-muted/30 sticky top-0 h-screen flex flex-col overflow-y-auto">
        {/* Header */}
        <div className="px-4 py-4 border-b">
          <div className="flex items-center gap-2.5">
            <div className="rounded-lg bg-primary p-1.5">
              <Shield className="h-4 w-4 text-primary-foreground" />
            </div>
            <div>
              <h1 className="text-sm font-semibold leading-none">Admin</h1>
              <p className="text-[11px] text-muted-foreground mt-0.5">Platform Management</p>
            </div>
          </div>
        </div>

        {/* Navigation */}
        <nav className="flex-1 px-2 py-3 space-y-4">
          {adminNavSections.map((section) => (
            <div key={section.label}>
              <p className="px-3 mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/70">
                {section.label}
              </p>
              <div className="space-y-0.5">
                {section.items.map((item) => {
                  const Icon = item.icon
                  const isActive =
                    item.href === '/admin'
                      ? currentPath === '/admin' || currentPath === '/admin/'
                      : currentPath.startsWith(item.href)
                  return (
                    <Link
                      key={item.href}
                      to={item.href}
                      className={cn(
                        'flex items-center gap-3 px-3 py-2 rounded-lg text-[13px] font-medium transition-all',
                        isActive
                          ? 'bg-primary text-primary-foreground shadow-sm'
                          : 'text-muted-foreground hover:text-foreground hover:bg-accent'
                      )}
                    >
                      <Icon className="h-4 w-4 shrink-0" />
                      {item.label}
                    </Link>
                  )
                })}
              </div>
            </div>
          ))}
        </nav>

        {/* Back to Dashboard */}
        <div className="px-2 py-3 border-t">
          <Link
            to="/"
            className="flex items-center gap-3 px-3 py-2 rounded-lg text-[13px] text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
          >
            <ArrowLeft className="h-4 w-4 shrink-0" />
            Back to Dashboard
          </Link>
        </div>
      </aside>

      {/* Content */}
      <main className="flex-1 min-w-0">
        <div className="p-6 lg:p-8">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
