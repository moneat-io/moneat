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

import {createFileRoute, Link, redirect, useNavigate} from '@tanstack/react-router'
import {useState} from 'react'
import {Github} from 'lucide-react'
import {api} from '@/lib/api'
import {trackEvent} from '@/lib/analytics'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {cn} from '@/lib/utils'
import {GRADIENT_TEXT} from '@/components/landing/Landing'
import {AuthAlert, AuthDivider, AuthField, AuthShell} from '@/components/auth/AuthShell'
import {authInputClass, authPrimaryButtonClass, authSecondaryButtonClass} from '@/components/auth/authStyles'
import {Helmet} from 'react-helmet-async'

function normalizePostLoginRedirect(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  if (!trimmed) return undefined

  if (trimmed.startsWith('/') && !trimmed.startsWith('//')) {
    return trimmed
  }

  try {
    const url = new URL(trimmed, 'https://moneat.io')
    return `${url.pathname}${url.search}${url.hash}` || undefined
  } catch {
    return undefined
  }
}

export const Route = createFileRoute('/login')({
  beforeLoad: ({ search }) => {
    const s = search as Record<string, unknown>
    const redirectUri = s.redirect_uri as string | undefined
    const postLoginRedirect = normalizePostLoginRedirect(s.redirect)

    // If user is already authenticated AND redirect_uri exists (mobile login),
    // log them out automatically so they can log in fresh
    if (api.isAuthenticated() && redirectUri) {
      api.logout()
      return
    }

    // If authenticated with a post-login redirect (e.g. from email link), go there
    if (api.isAuthenticated() && postLoginRedirect) {
      throw redirect({ to: postLoginRedirect as never })
    }

    // If authenticated with no special params, redirect to home
    if (api.isAuthenticated()) {
      throw redirect({ to: '/' })
    }
  },
  component: LoginPage,
})

function LoginPage() {
  const navigate = useNavigate()
  const searchParams = new URLSearchParams(window.location.search)
  const inviteToken = searchParams.get('inviteToken') || undefined
  const redirectUri = searchParams.get('redirect_uri') || undefined
  const postLoginRedirect =
    normalizePostLoginRedirect(searchParams.get('redirect') || undefined)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [ssoLoading, setSsoLoading] = useState(false)
  const [showSsoInput, setShowSsoInput] = useState(false)
  const [ssoEmail, setSsoEmail] = useState('')

  // Validate redirect URI against allowlist
  const isValidRedirectUri = (uri: string): boolean => {
    try {
      const url = new URL(uri)
      const allowedHosts = ['moneat.io', 'www.moneat.io']

      // Allow moneat:// deep links (production mobile app scheme)
      if (url.protocol === 'moneat:') {
        return true
      }

      // Allow https on moneat.io domains
      if (url.protocol === 'https:' && allowedHosts.includes(url.hostname)) {
        return true
      }

      // Allow Expo deep links only in local development
      if ((url.protocol === 'exp:' || url.protocol === 'exps:') &&
          process.env.NODE_ENV === 'development') {
        return true
      }

      return false
    } catch {
      return false
    }
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setLoading(true)
    setError('')

    try {
      const { token } = await api.login(email, password)
      trackEvent('Login', { method: 'email' })
      if (inviteToken) {
        navigate({ to: '/accept-invite', search: { token: inviteToken } })
      } else if (redirectUri) {
        // Validate redirect URI before redirecting (mobile deep link)
        if (!isValidRedirectUri(redirectUri)) {
          setError('Invalid redirect URI')
          setLoading(false)
          return
        }
        window.location.href = `${redirectUri}#token=${token}`
      } else if (postLoginRedirect) {
        // Redirect back to the page the user was trying to access (e.g. from email link)
        window.location.href = postLoginRedirect
      } else {
        navigate({ to: '/' })
      }
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : String(err)
      if (errorMessage === 'NETWORK_ERROR') {
        setError('Unable to connect to the server. Please check your connection and try again.')
      } else {
        setError('Invalid email or password. Please try again.')
      }
    } finally {
      setLoading(false)
    }
  }

  const handleSsoLogin = async (e: React.FormEvent) => {
    e.preventDefault()
    setSsoLoading(true)
    setError('')

    try {
      const response = await api.initSso(ssoEmail)
      // Redirect to SSO provider
      window.location.href = response.redirectUrl
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : String(err)
      if (errorMessage === 'NETWORK_ERROR') {
        setError('Unable to connect to the server. Please check your connection and try again.')
      } else {
        setError(errorMessage || 'SSO login failed. Please try again.')
      }
      setSsoLoading(false)
    }
  }

  return (
    <>
      <Helmet>
        <title>Sign In | Moneat</title>
        <meta name="robots" content="noindex" />
      </Helmet>
      <AuthShell
        kicker="Welcome back"
        heading="Sign in to Moneat"
        subheading="Continue with your email, GitHub, or your company's SSO."
        panelHeadline={
          <>
            Your whole stack, behind <span className={GRADIENT_TEXT}>one login.</span>
          </>
        }
        panelLede="Errors, logs, traces, infrastructure, uptime, and on-call — together in one workspace your team already knows how to read."
        footer={
          <div className="flex flex-col items-center gap-2 text-sm">
            <p className="text-slate-400">
              Don&apos;t have an account?{' '}
              <Link to="/signup" className="font-medium text-indigo-300 underline-offset-4 hover:text-white hover:underline">
                Create one
              </Link>
            </p>
            <Link to="/demo" className="font-brandmono text-xs text-slate-500 underline-offset-4 hover:text-slate-300 hover:underline">
              Explore the live demo →
            </Link>
          </div>
        }
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

          <AuthField id="password" label="Password">
            <Input
              id="password"
              type="password"
              placeholder="Enter your password"
              className={authInputClass}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
            <div className="text-right">
              <Link
                to="/forgot-password"
                className="text-xs text-slate-400 underline-offset-4 hover:text-indigo-300 hover:underline"
              >
                Forgot password?
              </Link>
            </div>
          </AuthField>

          <Button type="submit" className={authPrimaryButtonClass} disabled={loading}>
            {loading ? 'Signing in…' : 'Sign in'}
          </Button>
        </form>

        <div className="mt-6 grid gap-4">
          <AuthDivider label="or continue with" />

          <Button
            type="button"
            variant="outline"
            className={authSecondaryButtonClass}
            onClick={() => {
              const backendUrl = import.meta.env.VITE_BACKEND_URL || 'https://api.moneat.io'
              window.location.href = `${backendUrl}/auth/github`
            }}
          >
            <Github />
            GitHub
          </Button>

          {!showSsoInput ? (
            <button
              type="button"
              className="font-brandmono text-xs uppercase tracking-[0.14em] text-slate-500 transition-colors hover:text-indigo-300"
              onClick={() => setShowSsoInput(true)}
            >
              Sign in with SSO instead
            </button>
          ) : (
            <form onSubmit={handleSsoLogin} className="grid gap-3 rounded-lg border border-white/[0.08] bg-white/[0.02] p-4">
              <AuthField id="sso-email" label="Work email">
                <Input
                  id="sso-email"
                  type="email"
                  placeholder="you@company.com"
                  className={authInputClass}
                  value={ssoEmail}
                  onChange={(e) => setSsoEmail(e.target.value)}
                  required
                />
              </AuthField>
              <div className="flex gap-2">
                <Button type="submit" className={cn(authPrimaryButtonClass, 'flex-1')} disabled={ssoLoading}>
                  {ssoLoading ? 'Redirecting…' : 'Continue with SSO'}
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  className="h-11 text-slate-400 hover:bg-white/[0.05] hover:text-white"
                  onClick={() => {
                    setShowSsoInput(false)
                    setSsoEmail('')
                  }}
                >
                  Cancel
                </Button>
              </div>
            </form>
          )}
        </div>
      </AuthShell>
    </>
  )
}
