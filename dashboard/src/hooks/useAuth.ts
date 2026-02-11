import { useState, useEffect } from 'react'

interface User {
  id: number
  email: string
  name?: string
  emailVerified: boolean
  onboardingCompleted: boolean
  orgId?: number
  orgRole?: string
}

function parseJWT(token: string): any {
  try {
    const base64Url = token.split('.')[1]
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/')
    const jsonPayload = decodeURIComponent(
      atob(base64)
        .split('')
        .map(c => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    )
    return JSON.parse(jsonPayload)
  } catch {
    return null
  }
}

export function useAuth() {
  const [user, setUser] = useState<User | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    const token = sessionStorage.getItem('impersonate_token') || localStorage.getItem('auth_token')
    
    if (!token) {
      setUser(null)
      setIsLoading(false)
      return
    }

    const payload = parseJWT(token)
    if (payload) {
      setUser({
        id: payload.userId,
        email: payload.email,
        name: payload.name,
        emailVerified: true, // If they have a token, they're verified
        onboardingCompleted: true,
        orgId: payload.orgId,
        orgRole: payload.orgRole,
      })
    }
    
    setIsLoading(false)
  }, [])

  return { user, isLoading }
}
