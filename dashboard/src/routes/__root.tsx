import { createRootRoute, Outlet, useRouterState } from '@tanstack/react-router'
import { ThemeToggle } from '../components/theme-toggle'
import { Sidebar } from '../components/sidebar'
import { api } from '../lib/api'

export const Route = createRootRoute({
  component: RootComponent,
})

function RootComponent() {
  const router = useRouterState()
  const currentPath = router.location.pathname
  
  // Don't show sidebar on auth pages
  const isAuthPage = ['/login', '/signup', '/verify-email', '/forgot-password', '/reset-password'].includes(currentPath)
  const showSidebar = api.isAuthenticated() && !isAuthPage

  return (
    <div className="min-h-screen bg-background">
      <div className="fixed top-4 right-4 z-50">
        <ThemeToggle />
      </div>
      {showSidebar && <Sidebar />}
      <div className={showSidebar ? 'ml-16' : ''}>
        <Outlet />
      </div>
    </div>
  )
}
