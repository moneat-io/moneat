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

import {render, screen, waitFor} from '@testing-library/react'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {afterEach, describe, expect, it, vi} from 'vitest'
import type {ReactNode} from 'react'
import type {OverviewResponse} from '@/lib/api/types'
import {api} from '@/lib/api'
import {OverviewDataProvider} from '../OverviewDataProvider'
import {useKpis, useSystemStatus} from '../overviewData'

vi.mock('@/lib/api', () => ({
  api: {
    getOverview: vi.fn(),
  },
}))

function Probe() {
  const status = useSystemStatus()
  const kpis = useKpis()
  return (
    <div>
      <span>{status.state}</span>
      <span>{kpis[0]?.label}</span>
    </div>
  )
}

function Wrapper({children}: {children: ReactNode}) {
  const client = new QueryClient({
    defaultOptions: {
      queries: {retry: false},
    },
  })
  return (
    <QueryClientProvider client={client}>
      <OverviewDataProvider>{children}</OverviewDataProvider>
    </QueryClientProvider>
  )
}

describe('OverviewDataProvider', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('exposes overview data from the API response', async () => {
    vi.mocked(api.getOverview).mockResolvedValue(overviewResponse())

    render(<Probe />, {wrapper: Wrapper})

    await waitFor(() => expect(screen.getByText('Action needed')).toBeInTheDocument())
    expect(screen.getByText('Errors')).toBeInTheDocument()
    expect(api.getOverview).toHaveBeenCalledTimes(1)
  })
})

function overviewResponse(): OverviewResponse {
  return {
    systemStatus: {
      state: 'Action needed',
      severity: 'bad',
      counts: {incidents: 1, alerts: 2, degraded: 3, hostsOffline: 4},
      ai: {summary: 'checkout-api is degraded', incidentId: 'INC-204'},
    },
    kpis: [
      {
        id: 'errors',
        label: 'Errors',
        value: '12',
        delta: {value: '4%', direction: 'up', tone: 'bad'},
        status: 'bad',
        spark: [1, 2, 3],
      },
    ],
    serviceHealth: [],
    telemetry: {
      errors: [1],
      latency: [2],
      throughput: [3],
      logs: [4],
      deployAtPct: 0,
      deployLabel: 'latest deploy',
    },
    triage: {incidents: [], alerts: [], issues: [], security: []},
    infra: {gauges: [], containers: 0, pods: 0, upLabel: '0/0 up'},
    uptime: {monitors: [], upLabel: '0/0 up', statusPages: '0 status pages'},
    deploys: [],
    activity: [],
  }
}
