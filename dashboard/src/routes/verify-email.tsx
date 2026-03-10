// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {createFileRoute, useNavigate} from '@tanstack/react-router'
import {useEffect, useState} from 'react'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Logo} from '@/components/Logo'
import {api} from '@/lib/api'

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
      } catch (error: unknown) {
        setStatus('error')
        const err = error as { response?: { data?: { error?: string } } }
        setMessage(err.response?.data?.error || 'Failed to verify email')
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
