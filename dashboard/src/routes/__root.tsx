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

import {createRootRoute, Outlet, Link, useRouterState, useNavigate} from '@tanstack/react-router'
import {useCallback, useEffect, useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {Sidebar, SIDEBAR_COLLAPSED_WIDTH, SIDEBAR_EXPANDED_WIDTH} from '../components/Sidebar'
import {CommandPalette} from '../components/CommandPalette'
import {CommandPaletteProvider} from '../contexts/CommandPaletteProvider'
import {Toaster} from '../components/ui/toaster'
import {api} from '../lib/api'
import {isDemo} from '../lib/demo'
import {APP_OVERVIEW_SEARCH, isPublicLandingRoute} from '../lib/overview-route'
import {DemoBanner} from '../components/demo/DemoBanner'
import {AiFloatingPanel} from '../components/AiFloatingPanel'
import {AiSplitPanel} from '../components/AiSplitPanel'
import {useCommandPalette} from '../hooks/useCommandPalette'

export const Route = createRootRoute({
  component: RootComponent,
  notFoundComponent: NotFoundPage,
})

const STATIC_TITLES: Record<string, string> = {
  '/': 'Overview',
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
  '/error-tracking': 'Error Tracking',
  '/log-management': 'Log Management',
  '/infrastructure-monitoring': 'Infrastructure Monitoring',
  '/uptime-monitoring': 'Uptime Monitoring',
  '/session-replay': 'Session Replay',
  '/performance-monitoring': 'Performance Monitoring',
  '/profiling': 'Profiling',
  '/on-call-management': 'On-Call Management',
  '/public-status-pages': 'Public Status Pages',
  '/alerting': 'Alerting',
  '/ai-observability': 'AI Observability',
  '/mcp-server': 'MCP Server',
  '/custom-dashboards': 'Custom Dashboards',
  '/security-sbom': 'SBOM Security',
  '/pricing': 'Pricing',
  '/pricing-calculator': 'Pricing Calculator',
  '/compare': 'Compare Moneat 2026',
  '/datadog-alternative': 'Datadog Alternative 2026',
  '/sentry-alternative': 'Sentry Alternative 2026',
  '/better-stack-alternative': 'Better Stack Alternative 2026',
  '/signoz-alternative': 'SigNoz Alternative 2026',
  '/docs': 'Documentation',
  '/blog': 'Blog',
  '/projects': 'Services',
  '/feedback': 'Feedback',
  '/performance': 'Traces',
  '/performance/traces': 'Traces',
  '/performance/service-map': 'Service Map',
  '/releases': 'Releases',
  '/replays': 'Session Replays',
  '/logs': 'Logs',
  '/setup': 'Setup',
  '/usage-insights': 'Usage Insights',
  '/resources': 'Resources',
  '/monitoring': 'Infrastructure Monitoring',
  '/monitoring/hosts': 'Hosts',
  '/monitoring/map': 'Map Explorer',
  '/monitoring/containers': 'Containers',
  '/monitoring/processes': 'Processes',
  '/monitoring/network': 'Network Connections',
  '/monitoring/events': 'Events',
  '/monitoring/service-map': 'Map Explorer',
  '/on-call': 'On-Call Management',
  '/settings': 'Settings & Billing',
  '/admin': 'Admin Overview',
  '/admin/organizations': 'Admin Organizations',
  '/admin/usage': 'Admin Usage',
  '/admin/revenue': 'Admin Revenue',
  '/admin/billing': 'Admin Billing',
  '/admin/emails': 'Admin Emails',
  '/admin/infrastructure': 'Admin Infrastructure',
  '/workflows/connections': 'Workflow Connections',
}

// Feature/marketing page routes (shared between public route gating and sidebar visibility)
const FEATURE_ROUTES = [
  '/error-tracking',
  '/log-management',
  '/infrastructure-monitoring',
  '/uptime-monitoring',
  '/session-replay',
  '/performance-monitoring',
  '/profiling',
  '/on-call-management',
  '/public-status-pages',
  '/alerting',
  '/ai-observability',
  '/mcp-server',
  '/custom-dashboards',
  '/security-sbom',
  '/pricing',
  '/compare',
  '/datadog-alternative',
  '/sentry-alternative',
  '/better-stack-alternative',
  '/signoz-alternative',
] as const

// Public routes that don't require authentication, verification checks, or the authenticated app shell.
const PUBLIC_ROUTES: ReadonlySet<string> = new Set([
  '/login',
  '/signup',
  '/verify-email',
  '/verify-email-required',
  '/forgot-password',
  '/reset-password',
  '/onboarding',
  '/terms',
  '/privacy',
  '/accept-invite',
  '/demo',
  '/impersonate-callback',
  '/pricing-calculator',
  ...FEATURE_ROUTES,
])

const PUBLIC_ROUTE_PREFIXES = ['/s', '/auth', '/legal', '/docs', '/blog'] as const

function normalizePath(pathname: string): string {
  if (!pathname || pathname === '/') return '/'
  return pathname.replace(/\/+$/, '')
}

function isRouteBranch(pathname: string, routeRoot: string): boolean {
  return pathname === routeRoot || pathname.startsWith(`${routeRoot}/`)
}

export function isPublicAppRoute(pathname: string, search: unknown): boolean {
  const normalizedPath = normalizePath(pathname)
  return (
    isPublicLandingRoute(normalizedPath, search) ||
    PUBLIC_ROUTES.has(normalizedPath) ||
    PUBLIC_ROUTE_PREFIXES.some((routeRoot) => isRouteBranch(normalizedPath, routeRoot))
  )
}

interface AuthenticatedSidebarVisibilityOptions {
  readonly isAuthenticated: boolean
  readonly pathname: string
  readonly search: unknown
}

export function shouldShowAuthenticatedSidebar({
  isAuthenticated,
  pathname,
  search,
}: AuthenticatedSidebarVisibilityOptions): boolean {
  return isAuthenticated && !isPublicAppRoute(pathname, search)
}

function formatEntityId(rawValue: string): string {
  const decoded = decodeURIComponent(rawValue)

  // Keep long opaque IDs readable while still preserving identity.
  if (/^[a-f0-9-]{16,}$/i.test(decoded) || decoded.length > 36) {
    return `${decoded.slice(0, 8)}...${decoded.slice(-4)}`
  }

  return decoded
}

function getDocumentTitle(pathname: string, isPublicLandingPage: boolean): string {
  const normalizedPath = normalizePath(pathname)

  if (isPublicLandingPage && normalizedPath === '/') {
    return 'Moneat | Error, Performance, and Replay Monitoring'
  }

  const staticTitle = STATIC_TITLES[normalizedPath]
  if (staticTitle) {
    return `${staticTitle} | Moneat`
  }

  const dynamicMatchers: Array<[RegExp, (matches: RegExpMatchArray) => string]> = [
    [/^\/projects\/([^/]+)\/settings$/, (match) => `Service ${formatEntityId(match[1])} Settings`],
    [/^\/issues\/([^/]+)$/, (match) => `Issue ${formatEntityId(match[1])}`],
    [/^\/feedback\/([^/]+)$/, (match) => `Feedback ${formatEntityId(match[1])}`],
    [/^\/performance\/traces\/([^/]+)$/, (match) => `Trace ${formatEntityId(match[1])}`],
    [/^\/performance\/([^/]+)$/, (match) => `Transaction ${formatEntityId(match[1])}`],
    [/^\/releases\/([^/]+)$/, (match) => `Release ${formatEntityId(match[1])}`],
    [/^\/replays\/([^/]+)$/, (match) => `Replay ${formatEntityId(match[1])}`],
    [/^\/monitoring\/([^/]+)$/, (match) => `System ${formatEntityId(match[1])}`],
    [/^\/admin\/organizations\/([^/]+)$/, (match) => `Organization ${formatEntityId(match[1])}`],
  ]

  for (const [pattern, toTitle] of dynamicMatchers) {
    const matches = pattern.exec(normalizedPath)
    if (matches) {
      return `${toTitle(matches)} | Moneat`
    }
  }

  return 'Moneat | Error, Performance, and Replay Monitoring'
}

function NotFoundPage() {
  return (
    <div className="min-h-[70vh] flex flex-col items-center justify-center px-4 text-center">
      <div className="mb-6 select-none">
        <svg
          xmlns="http://www.w3.org/2000/svg"
          viewBox="0 0 48 48"
          className="h-16 w-16 mx-auto mb-6"
          aria-hidden="true"
        >
          <circle
            cx="24" cy="24" r="18"
            fill="none" stroke="#38bdf8" strokeWidth="2"
            strokeDasharray="4 3"
          />
          <line
            x1="18" y1="18" x2="30" y2="30"
            stroke="#38bdf8" strokeWidth="2.5"
            strokeLinecap="round"
          />
          <line
            x1="30" y1="18" x2="18" y2="30"
            stroke="#38bdf8" strokeWidth="2.5"
            strokeLinecap="round"
          />
        </svg>
        <p className="text-8xl font-bold text-slate-800 leading-none tracking-tight">
          404
        </p>
      </div>
      <h1 className="text-2xl font-semibold text-white mb-3">
        Page not found
      </h1>
      <p className="text-slate-400 max-w-sm mb-10">
        The page you're looking for doesn't exist or may have been moved.
      </p>
      <div className="flex items-center gap-4">
        <Link
          to="/"
          search={APP_OVERVIEW_SEARCH}
          className="px-5 py-2.5 text-sm font-medium text-white bg-sky-500 hover:bg-sky-600 rounded-lg transition-colors"
        >
          Go to overview
        </Link>
      </div>
    </div>
  )
}

function RootComponent() {
  const router = useRouterState()
  const navigate = useNavigate()
  const currentPath = router.location.pathname
  const [isAuthenticated, setIsAuthenticated] = useState(api.isAuthenticated())
  const [isSidebarExpanded, setIsSidebarExpanded] = useState(() => {
    if (typeof window === 'undefined') return true
    const saved = localStorage.getItem('moneat:sidebar-expanded')
    if (saved !== null) return saved === 'true'
    // Desktop: expanded by default; mobile: collapsed by default
    return window.matchMedia('(min-width: 768px)').matches
  })
  const handleSidebarExpandedChange = useCallback((expanded: boolean) => {
    setIsSidebarExpanded(expanded)
    localStorage.setItem('moneat:sidebar-expanded', String(expanded))
  }, [])
  const [onboardingChecked, setOnboardingChecked] = useState(false)
  const [authCheckComplete, setAuthCheckComplete] = useState(false)
  const [headerHeight, setHeaderHeight] = useState(0)
  const headerRef = useCallback((node: HTMLDivElement | null) => {
    if (!node) return
    const update = () => setHeaderHeight(node.offsetHeight)
    update()
    const observer = new ResizeObserver(update)
    observer.observe(node)
    return () => observer.disconnect()
  }, [])

  const { data: user, isFetching: isUserFetching } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => api.getCurrentUser(),
    enabled: isAuthenticated,
  })
  const demoEpochMs = user?.demoEpochMs ?? null
  const showDemoBanner = demoEpochMs !== null && !isUserFetching && isDemo()
  const isLandingPage = isPublicLandingRoute(currentPath, router.location.search)
  const isPublicRoute = isPublicAppRoute(currentPath, router.location.search)

  useEffect(() => {
    document.title = getDocumentTitle(currentPath, isLandingPage)
  }, [currentPath, isLandingPage])

  // Centralized authentication and user status check
  useEffect(() => {
    let cancelled = false

    async function checkUserStatus() {
      // Skip check on public routes and status pages
      if (isPublicRoute) {
        setOnboardingChecked(true)
        setAuthCheckComplete(true)
        return
      }

      // If session flag is not set, try to validate via cookie (cold-load case)
      if (!isAuthenticated) {
        const hasSession = await api.checkAuth()
        if (cancelled) return
        setIsAuthenticated(hasSession) // Update state based on auth check
        setAuthCheckComplete(true)
        setOnboardingChecked(true)
        if (!hasSession) {
          return
        }
      } else {
        setAuthCheckComplete(true)
        setOnboardingChecked(true)
      }
    }

    checkUserStatus()
    return () => {
      cancelled = true
    }
  }, [isAuthenticated, isPublicRoute])

  useEffect(() => {
    if (!isAuthenticated || !user) return
    if (isPublicRoute) return

    if (!user.emailVerified && currentPath !== '/verify-email-required') {
      navigate({ to: '/verify-email-required' })
      return
    }

    if (!user.onboardingCompleted && currentPath !== '/onboarding') {
      navigate({ to: '/onboarding' })
    }
  }, [currentPath, isAuthenticated, isPublicRoute, navigate, user])
  
  const showSidebar = shouldShowAuthenticatedSidebar({
    isAuthenticated,
    pathname: currentPath,
    search: router.location.search,
  })
  const sidebarWidth = isSidebarExpanded ? SIDEBAR_EXPANDED_WIDTH : SIDEBAR_COLLAPSED_WIDTH

  // Show loading state while checking auth and onboarding
  if (!authCheckComplete && !isPublicRoute) {
    return null
  }
  
  if (isAuthenticated && !onboardingChecked && !isPublicRoute) {
    return null
  }

  return (
    <div className="min-h-screen bg-background">
      {showSidebar && (
        <CommandPaletteProvider>
          {/* Fixed header: optional demo banner */}
          <div ref={headerRef} className="fixed top-0 left-0 right-0 z-50 w-full">
            <DemoBanner isDemoMode={showDemoBanner} />
          </div>
          <Sidebar
            isExpanded={isSidebarExpanded}
            onExpandedChange={handleSidebarExpandedChange}
            headerHeight={headerHeight}
          />
          <AuthenticatedContent sidebarWidth={sidebarWidth} headerHeight={headerHeight} />
          <CommandPalette />
          <AiFloatingPanel />
        </CommandPaletteProvider>
      )}
      {!showSidebar && (
        <div
          className="transition-[margin-left] duration-300"
          style={{ marginLeft: 0 }}
        >
          {isAuthenticated && !isPublicRoute && <DemoBanner isDemoMode={showDemoBanner} />}
          <Outlet />
        </div>
      )}
      <Toaster />
    </div>
  )
}

function AuthenticatedContent({
  sidebarWidth,
  headerHeight,
}: {
  sidebarWidth: number
  headerHeight: number
}) {
  const palette = useCommandPalette()
  const aiPanelMode = palette?.aiPanelMode ?? 'dialog'

  if (aiPanelMode === 'split') {
    // Use position:fixed so the container fills exactly the space below the header
    // with no margin/calc rounding errors or body-scroll bleed
    return (
      <AiSplitPanel
        style={{
          position: 'fixed',
          top: headerHeight,
          left: sidebarWidth,
          right: 0,
          bottom: 0,
          '--header-height': `${headerHeight}px`,
          '--sidebar-width': `${sidebarWidth}px`,
        } as React.CSSProperties}
        className="transition-[left,top] duration-300"
      >
        <Outlet />
      </AiSplitPanel>
    )
  }

  // Fixed region below the header and right of the sidebar (mirrors the split-panel
  // branch above). This gives in-flow pages a definite height, so an ExplorerShell —
  // or any `h-full` page — reaches the bottom of the viewport; taller content scrolls
  // within this region instead of the body.
  return (
    <div
      className="z-0 overflow-y-auto transition-[left,top] duration-300"
      style={{
        position: 'fixed',
        top: headerHeight,
        left: sidebarWidth,
        right: 0,
        bottom: 0,
        '--header-height': `${headerHeight}px`,
        '--sidebar-width': `${sidebarWidth}px`,
      } as React.CSSProperties}
    >
      <Outlet />
    </div>
  )
}
