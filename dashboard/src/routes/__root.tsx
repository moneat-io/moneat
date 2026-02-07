import { createRootRoute, Outlet, useRouterState } from '@tanstack/react-router'
import { Sidebar } from '../components/sidebar'
import { Toaster } from '../components/ui/toaster'
import { api } from '../lib/api'

export const Route = createRootRoute({
  component: RootComponent,
})

function RootComponent() {
  const router = useRouterState()
  const currentPath = router.location.pathname
  
  // Don't show sidebar on auth pages, landing page (when logged out), or admin (has its own layout)
  const isAuthPage = ['/login', '/signup', '/verify-email', '/forgot-password', '/reset-password'].includes(currentPath)
  const isLandingPage = currentPath === '/' && !api.isAuthenticated()
  const isAdminRoute = currentPath.startsWith('/admin')
  const showSidebar = api.isAuthenticated() && !isAuthPage && !isLandingPage && !isAdminRoute

  return (
    <div className="min-h-screen bg-background">
      {showSidebar && <Sidebar />}
      <div className={showSidebar ? 'ml-16' : ''}>
        <Outlet />
      </div>
      <Toaster />
    </div>
  )
}
