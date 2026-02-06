import { createFileRoute, Link } from '@tanstack/react-router'
import { useState } from 'react'
import { api } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Logo } from '@/components/logo'

export const Route = createFileRoute('/forgot-password')({
  component: ForgotPasswordPage,
})

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
    } catch (err) {
      setError('Failed to send reset email. Please try again.')
    } finally {
      setLoading(false)
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
                <p className="text-sm text-muted-foreground mb-4">
                  If an account exists with {email}, we&apos;ve sent a password reset link.
                </p>
                <p className="text-sm text-muted-foreground mb-8">
                  The link will expire in 1 hour. If you don&apos;t receive an email, check your spam folder.
                </p>

                <Link to="/login" className="text-sm text-primary hover:underline">
                  Back to login
                </Link>
              </>
            ) : (
              <>
                <h1 className="text-2xl font-bold mb-1">Reset your password</h1>
                <p className="text-sm text-muted-foreground mb-8">
                  Enter your email address and we&apos;ll send you a link to reset your password
                </p>

                <form onSubmit={handleSubmit} className="space-y-4">
                  {error && <div className="text-sm text-destructive">{error}</div>}

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

                  <Button type="submit" className="w-full" disabled={loading}>
                    {loading ? 'Sending...' : 'Send reset link'}
                  </Button>
                </form>

                <div className="mt-6 flex justify-center text-sm text-muted-foreground">
                  Remember your password?
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
