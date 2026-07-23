import {render, waitFor} from '@testing-library/react'
import type {ComponentType} from 'react'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {storeMobileAuthCallback} from '@/lib/mobile-auth'

const {mockApi, mockNavigate} = vi.hoisted(() => ({
  mockApi: {
    createMobileSession: vi.fn(),
  },
  mockNavigate: vi.fn(),
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({options}),
  useNavigate: () => mockNavigate,
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@/components/Logo', () => ({
  Logo: () => <div />,
}))

import {Route as OAuthCallbackRoute} from '../auth.oauth.callback'
import {Route as SsoCallbackRoute} from '../auth.sso.callback'

const originalLocation = window.location
const assign = vi.fn()

describe('mobile provider callbacks', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.sessionStorage.clear()
    mockApi.createMobileSession.mockResolvedValue({
      token: 'mobile-access-token',
      refreshToken: 'mobile-refresh-token',
    })
    Object.defineProperty(window, 'location', {
      value: {...originalLocation, assign} as Location,
      writable: true,
      configurable: true,
    })
  })

  afterEach(() => {
    Object.defineProperty(window, 'location', {
      value: originalLocation,
      writable: true,
      configurable: true,
    })
  })

  it('returns GitHub authentication to the native app', async () => {
    storeMobileAuthCallback('moneat://auth')
    const OAuthCallbackPage = OAuthCallbackRoute.options.component as ComponentType

    render(<OAuthCallbackPage />)

    await waitFor(() => {
      expect(mockApi.createMobileSession).toHaveBeenCalledTimes(1)
      expect(assign).toHaveBeenCalledWith(
        'moneat://auth#token=mobile-access-token&refreshToken=mobile-refresh-token'
      )
    })
  })

  it('returns SSO authentication to the native app', async () => {
    storeMobileAuthCallback('moneat://auth')
    const SsoCallbackPage = SsoCallbackRoute.options.component as ComponentType

    render(<SsoCallbackPage />)

    await waitFor(() => {
      expect(mockApi.createMobileSession).toHaveBeenCalledTimes(1)
      expect(assign).toHaveBeenCalledWith(
        'moneat://auth#token=mobile-access-token&refreshToken=mobile-refresh-token'
      )
    })
  })
})
