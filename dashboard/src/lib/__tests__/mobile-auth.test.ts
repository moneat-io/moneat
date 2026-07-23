import {beforeEach, describe, expect, it} from 'vitest'
import {
  consumeMobileAuthCallback,
  mobileAuthCallbackUrl,
  normalizeMobileAuthCallback,
  storeMobileAuthCallback,
} from '../mobile-auth'

describe('mobile auth callback state', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('allows only the registered native callback', () => {
    expect(normalizeMobileAuthCallback('moneat://auth')).toBe('moneat://auth')
    expect(normalizeMobileAuthCallback('moneat://settings')).toBeUndefined()
    expect(normalizeMobileAuthCallback('https://evil.example/callback')).toBeUndefined()
  })

  it('stores and consumes a validated callback once', () => {
    storeMobileAuthCallback('moneat://auth')

    expect(consumeMobileAuthCallback()).toBe('moneat://auth')
    expect(consumeMobileAuthCallback()).toBeUndefined()
  })

  it('returns tokens in the native callback fragment', () => {
    expect(
      mobileAuthCallbackUrl('moneat://auth', 'access token', 'refresh/token')
    ).toBe('moneat://auth#token=access+token&refreshToken=refresh%2Ftoken')
  })
})
