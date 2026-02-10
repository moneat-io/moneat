import { createFileRoute, redirect, useNavigate } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import { Logo } from '@/components/logo'

export const Route = createFileRoute('/auth/sso/callback')({
  component: SsoCallbackPage,
})

function SsoCallbackPage() {
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const token = params.get('token')
    const errorParam = params.get('error')
    const errorMessage = params.get('message')

    if (errorParam) {
      setError(errorMessage || 'SSO authentication failed. Please try again.')
      return
    }

    if (token) {
      localStorage.setItem('auth_token', token)
      // Redirect to home page
      navigate({ to: '/' })
    } else {
      setError('Invalid SSO callback. Missing authentication token.')
    }
  }, [navigate])

  if (error) {
    return (
      <div className="min-h-screen bg-background flex items-center justify-center px-4">
        <div className="max-w-md w-full text-center">
          <Logo className="h-10 mx-auto mb-8" />
          <div className="bg-destructive/10 border border-destructive/20 rounded-lg p-6 mb-6">
            <h2 className="text-lg font-semibold text-destructive mb-2">
              SSO Authentication Failed
            </h2>
            <p className="text-sm text-muted-foreground">{error}</p>
          </div>
          <a
            href="/login"
            className="text-sm text-primary hover:underline"
          >
            Return to login
          </a>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-background flex items-center justify-center">
      <div className="text-center">
        <Logo className="h-10 mx-auto mb-6" />
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary mx-auto"></div>
        <p className="mt-4 text-sm text-muted-foreground">Completing SSO login...</p>
      </div>
    </div>
  )
}
