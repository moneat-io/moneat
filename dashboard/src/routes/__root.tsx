import {createRootRoute, Outlet, useRouterState} from '@tanstack/react-router'
import {useEffect} from 'react'
import {Sidebar} from '../components/sidebar'
import {Toaster} from '../components/ui/toaster'
import {api} from '../lib/api'

export const Route = createRootRoute({
  component: RootComponent,
})

const STATIC_TITLES: Record<string, string> = {
  '/': 'Dashboard',
  '/login': 'Sign In',
  '/signup': 'Create Account',
  '/terms': 'Terms of Use',
  '/privacy': 'Privacy Policy',
  '/legal/terms': 'Terms of Use',
  '/legal/privacy': 'Privacy Policy',
  '/verify-email': 'Verify Email',
  '/forgot-password': 'Forgot Password',
  '/reset-password': 'Reset Password',
  '/onboarding': 'Get Started',
  '/analytics': 'Analytics',
  '/projects': 'Projects',
  '/feedback': 'Feedback',
  '/performance': 'Performance',
  '/releases': 'Releases',
  '/replays': 'Session Replays',
  '/monitoring': 'Infrastructure Monitoring',
  '/settings': 'Settings & Billing',
  '/admin': 'Admin Overview',
  '/admin/organizations': 'Admin Organizations',
  '/admin/usage': 'Admin Usage',
  '/admin/revenue': 'Admin Revenue',
  '/admin/billing': 'Admin Billing',
  '/admin/emails': 'Admin Emails',
  '/admin/infrastructure': 'Admin Infrastructure',
}

function normalizePath(pathname: string): string {
  if (!pathname || pathname === '/') return '/'
  return pathname.replace(/\/+$/, '')
}

function formatEntityId(rawValue: string): string {
  const decoded = decodeURIComponent(rawValue)

  // Keep long opaque IDs readable while still preserving identity.
  if (/^[a-f0-9-]{16,}$/i.test(decoded) || decoded.length > 36) {
    return `${decoded.slice(0, 8)}...${decoded.slice(-4)}`
  }

  return decoded
}

function getDocumentTitle(pathname: string, isAuthenticated: boolean): string {
  const normalizedPath = normalizePath(pathname)

  if (!isAuthenticated && normalizedPath === '/') {
    return 'Moneat | Error, Performance, and Replay Monitoring'
  }

  const dynamicMatchers: Array<[RegExp, (matches: RegExpMatchArray) => string]> = [
    [/^\/projects\/([^/]+)\/settings$/, (match) => `Project ${formatEntityId(match[1])} Settings`],
    [/^\/projects\/([^/]+)$/, (match) => `Project ${formatEntityId(match[1])}`],
    [/^\/issues\/([^/]+)$/, (match) => `Issue ${formatEntityId(match[1])}`],
    [/^\/feedback\/([^/]+)$/, (match) => `Feedback ${formatEntityId(match[1])}`],
    [/^\/performance\/([^/]+)$/, (match) => `Transaction ${formatEntityId(match[1])}`],
    [/^\/releases\/([^/]+)$/, (match) => `Release ${formatEntityId(match[1])}`],
    [/^\/replays\/([^/]+)$/, (match) => `Replay ${formatEntityId(match[1])}`],
    [/^\/monitoring\/([^/]+)$/, (match) => `System ${formatEntityId(match[1])}`],
    [/^\/admin\/organizations\/([^/]+)$/, (match) => `Organization ${formatEntityId(match[1])}`],
  ]

  for (const [pattern, toTitle] of dynamicMatchers) {
    const matches = normalizedPath.match(pattern)
    if (matches) {
      return `${toTitle(matches)} | Moneat`
    }
  }

  const staticTitle = STATIC_TITLES[normalizedPath]
  if (staticTitle) {
    return `${staticTitle} | Moneat`
  }

  return 'Moneat | Error, Performance, and Replay Monitoring'
}

function RootComponent() {
  const router = useRouterState()
  const currentPath = router.location.pathname
  const isAuthenticated = api.isAuthenticated()

  useEffect(() => {
    document.title = getDocumentTitle(currentPath, isAuthenticated)
  }, [currentPath, isAuthenticated])
  
  // Don't show sidebar on auth pages, landing page (when logged out), or admin (has its own layout)
  const isAuthPage = ['/login', '/signup', '/verify-email', '/forgot-password', '/reset-password'].includes(currentPath)
  const isLandingPage = currentPath === '/' && !isAuthenticated
  const isAdminRoute = currentPath.startsWith('/admin')
  const showSidebar = isAuthenticated && !isAuthPage && !isLandingPage && !isAdminRoute

  return (
    <div className="min-h-screen bg-background">
      {showSidebar && <Sidebar />}
      <div className={showSidebar ? 'ml-16' : ''}>
        <Outlet />
      </div>
      <Toaster />
    </div>
  )
}
