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
import {useState, useEffect} from 'react'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Logo} from '@/components/Logo'
import {api} from '@/lib/api'
import {Helmet} from 'react-helmet-async'

export const Route = createFileRoute('/verify-email-required')({
  component: VerifyEmailRequired,
})

function VerifyEmailRequired() {
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [resending, setResending] = useState(false)
  const [resendMessage, setResendMessage] = useState('')
  const [checking, setChecking] = useState(false)

  useEffect(() => {
    // Get user's email from JWT or API
    async function loadUserEmail() {
      try {
        const user = await api.getCurrentUser()
        setEmail(user.email)
        
        // If they're verified, redirect to home
        if (user.emailVerified) {
          navigate({ to: '/' })
        }
      } catch (error) {
        console.error('Failed to load user:', error)
        // If can't get user, redirect to login
        navigate({ to: '/login' })
      }
    }
    
    loadUserEmail()
  }, [navigate])

  const handleResendEmail = async () => {
    if (!email) return
    
    setResending(true)
    setResendMessage('')

    try {
      await api.resendVerificationEmail(email)
      setResendMessage('Verification email sent! Please check your inbox.')
    } catch {
      setResendMessage('Failed to resend email. Please try again later.')
    } finally {
      setResending(false)
    }
  }

  const handleCheckStatus = async () => {
    setChecking(true)
    try {
      const user = await api.getCurrentUser()
      if (user.emailVerified) {
        navigate({ to: '/' })
      } else {
        setResendMessage('Email not verified yet. Please check your inbox and click the verification link.')
      }
    } catch (error) {
      console.error('Failed to check status:', error)
    } finally {
      setChecking(false)
    }
  }

  const handleLogout = async () => {
    await api.logout()
    navigate({ to: '/login' })
  }

  return (
    <div>
      <Helmet>
        <title>Verify Your Email | Moneat</title>
        <meta name="robots" content="noindex" />
      </Helmet>
      <div className="flex min-h-screen items-center justify-center px-4 bg-background">
        <Card className="w-full max-w-md">
          <CardHeader className="text-center space-y-4">
            <div className="flex justify-center">
              <Logo className="h-10" />
            </div>
            <CardTitle>Verify your email</CardTitle>
            <CardDescription>
              We sent a verification link to <strong>{email}</strong>
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="text-sm text-muted-foreground text-center">
              Please check your email and click the verification link to continue. You must verify your email before you can use Moneat.
            </div>

            {resendMessage && (
              <div className={`text-sm text-center ${resendMessage.includes('sent') ? 'text-green-600' : 'text-destructive'}`}>
                {resendMessage}
              </div>
            )}

            <div className="space-y-2">
              <Button 
                onClick={handleCheckStatus} 
                disabled={checking}
                className="w-full"
                variant="default"
              >
                {checking ? 'Checking...' : 'I\'ve verified my email'}
              </Button>

              <Button
                onClick={handleResendEmail}
                disabled={resending || !email}
                className="w-full"
                variant="outline"
              >
                {resending ? 'Sending...' : 'Resend verification email'}
              </Button>

              <Button
                onClick={handleLogout}
                className="w-full"
                variant="ghost"
              >
                Sign out
              </Button>
            </div>

            <div className="text-xs text-muted-foreground text-center pt-4">
              Make sure to check your spam folder if you don't see the email.
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
