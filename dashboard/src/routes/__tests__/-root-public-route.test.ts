import {describe, expect, it} from 'vitest'
import {APP_OVERVIEW_SEARCH} from '@/lib/overview-route'
import {isPublicAppRoute, shouldShowAuthenticatedSidebar} from '../__root'

describe('root public route shell visibility', () => {
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
})
