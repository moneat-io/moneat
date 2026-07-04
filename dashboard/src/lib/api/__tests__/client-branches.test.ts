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

import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '@/lib/api'
import { resetAuthRedirectForTesting } from '@/lib/api/client'

const API_BASE = 'http://localhost:8080'

describe('client – branch coverage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
    resetAuthRedirectForTesting.current?.()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  // ──── handle401Retry – refresh succeeds ────

  describe('handle401Retry – refresh succeeds', () => {
    it('retries the request after successful token refresh', async () => {
      let callCount = 0
      server.use(
        http.get(`${API_BASE}/v1/protected-data`, () => {
          callCount++
          if (callCount === 1) {
            return new HttpResponse(null, { status: 401 })
          }
          return HttpResponse.json({ data: 'ok' })
        }),
        http.post(`${API_BASE}/auth/refresh`, () => {
          return HttpResponse.json({ success: true })
        })
      )

      const result = await api.get(`${API_BASE}/v1/protected-data`)
      expect(result).toEqual({ data: 'ok' })
      expect(callCount).toBe(2)
    })
  })

  // ──── handle401Retry – refresh fails ────

  describe('handle401Retry – refresh fails', () => {
    it('redirects to login when refresh fails', async () => {
      const assignSpy = vi.fn()
      Object.defineProperty(globalThis.window, 'location', {
        value: {
          pathname: '/issues/issue-123',
          search: '?projectId=service-1&status=unresolved',
          hash: '#events',
          assign: assignSpy,
        },
        writable: true,
        configurable: true,
      })

      server.use(
        http.get(`${API_BASE}/v1/needs-auth`, () => {
          return new HttpResponse(null, { status: 401 })
        }),
        http.post(`${API_BASE}/auth/refresh`, () => {
          return new HttpResponse(null, { status: 401 })
        }),
        http.post(`${API_BASE}/auth/logout`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await expect(api.get(`${API_BASE}/v1/needs-auth`)).rejects.toThrow(
        'Unauthorized'
      )
      expect(assignSpy).toHaveBeenCalledWith('/login')
    })
  })

  // ──── handle401Retry – authRetryCount >= 1 ────

  describe('handle401Retry – already retried once', () => {
    it('logs out immediately after second 401', async () => {
      let callCount = 0
      const assignSpy = vi.fn()
      Object.defineProperty(globalThis.window, 'location', {
        value: { pathname: '/settings', assign: assignSpy },
        writable: true,
        configurable: true,
      })

      server.use(
        http.get(`${API_BASE}/v1/always-401`, () => {
          callCount++
          return new HttpResponse(null, { status: 401 })
        }),
        http.post(`${API_BASE}/auth/refresh`, () => {
          return HttpResponse.json({ success: true })
        }),
        http.post(`${API_BASE}/auth/logout`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await expect(api.get(`${API_BASE}/v1/always-401`)).rejects.toThrow(
        'Unauthorized'
      )
      expect(callCount).toBe(2)
    })
  })

  // ──── logoutAndRedirect – on auth page ────

  describe('logoutAndRedirect – on auth page', () => {
    it('does not redirect when already on login page', async () => {
      const assignSpy = vi.fn()
      Object.defineProperty(globalThis.window, 'location', {
        value: { pathname: '/login', assign: assignSpy },
        writable: true,
        configurable: true,
      })

      server.use(
        http.get(`${API_BASE}/v1/on-auth`, () => {
          return new HttpResponse(null, { status: 401 })
        }),
        http.post(`${API_BASE}/auth/refresh`, () => {
          return new HttpResponse(null, { status: 401 })
        }),
        http.post(`${API_BASE}/auth/logout`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await expect(api.get(`${API_BASE}/v1/on-auth`)).rejects.toThrow(
        'Unauthorized'
      )
      expect(assignSpy).not.toHaveBeenCalled()
    })

    it('does not redirect when on signup page', async () => {
      const assignSpy = vi.fn()
      Object.defineProperty(globalThis.window, 'location', {
        value: { pathname: '/signup', assign: assignSpy },
        writable: true,
        configurable: true,
      })

      server.use(
        http.get(`${API_BASE}/v1/on-signup`, () => {
          return new HttpResponse(null, { status: 401 })
        }),
        http.post(`${API_BASE}/auth/refresh`, () => {
          return new HttpResponse(null, { status: 401 })
        }),
        http.post(`${API_BASE}/auth/logout`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await expect(api.get(`${API_BASE}/v1/on-signup`)).rejects.toThrow(
        'Unauthorized'
      )
      expect(assignSpy).not.toHaveBeenCalled()
    })
  })

  // ──── fetchWithAuth – 401 response ────

  describe('fetchWithAuth – 401 response', () => {
    it('retries fetchWithAuth after 401 and successful refresh', async () => {
      let callCount = 0
      server.use(
        http.get(`${API_BASE}/v1/fetch-auth`, () => {
          callCount++
          if (callCount === 1) {
            return new HttpResponse(null, { status: 401 })
          }
          return HttpResponse.json({ ok: true })
        }),
        http.post(`${API_BASE}/auth/refresh`, () => {
          return HttpResponse.json({ success: true })
        })
      )

      const resp = await api.get(`${API_BASE}/v1/fetch-auth`)
      expect(resp).toEqual({ ok: true })
      expect(callCount).toBe(2)
    })
  })

  // ──── demo mode refresh path ────

  describe('demo mode refresh', () => {
    it('uses demo-refresh endpoint when in demo mode', async () => {
      sessionStorage.setItem('demoEpochMs', '1700000000000')

      let demoCalled = false
      let callCount = 0
      server.use(
        http.get(`${API_BASE}/v1/demo-data`, () => {
          callCount++
          if (callCount === 1) {
            return new HttpResponse(null, { status: 401 })
          }
          return HttpResponse.json({ demo: true })
        }),
        http.post(`${API_BASE}/auth/demo-refresh`, () => {
          demoCalled = true
          return HttpResponse.json({
            success: true,
            demoEpochMs: 1700000000000,
          })
        })
      )

      const result = await api.get(`${API_BASE}/v1/demo-data`)
      expect(result).toEqual({ demo: true })
      expect(demoCalled).toBe(true)
    })

    it('logs out when demo-refresh fails', async () => {
      sessionStorage.setItem('demoEpochMs', '1700000000000')
      const assignSpy = vi.fn()
      Object.defineProperty(globalThis.window, 'location', {
        value: { pathname: '/dashboard', assign: assignSpy },
        writable: true,
        configurable: true,
      })

      server.use(
        http.get(`${API_BASE}/v1/demo-fail`, () => {
          return new HttpResponse(null, { status: 401 })
        }),
        http.post(`${API_BASE}/auth/demo-refresh`, () => {
          return new HttpResponse(null, { status: 401 })
        }),
        http.post(`${API_BASE}/auth/logout`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await expect(api.get(`${API_BASE}/v1/demo-fail`)).rejects.toThrow(
        'Unauthorized'
      )
    })
  })

  // ──── checkAuth – 403 response ────

  describe('checkAuth – 403 response', () => {
    it('removes authenticated flag and returns false on 403', async () => {
      sessionStorage.setItem('demoEpochMs', '1700000000000')
      server.use(
        http.get(`${API_BASE}/v1/user`, () => {
          return new HttpResponse(null, { status: 403 })
        })
      )

      const result = await api.checkAuth()
      expect(result).toBe(false)
      expect(sessionStorage.getItem('authenticated')).toBeNull()
      expect(sessionStorage.getItem('demoEpochMs')).toBeNull()
    })
  })

  // ──── checkAuth – with impersonate token ────

  describe('checkAuth – with impersonate token', () => {
    it('sends Authorization header during checkAuth', async () => {
      sessionStorage.setItem('impersonate_token', 'imp-tok')
      server.use(
        http.get(`${API_BASE}/v1/user`, ({ request }) => {
          expect(request.headers.get('Authorization')).toBe('Bearer imp-tok')
          return HttpResponse.json({ id: 1 })
        })
      )

      const result = await api.checkAuth()
      expect(result).toBe(true)
    })
  })

  // ──── get() – URL path rewriting ────

  describe('get() – URL path rewriting', () => {
    it('strips /v1 prefix from relative URL before prepending API_BASE', async () => {
      server.use(
        http.get(`${API_BASE}/v1/data`, () => {
          return HttpResponse.json({ stripped: true })
        })
      )

      const result = await api.get('/v1/data')
      expect(result).toEqual({ stripped: true })
    })

    it('uses relative path that does not start with /v1', async () => {
      server.use(
        http.get(`${API_BASE}/v1/other`, () => {
          return HttpResponse.json({ other: true })
        })
      )

      const result = await api.get('/other')
      expect(result).toEqual({ other: true })
    })
  })

  // ──── logout – network error during logout ────

  describe('logout – error handling', () => {
    it('completes even when logout endpoint fails', async () => {
      server.use(
        http.post(`${API_BASE}/auth/logout`, () => {
          return HttpResponse.error()
        })
      )

      await api.logout()
      expect(sessionStorage.getItem('authenticated')).toBeNull()
    })
  })
})
