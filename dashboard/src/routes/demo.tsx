import { createFileRoute, redirect } from '@tanstack/react-router'
import { api } from '@/lib/api'
import { setDemoEpoch } from '@/lib/demo'

export const Route = createFileRoute('/demo')({
  beforeLoad: async () => {
    try {
      // Call demo login endpoint
      const response = await api.demoLogin()
      
      // Store the demo token
      // Since the backend returns a JWT, we need to set it as a cookie
      document.cookie = `auth_token=${response.token}; path=/; max-age=86400; SameSite=Lax`
      sessionStorage.setItem('authenticated', 'true')
      
      // Set the demo epoch
      setDemoEpoch(response.demoEpochMs)
      
      // Redirect to the dashboard
      throw redirect({ to: '/' })
    } catch (error) {
      console.error('Demo login failed:', error)
      // Redirect to login on failure
      throw redirect({ to: '/login', search: { error: 'demo_failed' } })
    }
  },
  component: () => {
    // This component should never render due to beforeLoad redirect
    return <div>Loading demo...</div>
  },
})
