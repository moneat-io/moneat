import { createFileRoute, redirect, Link, Outlet, useRouterState } from '@tanstack/react-router'
import { api } from '@/lib/api'
import { LayoutDashboard, Building2, BarChart3, DollarSign, Server, ArrowLeft, Shield } from 'lucide-react'

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
      {/* Header */}
      <div className="sticky top-0 z-30 bg-background/80 backdrop-blur-lg border-b">
        <div className="px-6 flex items-center justify-between max-w-[1400px] mx-auto h-14">
          <div className="flex items-center gap-4">
            <Link
              to="/"
              className="text-sm text-muted-foreground hover:text-foreground flex items-center gap-1.5 transition-colors"
            >
              <ArrowLeft className="h-3.5 w-3.5" />
              Back
            </Link>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-2">
              <div className="rounded-md bg-primary p-1">
                <Shield className="h-3.5 w-3.5 text-primary-foreground" />
              </div>
              <h1 className="text-base font-semibold">Admin</h1>
            </div>
          </div>
        </div>
        <nav className="flex gap-0.5 px-6 max-w-[1400px] mx-auto -mb-px">
          {adminNavItems.map((item) => {
            const Icon = item.icon
            const isActive =
              currentPath === item.href ||
              (item.href !== '/admin' && currentPath.startsWith(item.href))
            return (
              <Link
                key={item.href}
                to={item.href}
                className={`flex items-center gap-2 px-3 py-2.5 text-sm font-medium border-b-2 transition-colors rounded-t-md ${
                  isActive
                    ? 'border-primary text-foreground bg-muted/50'
                    : 'border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/30'
                }`}
              >
                <Icon className="h-4 w-4" />
                {item.label}
              </Link>
            )
          })}
        </nav>
      </div>

      {/* Content */}
      <div className="px-6 py-6 max-w-[1400px] mx-auto">
        <Outlet />
      </div>
    </div>
  )
}
