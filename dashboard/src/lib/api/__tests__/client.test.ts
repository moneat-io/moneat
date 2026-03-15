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
import { resetAuthRedirectForTesting } from '@/lib/api/client'

const API_BASE = 'http://localhost:8080'

describe('client', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
    resetAuthRedirectForTesting.current?.()
  })

  // ──── request() basic flow ────

  describe('request() basic flow', () => {
    it('makes a GET request and returns parsed JSON', async () => {
      const data = { items: [1, 2, 3] }
      server.use(
        http.get(`${API_BASE}/v1/test-endpoint`, () => HttpResponse.json(data))
      )

      const result = await api.get('/v1/test-endpoint')
      expect(result).toEqual(data)
    })
  })

  // ──── request() 204 response ────

  describe('request() 204 response', () => {
    it('returns undefined for 204 No Content', async () => {
      server.use(
        http.get(`${API_BASE}/v1/no-content`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )

      const result = await api.get(`${API_BASE}/v1/no-content`)
      expect(result).toBeUndefined()
    })
  })

  // ──── request() non-ok response ────

  describe('request() non-ok response', () => {
    it('throws error with message from response body', async () => {
      server.use(
        http.get(`${API_BASE}/v1/fail`, () =>
          HttpResponse.json({ error: 'Something went wrong' }, { status: 400 })
        )
      )

      await expect(api.get(`${API_BASE}/v1/fail`)).rejects.toThrow('Something went wrong')
    })
  })

  // ──── request() non-ok with no JSON body ────

  describe('request() non-ok with no JSON body', () => {
    it('throws error with status text when no JSON body', async () => {
      server.use(
        http.get(`${API_BASE}/v1/fail-text`, () =>
          new HttpResponse('Server Error', {
            status: 500,
            statusText: 'Internal Server Error',
          })
        )
      )

      await expect(api.get(`${API_BASE}/v1/fail-text`)).rejects.toThrow(
        /API Error: 500/
      )
    })
  })

  // ──── request() network error ────

  describe('request() network error', () => {
    it('throws NETWORK_ERROR on fetch failure', async () => {
      server.use(
        http.get(`${API_BASE}/v1/network-fail`, () => HttpResponse.error())
      )

      await expect(api.get(`${API_BASE}/v1/network-fail`)).rejects.toThrow('NETWORK_ERROR')
    })
  })

  // ──── request() with impersonate token ────

  describe('request() with impersonate token', () => {
    it('sends Authorization header when impersonate_token is set', async () => {
      sessionStorage.setItem('impersonate_token', 'test-token')
      server.use(
        http.get(`${API_BASE}/v1/protected`, ({ request }) => {
          expect(request.headers.get('Authorization')).toBe('Bearer test-token')
          return HttpResponse.json({ ok: true })
        })
      )

      const result = await api.get(`${API_BASE}/v1/protected`)
      expect(result).toEqual({ ok: true })
    })
  })

  // ──── get() with absolute URL ────

  describe('get() with absolute URL', () => {
    it('uses absolute URL directly', async () => {
      server.use(
        http.get(`${API_BASE}/v1/absolute`, () =>
          HttpResponse.json({ source: 'absolute' })
        )
      )

      const result = await api.get(`${API_BASE}/v1/absolute`)
      expect(result).toEqual({ source: 'absolute' })
    })
  })

  // ──── get() with relative URL ────

  describe('get() with relative URL starting with /', () => {
    it('prepends API_BASE to relative URL', async () => {
      server.use(
        http.get(`${API_BASE}/v1/relative`, () =>
          HttpResponse.json({ source: 'relative' })
        )
      )

      const result = await api.get('/v1/relative')
      expect(result).toEqual({ source: 'relative' })
    })
  })

  // ──── checkAuth() success ────

  describe('checkAuth() success', () => {
    it('returns true and sets authenticated flag on 200', async () => {
      sessionStorage.removeItem('authenticated')
      server.use(
        http.get(`${API_BASE}/v1/user`, () =>
          HttpResponse.json({ id: 1, email: 'a@b.com' })
        )
      )

      const result = await api.checkAuth()
      expect(result).toBe(true)
      expect(sessionStorage.getItem('authenticated')).toBe('true')
    })
  })

  // ──── checkAuth() 401 ────

  describe('checkAuth() 401', () => {
    it('returns false and removes authenticated flag on 401', async () => {
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

  // ──── checkAuth() network error ────

  describe('checkAuth() network error', () => {
    it('returns false on network error', async () => {
      server.use(
        http.get(`${API_BASE}/v1/user`, () => HttpResponse.error())
      )

      const result = await api.checkAuth()
      expect(result).toBe(false)
    })
  })

  // ──── isAuthenticated() ────

  describe('isAuthenticated()', () => {
    it('returns true when sessionStorage has authenticated = true', () => {
      expect(api.isAuthenticated()).toBe(true)
    })

    it('returns false when authenticated flag is missing', () => {
      sessionStorage.clear()
      expect(api.isAuthenticated()).toBe(false)
    })
  })

  // ──── isAuthenticated() with impersonate token ────

  describe('isAuthenticated() with impersonate token', () => {
    it('returns true when impersonate_token is set', () => {
      sessionStorage.clear()
      sessionStorage.setItem('impersonate_token', 'test-token')
      expect(api.isAuthenticated()).toBe(true)
    })
  })

  // ──── logout() ────

  describe('logout()', () => {
    it('clears sessionStorage items and calls /auth/logout', async () => {
      sessionStorage.setItem('impersonate_token', 'imp-token')
      localStorage.setItem('selectedProjectId', '42')
      let logoutCalled = false
      server.use(
        http.post(`${API_BASE}/auth/logout`, () => {
          logoutCalled = true
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.logout()
      expect(sessionStorage.getItem('authenticated')).toBeNull()
      expect(sessionStorage.getItem('impersonate_token')).toBeNull()
      expect(localStorage.getItem('selectedProjectId')).toBeNull()
      expect(logoutCalled).toBe(true)
    })
  })
})
