import { describe, it, expect, beforeEach, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '../api'

const API_BASE = 'http://localhost:8080'

describe('ApiClient', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  describe('Auth token handling', () => {
    it('sends requests with credentials include for cookie-based auth', async () => {
      let capturedInit: RequestInit | undefined
      const originalFetch = global.fetch
      global.fetch = vi.fn(async (_url: any, init?: RequestInit) => {
        capturedInit = init
        return new Response(JSON.stringify([]), { status: 200 })
      }) as any

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
    it('clears session and redirects to /login on 401', async () => {
      const mockAssign = vi.fn()
      const originalLocation = window.location
      // @ts-expect-error - Mocking window.location for tests
      delete window.location
      // @ts-expect-error - Mocking window.location for tests
      window.location = { ...originalLocation, assign: mockAssign }

      sessionStorage.setItem('authenticated', 'true')
      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return new HttpResponse(null, { status: 401 })
        })
      )

      await expect(api.getProjects()).rejects.toThrow('Unauthorized')

      expect(sessionStorage.getItem('authenticated')).toBeNull()
      expect(mockAssign).toHaveBeenCalledWith('/login')

      // @ts-expect-error - Restoring window.location
      window.location = originalLocation
    })

    it('does not redirect if already on auth page', async () => {
      const mockAssign = vi.fn()
      const originalLocation = window.location
      // @ts-expect-error - Mocking window.location for tests
      delete window.location
      // @ts-expect-error - Mocking window.location for tests
      window.location = { ...originalLocation, pathname: '/login', assign: mockAssign }

      sessionStorage.setItem('authenticated', 'true')
      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return new HttpResponse(null, { status: 401 })
        })
      )

      await expect(api.getProjects()).rejects.toThrow('Unauthorized')

      expect(mockAssign).not.toHaveBeenCalled()

      // @ts-expect-error - Restoring window.location
      window.location = originalLocation
    })

    it('redirects on 401 even without session flag', async () => {
      const mockAssign = vi.fn()
      const originalLocation = window.location
      // @ts-expect-error - Mocking window.location for tests
      delete window.location
      // @ts-expect-error - Mocking window.location for tests
      window.location = { ...originalLocation, assign: mockAssign }

      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return new HttpResponse(null, { status: 401 })
        })
      )

      await expect(api.getProjects()).rejects.toThrow('Unauthorized')

      expect(mockAssign).toHaveBeenCalledWith('/login')

      // @ts-expect-error - Restoring window.location
      window.location = originalLocation
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
        http.delete(`${API_BASE}/v1/projects/1`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      sessionStorage.setItem('authenticated', 'true')
      const result = await api.deleteProject(1)

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
