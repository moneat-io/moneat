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
    it('includes auth token in Authorization header when present', async () => {
      let capturedHeaders: Headers | undefined
      server.use(
        http.get(`${API_BASE}/v1/projects`, ({ request }) => {
          capturedHeaders = request.headers
          return HttpResponse.json([])
        })
      )

      localStorage.setItem('auth_token', 'test-token')
      await api.getProjects()

      expect(capturedHeaders?.get('Authorization')).toBe('Bearer test-token')
    })

    it('prioritizes impersonate_token over auth_token', async () => {
      let capturedHeaders: Headers | undefined
      server.use(
        http.get(`${API_BASE}/v1/projects`, ({ request }) => {
          capturedHeaders = request.headers
          return HttpResponse.json([])
        })
      )

      localStorage.setItem('auth_token', 'regular-token')
      sessionStorage.setItem('impersonate_token', 'admin-token')
      await api.getProjects()

      expect(capturedHeaders?.get('Authorization')).toBe('Bearer admin-token')
    })

    it('makes request without Authorization header when no token present', async () => {
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

    it('stores token in localStorage after successful login', async () => {
      server.use(
        http.post(`${API_BASE}/auth/login`, () => {
          return HttpResponse.json({
            token: 'login-token',
            user: { id: 1, email: 'test@example.com', emailVerified: true, onboardingCompleted: true },
          })
        })
      )

      await api.login('test@example.com', 'password')

      expect(localStorage.getItem('auth_token')).toBe('login-token')
    })

    it('stores token in localStorage after successful signup', async () => {
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

      expect(localStorage.getItem('auth_token')).toBe('signup-token')
    })
  })

  describe('401 logout and redirect behavior', () => {
    it('clears tokens and redirects to /login on 401', async () => {
      const originalLocation = window.location
      delete (window as any).location
      window.location = { ...originalLocation, assign: vi.fn() }

      localStorage.setItem('auth_token', 'expired-token')
      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return new HttpResponse(null, { status: 401 })
        })
      )

      await expect(api.getProjects()).rejects.toThrow('Unauthorized')

      expect(localStorage.getItem('auth_token')).toBeNull()
      expect(window.location.assign).toHaveBeenCalledWith('/login')

      window.location = originalLocation
    })

    it('does not redirect if already on auth page', async () => {
      const originalLocation = window.location
      delete (window as any).location
      window.location = { ...originalLocation, pathname: '/login', assign: vi.fn() }

      localStorage.setItem('auth_token', 'expired-token')
      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return new HttpResponse(null, { status: 401 })
        })
      )

      await expect(api.getProjects()).rejects.toThrow('Unauthorized')

      expect(window.location.assign).not.toHaveBeenCalled()

      window.location = originalLocation
    })

    it('does not redirect on 401 without token', async () => {
      const originalLocation = window.location
      delete (window as any).location
      window.location = { ...originalLocation, assign: vi.fn() }

      server.use(
        http.post(`${API_BASE}/auth/login`, () => {
          return new HttpResponse(null, { status: 401 })
        })
      )

      await expect(api.login('bad@example.com', 'wrong')).rejects.toThrow()

      expect(window.location.assign).not.toHaveBeenCalled()

      window.location = originalLocation
    })

    it('clears both localStorage and sessionStorage on logout', () => {
      localStorage.setItem('auth_token', 'regular-token')
      sessionStorage.setItem('impersonate_token', 'admin-token')

      api.logout()

      expect(localStorage.getItem('auth_token')).toBeNull()
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

      localStorage.setItem('auth_token', 'test-token')
      const result = await api.deleteProject(1)

      expect(result).toBeUndefined()
    })
  })

  describe('Authentication state', () => {
    it('isAuthenticated returns true when auth_token exists', () => {
      localStorage.setItem('auth_token', 'test-token')
      expect(api.isAuthenticated()).toBe(true)
    })

    it('isAuthenticated returns true when impersonate_token exists', () => {
      sessionStorage.setItem('impersonate_token', 'admin-token')
      expect(api.isAuthenticated()).toBe(true)
    })

    it('isAuthenticated returns false when no tokens exist', () => {
      expect(api.isAuthenticated()).toBe(false)
    })
  })
})
