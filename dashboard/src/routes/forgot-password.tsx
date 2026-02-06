import { createFileRoute, Link } from '@tanstack/react-router'
import { useState } from 'react'
import { api } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card'
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

  if (success) {
    return (
      <div className="flex min-h-screen items-center justify-center px-4">
        <Card className="w-full max-w-md">
          <CardHeader className="text-center">
            <CardTitle className="text-2xl">Check your email</CardTitle>
            <CardDescription>
              If an account exists with {email}, we've sent a password reset link.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-muted-foreground text-center">
              The link will expire in 1 hour. If you don't receive an email, check your spam folder.
            </p>
          </CardContent>
          <CardFooter className="flex justify-center text-sm text-muted-foreground">
            <Link to="/login" className="text-primary hover:underline">
              Back to login
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
            <CardTitle className="text-2xl">Reset your password</CardTitle>
            <CardDescription className="mt-1">
              Enter your email address and we'll send you a link to reset your password
            </CardDescription>
          </div>
        </CardHeader>
        <CardContent>
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
        </CardContent>
        <CardFooter className="flex justify-center text-sm text-muted-foreground">
          Remember your password?{' '}
          <Link to="/login" className="ml-1 text-primary hover:underline">
            Sign in
          </Link>
        </CardFooter>
      </Card>
    </div>
  )
}
