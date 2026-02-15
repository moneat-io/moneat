import {createFileRoute, Link, redirect, useNavigate} from '@tanstack/react-router'
import {useState} from 'react'
import {api} from '@/lib/api'
import {Logo} from '@/components/logo'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Helmet} from 'react-helmet-async'

export const Route = createFileRoute('/login')({
  beforeLoad: ({ search }) => {
    // If user is already authenticated and there's NO redirect_uri (normal web login)
    // then redirect to home. But if redirect_uri exists (mobile login), allow
    // the page to load so they can log in again from mobile
    const redirectUri = (search as any).redirect_uri
    if (api.isAuthenticated() && !redirectUri) {
      throw redirect({ to: '/' })
    }
    
    // If user is already authenticated AND redirect_uri exists (mobile login),
    // log them out automatically so they can log in fresh
    if (api.isAuthenticated() && redirectUri) {
      api.logout()
    }
  },
  component: LoginPage,
})

function LoginPage() {
  const navigate = useNavigate()
  const searchParams = new URLSearchParams(window.location.search)
  const inviteToken = searchParams.get('inviteToken') || undefined
  const redirectUri = searchParams.get('redirect_uri') || undefined
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
      // Allow moneat:// scheme for mobile app
      // Add other allowed schemes/domains as needed
      const allowedSchemes = ['moneat']
      const allowedHosts = ['moneat.io', 'www.moneat.io']
      
      if (allowedSchemes.includes(url.protocol.replace(':', ''))) {
        return true
      }
      
      if (url.protocol === 'https:' && allowedHosts.includes(url.hostname)) {
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
      if (inviteToken) {
        navigate({ to: '/accept-invite', search: { token: inviteToken } })
      } else if (redirectUri) {
        // Validate redirect URI before redirecting
        if (!isValidRedirectUri(redirectUri)) {
          setError('Invalid redirect URI')
          setLoading(false)
          return
        }
        
        // Redirect back to mobile app with token
        // Use window.location for external redirect
        const hasQuery = redirectUri.includes('?')
        const separator = hasQuery ? '&' : '?'
        window.location.href = `${redirectUri}${separator}token=${token}`
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
    <div>
      <Helmet>
        <title>Sign In | Moneat</title>
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

            <h1 className="text-2xl font-bold mb-1">Welcome back</h1>
            <p className="text-sm text-muted-foreground mb-8">Sign in to your account</p>

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

              <div className="space-y-2">
                <Label htmlFor="password">Password</Label>
                <Input
                  id="password"
                  type="password"
                  placeholder="Enter your password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                />
                <div className="text-right">
                  <Link to="/forgot-password" className="text-sm text-primary hover:underline">
                    Forgot password?
                  </Link>
                </div>
              </div>

              <Button type="submit" className="w-full" disabled={loading}>
                {loading ? 'Signing in...' : 'Sign in'}
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

              {!showSsoInput ? (
                <Button
                  type="button"
                  variant="ghost"
                  className="w-full mt-3 text-sm"
                  onClick={() => setShowSsoInput(true)}
                >
                  Or login with SSO
                </Button>
              ) : (
                <form onSubmit={handleSsoLogin} className="mt-3 space-y-3">
                  <div className="space-y-2">
                    <Label htmlFor="sso-email">Work Email</Label>
                    <Input
                      id="sso-email"
                      type="email"
                      placeholder="you@company.com"
                      value={ssoEmail}
                      onChange={(e) => setSsoEmail(e.target.value)}
                      required
                    />
                  </div>
                  <div className="flex gap-2">
                    <Button type="submit" className="flex-1" disabled={ssoLoading}>
                      {ssoLoading ? 'Redirecting...' : 'Continue with SSO'}
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
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

            <div className="mt-6 flex justify-center text-sm text-muted-foreground">
              Don't have an account?
              <Link to="/signup" className="ml-1 text-primary hover:underline">
                Sign up
              </Link>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
