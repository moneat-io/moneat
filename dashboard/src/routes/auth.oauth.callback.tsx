// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import {createFileRoute, useNavigate} from '@tanstack/react-router'
import {useEffect, useMemo} from 'react'
import {Logo} from '@/components/logo'

export const Route = createFileRoute('/auth/oauth/callback')({
  component: OAuthCallbackPage,
})

function OAuthCallbackPage() {
  const navigate = useNavigate()
  
  // Parse error from URL params once, not in effect
  const error = useMemo(() => {
    const searchParams = new URLSearchParams(window.location.search)
    const errorParam = searchParams.get('error')
    const message = searchParams.get('message')
    return errorParam ? (message || 'Authentication failed. Please try again.') : null
  }, [])

  useEffect(() => {
    if (error) {
      const timer = setTimeout(() => {
        navigate({ to: '/login' })
      }, 3000)
      return () => clearTimeout(timer)
    }

    // Auth token is now set as httpOnly cookie by the backend redirect
    sessionStorage.setItem('authenticated', 'true')
    const timer = setTimeout(() => {
      navigate({ to: '/' })
    }, 500)
    return () => clearTimeout(timer)
  }, [navigate, error])

  return (
    <div className="flex min-h-screen items-center justify-center bg-background">
      <div className="text-center">
        <Logo className="h-12 mx-auto mb-6" />
        {error ? (
          <>
            <h1 className="text-xl font-semibold text-destructive mb-2">Authentication Failed</h1>
            <p className="text-muted-foreground mb-4">{error}</p>
            <p className="text-sm text-muted-foreground">Redirecting to login...</p>
          </>
        ) : (
          <>
            <h1 className="text-xl font-semibold mb-2">Completing sign in...</h1>
            <div className="flex items-center justify-center gap-2 text-muted-foreground">
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
              <span>Please wait</span>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
