import {createRootRoute, Outlet, useRouterState, useNavigate} from '@tanstack/react-router'
import {useEffect, useState} from 'react'
import {Sidebar, SIDEBAR_COLLAPSED_WIDTH, SIDEBAR_EXPANDED_WIDTH} from '../components/sidebar'
import {Toaster} from '../components/ui/toaster'
import {ChatWidget} from '../components/ai-chat/ChatWidget'
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
  '/projects': 'Projects',
  '/feedback': 'Feedback',
  '/performance': 'Performance',
  '/releases': 'Releases',
  '/replays': 'Session Replays',
  '/monitoring': 'Infrastructure Monitoring',
  '/on-call': 'On-Call Management',
  '/settings': 'Settings & Billing',
  '/admin': 'Admin Overview',
  '/admin/organizations': 'Admin Organizations',
  '/admin/usage': 'Admin Usage',
  '/admin/revenue': 'Admin Revenue',
  '/admin/billing': 'Admin Billing',
  '/admin/emails': 'Admin Emails',
  '/admin/infrastructure': 'Admin Infrastructure',
}

// Public routes that don't require authentication or verification checks
const PUBLIC_ROUTES = new Set([
  '/login',
  '/signup',
  '/verify-email',
  '/verify-email-required',
  '/forgot-password',
  '/reset-password',
  '/onboarding',
  '/terms',
  '/privacy',
])

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
    [/^\/projects\/([^/]+)\/logs$/, (match) => `Project ${formatEntityId(match[1])} Logs`],
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
  const navigate = useNavigate()
  const currentPath = router.location.pathname
  const isAuthenticated = api.isAuthenticated()
  const [isSidebarExpanded, setIsSidebarExpanded] = useState(false)
  const [onboardingChecked, setOnboardingChecked] = useState(false)

  useEffect(() => {
    document.title = getDocumentTitle(currentPath, isAuthenticated)
  }, [currentPath, isAuthenticated])

  // Centralized email verification and onboarding check
  useEffect(() => {
    async function checkUserStatus() {
      // Skip check if not authenticated or on public routes or public status pages
      if (!isAuthenticated || PUBLIC_ROUTES.has(currentPath) || currentPath.startsWith('/s/') || currentPath.startsWith('/auth/') || currentPath.startsWith('/legal/') || currentPath.startsWith('/docs')) {
        setOnboardingChecked(true)
        return
      }

      try {
        const user = await api.getCurrentUser()
        
        // First check: Email verification (blocks everything)
        if (!user.emailVerified && currentPath !== '/verify-email-required') {
          navigate({ to: '/verify-email-required' })
          setOnboardingChecked(true)
          return
        }
        
        // Second check: Onboarding completion (only after email is verified)
        if (!user.onboardingCompleted && currentPath !== '/onboarding') {
          navigate({ to: '/onboarding' })
        }
      } catch (error) {
        console.error('Failed to check user status:', error)
      } finally {
        setOnboardingChecked(true)
      }
    }

    checkUserStatus()
  }, [isAuthenticated, currentPath, navigate])
  
  // Don't show sidebar on auth pages, landing page (when logged out), or public status pages
  const isAuthPage = ['/login', '/signup', '/verify-email', '/verify-email-required', '/forgot-password', '/reset-password'].includes(currentPath)
  const isLandingPage = currentPath === '/' && !isAuthenticated
  const isPublicStatusPage = currentPath.startsWith('/s/')
  const isDocsPage = currentPath.startsWith('/docs')
  const showSidebar = isAuthenticated && !isAuthPage && !isLandingPage && !isPublicStatusPage && !isDocsPage
  const sidebarWidth = isSidebarExpanded ? SIDEBAR_EXPANDED_WIDTH : SIDEBAR_COLLAPSED_WIDTH

  // Show loading state while checking onboarding
  if (isAuthenticated && !onboardingChecked && !PUBLIC_ROUTES.has(currentPath) && !currentPath.startsWith('/s/') && !currentPath.startsWith('/auth/') && !currentPath.startsWith('/legal/') && !currentPath.startsWith('/docs')) {
    return null
  }

  return (
    <div className="min-h-screen bg-background">
      {showSidebar && (
        <Sidebar 
          isExpanded={isSidebarExpanded} 
          onExpandedChange={setIsSidebarExpanded}
        />
      )}
      <div
        className="transition-[margin-left] duration-300"
        style={{ marginLeft: showSidebar ? sidebarWidth : 0 }}
      >
        <Outlet />
      </div>
      <Toaster />
      {showSidebar && user?.isAdmin && <ChatWidget />}
    </div>
  )
}
