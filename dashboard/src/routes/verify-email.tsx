import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
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
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900 px-4">
      <Card className="w-full max-w-md">
        <CardHeader>
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
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-gray-900 dark:border-gray-100"></div>
            </div>
          )}
          {status === 'success' && (
            <div className="text-center">
              <div className="text-green-600 dark:text-green-400 mb-2">✓</div>
              <p className="text-sm text-gray-600 dark:text-gray-400">{message}</p>
            </div>
          )}
          {status === 'error' && (
            <div className="text-center">
              <div className="text-red-600 dark:text-red-400 mb-2">✗</div>
              <p className="text-sm text-gray-600 dark:text-gray-400">{message}</p>
              <button
                onClick={() => navigate({ to: '/login' })}
                className="mt-4 text-sm text-blue-600 hover:underline"
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
