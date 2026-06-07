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

import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '@/lib/api'

const API_BASE = 'http://localhost:8080'

// Test-only credentials – not used in production
const TEST_PASSWORD = 'pass123'
const TEST_PASSWORD_BOB = 'pass456'
const TEST_PASSWORD_RESET = 'newPass123'
const legalConsent = {
  acceptTerms: true,
  acceptPrivacy: true,
  termsVersion: '1.0',
  privacyVersion: '1.0',
}

describe('authMethods', () => {
  beforeEach(() => {
    globalThis.localStorage?.clear()
    globalThis.sessionStorage?.clear()
  })

  // ──── signup ────

  describe('signup', () => {
    it('signs up without invite token', async () => {
      const authResponse = { token: 'jwt-token', user: { id: 1, email: 'a@b.com' } }
      server.use(
        http.post(`${API_BASE}/auth/signup`, async ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.has('inviteToken')).toBe(false)
          const body = await request.json()
          expect(body).toEqual({
            email: 'a@b.com',
            password: TEST_PASSWORD,
            name: 'Alice',
            ...legalConsent,
          })
          return HttpResponse.json(authResponse)
        })
      )

      const result = await api.signup('a@b.com', TEST_PASSWORD, 'Alice', legalConsent)
      expect(result).toEqual(authResponse)
      expect(globalThis.sessionStorage?.getItem('authenticated')).toBe('true')
    })

    it('signs up with invite token', async () => {
      const authResponse = { token: 'jwt-token', user: { id: 2, email: 'b@c.com' } }
      server.use(
        http.post(`${API_BASE}/auth/signup`, async ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('inviteToken')).toBe('inv-123')
          const body = await request.json()
          expect(body).toEqual({
            email: 'b@c.com',
            password: TEST_PASSWORD_BOB,
            name: 'Bob',
            ...legalConsent,
          })
          return HttpResponse.json(authResponse)
        })
      )

      const result = await api.signup('b@c.com', TEST_PASSWORD_BOB, 'Bob', legalConsent, 'inv-123')
      expect(result).toEqual(authResponse)
      expect(globalThis.sessionStorage?.getItem('authenticated')).toBe('true')
    })
  })

  // ──── login ────

  describe('login', () => {
    it('logs in and sets authenticated flag', async () => {
      const authResponse = { token: 'jwt-token', user: { id: 1, email: 'a@b.com' } }
      server.use(
        http.post(`${API_BASE}/auth/login`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual({ email: 'a@b.com', password: TEST_PASSWORD })
          return HttpResponse.json(authResponse)
        })
      )

      const result = await api.login('a@b.com', TEST_PASSWORD)
      expect(result).toEqual(authResponse)
      expect(globalThis.sessionStorage?.getItem('authenticated')).toBe('true')
    })
  })

  // ──── SSO ────

  describe('initSso', () => {
    it('initiates SSO with email and orgSlug', async () => {
      const data = { redirectUrl: 'https://idp.example.com/sso', providerType: 'saml', state: 'abc' }
      server.use(
        http.post(`${API_BASE}/auth/sso/init`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual({ email: 'a@b.com', orgSlug: 'acme' })
          return HttpResponse.json(data)
        })
      )

      const result = await api.initSso('a@b.com', 'acme')
      expect(result).toEqual(data)
    })
  })

  describe('checkSsoRequired', () => {
    it('checks if SSO is required for email', async () => {
      server.use(
        http.post(`${API_BASE}/v1/sso/check-required`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual({ email: 'user@corp.com' })
          return HttpResponse.json({ required: true })
        })
      )

      const result = await api.checkSsoRequired('user@corp.com')
      expect(result).toEqual({ required: true })
    })
  })

  describe('getSsoConfig', () => {
    it('fetches SSO config for organization', async () => {
      const config = { provider: 'saml', entityId: 'urn:example', ssoUrl: 'https://idp.example.com' }
      server.use(
        http.get(`${API_BASE}/v1/sso/config`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('organizationId')).toBe('42')
          return HttpResponse.json(config)
        })
      )

      const result = await api.getSsoConfig(42)
      expect(result).toEqual(config)
    })
  })

  describe('configureSso', () => {
    it('updates SSO configuration', async () => {
      const config = { provider: 'saml', entityId: 'urn:new', ssoUrl: 'https://new-idp.example.com' }
      server.use(
        http.put(`${API_BASE}/v1/sso/config`, async ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('organizationId')).toBe('42')
          const body = await request.json()
          expect(body).toEqual(config)
          return HttpResponse.json(config)
        })
      )

      const result = await api.configureSso(42, config as never)
      expect(result).toEqual(config)
    })
  })

  describe('verifySsoDomain', () => {
    it('verifies SSO domain for organization', async () => {
      const config = {
        emailDomain: 'corp.example',
        emailDomainVerified: true,
      }
      server.use(
        http.post(`${API_BASE}/v1/sso/config/domain/verify`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('organizationId')).toBe('42')
          return HttpResponse.json(config)
        })
      )

      const result = await api.verifySsoDomain(42)
      expect(result).toEqual(config)
    })
  })

  describe('deleteSsoConfig', () => {
    it('deletes SSO configuration', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/sso/config`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('organizationId')).toBe('42')
          return new HttpResponse(null, { status: 204 })
        })
      )

      const result = await api.deleteSsoConfig(42)
      expect(result).toBeUndefined()
    })
  })

  // ──── Impersonation ────

  describe('impersonateUser', () => {
    it('impersonates a user by id', async () => {
      server.use(
        http.post(`${API_BASE}/v1/admin/impersonate/7`, () =>
          HttpResponse.json({ token: 'impersonate-jwt' })
        )
      )

      const result = await api.impersonateUser(7)
      expect(result).toEqual({ token: 'impersonate-jwt' })
    })
  })

  // ──── Onboarding ────

  describe('completeOnboarding', () => {
    it('sends onboarding data', async () => {
      const options = {
        organizationName: 'Acme',
        companySize: '10-50',
        slug: 'acme',
        referralSource: 'google',
        utmSource: 'twitter',
      }
      server.use(
        http.post(`${API_BASE}/auth/complete-onboarding`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual(options)
          return HttpResponse.json({ success: true })
        })
      )

      const result = await api.completeOnboarding(options)
      expect(result).toEqual({ success: true })
    })
  })

  // ──── Demo Login ────

  describe('demoLogin', () => {
    it('performs demo login and sets authenticated flag and demo epoch', async () => {
      const data = { token: 'demo-jwt', demoEpochMs: 1700000000000 }
      server.use(
        http.post(`${API_BASE}/auth/demo-login`, () => HttpResponse.json(data))
      )

      const result = await api.demoLogin()
      expect(result).toEqual(data)
      expect(globalThis.sessionStorage?.getItem('authenticated')).toBe('true')
      expect(globalThis.sessionStorage?.getItem('demoEpochMs')).toBe('1700000000000')
    })
  })

  // ──── Slug Availability ────

  describe('checkSlugAvailability', () => {
    it('checks slug availability', async () => {
      server.use(
        http.get(`${API_BASE}/auth/check-slug`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('slug')).toBe('my-org')
          return HttpResponse.json({ available: true })
        })
      )

      const result = await api.checkSlugAvailability('my-org')
      expect(result).toEqual({ available: true })
    })
  })

  // ──── Email Verification ────

  describe('resendVerificationEmail', () => {
    it('resends verification email', async () => {
      server.use(
        http.post(`${API_BASE}/auth/resend-verification`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual({ email: 'a@b.com' })
          return HttpResponse.json({ message: 'Sent' })
        })
      )

      const result = await api.resendVerificationEmail('a@b.com')
      expect(result).toEqual({ message: 'Sent' })
    })
  })

  describe('verifyEmail', () => {
    it('verifies email with token', async () => {
      server.use(
        http.post(`${API_BASE}/auth/verify-email`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual({ token: 'verify-token' })
          return HttpResponse.json({ message: 'Verified' })
        })
      )

      const result = await api.verifyEmail('verify-token')
      expect(result).toEqual({ message: 'Verified' })
    })
  })

  // ──── Password Reset ────

  describe('forgotPassword', () => {
    it('requests password reset', async () => {
      server.use(
        http.post(`${API_BASE}/auth/forgot-password`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual({ email: 'a@b.com' })
          return HttpResponse.json({ message: 'Email sent' })
        })
      )

      const result = await api.forgotPassword('a@b.com')
      expect(result).toEqual({ message: 'Email sent' })
    })
  })

  describe('resetPassword', () => {
    it('resets password with token', async () => {
      server.use(
        http.post(`${API_BASE}/auth/reset-password`, async ({ request }) => {
          const body = await request.json()
          expect(body).toEqual({ token: 'reset-token', newPassword: TEST_PASSWORD_RESET })
          return HttpResponse.json({ message: 'Password reset' })
        })
      )

      const result = await api.resetPassword('reset-token', TEST_PASSWORD_RESET)
      expect(result).toEqual({ message: 'Password reset' })
    })
  })

  // ──── Auth State ────

  describe('isAuthenticated', () => {
    it('returns true when authenticated flag is set', () => {
      globalThis.sessionStorage?.setItem('authenticated', 'true')
      expect(api.isAuthenticated()).toBe(true)
    })

    it('returns false when authenticated flag is not set', () => {
      globalThis.sessionStorage?.clear()
      expect(api.isAuthenticated()).toBe(false)
    })
  })

  describe('logout', () => {
    it('clears session storage and calls logout endpoint', async () => {
      globalThis.sessionStorage?.setItem('authenticated', 'true')
      server.use(
        http.post(`${API_BASE}/auth/logout`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )

      await api.logout()
      expect(globalThis.sessionStorage?.getItem('authenticated')).toBeNull()
    })
  })

  describe('checkAuth', () => {
    it('returns true when user endpoint responds 200', async () => {
      server.use(
        http.get(`${API_BASE}/v1/user`, () =>
          HttpResponse.json({ id: 1, email: 'a@b.com' })
        )
      )

      const result = await api.checkAuth()
      expect(result).toBe(true)
      expect(globalThis.sessionStorage?.getItem('authenticated')).toBe('true')
    })

    it('returns false when user endpoint responds 401', async () => {
      globalThis.sessionStorage?.setItem('authenticated', 'true')
      server.use(
        http.get(`${API_BASE}/v1/user`, () =>
          new HttpResponse(null, { status: 401 })
        ),
        http.post(`${API_BASE}/auth/refresh`, () =>
          new HttpResponse(null, { status: 401 })
        )
      )

      const result = await api.checkAuth()
      expect(result).toBe(false)
    })
  })
})
