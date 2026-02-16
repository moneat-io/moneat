// Moneat - Mobile-First Error Monitoring Platform
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
import {useEffect, useState} from 'react'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Checkbox} from '@/components/ui/checkbox'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {LEGAL_PRIVACY_VERSION, LEGAL_TERMS_VERSION} from '@/lib/legal'
import {Logo} from '@/components/logo'
import {Helmet} from 'react-helmet-async'

export const Route = createFileRoute('/signup')({
  beforeLoad: () => {
    if (api.isAuthenticated()) {
      throw redirect({ to: '/' })
    }
  },
  component: SignupPage,
})

function SignupPage() {
  const searchParams = new URLSearchParams(window.location.search)
  const inviteToken = searchParams.get('inviteToken') || undefined
  
  // Capture UTM parameters from URL
  const utmSource = searchParams.get('utm_source') || undefined
  const utmMedium = searchParams.get('utm_medium') || undefined
  const utmCampaign = searchParams.get('utm_campaign') || undefined
  const utmContent = searchParams.get('utm_content') || undefined
  const utmTerm = searchParams.get('utm_term') || undefined
  
  useEffect(() => {
    // Store UTM params for onboarding, or clear stale values on non-UTM visits.
    if (utmSource || utmMedium || utmCampaign || utmContent || utmTerm) {
      localStorage.setItem('utm_params', JSON.stringify({
        utmSource,
        utmMedium,
        utmCampaign,
        utmContent,
        utmTerm,
      }))
      return
    }
    localStorage.removeItem('utm_params')
  }, [utmSource, utmMedium, utmCampaign, utmContent, utmTerm])
  
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
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

    try {
      await api.signup(email, password, name || undefined, {
        acceptTerms: true,
        acceptPrivacy: true,
        termsVersion: LEGAL_TERMS_VERSION,
        privacyVersion: LEGAL_PRIVACY_VERSION,
      }, inviteToken)
      setSuccess(true)
    } catch (err) {
      setError('Failed to create account. Email may already be in use.')
    }
  }

  const handleResendEmail = async () => {
    setResending(true)
    setResendMessage('')

    try {
      await api.resendVerificationEmail(email)
      setResendMessage('Verification email sent! Please check your inbox.')
    } catch (err) {
      setResendMessage('Failed to resend email. Please try again later.')
    } finally {
      setResending(false)
    }
  }

  return (
    <div>
      <Helmet>
        <title>Sign Up | Moneat</title>
        <meta name="robots" content="noindex" />
      </Helmet>
      <div className="mx-auto flex min-h-screen max-w-6xl overflow-hidden border-x border-border">
        <div className="hidden lg:flex lg:w-1/2 bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900 relative flex-col justify-between p-10">
          <div className="text-white">
            <Logo className="h-10 text-white" />
          </div>
          <div>
            <h2 className="text-3xl font-bold text-white mb-3">
              Error monitoring
              <br />
              that just works.
            </h2>
            <p className="text-slate-400 text-sm leading-relaxed max-w-xs">
              Track, triage, and resolve issues before your users even notice.
            </p>
          </div>
          <svg viewBox="0 0 400 60" className="absolute bottom-0 left-0 right-0 opacity-10" aria-hidden="true">
            <polyline points="0,40 60,40 80,10 120,50 160,10 200,40 400,40" fill="none" stroke="#38bdf8" strokeWidth="2" />
          </svg>
        </div>

        <div className="flex-1 flex items-center justify-center px-8 py-12 lg:px-16 bg-background">
          <div className="w-full max-w-md">
            <div className="lg:hidden flex justify-center mb-8">
              <Logo className="h-10" />
            </div>

            {success ? (
              <>
                <h1 className="text-2xl font-bold mb-1">Check your email</h1>
                <p className="text-sm text-muted-foreground mb-8">
                  We sent a verification link to {email}. Verify your email before signing in.
                </p>

                <div className="space-y-4">
                  {resendMessage && (
                    <div className={`text-sm ${resendMessage.includes('sent') ? 'text-green-600' : 'text-destructive'}`}>
                      {resendMessage}
                    </div>
                  )}

                  <p className="text-sm text-muted-foreground">
                    Didn&apos;t receive the email?{' '}
                    <button
                      onClick={handleResendEmail}
                      disabled={resending}
                      className="text-primary hover:underline disabled:opacity-50"
                    >
                      {resending ? 'Sending...' : 'Resend verification email'}
                    </button>
                  </p>

                  <Link to="/login">
                    <Button className="w-full">Go to Sign In</Button>
                  </Link>
                </div>
              </>
            ) : (
              <>
                <h1 className="text-2xl font-bold mb-1">Create an account</h1>
                <p className="text-sm text-muted-foreground mb-8">Sign up to get started</p>

                <form onSubmit={handleSubmit} className="space-y-4">
                  {error && <div className="text-sm text-destructive">{error}</div>}

                  <div className="space-y-2">
                    <Label htmlFor="name">Name (optional)</Label>
                    <Input
                      id="name"
                      type="text"
                      placeholder="Your name"
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                    />
                  </div>

                  <div className="space-y-2">
                    <Label htmlFor="email">Email</Label>
                    <Input
                      id="email"
                      type="email"
                      placeholder="you@example.com"
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      required
                    />
                  </div>

                  <div className="space-y-2">
                    <Label htmlFor="password">Password</Label>
                    <Input
                      id="password"
                      type="password"
                      placeholder="Create a password"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      required
                    />
                  </div>

                  <div className="flex items-start gap-3 rounded-md border border-border/60 p-3">
                    <Checkbox
                      id="accept-legal"
                      checked={acceptedLegal}
                      onCheckedChange={(checked) => setAcceptedLegal(checked === true)}
                      className="mt-0.5"
                    />
                    <Label htmlFor="accept-legal" className="text-sm font-normal leading-relaxed">
                      I agree to the{' '}
                      <Link to="/legal/terms" target="_blank" rel="noreferrer" className="text-primary hover:underline">
                        Terms of Use
                      </Link>{' '}
                      and{' '}
                      <Link to="/legal/privacy" target="_blank" rel="noreferrer" className="text-primary hover:underline">
                        Privacy Policy
                      </Link>.
                    </Label>
                  </div>

                  <Button type="submit" className="w-full" disabled={!acceptedLegal}>
                    Sign up
                  </Button>
                </form>

                <div className="mt-6">
                  <div className="relative">
                    <div className="absolute inset-0 flex items-center">
                      <div className="w-full border-t border-border"></div>
                    </div>
                    <div className="relative flex justify-center text-xs uppercase">
                      <span className="bg-background px-2 text-muted-foreground">Or continue with</span>
                    </div>
                  </div>

                  <div className="mt-4">
                    <Button
                      type="button"
                      variant="outline"
                      className="w-full"
                      onClick={() => {
                        const backendUrl = import.meta.env.VITE_API_URL || 'http://localhost:8080'
                        window.location.href = `${backendUrl}/auth/github`
                      }}
                    >
                      <svg className="mr-2 h-4 w-4" fill="currentColor" viewBox="0 0 24 24">
                        <path fillRule="evenodd" d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z" clipRule="evenodd" />
                      </svg>
                      GitHub
                    </Button>
                  </div>
                  
                  <p className="mt-4 text-xs text-center text-muted-foreground">
                    By signing up with GitHub, you agree to our{' '}
                    <Link to="/legal/terms" target="_blank" rel="noreferrer" className="text-primary hover:underline">
                      Terms of Use
                    </Link>{' '}
                    and{' '}
                    <Link to="/legal/privacy" target="_blank" rel="noreferrer" className="text-primary hover:underline">
                      Privacy Policy
                    </Link>
                  </p>
                </div>

                <div className="mt-6 flex justify-center text-sm text-muted-foreground">
                  Already have an account?
                  <Link to="/login" className="ml-1 text-primary hover:underline">
                    Sign in
                  </Link>
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
