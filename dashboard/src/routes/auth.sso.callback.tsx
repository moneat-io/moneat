// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { useEffect, useMemo } from 'react'
import { Logo } from '@/components/logo'

export const Route = createFileRoute('/auth/sso/callback')({
  component: SsoCallbackPage,
})

function SsoCallbackPage() {
  const navigate = useNavigate()
  
  // Parse error from URL params once, not in effect
  const error = useMemo(() => {
    const params = new URLSearchParams(window.location.search)
    const errorParam = params.get('error')
    const errorMessage = params.get('message')
    return errorParam ? (errorMessage || 'SSO authentication failed. Please try again.') : null
  }, [])

  useEffect(() => {
    if (error) {
      return
    }

    // Auth token is now set as httpOnly cookie by the backend redirect
    sessionStorage.setItem('authenticated', 'true')
    const timer = setTimeout(() => {
      navigate({ to: '/' })
    }, 100)
    return () => clearTimeout(timer)
  }, [navigate, error])

  if (error) {
    return (
      <div className="flex items-center justify-center px-4">
        <div className="max-w-md w-full text-center">
          <Logo className="h-10 mx-auto mb-8" />
          <div className="bg-destructive/10 border border-destructive/20 rounded-lg p-6 mb-6">
            <h2 className="text-lg font-semibold text-destructive mb-2">
              SSO Authentication Failed
            </h2>
            <p className="text-sm text-muted-foreground">{error}</p>
          </div>
          <button
            onClick={() => navigate({ to: '/login' })}
            className="text-sm text-primary hover:underline"
          >
            Return to login
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background">
      <div className="text-center">
        <Logo className="h-12 mx-auto mb-6" />
        <h1 className="text-xl font-semibold mb-2">Completing SSO sign in...</h1>
        <div className="flex items-center justify-center gap-2 text-muted-foreground">
          <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
          <span>Please wait</span>
        </div>
      </div>
    </div>
  )
}
