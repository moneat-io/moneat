import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Logo } from '@/components/logo'
import { api } from '@/lib/api'

export const Route = createFileRoute('/verify-email')({
  component: VerifyEmail,
})

function VerifyEmail() {
  const navigate = useNavigate()
  const { token } = Route.useSearch() as { token?: string }
  const [status, setStatus] = useState<'loading' | 'success' | 'error'>('loading')
  const [message, setMessage] = useState('')

  useEffect(() => {
    const verifyEmail = async () => {
      if (!token) {
        setStatus('error')
        setMessage('No verification token provided')
        return
      }

      try {
        await api.verifyEmail(token)
        setStatus('success')
        setMessage('Email verified successfully! Redirecting to login...')
        
        setTimeout(() => {
          navigate({ to: '/login' })
        }, 2000)
      } catch (error: any) {
        setStatus('error')
        setMessage(error.response?.data?.error || 'Failed to verify email')
      }
    }

    verifyEmail()
  }, [token, navigate])

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <Card className="w-full max-w-md">
        <CardHeader className="text-center space-y-4">
          <div className="flex justify-center">
            <Logo className="h-10" />
          </div>
          <CardTitle>Email Verification</CardTitle>
          <CardDescription>
            {status === 'loading' && 'Verifying your email...'}
            {status === 'success' && 'Verification successful'}
            {status === 'error' && 'Verification failed'}
          </CardDescription>
        </CardHeader>
        <CardContent>
          {status === 'loading' && (
            <div className="flex justify-center">
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-foreground"></div>
            </div>
          )}
          {status === 'success' && (
            <div className="text-center">
              <div className="text-green-600 mb-2">✓</div>
              <p className="text-sm text-muted-foreground">{message}</p>
            </div>
          )}
          {status === 'error' && (
            <div className="text-center">
              <div className="text-destructive mb-2">✗</div>
              <p className="text-sm text-muted-foreground">{message}</p>
              <button
                onClick={() => navigate({ to: '/login' })}
                className="mt-4 text-sm text-primary hover:underline"
              >
                Go to login
              </button>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
