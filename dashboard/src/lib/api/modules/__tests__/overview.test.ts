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

import {beforeEach, describe, expect, it} from 'vitest'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {api} from '@/lib/api'
import type {OverviewResponse} from '@/lib/api/types'

const API_BASE = 'http://localhost:8080'

describe('overview API', () => {
  beforeEach(() => {
    globalThis.localStorage.clear()
    globalThis.sessionStorage.clear()
    globalThis.sessionStorage.setItem('authenticated', 'true')
  })

  it('fetches the organization overview payload', async () => {
    const overview: OverviewResponse = {
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
    server.use(
      http.get(`${API_BASE}/v1/overview`, () => HttpResponse.json(overview))
    )

    await expect(api.getOverview()).resolves.toEqual(overview)
  })
})
