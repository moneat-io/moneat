import { createFileRoute, redirect } from '@tanstack/react-router'
import { api } from '@/lib/api'
import { setDemoEpoch } from '@/lib/demo'

export const Route = createFileRoute('/demo')({
  beforeLoad: async () => {
    try {
      // Call demo login endpoint - backend will set httpOnly cookie automatically
      const response = await api.demoLogin()
      
      // Set the demo epoch for time virtualization
      setDemoEpoch(response.demoEpochMs)
      
      // Small delay to ensure cookie is set before redirect
      await new Promise(resolve => setTimeout(resolve, 100))
    } catch (error) {
      console.error('Demo login failed:', error)
      // Redirect to login on failure
      throw redirect({ to: '/login', search: { error: 'demo_failed' } })
    }
    
    // Redirect to the projects page on success (specific authenticated route)
    throw redirect({ to: '/projects' })
  },
  component: () => {
    // This component should never render due to beforeLoad redirect
    return <div>Loading demo...</div>
  },
})
