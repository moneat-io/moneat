import { createFileRoute, redirect, Link } from '@tanstack/react-router'
import { useState } from 'react'
import { api } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card'
import { Logo } from '@/components/logo'

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
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)
  const [resending, setResending] = useState(false)
  const [resendMessage, setResendMessage] = useState('')

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    
    try {
      await api.signup(email, password, name || undefined)
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

  if (success) {
    return (
      <div className="flex min-h-screen items-center justify-center px-4">
        <Card className="w-full max-w-md">
          <CardHeader className="text-center">
            <CardTitle className="text-2xl">Check your email</CardTitle>
            <CardDescription>
              We've sent a verification link to {email}. Please verify your email before signing in.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {resendMessage && (
              <div className={`text-sm text-center ${resendMessage.includes('sent') ? 'text-green-600' : 'text-destructive'}`}>
                {resendMessage}
              </div>
            )}
            <div className="text-center text-sm text-muted-foreground">
              Didn't receive the email?{' '}
              <button
                onClick={handleResendEmail}
                disabled={resending}
                className="text-primary hover:underline disabled:opacity-50"
              >
                {resending ? 'Sending...' : 'Resend verification email'}
              </button>
            </div>
          </CardContent>
          <CardFooter className="flex justify-center">
            <Link to="/login">
              <Button>Go to Sign In</Button>
            </Link>
          </CardFooter>
        </Card>
      </div>
    )
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <Card className="w-full max-w-md">
        <CardHeader className="text-center space-y-4">
          <div className="flex justify-center">
            <Logo className="h-10" />
          </div>
          <div>
            <CardTitle className="text-2xl">Create an account</CardTitle>
            <CardDescription className="mt-1">Sign up to get started</CardDescription>
          </div>
        </CardHeader>
        <CardContent>
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
            <Button type="submit" className="w-full">
              Sign up
            </Button>
          </form>
        </CardContent>
        <CardFooter className="flex justify-center text-sm text-muted-foreground">
          Already have an account?{' '}
          <Link to="/login" className="ml-1 text-primary hover:underline">
            Sign in
          </Link>
        </CardFooter>
      </Card>
    </div>
  )
}
