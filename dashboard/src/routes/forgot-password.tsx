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

import {createFileRoute, Link} from '@tanstack/react-router'
import {useState} from 'react'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {AuthAlert, AuthField, AuthShell} from '@/components/auth/AuthShell'
import {authInputClass, authPrimaryButtonClass} from '@/components/auth/authStyles'
import {Helmet} from 'react-helmet-async'

export const Route = createFileRoute('/forgot-password')({
  component: ForgotPasswordPage,
})

const backToLogin = (
  <p className="text-center text-sm text-slate-400">
    Remember your password?{' '}
    <Link to="/login" className="font-medium text-indigo-300 underline-offset-4 hover:text-white hover:underline">
      Sign in
    </Link>
  </p>
)

function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const [success, setSuccess] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setLoading(true)
    setError('')

    try {
      await api.forgotPassword(email)
      setSuccess(true)
    } catch {
      setError('Failed to send reset email. Please try again.')
    } finally {
      setLoading(false)
    }
  }

  if (success) {
    return (
      <>
        <Helmet>
          <title>Reset Password | Moneat</title>
          <meta name="robots" content="noindex" />
        </Helmet>
        <AuthShell
          kicker="Check your inbox"
          heading="Reset link sent"
          subheading={
            <>
              If an account exists for <span className="font-medium text-slate-200">{email}</span>, a password reset link
              is on its way.
            </>
          }
          footer={backToLogin}
        >
          <div className="grid gap-5">
            <AuthAlert tone="success">
              The link expires in 1 hour. If it doesn&apos;t arrive in a few minutes, check your spam folder.
            </AuthAlert>
            <Button asChild className={authPrimaryButtonClass}>
              <Link to="/login">Back to sign in</Link>
            </Button>
          </div>
        </AuthShell>
      </>
    )
  }

  return (
    <>
      <Helmet>
        <title>Reset Password | Moneat</title>
        <meta name="robots" content="noindex" />
      </Helmet>
      <AuthShell
        kicker="Account recovery"
        heading="Reset your password"
        subheading="Enter your email and we'll send you a link to set a new password."
        footer={backToLogin}
      >
        <form onSubmit={handleSubmit} className="grid gap-4">
          {error && <AuthAlert tone="danger">{error}</AuthAlert>}

          <AuthField id="email" label="Email">
            <Input
              id="email"
              type="email"
              placeholder="you@company.com"
              className={authInputClass}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </AuthField>

          <Button type="submit" className={authPrimaryButtonClass} disabled={loading}>
            {loading ? 'Sending…' : 'Send reset link'}
          </Button>
        </form>
      </AuthShell>
    </>
  )
}
