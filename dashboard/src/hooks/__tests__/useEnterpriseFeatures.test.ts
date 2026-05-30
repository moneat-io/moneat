import { describe, it, expect } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import React from 'react'
import { useEnterpriseFeatures, useHasModule } from '../useEnterpriseFeatures'
import { server } from '../../test/mocks/server'
import { http, HttpResponse } from 'msw'

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })
  return ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client: queryClient }, children)
}

describe('useEnterpriseFeatures', () => {
  it('returns enterprise features on success', async () => {
    server.use(
      http.get('*/features', () =>
        HttpResponse.json({ enterprise: true, modules: ['saml', 'oncall'] })
      )
    )

    const { result } = renderHook(() => useEnterpriseFeatures(), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.enterprise).toBe(true)
    expect(result.current.data?.modules).toContain('saml')
  })

  it('reports an error when endpoint fails', async () => {
    server.use(
      http.get('*/features', () =>
        new HttpResponse(null, { status: 500 })
      )
    )

    const { result } = renderHook(() => useEnterpriseFeatures(), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error?.message).toBe('Unable to load feature availability')
  })
})

describe('useHasModule', () => {
  it('returns true when module is present', async () => {
    server.use(
      http.get('*/features', () =>
        HttpResponse.json({ enterprise: true, modules: ['saml', 'oncall'] })
      )
    )

    const { result } = renderHook(() => useHasModule('saml'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current).toBe(true))
  })

  it('returns false when module is absent', async () => {
    server.use(
      http.get('*/features', () =>
        HttpResponse.json({ enterprise: true, modules: ['oncall'] })
      )
    )

    const { result } = renderHook(() => useHasModule('saml'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current).toBe(false))
  })
})
