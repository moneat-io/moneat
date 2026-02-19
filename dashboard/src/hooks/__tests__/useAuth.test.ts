import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { useAuth } from '../useAuth'
import { server } from '../../test/mocks/server'
import { http, HttpResponse } from 'msw'

describe('useAuth', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('starts in loading state', () => {
    const { result } = renderHook(() => useAuth())
    expect(result.current.isLoading).toBe(true)
    expect(result.current.user).toBeNull()
  })

  it('sets user on successful auth check', async () => {
    server.use(
      http.get('*/v1/user', () =>
        HttpResponse.json({
          id: 1,
          email: 'test@example.com',
          name: 'Test',
          emailVerified: true,
          onboardingCompleted: true,
        })
      )
    )

    const { result } = renderHook(() => useAuth())
    await waitFor(() => expect(result.current.isLoading).toBe(false))

    expect(result.current.user).not.toBeNull()
    expect(result.current.user?.email).toBe('test@example.com')
    expect(sessionStorage.getItem('authenticated')).toBe('true')
  })

  it('sets user to null on auth failure', async () => {
    server.use(
      http.get('*/v1/user', () =>
        new HttpResponse(null, { status: 401 })
      )
    )

    const { result } = renderHook(() => useAuth())
    await waitFor(() => expect(result.current.isLoading).toBe(false))

    expect(result.current.user).toBeNull()
    expect(sessionStorage.getItem('authenticated')).toBeNull()
  })
})
