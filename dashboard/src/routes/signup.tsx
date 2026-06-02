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

import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useState} from 'react'
import {Github} from 'lucide-react'
import {api} from '@/lib/api'
import {trackEvent} from '@/lib/analytics'
import {APP_OVERVIEW_SEARCH} from '@/lib/overview-route'
import {isDemo} from '@/lib/demo'
import {Button} from '@/components/ui/button'
import {Checkbox} from '@/components/ui/checkbox'
import {Input} from '@/components/ui/input'
import {LEGAL_PRIVACY_VERSION, LEGAL_TERMS_VERSION} from '@/lib/legal'
import {GRADIENT_TEXT} from '@/components/landing/Landing'
import {AuthAlert, AuthDivider, AuthField, AuthShell} from '@/components/auth/AuthShell'
import {authInputClass, authPrimaryButtonClass, authSecondaryButtonClass} from '@/components/auth/authStyles'
import {Helmet} from 'react-helmet-async'

async function ensureSignupRouteCanLoad() {
  if (!api.isAuthenticated()) {
    return
  }

  if (isDemo()) {
    await api.logout()
    return
  }

  throw redirect({ to: '/', search: APP_OVERVIEW_SEARCH })
}

export const Route = createFileRoute('/signup')({
  beforeLoad: ensureSignupRouteCanLoad,
  component: SignupPage,
})

function SignupPage() {
  const searchParams = new URLSearchParams(window.location.search)
  const inviteToken = searchParams.get('inviteToken') || undefined
  
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [name, setName] = useState('')
  const [acceptedLegal, setAcceptedLegal] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)
  const [resending, setResending] = useState(false)
  const [resendMessage, setResendMessage] = useState('')

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')

    if (!acceptedLegal) {
      setError('You must agree to the Terms of Use and Privacy Policy to create an account.')
      return
    }

    // Client-side validation
    if (password.length < 8) {
      setError('Password must be at least 8 characters.')
      return
    }

    if (password !== confirmPassword) {
      setError('Passwords do not match.')
      return
    }

    try {
      await api.signup(email, password, name || undefined, {
        acceptTerms: true,
        acceptPrivacy: true,
        termsVersion: LEGAL_TERMS_VERSION,
        privacyVersion: LEGAL_PRIVACY_VERSION,
      }, inviteToken)
      trackEvent('Signup')
      setSuccess(true)
    } catch (err: unknown) {
      if (err instanceof Error) {
        setError(err.message)
      } else {
        setError('Failed to create account. Please try again.')
      }
    }
  }

  const handleResendEmail = async () => {
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

  const panelHeadline = (
    <>
      Start shipping with <span className={GRADIENT_TEXT}>fewer surprises.</span>
    </>
  )
  const panelLede =
    'Create your account, point your existing SDKs and agents at Moneat, and watch every signal land in one workspace. 1 GB free to start.'

  if (success) {
    return (
      <>
        <Helmet>
          <title>Sign Up | Moneat</title>
          <meta name="robots" content="noindex" />
        </Helmet>
        <AuthShell
          kicker="Check your inbox"
          heading="Verify your email"
          subheading={
            <>
              We sent a verification link to <span className="font-medium text-slate-200">{email}</span>. Confirm it to
              activate your account.
            </>
          }
          panelHeadline={panelHeadline}
          panelLede={panelLede}
          footer={
            <p className="text-center text-sm text-slate-400">
              Already verified?{' '}
              <Link to="/login" className="font-medium text-indigo-300 underline-offset-4 hover:text-white hover:underline">
                Sign in
              </Link>
            </p>
          }
        >
          <div className="grid gap-5">
            {resendMessage && (
              <AuthAlert tone={resendMessage.includes('sent') ? 'success' : 'danger'}>{resendMessage}</AuthAlert>
            )}

            <Button asChild className={authPrimaryButtonClass}>
              <Link to="/login">Go to sign in</Link>
            </Button>

            <p className="text-center text-sm text-slate-400">
              Didn&apos;t get the email?{' '}
              <button
                onClick={handleResendEmail}
                disabled={resending}
                className="font-medium text-indigo-300 underline-offset-4 hover:text-white hover:underline disabled:opacity-50"
              >
                {resending ? 'Sending…' : 'Resend it'}
              </button>
            </p>
          </div>
        </AuthShell>
      </>
    )
  }

  return (
    <>
      <Helmet>
        <title>Sign Up | Moneat</title>
        <meta name="robots" content="noindex" />
      </Helmet>
      <AuthShell
        kicker="Get started"
        heading="Create your account"
        subheading="Free to start — no credit card required."
        panelHeadline={panelHeadline}
        panelLede={panelLede}
        footer={
          <p className="text-center text-sm text-slate-400">
            Already have an account?{' '}
            <Link to="/login" className="font-medium text-indigo-300 underline-offset-4 hover:text-white hover:underline">
              Sign in
            </Link>
          </p>
        }
      >
        <form onSubmit={handleSubmit} className="grid gap-4">
          {error && <AuthAlert tone="danger">{error}</AuthAlert>}

          <AuthField id="name" label="Name" hint="Optional — shown to teammates you invite.">
            <Input
              id="name"
              type="text"
              placeholder="Your name"
              className={authInputClass}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </AuthField>

          <AuthField id="email" label="Email" required>
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

          <AuthField id="password" label="Password" required hint="At least 8 characters.">
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

          <label
            htmlFor="accept-legal"
            className="flex cursor-pointer items-start gap-3 rounded-lg border border-white/[0.08] bg-white/[0.02] p-3.5 text-sm leading-relaxed text-slate-300"
          >
            <Checkbox
              id="accept-legal"
              checked={acceptedLegal}
              onCheckedChange={(checked) => setAcceptedLegal(checked === true)}
              className="mt-0.5 border-white/30 data-[state=checked]:border-indigo-400 data-[state=checked]:bg-indigo-500 data-[state=checked]:text-white"
            />
            <span>
              I agree to the{' '}
              <Link
                to="/legal/terms"
                target="_blank"
                rel="noreferrer"
                onClick={(event) => event.stopPropagation()}
                className="text-indigo-300 underline-offset-4 hover:underline"
              >
                Terms of Use
              </Link>{' '}
              and{' '}
              <Link
                to="/legal/privacy"
                target="_blank"
                rel="noreferrer"
                onClick={(event) => event.stopPropagation()}
                className="text-indigo-300 underline-offset-4 hover:underline"
              >
                Privacy Policy
              </Link>
              .
            </span>
          </label>

          <Button type="submit" className={authPrimaryButtonClass} disabled={!acceptedLegal}>
            Create account
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

          <p className="text-center text-xs leading-relaxed text-slate-500">
            By continuing with GitHub you agree to our{' '}
            <Link to="/legal/terms" target="_blank" rel="noreferrer" className="text-slate-400 underline-offset-4 hover:text-indigo-300 hover:underline">
              Terms of Use
            </Link>{' '}
            and{' '}
            <Link to="/legal/privacy" target="_blank" rel="noreferrer" className="text-slate-400 underline-offset-4 hover:text-indigo-300 hover:underline">
              Privacy Policy
            </Link>
            .
          </p>
        </div>
      </AuthShell>
    </>
  )
}
