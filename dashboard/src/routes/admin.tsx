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

import {createFileRoute, isRedirect, Link, Outlet, redirect, useRouterState} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {
    AlertTriangle,
    ArrowLeft,
    BarChart3,
    Bell,
    Building2,
    CreditCard,
    DollarSign,
    LayoutDashboard,
    Mail,
    Menu,
    Server,
    Shield,
    Users,
    X,
} from 'lucide-react'
import {cn} from '@/lib/utils'
import {useState} from 'react'

export const Route = createFileRoute('/admin')({
  beforeLoad: async () => {
    try {
      const user = await api.getCurrentUser()
      if (!user.isAdmin) {
        throw redirect({to: '/'})
      }
    } catch (e) {
      if (isRedirect(e)) throw e
      const err = e as Error & { status?: number }
      // Network errors shouldn't kick users to login — surface them instead
      if (err.message === 'NETWORK_ERROR') throw e
      console.error('[Admin] access check failed:', err.message, err.status)
      throw redirect({to: '/login'})
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
      {icon: Users, label: 'Users', href: '/admin/users'},
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
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)

  return (
    <div className="flex min-h-screen">
      {/* Mobile Menu Button */}
      <button
        onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
        className="lg:hidden fixed top-4 left-4 z-50 p-2 rounded-lg bg-background border shadow-lg"
        aria-label="Toggle admin menu"
      >
        {mobileMenuOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
      </button>

      {/* Mobile Overlay */}
      {mobileMenuOpen && (
        <div
          className="lg:hidden fixed inset-0 bg-black/50 z-40"
          onClick={() => setMobileMenuOpen(false)}
        />
      )}

      {/* Admin Side Navigation */}
      <aside className={cn(
        "w-52 shrink-0 border-r h-screen flex flex-col overflow-y-auto",
        "lg:sticky lg:top-0 lg:bg-muted/30",
        "fixed top-0 left-0 z-40 transition-transform duration-300 bg-background",
        mobileMenuOpen ? "translate-x-0" : "-translate-x-full lg:translate-x-0"
      )}>
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
                      onClick={() => setMobileMenuOpen(false)}
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
            onClick={() => setMobileMenuOpen(false)}
            className="flex items-center gap-3 px-3 py-2 rounded-lg text-[13px] text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
          >
            <ArrowLeft className="h-4 w-4 shrink-0" />
            Back to Dashboard
          </Link>
        </div>
      </aside>

      {/* Content */}
      <main className="flex-1 min-w-0">
        <div className="p-6 lg:p-8 pt-16 lg:pt-6">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
