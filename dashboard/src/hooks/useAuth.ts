import { useState, useEffect } from 'react'
import { api } from '@/lib/api'

interface User {
  id: number
  email: string
  name?: string
  emailVerified: boolean
  onboardingCompleted: boolean
  orgId?: number
  orgRole?: string
}

export function useAuth() {
  const [user, setUser] = useState<User | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    // Check authentication by calling the backend (token is in httpOnly cookie)
    api.getCurrentUser()
      .then((userData) => {
        setUser({
          id: userData.id,
          email: userData.email,
          name: userData.name,
          emailVerified: userData.emailVerified,
          onboardingCompleted: userData.onboardingCompleted,
        })
        // Keep session flag in sync
        sessionStorage.setItem('authenticated', 'true')
      })
      .catch(() => {
        setUser(null)
        sessionStorage.removeItem('authenticated')
      })
      .finally(() => {
        setIsLoading(false)
      })
  }, [])

  return { user, isLoading }
}
