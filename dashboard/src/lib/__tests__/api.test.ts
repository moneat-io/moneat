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

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api, resetAuthRedirectForTesting } from '../api'

const API_BASE = 'http://localhost:8080'

function mockWindowLocation(location: Partial<Location> & Pick<Location, 'pathname'>): () => void {
  const originalLocation = window.location
  Object.defineProperty(window, 'location', {
    value: {...originalLocation, ...location} as Location,
    writable: true,
    configurable: true,
  })
  return () => {
    Object.defineProperty(window, 'location', {
      value: originalLocation,
      writable: true,
      configurable: true,
    })
  }
}

describe('ApiClient', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    const reset = resetAuthRedirectForTesting.current
    if (typeof reset !== 'function') {
      throw new TypeError('resetAuthRedirectForTesting.current is not a function')
    }
    reset()
  })

  describe('Auth token handling', () => {
    it('sends requests with credentials include for cookie-based auth', async () => {
      let capturedInit: RequestInit | undefined
      const originalFetch = global.fetch
      global.fetch = vi.fn(async (_url: string | URL | Request, init?: RequestInit) => {
        capturedInit = init
        return new Response(JSON.stringify([]), { status: 200 })
      }) as typeof fetch

      await api.getProjects()

      expect(capturedInit?.credentials).toBe('include')
      global.fetch = originalFetch
    })

    it('includes impersonate_token in Authorization header when present', async () => {
      let capturedHeaders: Headers | undefined
      server.use(
        http.get(`${API_BASE}/v1/projects`, ({ request }) => {
          capturedHeaders = request.headers
          return HttpResponse.json([])
        })
      )

      sessionStorage.setItem('impersonate_token', 'admin-token')
      await api.getProjects()

      expect(capturedHeaders?.get('Authorization')).toBe('Bearer admin-token')
    })

    it('makes request without Authorization header when no impersonate token present', async () => {
      let capturedHeaders: Headers | undefined
      server.use(
        http.post(`${API_BASE}/auth/login`, ({ request }) => {
          capturedHeaders = request.headers
          return HttpResponse.json({
            token: 'new-token',
            user: { id: 1, email: 'test@example.com', emailVerified: true, onboardingCompleted: true },
          })
        })
      )

      await api.login('test@example.com', 'password')

      expect(capturedHeaders?.get('Authorization')).toBeNull()
    })

    it('sets authenticated session flag after successful login', async () => {
      server.use(
        http.post(`${API_BASE}/auth/login`, () => {
          return HttpResponse.json({
            token: 'login-token',
            user: { id: 1, email: 'test@example.com', emailVerified: true, onboardingCompleted: true },
          })
        })
      )

      await api.login('test@example.com', 'password')

      expect(sessionStorage.getItem('authenticated')).toBe('true')
    })

    it('sets authenticated session flag after successful signup', async () => {
      server.use(
        http.post(`${API_BASE}/auth/signup`, () => {
          return HttpResponse.json({
            token: 'signup-token',
            user: { id: 1, email: 'test@example.com', emailVerified: false, onboardingCompleted: false },
          })
        })
      )

      const consent = {
        acceptTerms: true,
        acceptPrivacy: true,
        termsVersion: '1.0',
        privacyVersion: '1.0',
      }
      await api.signup('test@example.com', 'password', 'Test User', consent)

      expect(sessionStorage.getItem('authenticated')).toBe('true')
    })
  })

  describe('401 logout and redirect behavior', () => {
    it('clears session and redirects to login on 401', async () => {
      const mockAssign = vi.fn()
      const restoreLocation = mockWindowLocation({
        pathname: '/issues/issue-123',
        search: '?projectId=service-1&status=unresolved',
        hash: '#events',
        assign: mockAssign,
      })

      sessionStorage.setItem('authenticated', 'true')
      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return new HttpResponse(null, { status: 401 })
        })
      )

      await expect(api.getProjects()).rejects.toThrow('Unauthorized')

      expect(sessionStorage.getItem('authenticated')).toBeNull()
      expect(mockAssign).toHaveBeenCalledWith('/login')

      restoreLocation()
    })

    it('attempts token refresh at most once for persistent 401 responses', async () => {
      const mockAssign = vi.fn()
      const restoreLocation = mockWindowLocation({
        pathname: '/admin/infrastructure',
        search: '',
        hash: '',
        assign: mockAssign,
      })

      sessionStorage.setItem('authenticated', 'true')
      localStorage.setItem('refresh_token', 'refresh-token-1')

      let projectCalls = 0
      let refreshCalls = 0

      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          projectCalls += 1
          return new HttpResponse(null, { status: 401 })
        }),
        http.post(`${API_BASE}/auth/refresh`, () => {
          refreshCalls += 1
          return HttpResponse.json({
            token: 'new-token',
            refreshToken: 'refresh-token-2',
            user: { id: 1, email: 'test@example.com', emailVerified: true, onboardingCompleted: true },
          })
        })
      )

      await expect(api.getProjects()).rejects.toThrow('Unauthorized')
      expect(projectCalls).toBe(2)
      expect(refreshCalls).toBe(1)
      expect(mockAssign).toHaveBeenCalledWith('/login')

      restoreLocation()
    })

    it('does not redirect if already on auth page', async () => {
      const mockAssign = vi.fn()
      const restoreLocation = mockWindowLocation({pathname: '/login', assign: mockAssign})

      sessionStorage.setItem('authenticated', 'true')
      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return new HttpResponse(null, { status: 401 })
        })
      )

      await expect(api.getProjects()).rejects.toThrow('Unauthorized')

      expect(mockAssign).not.toHaveBeenCalled()

      restoreLocation()
    })

    it('does not redirect from the public landing page on 401', async () => {
      const mockAssign = vi.fn()
      const restoreLocation = mockWindowLocation({pathname: '/', search: '', hash: '', assign: mockAssign})

      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return new HttpResponse(null, { status: 401 })
        })
      )

      await expect(api.getProjects()).rejects.toThrow('Unauthorized')

      expect(mockAssign).not.toHaveBeenCalled()

      restoreLocation()
    })

    it('redirects from the authenticated overview on 401', async () => {
      const mockAssign = vi.fn()
      const restoreLocation = mockWindowLocation({
        pathname: '/',
        search: '?view=overview',
        hash: '',
        assign: mockAssign,
      })

      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return new HttpResponse(null, { status: 401 })
        })
      )

      await expect(api.getProjects()).rejects.toThrow('Unauthorized')

      expect(mockAssign).toHaveBeenCalledWith('/login')

      restoreLocation()
    })

    it('redirects on 401 even without session flag', async () => {
      const mockAssign = vi.fn()
      const restoreLocation = mockWindowLocation({
        pathname: '/dashboard',
        search: '',
        hash: '',
        assign: mockAssign,
      })

      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return new HttpResponse(null, { status: 401 })
        })
      )

      await expect(api.getProjects()).rejects.toThrow('Unauthorized')

      expect(mockAssign).toHaveBeenCalledWith('/login')

      restoreLocation()
    })

    it('clears sessionStorage on logout', () => {
      sessionStorage.setItem('authenticated', 'true')
      sessionStorage.setItem('impersonate_token', 'admin-token')

      api.logout()

      expect(sessionStorage.getItem('authenticated')).toBeNull()
      expect(sessionStorage.getItem('impersonate_token')).toBeNull()
    })
  })

  describe('Error normalization', () => {
    it('extracts error message from API error response', async () => {
      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return HttpResponse.json({ error: 'Project not found' }, { status: 404 })
        })
      )

      await expect(api.getProjects()).rejects.toThrow('Project not found')
    })

    it('preserves response status on API errors for retry policy decisions', async () => {
      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return HttpResponse.json({ error: 'Project not found' }, { status: 404 })
        })
      )

      await expect(api.getProjects()).rejects.toMatchObject({ status: 404 })
    })

    it('falls back to status text when no error field in response', async () => {
      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return new HttpResponse('Bad Request', { status: 400 })
        })
      )

      await expect(api.getProjects()).rejects.toThrow('API Error: 400 Bad Request')
    })

    it('normalizes network errors to NETWORK_ERROR', async () => {
      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return HttpResponse.error()
        })
      )

      await expect(api.getProjects()).rejects.toThrow('NETWORK_ERROR')
    })

    it('handles 204 No Content responses', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/projects/proj-1`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      sessionStorage.setItem('authenticated', 'true')
      const result = await api.deleteProject('proj-1')

      expect(result).toBeUndefined()
    })
  })

  describe('Authentication state', () => {
    it('isAuthenticated returns true when session flag is set', () => {
      sessionStorage.setItem('authenticated', 'true')
      expect(api.isAuthenticated()).toBe(true)
    })

    it('isAuthenticated returns true when impersonate_token exists', () => {
      sessionStorage.setItem('impersonate_token', 'admin-token')
      expect(api.isAuthenticated()).toBe(true)
    })

    it('isAuthenticated returns false when no session state exists', () => {
      expect(api.isAuthenticated()).toBe(false)
    })
  })
})
