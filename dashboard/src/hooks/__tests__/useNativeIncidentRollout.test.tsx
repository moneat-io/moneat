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

import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {renderHook, waitFor} from '@testing-library/react'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {getCurrentUser: vi.fn(), getNativeIncidentCapabilities: vi.fn()},
}))

vi.mock('@/lib/api', () => ({api: mockApi}))

import {nativeIncidentUnavailableCopy, useNativeIncidentRollout} from '../useNativeIncidentRollout'

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}},
  })
  return ({children}: {children: React.ReactNode}) =>
    React.createElement(QueryClientProvider, {client: queryClient}, children)
}

describe('useNativeIncidentRollout', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getCurrentUser.mockResolvedValue({orgId: 'organization-one'})
  })

  it('reports enabled with state and environment when the capability is on', async () => {
    mockApi.getNativeIncidentCapabilities.mockResolvedValue({
      enabled: true,
      environment: 'production',
      state: 'ENABLED',
      externalProviderPassthroughAffected: false,
    })

    const {result} = renderHook(() => useNativeIncidentRollout(), {wrapper: createWrapper()})

    await waitFor(() => expect(result.current.enabled).toBe(true))
    expect(result.current.state).toBe('ENABLED')
    expect(result.current.environment).toBe('production')
    expect(result.current.isLoading).toBe(false)
  })

  it('reports disabled while preserving the reason state', async () => {
    mockApi.getNativeIncidentCapabilities.mockResolvedValue({
      enabled: false,
      environment: 'production',
      state: 'DISABLED',
      externalProviderPassthroughAffected: false,
    })

    const {result} = renderHook(() => useNativeIncidentRollout(), {wrapper: createWrapper()})

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.enabled).toBe(false)
    expect(result.current.state).toBe('DISABLED')
  })

  it('fails closed when the capability request errors', async () => {
    mockApi.getNativeIncidentCapabilities.mockRejectedValue(new Error('boom'))

    const {result} = renderHook(() => useNativeIncidentRollout(), {wrapper: createWrapper()})

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.enabled).toBe(false)
  })
})

describe('nativeIncidentUnavailableCopy', () => {
  it('invites a retry for transient states and query errors', () => {
    expect(nativeIncidentUnavailableCopy({state: 'EVALUATION_ERROR', isError: false}).title).toMatch(
      /temporarily unavailable/i
    )
    expect(nativeIncidentUnavailableCopy({state: 'PROVIDER_UNAVAILABLE', isError: false}).title).toMatch(
      /temporarily unavailable/i
    )
    expect(nativeIncidentUnavailableCopy({state: undefined, isError: true}).title).toMatch(
      /temporarily unavailable/i
    )
  })

  it('points at the administrator for a deliberate disable', () => {
    const copy = nativeIncidentUnavailableCopy({state: 'DISABLED', isError: false})
    expect(copy.title).toMatch(/not available/i)
    expect(copy.description).toMatch(/administrator/i)
  })
})
