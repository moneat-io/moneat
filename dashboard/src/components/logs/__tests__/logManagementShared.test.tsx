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

import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {render, screen, waitFor} from '@testing-library/react'
import type {ReactElement} from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'

import {
  GROUP_BY_NONE,
  soleLevel,
  suggestMonitorName,
  toGroupBySelectValue,
  toGroupByValue,
  useLogVolume,
} from '@/components/logs/logManagementShared'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    getLogAggregate: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

function renderWithClient(ui: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

function LogVolumeProbe({
  enabled = true,
  query = 'level:error',
  levels = ['error'],
  groupBy = 'service',
}: {
  readonly enabled?: boolean
  readonly query?: string
  readonly levels?: string[]
  readonly groupBy?: string | null
}) {
  const result = useLogVolume({
    query,
    levels,
    groupBy,
    from: '2026-06-01T00:00:00.000Z',
    to: '2026-06-01T01:00:00.000Z',
    enabled,
  })
  return (
    <div>
      <span data-testid="total">{result.total}</span>
      <span data-testid="bucket-count">{result.buckets.length}</span>
      <span data-testid="fetching">{String(result.isFetching)}</span>
      <span data-testid="error">{String(result.isError)}</span>
    </div>
  )
}

describe('logManagementShared helpers', () => {
  it('maps group-by values between stored API values and select values', () => {
    expect(toGroupByValue(GROUP_BY_NONE)).toBeNull()
    expect(toGroupByValue('service')).toBe('service')
    expect(toGroupBySelectValue(null)).toBe(GROUP_BY_NONE)
    expect(toGroupBySelectValue(undefined)).toBe(GROUP_BY_NONE)
    expect(toGroupBySelectValue('')).toBe(GROUP_BY_NONE)
    expect(toGroupBySelectValue('environment')).toBe('environment')
  })

  it('extracts the single active level and suggests monitor names from it', () => {
    expect(soleLevel([])).toBeNull()
    expect(soleLevel([' error ', 'error'])).toBe('error')
    expect(soleLevel(['warn', 'error'])).toBeNull()

    expect(suggestMonitorName(['error'])).toBe('High error log volume')
    expect(suggestMonitorName(['fatal'])).toBe('High fatal log volume')
    expect(suggestMonitorName(['warn'])).toBe('warn log volume')
    expect(suggestMonitorName(['warn', 'error'])).toBe('')
  })
})

describe('useLogVolume', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('loads a real aggregate preview for the supplied query filters', async () => {
    mockApi.getLogAggregate.mockResolvedValue({
      buckets: [{timestamp: '2026-06-01T00:00:00.000Z', count: 4}],
      totalCount: 4,
      interval: '1h',
    })

    renderWithClient(<LogVolumeProbe />)

    await waitFor(() => expect(screen.getByTestId('total')).toHaveTextContent('4'))
    expect(screen.getByTestId('bucket-count')).toHaveTextContent('1')
    expect(screen.getByTestId('error')).toHaveTextContent('false')
    expect(mockApi.getLogAggregate).toHaveBeenCalledWith({
      from: '2026-06-01T00:00:00.000Z',
      to: '2026-06-01T01:00:00.000Z',
      query: 'level:error',
      levels: ['error'],
      groupBy: 'service',
    })
  })

  it('stays idle when disabled and treats empty filters as omitted values', async () => {
    renderWithClient(<LogVolumeProbe enabled={false} query="  " levels={[]} groupBy={null} />)

    expect(screen.getByTestId('total')).toHaveTextContent('0')
    expect(screen.getByTestId('bucket-count')).toHaveTextContent('0')
    expect(screen.getByTestId('fetching')).toHaveTextContent('false')
    expect(screen.getByTestId('error')).toHaveTextContent('false')
    expect(mockApi.getLogAggregate).not.toHaveBeenCalled()
  })

  it('reports aggregate errors without inventing preview data', async () => {
    mockApi.getLogAggregate.mockRejectedValue(new Error('boom'))

    renderWithClient(<LogVolumeProbe />)

    await waitFor(() => expect(screen.getByTestId('error')).toHaveTextContent('true'))
    expect(screen.getByTestId('total')).toHaveTextContent('0')
    expect(screen.getByTestId('bucket-count')).toHaveTextContent('0')
  })
})
