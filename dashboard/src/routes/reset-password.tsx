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

import {createFileRoute, Link, useNavigate} from '@tanstack/react-router'
import {useState} from 'react'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {AuthAlert, AuthField, AuthShell} from '@/components/auth/AuthShell'
import {authInputClass, authPrimaryButtonClass} from '@/components/auth/authStyles'
import {Helmet} from 'react-helmet-async'

export const Route = createFileRoute('/reset-password')({
  component: ResetPasswordPage,
  validateSearch: (search: Record<string, unknown>) => {
    return {
      token: (search.token as string) || '',
    }
  },
})

function ResetPasswordPage() {
  const navigate = useNavigate()
  const { token } = Route.useSearch()
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')

    if (password.length < 8) {
      setError('Password must be at least 8 characters')
      return
    }

    if (password !== confirmPassword) {
      setError('Passwords do not match')
      return
    }

    if (!token) {
      setError('Invalid reset link')
      return
    }

    setLoading(true)

    try {
      await api.resetPassword(token, password)
      navigate({ to: '/login' })
    } catch {
      setError('Invalid or expired reset link')
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <Helmet>
        <title>Set New Password | Moneat</title>
        <meta name="robots" content="noindex" />
      </Helmet>
      <AuthShell
        kicker="Account recovery"
        heading="Set a new password"
        subheading="Choose a strong password for your account."
        footer={
          <p className="text-center text-sm text-slate-400">
            <Link to="/login" className="font-medium text-indigo-300 underline-offset-4 hover:text-white hover:underline">
              Back to sign in
            </Link>
          </p>
        }
      >
        <form onSubmit={handleSubmit} className="grid gap-4">
          {error && <AuthAlert tone="danger">{error}</AuthAlert>}

          <AuthField id="password" label="New password" required hint="At least 8 characters.">
            <Input
              id="password"
              type="password"
              placeholder="Create a password"
              className={authInputClass}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </AuthField>

          <AuthField id="confirmPassword" label="Confirm password" required>
            <Input
              id="confirmPassword"
              type="password"
              placeholder="Re-enter your password"
              className={authInputClass}
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              required
            />
          </AuthField>

          <Button type="submit" className={authPrimaryButtonClass} disabled={loading}>
            {loading ? 'Resetting…' : 'Reset password'}
          </Button>
        </form>
      </AuthShell>
    </>
  )
}
