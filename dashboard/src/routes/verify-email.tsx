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
import {Loader2} from 'lucide-react'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {AuthAlert, AuthShell} from '@/components/auth/AuthShell'
import {authPrimaryButtonClass} from '@/components/auth/authStyles'
import {Helmet} from 'react-helmet-async'

export const Route = createFileRoute('/verify-email')({
  component: VerifyEmail,
})

const STATUS_COPY = {
  loading: {kicker: 'Email verification', heading: 'Verifying your email', subheading: 'Hang tight while we confirm your link.'},
  success: {kicker: 'Email verification', heading: 'Email verified', subheading: "You're all set."},
  error: {kicker: 'Email verification', heading: 'Verification failed', subheading: "We couldn't verify this link."},
} as const

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
        setMessage('Email verified successfully! Redirecting to sign in…')

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

  const copy = STATUS_COPY[status]

  return (
    <>
      <Helmet>
        <title>Email Verification | Moneat</title>
        <meta name="robots" content="noindex" />
      </Helmet>
      <AuthShell kicker={copy.kicker} heading={copy.heading} subheading={copy.subheading}>
        {status === 'loading' && (
          <div className="flex items-center gap-3 text-sm text-slate-400">
            <Loader2 className="size-4 animate-spin text-indigo-300" />
            Confirming your verification link…
          </div>
        )}
        {status === 'success' && <AuthAlert tone="success">{message}</AuthAlert>}
        {status === 'error' && (
          <div className="grid gap-5">
            <AuthAlert tone="danger">{message}</AuthAlert>
            <Button className={authPrimaryButtonClass} onClick={() => navigate({ to: '/login' })}>
              Go to sign in
            </Button>
          </div>
        )}
      </AuthShell>
    </>
  )
}
