// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import {createFileRoute, useNavigate} from '@tanstack/react-router'
import {useEffect, useState} from 'react'
import {Logo} from '@/components/logo'

export const Route = createFileRoute('/auth/oauth/callback')({
  component: OAuthCallbackPage,
})

function OAuthCallbackPage() {
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const searchParams = new URLSearchParams(window.location.search)
    const errorParam = searchParams.get('error')
    const message = searchParams.get('message')

    if (errorParam) {
      setError(message || 'Authentication failed. Please try again.')
      setTimeout(() => {
        navigate({ to: '/login' })
      }, 3000)
      return
    }

    // Auth token is now set as httpOnly cookie by the backend redirect
    sessionStorage.setItem('authenticated', 'true')
    setTimeout(() => {
      navigate({ to: '/' })
    }, 500)
  }, [navigate])

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
