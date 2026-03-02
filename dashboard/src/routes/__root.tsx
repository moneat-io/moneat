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

import {createRootRoute, Outlet, useRouterState, useNavigate} from '@tanstack/react-router'
import {useCallback, useEffect, useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {Sidebar, SIDEBAR_COLLAPSED_WIDTH, SIDEBAR_EXPANDED_WIDTH} from '../components/sidebar'
import {CommandPalette} from '../components/CommandPalette'
import {AppTopBar, TOPBAR_HEIGHT} from '../components/AppTopBar'
import {CommandPaletteProvider} from '../contexts/command-palette-context'
import {Toaster} from '../components/ui/toaster'
import {api} from '../lib/api'
import {setDemoEpoch} from '../lib/demo'
import {DemoBanner} from '../components/demo/DemoBanner'
import {AiFloatingPanel} from '../components/AiFloatingPanel'
import {AiSplitPanel} from '../components/AiSplitPanel'
import {useCommandPalette} from '../hooks/useCommandPalette'

export const Route = createRootRoute({
  component: RootComponent,
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
  '/projects': 'Projects',
  '/feedback': 'Feedback',
  '/performance': 'Performance',
  '/releases': 'Releases',
  '/replays': 'Session Replays',
  '/logs': 'Logs',
  '/monitoring': 'Infrastructure Monitoring',
  '/monitoring/hosts': 'Hosts',
  '/monitoring/containers': 'Containers',
  '/monitoring/processes': 'Processes',
  '/monitoring/network': 'Network Connections',
  '/monitoring/events': 'Events',
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
  '/demo',
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
  const [isAuthenticated, setIsAuthenticated] = useState(api.isAuthenticated())
  const [isSidebarExpanded, setIsSidebarExpanded] = useState(false)
  const [onboardingChecked, setOnboardingChecked] = useState(false)
  const [authCheckComplete, setAuthCheckComplete] = useState(false)
  const [headerHeight, setHeaderHeight] = useState(TOPBAR_HEIGHT)
  const headerRef = useCallback((node: HTMLDivElement | null) => {
    if (!node) return
    const update = () => setHeaderHeight(node.offsetHeight)
    update()
    const observer = new ResizeObserver(update)
    observer.observe(node)
    return () => observer.disconnect()
  }, [])

  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => api.getCurrentUser(),
    enabled: isAuthenticated,
  })

  // Initialize demo mode when user data is loaded
  useEffect(() => {
    if (user?.demoEpochMs) {
      setDemoEpoch(user.demoEpochMs)
    }
  }, [user?.demoEpochMs])

  useEffect(() => {
    document.title = getDocumentTitle(currentPath, isAuthenticated)
  }, [currentPath, isAuthenticated])

  // Centralized authentication and user status check
  useEffect(() => {
    let cancelled = false

    async function checkUserStatus() {
      // Skip check on public routes and status pages
      if (PUBLIC_ROUTES.has(currentPath) || currentPath.startsWith('/s/') || currentPath.startsWith('/auth/') || currentPath.startsWith('/legal/') || currentPath.startsWith('/docs')) {
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
  }, [isAuthenticated, currentPath, navigate])

  useEffect(() => {
    if (!isAuthenticated || !user) return
    if (
      PUBLIC_ROUTES.has(currentPath) ||
      currentPath.startsWith('/s/') ||
      currentPath.startsWith('/auth/') ||
      currentPath.startsWith('/legal/') ||
      currentPath.startsWith('/docs')
    ) {
      return
    }

    if (!user.emailVerified && currentPath !== '/verify-email-required') {
      navigate({ to: '/verify-email-required' })
      return
    }

    if (!user.onboardingCompleted && currentPath !== '/onboarding') {
      navigate({ to: '/onboarding' })
    }
  }, [currentPath, isAuthenticated, navigate, user])
  
  // Don't show sidebar on auth pages, landing page (when logged out), or public status pages
  const isAuthPage = ['/login', '/signup', '/verify-email', '/verify-email-required', '/forgot-password', '/reset-password', '/onboarding'].includes(currentPath)
  const isLandingPage = currentPath === '/' && !isAuthenticated
  const isPublicStatusPage = currentPath.startsWith('/s/')
  const showSidebar = isAuthenticated && !isAuthPage && !isLandingPage && !isPublicStatusPage
  const sidebarWidth = isSidebarExpanded ? SIDEBAR_EXPANDED_WIDTH : SIDEBAR_COLLAPSED_WIDTH

  // Show loading state while checking auth and onboarding
  const isPublicRoute = PUBLIC_ROUTES.has(currentPath) || currentPath.startsWith('/s/') || currentPath.startsWith('/auth/') || currentPath.startsWith('/legal/') || currentPath.startsWith('/docs')
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
          {/* Fixed header: optional demo banner + top bar */}
          <div ref={headerRef} className="fixed top-0 left-0 right-0 z-50">
            <DemoBanner />
            <AppTopBar sidebarWidth={sidebarWidth} isSidebarExpanded={isSidebarExpanded} />
          </div>
          <Sidebar
            isExpanded={isSidebarExpanded}
            onExpandedChange={setIsSidebarExpanded}
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
          <DemoBanner />
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
        }}
        className="transition-[left,top] duration-300"
      >
        <Outlet />
      </AiSplitPanel>
    )
  }

  return (
    <div
      className="transition-[margin-left] duration-300"
      style={{marginLeft: sidebarWidth, marginTop: headerHeight}}
    >
      <Outlet />
    </div>
  )
}
