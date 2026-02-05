import { createRootRoute, Outlet } from '@tanstack/react-router'
import { ThemeToggle } from '../components/theme-toggle'

export const Route = createRootRoute({
  component: () => (
    <div className="min-h-screen bg-background">
      <div className="fixed top-4 right-4 z-50">
        <ThemeToggle />
      </div>
      <Outlet />
    </div>
  ),
})
