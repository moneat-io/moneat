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

import { isDemo, setDemoEpoch, syncDemoEpochFromUser } from '../demo'

export const API_BASE = `${import.meta.env.VITE_BACKEND_URL || ''}/v1`
const AUTH_PAGE_PATHS = new Set([
  '/',
  '/login',
  '/signup',
  '/verify-email',
  '/forgot-password',
  '/reset-password',
])
const AUTH_CHECK_TIMEOUT_MS = 4000
const API_REQUEST_TIMEOUT_MS = 15000

function withRequestTimeoutSignal(
  existingSignal?: AbortSignal,
  timeoutMs = API_REQUEST_TIMEOUT_MS
): { signal: AbortSignal; cleanup: () => void } {
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs)

  const onExistingAbort = () => controller.abort()
  if (existingSignal) {
    if (existingSignal.aborted) {
      controller.abort()
    } else {
      existingSignal.addEventListener('abort', onExistingAbort, { once: true })
    }
  }

  return {
    signal: controller.signal,
    cleanup: () => {
      clearTimeout(timeoutId)
      if (existingSignal) {
        existingSignal.removeEventListener('abort', onExistingAbort)
      }
    },
  }
}

export interface ApiClientCore {
  readonly API_BASE: string
  request<T>(endpoint: string, options?: RequestInit): Promise<T>
  get<T>(endpoint: string): Promise<T>
  fetchWithAuth(endpoint: string, options?: RequestInit): Promise<Response>
  logout(): Promise<void>
  checkAuth(): Promise<boolean>
  isAuthenticated(): boolean
}

function getImpersonateToken(): string | null {
  return globalThis.sessionStorage?.getItem('impersonate_token') ?? null
}

function buildErrorFromResponse(response: Response, errorData: { error?: string } | null): Error & { status: number } {
  const errorMessage = errorData?.error ?? `API Error: ${response.status} ${response.statusText}`
  const error = new Error(errorMessage) as Error & { status: number }
  error.status = response.status
  return error
}

function isAuthenticated(): boolean {
  return (
    !!getImpersonateToken() ||
    globalThis.sessionStorage?.getItem('authenticated') === 'true'
  )
}

/** Reset auth redirect state. Used by tests to avoid cross-test pollution. */
export const resetAuthRedirectForTesting: { current: (() => void) | null } = {
  current: null,
}

export function createApiClientCore(): ApiClientCore {
  let authRedirectInProgress = false
  let refreshPromise: Promise<boolean> | null = null

  resetAuthRedirectForTesting.current = () => {
    authRedirectInProgress = false
    refreshPromise = null
  }

  async function refreshAccessToken(): Promise<boolean> {
    if (isDemo()) {
      try {
        const { signal, cleanup } = withRequestTimeoutSignal()
        const response = await fetch(
          `${API_BASE.replace('/v1', '')}/auth/demo-refresh`,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            signal,
          }
        )
        cleanup()
        if (!response.ok) return false
        const data = await response.json()
        if (data.demoEpochMs) setDemoEpoch(data.demoEpochMs)
        globalThis.sessionStorage?.setItem('authenticated', 'true')
        return true
      } catch {
        return false
      }
    }

    try {
      const { signal, cleanup } = withRequestTimeoutSignal()
      const response = await fetch(
        `${API_BASE.replace('/v1', '')}/auth/refresh`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'include',
          signal,
        }
      )
      cleanup()
      if (!response.ok) return false
      globalThis.sessionStorage?.setItem('authenticated', 'true')
      return true
    } catch {
      return false
    }
  }

  async function logout(): Promise<void> {
    globalThis.sessionStorage?.removeItem('impersonate_token')
    globalThis.sessionStorage?.removeItem('authenticated')
    setDemoEpoch(null)
    try {
      const { signal, cleanup } = withRequestTimeoutSignal()
      await fetch(`${API_BASE.replace('/v1', '')}/auth/logout`, {
        method: 'POST',
        credentials: 'include',
        signal,
      })
      cleanup()
    } catch {
      // Ignore errors
    }
  }

  async function logoutAndRedirect(): Promise<never> {
    await logout()
    if (
      !authRedirectInProgress &&
      globalThis.window !== undefined &&
      !AUTH_PAGE_PATHS.has(globalThis.window.location.pathname)
    ) {
      authRedirectInProgress = true
      globalThis.window.location.assign('/login')
    }
    throw new Error('Unauthorized')
  }

  async function handle401Retry<T>(
    endpoint: string,
    options: RequestInit,
    authRetryCount: number,
    retry: (ep: string, opts: RequestInit, count: number) => Promise<T>
  ): Promise<T> {
    if (authRetryCount >= 1) return logoutAndRedirect()
    refreshPromise ??= refreshAccessToken().finally(() => {
      refreshPromise = null
    })
    const refreshed = await refreshPromise
    return refreshed ? retry(endpoint, options, authRetryCount + 1) : logoutAndRedirect()
  }

  async function request<T>(
    endpoint: string,
    options: RequestInit = {},
    authRetryCount = 0
  ): Promise<T> {
    const impersonateToken = getImpersonateToken()
    const headers = new Headers(options.headers ?? {})
    headers.set('Content-Type', 'application/json')
    if (impersonateToken) headers.set('Authorization', `Bearer ${impersonateToken}`)
    const headersObj = Object.fromEntries(headers.entries())

    const { signal, cleanup } = withRequestTimeoutSignal(
      options.signal as AbortSignal | undefined
    )

    let response: Response
    try {
      response = await fetch(endpoint, {
        ...options,
        headers: headersObj,
        credentials: 'include',
        signal,
      })
    } catch {
      const networkError = new Error('NETWORK_ERROR')
      ;(networkError as Error & { stack?: string }).stack = undefined
      throw networkError
    } finally {
      cleanup()
    }

    if (response.status === 401) {
      return handle401Retry<T>(endpoint, options, authRetryCount, request)
    }

    if (!response.ok) {
      let errorData: { error?: string } | null = null
      try {
        errorData = await response.json()
      } catch {
        // use default
      }
      throw buildErrorFromResponse(response, errorData)
    }
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  }

  async function fetchWithAuth(
    endpoint: string,
    options: RequestInit = {},
    authRetryCount = 0
  ): Promise<Response> {
    const impersonateToken = getImpersonateToken()
    const headers = new Headers(options.headers ?? {})
    if (impersonateToken) headers.set('Authorization', `Bearer ${impersonateToken}`)
    const headersObj = Object.fromEntries(headers.entries())

    const { signal, cleanup } = withRequestTimeoutSignal(
      options.signal as AbortSignal | undefined
    )

    let response: Response
    try {
      response = await fetch(endpoint, {
        ...options,
        headers: headersObj,
        credentials: 'include',
        signal,
      })
    } catch {
      throw new Error('NETWORK_ERROR')
    } finally {
      cleanup()
    }

    if (response.status === 401) {
      return handle401Retry(endpoint, options, authRetryCount, fetchWithAuth)
    }

    return response
  }

  async function get<T>(endpoint: string): Promise<T> {
    const url = endpoint.startsWith('/')
      ? `${API_BASE}${endpoint.replace(/^\/v1/, '')}`
      : endpoint
    return request<T>(url)
  }

  async function checkAuth(): Promise<boolean> {
    const impersonateToken = getImpersonateToken()
    const headers: Record<string, string> = {}
    if (impersonateToken) {
      headers['Authorization'] = `Bearer ${impersonateToken}`
    }
    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), AUTH_CHECK_TIMEOUT_MS)
    try {
      const response = await fetch(`${API_BASE}/user`, {
        method: 'GET',
        headers,
        credentials: 'include',
        signal: controller.signal,
      })
      if (!response.ok) {
        if (response.status === 401 || response.status === 403) {
          globalThis.sessionStorage?.removeItem('authenticated')
          setDemoEpoch(null)
        }
        return false
      }
      const user = await response
        .json()
        .catch(() => null) as { demoEpochMs?: number | null } | null
      syncDemoEpochFromUser(user)
      globalThis.sessionStorage?.setItem('authenticated', 'true')
      return true
    } catch {
      return false
    } finally {
      clearTimeout(timeoutId)
    }
  }

  return {
    get API_BASE() {
      return API_BASE
    },
    request,
    get,
    fetchWithAuth,
    logout,
    checkAuth,
    isAuthenticated,
  }
}
