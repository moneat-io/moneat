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
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {AuthAlert, AuthShell} from '@/components/auth/AuthShell'
import {authPrimaryButtonClass, authSecondaryButtonClass} from '@/components/auth/authStyles'
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
    <>
      <Helmet>
        <title>Verify Your Email | Moneat</title>
        <meta name="robots" content="noindex" />
      </Helmet>
      <AuthShell
        kicker="One more step"
        heading="Verify your email"
        subheading={
          email ? (
            <>
              We sent a verification link to <span className="font-medium text-slate-200">{email}</span>. Confirm it to
              start using Moneat.
            </>
          ) : (
            'Confirm your email address to start using Moneat.'
          )
        }
      >
        <div className="grid gap-5">
          {resendMessage && (
            <AuthAlert tone={resendMessage.includes('sent') ? 'success' : 'danger'}>{resendMessage}</AuthAlert>
          )}

          <div className="grid gap-2.5">
            <Button className={authPrimaryButtonClass} onClick={handleCheckStatus} disabled={checking}>
              {checking ? 'Checking…' : "I've verified my email"}
            </Button>
            <Button
              variant="outline"
              className={authSecondaryButtonClass}
              onClick={handleResendEmail}
              disabled={resending || !email}
            >
              {resending ? 'Sending…' : 'Resend verification email'}
            </Button>
            <Button
              variant="ghost"
              className="h-11 w-full text-slate-400 hover:bg-white/[0.05] hover:text-white"
              onClick={handleLogout}
            >
              Sign out
            </Button>
          </div>

          <p className="text-center text-xs text-slate-500">
            Don&apos;t see it? Check your spam folder — delivery can take a minute.
          </p>
        </div>
      </AuthShell>
    </>
  )
}
