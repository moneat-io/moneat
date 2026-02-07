import {createFileRoute, Outlet, redirect} from '@tanstack/react-router'
import {api} from '@/lib/api'

export const Route = createFileRoute('/performance')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }

    try {
      const user = await api.getCurrentUser()
      if (!user.onboardingCompleted) {
        throw redirect({ to: '/onboarding' })
      }
    } catch (error) {
      console.error('Failed to fetch user:', error)
    }
  },
  component: PerformanceLayout,
})

function PerformanceLayout() {
  return <Outlet />
}
