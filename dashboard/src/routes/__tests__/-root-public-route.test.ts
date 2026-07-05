import {beforeEach, describe, expect, it, vi} from 'vitest'
import {APP_OVERVIEW_SEARCH} from '@/lib/overview-route'
import {consumePendingAuthRedirect} from '@/lib/auth-redirect'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    isAuthenticated: vi.fn(),
    checkAuth: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    options,
    useNavigate: () => vi.fn(),
    useParams: () => ({}),
    useSearch: () => ({}),
  }),
  createRootRoute: (options: Record<string, unknown>) => ({...options, options}),
  Link: ({children}: {children: unknown}) => children,
  Outlet: () => null,
  redirect: (options: Record<string, unknown>) => ({...options, __redirect: true}),
  useNavigate: () => vi.fn(),
  useRouterState: () => ({location: {pathname: '/', search: {}, href: '/'}}),
}))

import {
  ensurePrivateRouteCanLoad,
  isAuthOptionalAppRoute,
  isPublicAppRoute,
  shouldShowAuthenticatedSidebar,
} from '../__root'

function routeLocation(pathname: string, href = pathname, search: unknown = {}) {
  return {pathname, href, search}
}

describe('root public route shell visibility', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.isAuthenticated.mockReturnValue(false)
    mockApi.checkAuth.mockResolvedValue(false)
  })

  it.each([
    ['landing page', '/', {}],
    ['docs', '/docs/getting-started', {}],
    ['blog', '/blog/keep-your-existing-sdks', {}],
    ['pricing calculator', '/pricing-calculator', {}],
  ])('treats %s as a public app route', (_label, pathname, search) => {
    expect(isPublicAppRoute(pathname, search)).toBe(true)
  })

  it('keeps the authenticated overview in the app shell', () => {
    expect(isPublicAppRoute('/', APP_OVERVIEW_SEARCH)).toBe(false)
    expect(isAuthOptionalAppRoute('/', APP_OVERVIEW_SEARCH)).toBe(false)
  })

  it('distinguishes shell-public authenticated routes from auth-optional routes', () => {
    expect(isPublicAppRoute('/onboarding', {})).toBe(true)
    expect(isAuthOptionalAppRoute('/onboarding', {})).toBe(false)
    expect(isPublicAppRoute('/verify-email-required', {})).toBe(true)
    expect(isAuthOptionalAppRoute('/verify-email-required', {})).toBe(false)
    expect(isAuthOptionalAppRoute('/login', {})).toBe(true)
  })

  it('does not show the authenticated sidebar on public routes', () => {
    expect(
      shouldShowAuthenticatedSidebar({
        isAuthenticated: true,
        pathname: '/docs/getting-started',
        search: {},
      })
    ).toBe(false)
  })

  it('shows the authenticated sidebar on private routes', () => {
    expect(
      shouldShowAuthenticatedSidebar({
        isAuthenticated: true,
        pathname: '/issues',
        search: {},
      })
    ).toBe(true)
  })

  it('redirects unauthenticated private deep links to login with the attempted route', async () => {
    const attemptedRoute = '/issues/issue-123?status=unresolved#events'

    await expect(
      ensurePrivateRouteCanLoad(routeLocation('/issues/issue-123', attemptedRoute, {status: 'unresolved'}))
    ).rejects.toMatchObject({
      __redirect: true,
      to: '/login',
    })
    expect(mockApi.checkAuth).toHaveBeenCalledOnce()
    expect(consumePendingAuthRedirect()).toBe(attemptedRoute)
  })

  it('redirects unauthenticated overview loads to login with the attempted route', async () => {
    await expect(
      ensurePrivateRouteCanLoad(routeLocation('/', '/?view=overview', APP_OVERVIEW_SEARCH))
    ).rejects.toMatchObject({
      __redirect: true,
      to: '/login',
    })
    expect(mockApi.checkAuth).toHaveBeenCalledOnce()
    expect(consumePendingAuthRedirect()).toBe('/?view=overview')
  })

  it.each([
    ['/onboarding'],
    ['/verify-email-required'],
  ])('redirects unauthenticated shell-public auth-required route %s', async (pathname) => {
    await expect(ensurePrivateRouteCanLoad(routeLocation(pathname))).rejects.toMatchObject({
      __redirect: true,
      to: '/login',
    })
    expect(mockApi.checkAuth).toHaveBeenCalledOnce()
    expect(consumePendingAuthRedirect()).toBe(pathname)
  })

  it('allows private routes when the session flag is already present', async () => {
    mockApi.isAuthenticated.mockReturnValue(true)

    await expect(ensurePrivateRouteCanLoad(routeLocation('/replays/replay-1'))).resolves.toBeUndefined()
    expect(mockApi.checkAuth).not.toHaveBeenCalled()
  })

  it('allows private routes when the auth cookie validates on cold load', async () => {
    mockApi.checkAuth.mockResolvedValue(true)

    await expect(ensurePrivateRouteCanLoad(routeLocation('/logs'))).resolves.toBeUndefined()
    expect(mockApi.checkAuth).toHaveBeenCalledOnce()
  })

  it('skips auth checks for auth-optional routes', async () => {
    await expect(ensurePrivateRouteCanLoad(routeLocation('/docs/getting-started'))).resolves.toBeUndefined()
    expect(mockApi.checkAuth).not.toHaveBeenCalled()
  })
})
