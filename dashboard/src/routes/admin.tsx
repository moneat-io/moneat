import { createFileRoute, redirect, Link, Outlet, useRouterState } from '@tanstack/react-router'
import { api } from '@/lib/api'
import { LayoutDashboard, Building2, BarChart3, DollarSign, Server, ArrowLeft } from 'lucide-react'

export const Route = createFileRoute('/admin')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
    try {
      const user = await api.getCurrentUser()
      if (!user.isAdmin) {
        throw redirect({ to: '/' })
      }
    } catch (e) {
      if (e instanceof Error && e.message?.includes('redirect')) throw e
      throw redirect({ to: '/' })
    }
  },
  component: AdminLayout,
})

const adminNavItems = [
  { icon: LayoutDashboard, label: 'Overview', href: '/admin' },
  { icon: Building2, label: 'Organizations', href: '/admin/organizations' },
  { icon: BarChart3, label: 'Usage', href: '/admin/usage' },
  { icon: DollarSign, label: 'Revenue', href: '/admin/revenue' },
  { icon: Server, label: 'Infrastructure', href: '/admin/infrastructure' },
]

function AdminLayout() {
  const router = useRouterState()
  const currentPath = router.location.pathname

  return (
    <div className="min-h-screen bg-background">
      <div className="border-b">
        <div className="p-4 flex items-center justify-between max-w-7xl mx-auto">
          <div className="flex items-center gap-6">
            <Link
              to="/"
              className="text-sm text-muted-foreground hover:text-foreground flex items-center gap-1"
            >
              <ArrowLeft className="h-4 w-4" />
              Back to app
            </Link>
            <h1 className="text-xl font-semibold">Admin Dashboard</h1>
          </div>
        </div>
        <nav className="flex gap-1 px-4 pb-0 max-w-7xl mx-auto">
          {adminNavItems.map((item) => {
            const Icon = item.icon
            const isActive = currentPath === item.href || (item.href !== '/admin' && currentPath.startsWith(item.href))
            return (
              <Link
                key={item.href}
                to={item.href}
                className={`flex items-center gap-2 px-4 py-3 text-sm font-medium border-b-2 transition-colors ${
                  isActive
                    ? 'border-primary text-foreground'
                    : 'border-transparent text-muted-foreground hover:text-foreground'
                }`}
              >
                <Icon className="h-4 w-4" />
                {item.label}
              </Link>
            )
          })}
        </nav>
      </div>
      <div className="p-6 max-w-7xl mx-auto">
        <Outlet />
      </div>
    </div>
  )
}
