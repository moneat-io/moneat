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

import {nativeIncidentUnavailableCopy, useNativeIncidentCapabilities} from '../useNativeIncidentCapabilities'

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}},
  })
  return ({children}: {children: React.ReactNode}) =>
    React.createElement(QueryClientProvider, {client: queryClient}, children)
}

describe('useNativeIncidentCapabilities', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getCurrentUser.mockResolvedValue({orgId: 'organization-one'})
  })

  it('reports enabled entitlement state without rollout metadata', async () => {
    mockApi.getNativeIncidentCapabilities.mockResolvedValue({
      enabled: true,
      entitlementEnabled: true,
      plan: 'TEAM',
      entitlementReason: null,
      quotas: {},
      externalProviderPassthroughAffected: false,
    })

    const {result} = renderHook(() => useNativeIncidentCapabilities(), {wrapper: createWrapper()})

    await waitFor(() => expect(result.current.enabled).toBe(true))
    expect(result.current.data?.plan).toBe('TEAM')
    expect(result.current.isLoading).toBe(false)
  })

  it('reports disabled when the organization entitlement is unavailable', async () => {
    mockApi.getNativeIncidentCapabilities.mockResolvedValue({
      enabled: false,
      entitlementEnabled: false,
      plan: 'FREE',
      entitlementReason: 'Upgrade the plan',
      quotas: {},
      externalProviderPassthroughAffected: false,
    })

    const {result} = renderHook(() => useNativeIncidentCapabilities(), {wrapper: createWrapper()})

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.enabled).toBe(false)
    expect(result.current.data?.entitlementReason).toBe('Upgrade the plan')
  })

  it('fails closed when the capability request errors', async () => {
    mockApi.getNativeIncidentCapabilities.mockRejectedValue(new Error('boom'))

    const {result} = renderHook(() => useNativeIncidentCapabilities(), {wrapper: createWrapper()})

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.enabled).toBe(false)
  })
})

describe('nativeIncidentUnavailableCopy', () => {
  it('invites a retry for transient request errors', () => {
    expect(nativeIncidentUnavailableCopy({isError: true, data: undefined}).title).toMatch(
      /temporarily unavailable/i
    )
  })

  it('points at plan or license entitlement when disabled', () => {
    const copy = nativeIncidentUnavailableCopy({
      isError: false,
      data: {
        enabled: false,
        entitlementEnabled: false,
        plan: 'FREE',
        entitlementReason: 'Upgrade the plan',
        quotas: {},
        externalProviderPassthroughAffected: false,
      },
    })
    expect(copy.title).toMatch(/not available/i)
    expect(copy.description).toMatch(/Upgrade the plan/i)
  })
})
