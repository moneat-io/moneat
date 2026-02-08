import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useState} from 'react'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Checkbox} from '@/components/ui/checkbox'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {LEGAL_PRIVACY_VERSION, LEGAL_TERMS_VERSION} from '@/lib/legal'
import {Logo} from '@/components/logo'

export const Route = createFileRoute('/signup')({
  beforeLoad: () => {
    if (api.isAuthenticated()) {
      throw redirect({ to: '/' })
    }
  },
  component: SignupPage,
})

function SignupPage() {
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
      })
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
    <div className="min-h-screen bg-background">
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
